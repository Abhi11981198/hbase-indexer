/*
 * Copyright 2013 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.hbaseindexer.indexer;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import com.ngdata.hbaseindexer.HBaseIndexerConfiguration;
import com.ngdata.hbaseindexer.Main;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.api.IndexerDefinitionBuilder;
import com.ngdata.hbaseindexer.model.api.WriteableIndexerModel;
import com.ngdata.hbaseindexer.util.net.NetUtils;
import com.ngdata.hbaseindexer.util.solr.SolrTestingUtility;
import com.ngdata.sep.impl.SepReplicationSource;
import com.ngdata.sep.impl.SepTestUtil;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.replication.ReplicationAdmin;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.apache.zookeeper.ZooKeeper.States.CONNECTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class IndexerIT {
    private static boolean firstTest = true;
    private static Configuration conf;
    private static HBaseTestingUtility hbaseTestUtil;
    private static SolrTestingUtility solrTestingUtility;
    private static CloudSolrServer collection1;
    private static CloudSolrServer collection2;
    private Main main;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        // Note on the use of @BeforeClass:
        //   Ideally, we would tear up and down everything with each test. Unfortunately, due to connection
        //   leaks in HBase (CDH4.2), we can't do this. For example, ReplicationPeer.zkw isn't closed, and
        //   while we can work around that particular one by deleting replication peers first, there are
        //   still other issues that I haven't tracked down completely.
        //   On the plus side, since stuff keeps running between tests, they should run a bit faster.

        // We'll use the same conf object for hbase master/rs, hbase-indexer, and hbase client
        conf = HBaseIndexerConfiguration.create();
        // The following are the standard settings that for hbase regionserver when using the SEP (see SEP docs)
        conf.setBoolean(HConstants.REPLICATION_ENABLE_KEY, true);
        conf.setLong("replication.source.sleepforretries", 50);
        conf.set("replication.replicationsource.implementation", SepReplicationSource.class.getName());

        hbaseTestUtil = new HBaseTestingUtility(conf);
        hbaseTestUtil.startMiniZKCluster(1);
        hbaseTestUtil.startMiniCluster(1);

        int zkClientPort = hbaseTestUtil.getZkCluster().getClientPort();

        conf.set("hbaseindexer.zookeeper.connectstring", "localhost:" + zkClientPort);

        solrTestingUtility = new SolrTestingUtility(zkClientPort, NetUtils.getFreePort());
        solrTestingUtility.start();
        solrTestingUtility.uploadConfig("config1",
                Resources.toByteArray(Resources.getResource(IndexerIT.class, "schema.xml")),
                Resources.toByteArray(Resources.getResource(IndexerIT.class, "solrconfig.xml")));
        solrTestingUtility.createCore("collection1_core1", "collection1", "config1", 1);
        solrTestingUtility.createCore("collection2_core1", "collection2", "config1", 1);

        collection1 = new CloudSolrServer(solrTestingUtility.getZkConnectString());
        collection1.setDefaultCollection("collection1");

        collection2 = new CloudSolrServer(solrTestingUtility.getZkConnectString());
        collection2.setDefaultCollection("collection2");
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        if (collection1 != null) {
            collection1.shutdown();
        }
        if (collection2 != null) {
            collection2.shutdown();
        }

        //  Stop Solr first, as it depends on ZooKeeper
        if (solrTestingUtility != null) {
            solrTestingUtility.stop();
        }

        if (hbaseTestUtil != null) {
            hbaseTestUtil.shutdownMiniCluster();
        }
    }

    @Before
    public void setUpBeforeTest() throws Exception {
        if (!firstTest) {
            // Delete /ngdata from zookeeper
            System.out.println(">>> Deleting /ngdata node from ZooKeeper");
            cleanZooKeeper("localhost:" + hbaseTestUtil.getZkCluster().getClientPort(), "/ngdata");

            // Delete all hbase tables
            System.out.println(">>> Deleting all HBase tables");
            HBaseAdmin admin = new HBaseAdmin(conf);
            for (HTableDescriptor table : admin.listTables()) {
                admin.disableTable(table.getName());
                admin.deleteTable(table.getName());
            }
            admin.close();

            // Delete all replication peers
            System.out.println(">>> Deleting all replication peers from HBase");
            ReplicationAdmin replAdmin = new ReplicationAdmin(conf);
            for (String peerId : replAdmin.listPeers().keySet()) {
                replAdmin.removePeer(peerId);
            }
            replAdmin.close();
            SepTestUtil.waitOnAllReplicationPeersStopped();

            // Clear Solr indexes
            collection1.deleteByQuery("*:*");
            collection1.commit();
            collection2.deleteByQuery("*:*");
            collection2.commit();
        } else {
            firstTest = false;
        }

        main = new Main();
        main.startServices(conf);
    }

    @After
    public void tearDownAfterTest() throws Exception {
        if (main != null) {
            main.stopServices();
        }
    }

    @Test
    public void testBasicScenario() throws Exception {
        // Create a table in HBase
        createTable("table1", "family1");

        // Add an indexer
        WriteableIndexerModel indexerModel = main.getIndexerModel();
        IndexerDefinition indexerDef = new IndexerDefinitionBuilder()
                .name("indexer1")
                .configuration("<indexer table='table1'><field name='field1_s' value='family1:qualifier1'/></indexer>".getBytes())
                .connectionType("solr")
                .connectionParams(ImmutableMap.of("solr.zk", solrTestingUtility.getZkConnectString(),
                        "solr.collection", "collection1"))
                .build();

        indexerModel.addIndexer(indexerDef);

        // Ingest
        HTable table = new HTable(conf, "table1");
        Put put = new Put(b("row1"));
        put.add(b("family1"), b("qualifier1"), b("value1"));
        table.put(put);

        // Commit Solr index and check data is present
        waitForSolrDocumentCount(1);

        table.close();
    }

    @Test
    public void testIndexerDefinitionChangesPickedUp() throws Exception {
        createTable("table1", "family1");

        WriteableIndexerModel indexerModel = main.getIndexerModel();
        IndexerDefinition indexerDef = new IndexerDefinitionBuilder()
                .name("indexer1")
                .configuration("<indexer table='table1'><field name='field1_s' value='family1:qualifier1'/></indexer>".getBytes())
                .connectionType("solr")
                .connectionParams(ImmutableMap.of("solr.zk", solrTestingUtility.getZkConnectString(),
                        "solr.collection", "collection1"))
                .build();

        indexerModel.addIndexer(indexerDef);

        HTable table = new HTable(conf, "table1");
        Put put = new Put(b("row1"));
        put.add(b("family1"), b("qualifier1"), b("value1"));
        table.put(put);

        // Also put a value which should not be processed by the current config
        put = new Put(b("row2"));
        put.add(b("family1"), b("qualifier2"), b("value1"));
        table.put(put);

        SepTestUtil.waitOnReplicationPeerReady("Indexer_indexer1");
        SepTestUtil.waitOnReplication(conf, 60000L);

        collection1.commit();
        QueryResponse response = collection1.query(new SolrQuery("*:*"));
        assertEquals(1, response.getResults().size());

        // update indexer model
        int oldEventCount = main.getIndexerSupervisor().getEventCount();
        String lock = indexerModel.lockIndexer("indexer1");
        indexerDef = new IndexerDefinitionBuilder()
                .startFrom(indexerModel.getFreshIndexer("indexer1"))
                .configuration(("<indexer table='table1'>" +
                        "<field name='field1_s' value='family1:qualifier1'/>" +
                        "<field name='field2_s' value='family1:qualifier2'/>" +
                        "</indexer>").getBytes())
                .build();
        indexerModel.updateIndexer(indexerDef, lock);
        indexerModel.unlockIndexer(lock);

        waitOnSupervisorEventCountChange(oldEventCount);

        put = new Put(b("row3"));
        put.add(b("family1"), b("qualifier2"), b("value1"));
        table.put(put);

        SepTestUtil.waitOnReplication(conf, 60000L);

        collection1.commit();
        response = collection1.query(new SolrQuery("*:*"));
        assertEquals(2, response.getResults().size());

        table.close();
    }

    @Test
    public void testTwoTablesTwoIndexes() throws Exception {
        createTable("table1", "family1");
        createTable("table2", "family1");

        assertEquals(0, collection1.query(new SolrQuery("*:*")).getResults().size());
        assertEquals(0, collection2.query(new SolrQuery("*:*")).getResults().size());

        WriteableIndexerModel indexerModel = main.getIndexerModel();
        IndexerDefinition indexerDef = new IndexerDefinitionBuilder()
                .name("indexer1")
                .configuration(("<indexer table='table1'>" +
                        "<field name='field1_s' value='family1:qualifier1'/>" +
                        "</indexer>").getBytes())
                .connectionType("solr")
                .connectionParams(ImmutableMap.of("solr.zk", solrTestingUtility.getZkConnectString(),
                        "solr.collection", "collection1"))
                .build();

        indexerModel.addIndexer(indexerDef);

        indexerDef = new IndexerDefinitionBuilder()
                .name("indexer2")
                .configuration(("<indexer table='table2'>" +
                        "<field name='field1_s' value='family1:qualifier1'/>" +
                        "</indexer>").getBytes())
                .connectionType("solr")
                .connectionParams(ImmutableMap.of("solr.zk", solrTestingUtility.getZkConnectString(),
                        "solr.collection", "collection2"))
                .build();

        indexerModel.addIndexer(indexerDef);

        HTable table1 = new HTable(conf, "table1");
        HTable table2 = new HTable(conf, "table2");

        Put put = new Put(b("row1"));
        put.add(b("family1"), b("qualifier1"), b("value1"));
        table1.put(put);

        put = new Put(b("row2"));
        put.add(b("family1"), b("qualifier1"), b("value1"));
        table2.put(put);

        SepTestUtil.waitOnReplicationPeerReady("Indexer_indexer1");
        SepTestUtil.waitOnReplicationPeerReady("Indexer_indexer2");
        SepTestUtil.waitOnReplication(conf, 60000L);

        collection1.commit();
        collection2.commit();

        assertEquals(1, collection1.query(new SolrQuery("*:*")).getResults().size());
        assertEquals(1, collection2.query(new SolrQuery("*:*")).getResults().size());

        table1.close();
        table2.close();
    }

    public void cleanZooKeeper(String zkConnectString, String rootToDelete) throws Exception {
        int sessionTimeout = 10000;

        ZooKeeper zk = new ZooKeeper(zkConnectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    System.err.println("ZooKeeper Disconnected.");
                } else if (event.getState() == Event.KeeperState.Expired) {
                    System.err.println("ZooKeeper session expired.");
                }
            }
        });

        long waitUntil = System.currentTimeMillis() + sessionTimeout;
        while (zk.getState() != CONNECTED && waitUntil > System.currentTimeMillis()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
        }

        if (zk.getState() != CONNECTED) {
            throw new RuntimeException("Failed to connect to ZK within " + sessionTimeout + "ms.");
        }

        if (zk.exists(rootToDelete, false) != null) {
            List<String> paths = new ArrayList<String>();
            collectChildren(rootToDelete, zk, paths);
            paths.add(rootToDelete);

            for (String path : paths) {
                zk.delete(path, -1, null, null);
            }

            // The above deletes are async, wait for them to be finished
            long startWait = System.currentTimeMillis();
            while (zk.exists(rootToDelete, null) != null) {
                Thread.sleep(5);

                if (System.currentTimeMillis() - startWait > 120000) {
                    throw new RuntimeException("State was not cleared in ZK within the expected timeout");
                }
            }
        }

        zk.close();
    }

    private void collectChildren(String path, ZooKeeper zk, List<String> paths) throws InterruptedException, KeeperException {
        List<String> children = zk.getChildren(path, false);
        for (String child : children) {
            String childPath = path + "/" + child;
            collectChildren(childPath, zk, paths);
            paths.add(childPath);
        }
    }

    private void createTable(String tableName, String familyName) throws Exception {
        HTableDescriptor tableDescriptor = new HTableDescriptor(tableName);
        HColumnDescriptor familyDescriptor = new HColumnDescriptor(familyName);
        familyDescriptor.setScope(HConstants.REPLICATION_SCOPE_GLOBAL);
        tableDescriptor.addFamily(familyDescriptor);

        HBaseAdmin hbaseAdmin = new HBaseAdmin(conf);
        hbaseAdmin.createTable(tableDescriptor);
        hbaseAdmin.close();
    }

    private void waitForSolrDocumentCount(int count) throws Exception {
        long waitUntil = System.currentTimeMillis() + 60000L;
        int resultSize = 0;
        while (resultSize != count) {
            collection1.commit();
            QueryResponse response = collection1.query(new SolrQuery("*:*"));
            resultSize = response.getResults().size();

            if (System.currentTimeMillis() > waitUntil) {
                fail("Document not indexed in Solr within timeout");
            } else {
                System.out.println("Waiting on document to be available in Solr...");
            }
            Thread.sleep(20);
        }
    }

    private void waitOnSupervisorEventCountChange(int oldEventCount) throws InterruptedException {
        long lastNotification = System.currentTimeMillis();
        while (main.getIndexerSupervisor().getEventCount() == oldEventCount) {
            if (System.currentTimeMillis() > lastNotification + 1000) {
                System.out.println("Waiting on change in number of events processed by IndexerSupervisor");
                lastNotification = System.currentTimeMillis();
            }
            Thread.sleep(20);
        }
    }

    private static byte[] b(String string) {
        return Bytes.toBytes(string);
    }
}
