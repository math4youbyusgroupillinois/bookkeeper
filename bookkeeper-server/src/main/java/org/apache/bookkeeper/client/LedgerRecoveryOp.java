/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.bookkeeper.client;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.client.AsyncCallback.CloseCallback;
import org.apache.bookkeeper.client.DigestManager.RecoveryData;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryListener;
import org.apache.bookkeeper.stats.BookkeeperClientStatsLogger.BookkeeperClientOp;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulated the ledger recovery operation. It first does a read
 * with entry-id of -1 (BookieProtocol.LAST_ADD_CONFIRMED) to all bookies. Then
 * starting from the last confirmed entry (from hints in the ledger entries),
 * it reads forward until it is not able to find a particular entry. It closes
 * the ledger at that entry.
 *
 */
class LedgerRecoveryOp implements ReadEntryListener, AddCallback {
    static final Logger LOG = LoggerFactory.getLogger(LedgerRecoveryOp.class);
    final LedgerHandle lh;
    final AtomicLong readCount, writeCount;
    final AtomicBoolean readDone;
    final AtomicBoolean callbackDone;
    final AtomicBoolean cancelled;
    final AtomicBoolean couldClose;
    volatile long startEntryToRead;
    volatile long endEntryToRead;
    final GenericCallback<Void> cb;
    // keep a copy of metadata for recovery.
    LedgerMetadata metadataForRecovery;
    boolean parallelRead = false;
    int readBatchSize = 1;

    class RecoveryReadOp extends ListenerBasedPendingReadOp {

        RecoveryReadOp(LedgerHandle lh, ScheduledExecutorService scheduler,
                       long startEntryId, long endEntryId,
                       ReadEntryListener cb, Object ctx) {
            super(lh, scheduler, startEntryId, endEntryId, cb, ctx);
            // disable piggyback lac when recovering
            enablePiggybackLAC(false);
        }

        @Override
        protected LedgerMetadata getLedgerMetadata() {
            return metadataForRecovery;
        }

    }

    public LedgerRecoveryOp(LedgerHandle lh, GenericCallback<Void> cb) {
        readCount = new AtomicLong(0);
        writeCount = new AtomicLong(0);
        readDone = new AtomicBoolean(false);
        callbackDone = new AtomicBoolean(false);
        cancelled = new AtomicBoolean(false);
        couldClose = new AtomicBoolean(false);
        this.cb = cb;
        this.lh = lh;
    }

    LedgerRecoveryOp parallelRead(boolean enabled) {
        this.parallelRead = enabled;
        return this;
    }

    LedgerRecoveryOp readBatchSize(int batchSize) {
        this.readBatchSize = batchSize;
        return this;
    }

    LedgerRecoveryOp setCouldClose(boolean could) {
        this.couldClose.set(could);
        return this;
    }

    public void initiate() {
        ReadLastConfirmedOp rlcop = new ReadLastConfirmedOp(lh,
                new ReadLastConfirmedOp.LastConfirmedDataCallback() {
                public void readLastConfirmedDataComplete(int rc, RecoveryData data) {
                    if (rc == BKException.Code.OK) {
                        synchronized (lh) {
                            lh.lastAddPushed = lh.lastAddConfirmed = data.lastAddConfirmed;
                            lh.length = data.length;
                            startEntryToRead = endEntryToRead = lh.lastAddConfirmed;
                        }
                        // keep a copy of ledger metadata before proceeding
                        // ledger recovery
                        metadataForRecovery = new LedgerMetadata(lh.getLedgerMetadata());
                        doRecoveryRead();
                    } else if (rc == BKException.Code.UnauthorizedAccessException) {
                        submitCallback(rc);
                    } else {
                        submitCallback(BKException.Code.ReadException);
                    }
                }
                });

        /**
         * Enable fencing on this op. When the read request reaches the bookies
         * server it will fence off the ledger, stopping any subsequent operation
         * from writing to it.
         */
        rlcop.initiateWithFencing();
    }

    private void submitCallback(int rc) {
        if (cancelled.get()) {
            return;
        }
        if (BKException.Code.OK == rc) {
            lh.getStatsLogger().getOpStatsLogger(BookkeeperClientOp.LEDGER_RECOVER_ADD_ENTRIES)
                    .registerSuccessfulEvent(writeCount.get());
            lh.getStatsLogger().getOpStatsLogger(BookkeeperClientOp.LEDGER_RECOVER_READ_ENTRIES)
                    .registerSuccessfulEvent(readCount.get());
        } else {
            lh.getStatsLogger().getOpStatsLogger(BookkeeperClientOp.LEDGER_RECOVER_ADD_ENTRIES)
                    .registerFailedEvent(writeCount.get());
            lh.getStatsLogger().getOpStatsLogger(BookkeeperClientOp.LEDGER_RECOVER_READ_ENTRIES)
                    .registerFailedEvent(readCount.get());
        }
        cb.operationComplete(rc, null);
    }

    /**
     * Try to read past the last confirmed.
     */
    private void doRecoveryRead() {
        if (cancelled.get()) {
            return;
        }
        if (!callbackDone.get()) {
            startEntryToRead = endEntryToRead + 1;
            endEntryToRead = endEntryToRead + readBatchSize;
            new RecoveryReadOp(lh, lh.bk.scheduler, startEntryToRead, endEntryToRead, this, null)
                    .parallelRead(parallelRead).initiate();
        }
    }

    private void closeAndCallback() {
        if (cancelled.get()) {
            return;
        }
        if (couldClose.get() && callbackDone.compareAndSet(false, true)) {
            lh.asyncCloseInternal(new CloseCallback() {
                @Override
                public void closeComplete(int rc, LedgerHandle lh, Object ctx) {
                    if (rc != KeeperException.Code.OK.intValue()) {
                        LOG.warn("Close failed: " + BKException.getMessage(rc));
                        submitCallback(BKException.Code.ZKException);
                    } else {
                        submitCallback(BKException.Code.OK);
                        LOG.debug("After closing length is: {}", lh.getLength());
                    }
                }
            }, null, BKException.Code.LedgerClosedException);
        }
    }

    @Override
    public void onEntryComplete(int rc, LedgerHandle lh, LedgerEntry entry, Object ctx) {
        if (cancelled.get()) {
            return;
        }
        if (!readDone.get() && rc == BKException.Code.OK) {
            readCount.incrementAndGet();
            byte[] data = entry.getEntry();

            /*
             * We will add this entry again to make sure it is written to enough
             * replicas. We subtract the length of the data itself, since it will
             * be added again when processing the call to add it.
             */
            synchronized (lh) {
                lh.length = entry.getLength() - (long) data.length;
            }
            lh.asyncRecoveryAddEntry(data, 0, data.length, this, null);
            if (entry.getEntryId() == endEntryToRead) {
                // trigger next batch read
                doRecoveryRead();
            }
            return;
        }

        if (rc == BKException.Code.NoSuchEntryException || rc == BKException.Code.NoSuchLedgerExistsException) {
            readDone.set(true);
            if (readCount.get() == writeCount.get()) {
                closeAndCallback();
            }
            return;
        }

        // otherwise, some other error, we can't handle
        if (BKException.Code.OK != rc && callbackDone.compareAndSet(false, true)) {
            LOG.error("Failure {} while reading entries: ({} - {}), ledger: {} while recovering ledger",
                      new Object[] { BKException.getMessage(rc), startEntryToRead, endEntryToRead, lh.getId() });
            submitCallback(rc);
        } else if (BKException.Code.OK == rc) {
            // we are here is because we successfully read an entry but readDone was already set to true.
            // this would happen on recovery a ledger than has gaps in the tail.
            LOG.warn("Successfully read entry {} for ledger {}, but readDone is already {}",
                     new Object[] { entry.getEntryId(), lh.getId(), readDone.get() });
        }
        return;
    }

    @Override
    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        if (cancelled.get()) {
            return;
        }
        if (rc != BKException.Code.OK) {
            LOG.error("Failure " + BKException.getMessage(rc) + " while writing entry: " + (entryId + 1)
                    + " ledger: " + lh.ledgerId + " while recovering ledger");
            if (callbackDone.compareAndSet(false, true)) {
                // Give up, we can't recover from this error
                submitCallback(rc);
            }
            return;
        }
        long numAdd = writeCount.incrementAndGet();
        if (readDone.get() && readCount.get() == numAdd) {
            closeAndCallback();
        }
    }

    /**
     * Cancel recovery operation.
     */
    void cancel() {
        cancelled.set(true);
    }

    /**
     * Notify ledger metadata updated.
     */
    void notifyClose() {
        if (couldClose.compareAndSet(false, true) && readDone.get()
                && readCount.get() == writeCount.get()) {
            closeAndCallback();
        }
    }

}
