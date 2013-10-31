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
package com.ngdata.hbaseindexer.model.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.ngdata.hbaseindexer.model.api.ActiveBatchBuildInfoBuilder;
import com.ngdata.hbaseindexer.model.api.BatchBuildInfoBuilder;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.api.IndexerDefinitionBuilder;
import com.ngdata.hbaseindexer.util.json.JsonUtil;
import net.iharder.Base64;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import com.ngdata.hbaseindexer.model.api.ActiveBatchBuildInfo;
import com.ngdata.hbaseindexer.model.api.BatchBuildInfo;

import static com.ngdata.hbaseindexer.model.api.IndexerDefinition.BatchIndexingState;
import static com.ngdata.hbaseindexer.model.api.IndexerDefinition.IncrementalIndexingState;
import static com.ngdata.hbaseindexer.model.api.IndexerDefinition.LifecycleState;

public class IndexerDefinitionJsonSerDeser {
    public static IndexerDefinitionJsonSerDeser INSTANCE = new IndexerDefinitionJsonSerDeser();

    public IndexerDefinitionBuilder fromJsonBytes(byte[] json) {
        return fromJsonBytes(json, new IndexerDefinitionBuilder());
    }

    public IndexerDefinitionBuilder fromJsonBytes(byte[] json, IndexerDefinitionBuilder indexerDefinitionBuilder) {
        ObjectNode node;
        try {
            node = (ObjectNode)new ObjectMapper().readTree(new ByteArrayInputStream(json));
        } catch (IOException e) {
            throw new RuntimeException("Error parsing indexer definition JSON.", e);
        }
        return fromJson(node, indexerDefinitionBuilder);
    }

    public IndexerDefinitionBuilder fromJson(ObjectNode node) {
        return fromJson(node, new IndexerDefinitionBuilder());
    }

    public IndexerDefinitionBuilder fromJson(ObjectNode node, IndexerDefinitionBuilder indexerDefinitionBuilder) {
        String name = JsonUtil.getString(node, "name");
        LifecycleState lifecycleState = LifecycleState.valueOf(JsonUtil.getString(node, "lifecycleState"));
        IncrementalIndexingState incrementalIndexingState = IncrementalIndexingState.valueOf(JsonUtil.getString(node, "incrementalIndexingState"));
        BatchIndexingState batchIndexingState = BatchIndexingState.valueOf(JsonUtil.getString(node, "batchIndexingState"));

        String queueSubscriptionId = JsonUtil.getString(node, "subscriptionId", null);
        long subscriptionTimestamp = JsonUtil.getLong(node, "subscriptionTimestamp", 0L);
        
        byte[] configuration = getByteArrayProperty(node, "configuration");

        String connectionType = JsonUtil.getString(node, "connectionType", null);
        ObjectNode connectionParamsNode = JsonUtil.getObject(node, "connectionParams", null);
        Map<String, String> connectionParams = null;
        if (connectionParamsNode != null) {
            connectionParams = new HashMap<String, String>();
            Iterator<Map.Entry<String, JsonNode>> it = connectionParamsNode.getFields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                connectionParams.put(entry.getKey(), entry.getValue().getTextValue());
            }
        }

        ActiveBatchBuildInfo activeBatchBuild = null;
        if (node.get("activeBatchBuild") != null) {
            ObjectNode buildNode = JsonUtil.getObject(node, "activeBatchBuild");
            ActiveBatchBuildInfoBuilder builder = new ActiveBatchBuildInfoBuilder();
            builder.jobId(JsonUtil.getString(buildNode, "jobId"));
            builder.submitTime(JsonUtil.getLong(buildNode, "submitTime"));
            builder.trackingUrl(JsonUtil.getString(buildNode, "trackingUrl", null));
            builder.batchIndexConfiguration(getByteArrayProperty(buildNode, "batchIndexConfiguration"));
            activeBatchBuild = builder.build();
        }

        BatchBuildInfo lastBatchBuild = null;
        if (node.get("lastBatchBuild") != null) {
            ObjectNode buildNode = JsonUtil.getObject(node, "lastBatchBuild");
            BatchBuildInfoBuilder builder = new BatchBuildInfoBuilder();
            builder.jobId(JsonUtil.getString(buildNode, "jobId"));
            builder.submitTime(JsonUtil.getLong(buildNode, "submitTime"));
            builder.success(JsonUtil.getBoolean(buildNode, "success"));
            builder.jobState(JsonUtil.getString(buildNode, "jobState"));
            builder.trackingUrl(JsonUtil.getString(buildNode, "trackingUrl", null));
            ObjectNode countersNode = JsonUtil.getObject(buildNode, "counters");
            Iterator<String> it = countersNode.getFieldNames();
            while (it.hasNext()) {
                String key = it.next();
                long value = JsonUtil.getLong(countersNode, key);
                builder.counter(key, value);
            }
            builder.batchIndexConfiguration(getByteArrayProperty(buildNode, "batchIndexConfiguration"));
            lastBatchBuild = builder.build();
        }

        byte[] batchIndexConfiguration = getByteArrayProperty(node, "batchIndexConfiguration");
        byte[] defaultBatchIndexConfiguration = getByteArrayProperty(node, "defaultBatchIndexConfiguration");

        int occVersion = JsonUtil.getInt(node, "occVersion");

        indexerDefinitionBuilder.name(name);
        indexerDefinitionBuilder.lifecycleState(lifecycleState);
        indexerDefinitionBuilder.incrementalIndexingState(incrementalIndexingState);
        indexerDefinitionBuilder.batchIndexingState(batchIndexingState);
        indexerDefinitionBuilder.subscriptionId(queueSubscriptionId);
        indexerDefinitionBuilder.subscriptionTimestamp(subscriptionTimestamp);
        indexerDefinitionBuilder.configuration(configuration);
        indexerDefinitionBuilder.connectionType(connectionType);
        indexerDefinitionBuilder.connectionParams(connectionParams);
        indexerDefinitionBuilder.activeBatchBuildInfo(activeBatchBuild);
        indexerDefinitionBuilder.lastBatchBuildInfo(lastBatchBuild);
        indexerDefinitionBuilder.batchIndexConfiguration(batchIndexConfiguration);
        indexerDefinitionBuilder.defaultBatchIndexConfiguration(defaultBatchIndexConfiguration);
        indexerDefinitionBuilder.occVersion(occVersion);
        return indexerDefinitionBuilder;
    }

    private byte[] getByteArrayProperty(ObjectNode node, String property) {
        try {
            String string = JsonUtil.getString(node, property, null);
            if (string == null)
                return null;
            return Base64.decode(string);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setByteArrayProperty(ObjectNode node, String property, byte[] data) {
        if (data == null)
            return;
        try {
            node.put(property, Base64.encodeBytes(data, Base64.GZIP));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] toJsonBytes(IndexerDefinition indexer) {
        try {
            return new ObjectMapper().writeValueAsBytes(toJson(indexer));
        } catch (IOException e) {
            throw new RuntimeException("Error serializing indexer definition to JSON.", e);
        }
    }

    public ObjectNode toJson(IndexerDefinition indexer) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();

        node.put("name", indexer.getName());
        node.put("lifecycleState", indexer.getLifecycleState().toString());
        node.put("batchIndexingState", indexer.getBatchIndexingState().toString());
        node.put("incrementalIndexingState", indexer.getIncrementalIndexingState().toString());

        node.put("occVersion", indexer.getOccVersion());

        if (indexer.getSubscriptionId() != null)
            node.put("subscriptionId", indexer.getSubscriptionId());
        
        node.put("subscriptionTimestamp", indexer.getSubscriptionTimestamp());

        setByteArrayProperty(node, "configuration", indexer.getConfiguration());

        if (indexer.getConnectionType() != null)
            node.put("connectionType", indexer.getConnectionType());

        if (indexer.getConnectionParams() != null) {
            ObjectNode paramsNode = node.putObject("connectionParams");
            for (Map.Entry<String, String> entry : indexer.getConnectionParams().entrySet()) {
                paramsNode.put(entry.getKey(), entry.getValue());
            }
        }

        if (indexer.getActiveBatchBuildInfo() != null) {
            ActiveBatchBuildInfo buildInfo = indexer.getActiveBatchBuildInfo();
            ObjectNode batchNode = node.putObject("activeBatchBuild");
            batchNode.put("jobId", buildInfo.getJobId());
            batchNode.put("submitTime", buildInfo.getSubmitTime());
            batchNode.put("trackingUrl", buildInfo.getTrackingUrl());
            setByteArrayProperty(batchNode, "batchIndexConfiguration", buildInfo.getBatchIndexConfiguration());
        }

        if (indexer.getLastBatchBuildInfo() != null) {
            BatchBuildInfo buildInfo = indexer.getLastBatchBuildInfo();
            ObjectNode batchNode = node.putObject("lastBatchBuild");
            batchNode.put("jobId", buildInfo.getJobId());
            batchNode.put("submitTime", buildInfo.getSubmitTime());
            batchNode.put("success", buildInfo.getSuccess());
            batchNode.put("jobState", buildInfo.getJobState());
            if (buildInfo.getTrackingUrl() != null)
                batchNode.put("trackingUrl", buildInfo.getTrackingUrl());
            ObjectNode countersNode = batchNode.putObject("counters");
            for (Map.Entry<String, Long> counter : buildInfo.getCounters().entrySet()) {
                countersNode.put(counter.getKey(), counter.getValue());
            }
            setByteArrayProperty(batchNode, "batchIndexConfiguration", buildInfo.getBatchIndexConfiguration());
        }

        setByteArrayProperty(node, "batchIndexConfiguration", indexer.getBatchIndexConfiguration());
        setByteArrayProperty(node, "defaultBatchIndexConfiguration", indexer.getDefaultBatchIndexConfiguration());

        return node;
    }
}
