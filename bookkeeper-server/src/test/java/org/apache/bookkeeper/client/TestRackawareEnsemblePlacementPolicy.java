/*
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

import static org.apache.bookkeeper.client.RackawareEnsemblePlacementPolicy.REPP_DNS_RESOLVER_CLASS;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.TestCase;

import org.apache.bookkeeper.client.BKException.BKNotEnoughBookiesException;
import org.apache.bookkeeper.net.NetworkTopology;
import org.apache.bookkeeper.util.StaticDNSResolver;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.Configuration;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestRackawareEnsemblePlacementPolicy extends TestCase {

    static final Logger LOG = LoggerFactory.getLogger(TestRackawareEnsemblePlacementPolicy.class);

    RackawareEnsemblePlacementPolicy repp;
    final ArrayList<InetSocketAddress> ensemble = new ArrayList<InetSocketAddress>();
    final List<Integer> writeSet = new ArrayList<Integer>();
    Configuration conf = new CompositeConfiguration();
    InetSocketAddress addr1, addr2, addr3, addr4;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        StaticDNSResolver.reset();
        StaticDNSResolver.addNodeToRack(InetAddress.getLocalHost().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack("127.0.0.1", NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack("localhost", NetworkTopology.DEFAULT_RACK);
        LOG.info("Set up static DNS Resolver.");
        conf.setProperty(REPP_DNS_RESOLVER_CLASS, StaticDNSResolver.class.getName());
        addr1 = new InetSocketAddress("127.0.0.2", 3181);
        addr2 = new InetSocketAddress("127.0.0.3", 3181);
        addr3 = new InetSocketAddress("127.0.0.4", 3181);
        addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostName(), NetworkTopology.DEFAULT_REGION + "/rack1");
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostName(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostName(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostName(), NetworkTopology.DEFAULT_REGION + "/rack2");
        ensemble.add(addr1);
        ensemble.add(addr2);
        ensemble.add(addr3);
        ensemble.add(addr4);
        for (int i = 0; i < 4; i++) {
            writeSet.add(i);
        }

        repp = new RackawareEnsemblePlacementPolicy();
        repp.initialize(conf);
    }

    @Override
    protected void tearDown() throws Exception {
        repp.uninitalize();
        super.tearDown();
    }

    static void updateMyRack(String rack) throws Exception {
        StaticDNSResolver.addNodeToRack(InetAddress.getLocalHost().getHostAddress(), rack);
        StaticDNSResolver.addNodeToRack(InetAddress.getLocalHost().getHostName(), rack);
        StaticDNSResolver.addNodeToRack("127.0.0.1", rack);
        StaticDNSResolver.addNodeToRack("localhost", rack);
    }

    @Test(timeout = 60000)
    public void testNodeDown() throws Exception {
        repp.uninitalize();
        updateMyRack(NetworkTopology.DEFAULT_RACK);

        repp = new RackawareEnsemblePlacementPolicy();
        repp.initialize(conf);

        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(1);
        expectedSet.add(2);
        expectedSet.add(3);
        expectedSet.add(0);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testNodeReadOnly() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RackawareEnsemblePlacementPolicy();
        repp.initialize(conf);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        Set<InetSocketAddress> ro = new HashSet<InetSocketAddress>();
        ro.add(addr1);
        repp.onClusterChanged(addrs, ro);

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(1);
        expectedSet.add(2);
        expectedSet.add(3);
        expectedSet.add(0);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testTwoNodesDown() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RackawareEnsemblePlacementPolicy();
        repp.initialize(conf);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        addrs.remove(addr2);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());

        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(2);
        expectedSet.add(3);
        expectedSet.add(0);
        expectedSet.add(1);
        LOG.info("reorder set : {}", reoderSet);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testNodeDownAndReadOnly() throws Exception {
        repp.uninitalize();
        updateMyRack("/r1/rack1");

        repp = new RackawareEnsemblePlacementPolicy();
        repp.initialize(conf);

        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        addrs.remove(addr2);
        Set<InetSocketAddress> roAddrs = new HashSet<InetSocketAddress>();
        roAddrs.add(addr2);
        repp.onClusterChanged(addrs, roAddrs);
        List<Integer> reoderSet = repp.reorderReadSequence(ensemble, writeSet);
        List<Integer> expectedSet = new ArrayList<Integer>();
        expectedSet.add(2);
        expectedSet.add(3);
        expectedSet.add(1);
        expectedSet.add(0);
        assertFalse(reoderSet == writeSet);
        assertEquals(expectedSet, reoderSet);
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithEnoughBookiesInSameRack() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r3");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        InetSocketAddress replacedBookie = repp.replaceBookie(addr2, new HashSet<InetSocketAddress>());
        assertEquals(addr3, replacedBookie);
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithEnoughBookiesInDifferentRack() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
        excludedAddrs.add(addr1);
        InetSocketAddress replacedBookie = repp.replaceBookie(addr2, excludedAddrs);

        assertFalse(addr1.equals(replacedBookie));
        assertTrue(addr3.equals(replacedBookie) || addr4.equals(replacedBookie));
    }

    @Test(timeout = 60000)
    public void testReplaceBookieWithNotEnoughBookies() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        // replace node under r2
        Set<InetSocketAddress> excludedAddrs = new HashSet<InetSocketAddress>();
        excludedAddrs.add(addr1);
        excludedAddrs.add(addr3);
        excludedAddrs.add(addr4);
        try {
            repp.replaceBookie(addr2, excludedAddrs);
            fail("Should throw BKNotEnoughBookiesException when there is not enough bookies");
        } catch (BKNotEnoughBookiesException bnebe) {
            // should throw not enou
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithSingleRack() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.9", 3181);
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(3, 2, new HashSet<InetSocketAddress>());
            assertEquals(0, getNumCoveredWriteQuorums(ensemble, 2));
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, new HashSet<InetSocketAddress>());
            assertEquals(0, getNumCoveredWriteQuorums(ensemble2, 2));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithMultipleRacks() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.1", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.4", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r2");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble = repp.newEnsemble(3, 2, new HashSet<InetSocketAddress>());
            int numCovered = getNumCoveredWriteQuorums(ensemble, 2);
            assertTrue(numCovered >= 1 && numCovered < 3);
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, new HashSet<InetSocketAddress>());
            numCovered = getNumCoveredWriteQuorums(ensemble2, 2);
            assertTrue(numCovered >= 1 && numCovered < 3);
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    @Test(timeout = 60000)
    public void testNewEnsembleWithEnoughRacks() throws Exception {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        InetSocketAddress addr5 = new InetSocketAddress("127.0.0.6", 3181);
        InetSocketAddress addr6 = new InetSocketAddress("127.0.0.7", 3181);
        InetSocketAddress addr7 = new InetSocketAddress("127.0.0.8", 3181);
        InetSocketAddress addr8 = new InetSocketAddress("127.0.0.9", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r3");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r4");
        StaticDNSResolver.addNodeToRack(addr5.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr6.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr7.getAddress().getHostAddress(), "/default-region/r3");
        StaticDNSResolver.addNodeToRack(addr8.getAddress().getHostAddress(), "/default-region/r4");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        addrs.add(addr5);
        addrs.add(addr6);
        addrs.add(addr7);
        addrs.add(addr8);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        try {
            ArrayList<InetSocketAddress> ensemble1 = repp.newEnsemble(3, 2, new HashSet<InetSocketAddress>());
            assertEquals(3, getNumCoveredWriteQuorums(ensemble1, 2));
            ArrayList<InetSocketAddress> ensemble2 = repp.newEnsemble(4, 2, new HashSet<InetSocketAddress>());
            assertEquals(4, getNumCoveredWriteQuorums(ensemble2, 2));
        } catch (BKNotEnoughBookiesException bnebe) {
            fail("Should not get not enough bookies exception even there is only one rack.");
        }
    }

    /**
     * Test for BOOKKEEPER-633
     */
    @Test(timeout = 60000)
    public void testRemoveBookieFromCluster() {
        InetSocketAddress addr1 = new InetSocketAddress("127.0.0.2", 3181);
        InetSocketAddress addr2 = new InetSocketAddress("127.0.0.3", 3181);
        InetSocketAddress addr3 = new InetSocketAddress("127.0.0.4", 3181);
        InetSocketAddress addr4 = new InetSocketAddress("127.0.0.5", 3181);
        // update dns mapping
        StaticDNSResolver.addNodeToRack(addr1.getAddress().getHostAddress(), NetworkTopology.DEFAULT_RACK);
        StaticDNSResolver.addNodeToRack(addr2.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr3.getAddress().getHostAddress(), "/default-region/r2");
        StaticDNSResolver.addNodeToRack(addr4.getAddress().getHostAddress(), "/default-region/r3");
        // Update cluster
        Set<InetSocketAddress> addrs = new HashSet<InetSocketAddress>();
        addrs.add(addr1);
        addrs.add(addr2);
        addrs.add(addr3);
        addrs.add(addr4);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
        addrs.remove(addr1);
        repp.onClusterChanged(addrs, new HashSet<InetSocketAddress>());
    }

    private int getNumCoveredWriteQuorums(ArrayList<InetSocketAddress> ensemble, int writeQuorumSize)
            throws Exception {
        int ensembleSize = ensemble.size();
        int numCoveredWriteQuorums = 0;
        for (int i = 0; i < ensembleSize; i++) {
            Set<String> racks = new HashSet<String>();
            for (int j = 0; j < writeQuorumSize; j++) {
                int bookieIdx = (i + j) % ensembleSize;
                InetSocketAddress addr = ensemble.get(bookieIdx);
                racks.add(StaticDNSResolver.getRack(addr.getAddress().getHostAddress()));
            }
            numCoveredWriteQuorums += (racks.size() > 1 ? 1 : 0);
        }
        return numCoveredWriteQuorums;
    }
}
