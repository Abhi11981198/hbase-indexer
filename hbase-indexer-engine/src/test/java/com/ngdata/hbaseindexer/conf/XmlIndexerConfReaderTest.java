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
package com.ngdata.hbaseindexer.conf;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.ngdata.hbaseindexer.conf.FieldDefinition.ValueSource;
import com.ngdata.hbaseindexer.uniquekey.HexUniqueKeyFormatter;
import org.junit.Test;

public class XmlIndexerConfReaderTest {
    @Test
    public void testValid() throws Exception {
        new XmlIndexerConfReader().read(asStream("<indexer table='foo'/>"));
    }

    @Test(expected = IndexerConfException.class)
    public void testInvalid() throws Exception {
        new XmlIndexerConfReader().read(asStream("<foo/>"));
    }

    private InputStream asStream(String data) {
        return new ByteArrayInputStream(data.getBytes());
    }

    @Test
    public void testFullIndexerConf() throws Exception {
        IndexerConf conf = new XmlIndexerConfReader().read(getClass().getResourceAsStream("indexerconf_full.xml"));

        assertEquals("table1", conf.getTable());
        assertEquals(IndexerConf.MappingType.COLUMN, conf.getMappingType());
        assertEquals(IndexerConf.RowReadMode.NEVER, conf.getRowReadMode());
        assertEquals("key", conf.getUniqueKeyField());
        assertEquals(HexUniqueKeyFormatter.class, conf.getUniqueKeyFormatterClass());

        List<FieldDefinition> fieldDefs = conf.getFieldDefinitions();
        List<FieldDefinition> expectedFieldDefs = Lists.newArrayList(
                new FieldDefinition("field1", "col:qual1", ValueSource.QUALIFIER, "float"),
                new FieldDefinition("field2", "col:qual2", ValueSource.VALUE, "long",
                        ImmutableMap.of("fieldKeyA", "fieldValueA", "fieldKeyB", "fieldValueB")));
        assertEquals(expectedFieldDefs, fieldDefs);
        
        List<DocumentExtractDefinition> extractDefs = conf.getDocumentExtractDefinitions();
        List<DocumentExtractDefinition> expectedExtractDefs = Lists.newArrayList(
                new DocumentExtractDefinition("testprefix_", "col:qual3", ValueSource.QUALIFIER, "text/html",
                        ImmutableMap.of("extractKeyA", "extractValueA", "extractKeyB", "extractValueB")));
        assertEquals(expectedExtractDefs, extractDefs);

        assertEquals(ImmutableMap.of("globalKeyA", "globalValueA", "globalKeyB", "globalValueB"), conf.getGlobalParams());
        
    }

    @Test
    public void testDefaults() throws Exception {
        IndexerConf conf = new XmlIndexerConfReader().read(getClass().getResourceAsStream("indexerconf_defaults.xml"));

        assertEquals("table1", conf.getTable());
        assertEquals(IndexerConf.DEFAULT_MAPPING_TYPE, conf.getMappingType());
        assertEquals(IndexerConf.DEFAULT_ROW_READ_MODE, conf.getRowReadMode());
        assertEquals(IndexerConf.DEFAULT_UNIQUE_KEY_FIELD, conf.getUniqueKeyField());
        assertEquals(IndexerConf.DEFAULT_UNIQUE_KEY_FORMATTER, conf.getUniqueKeyFormatterClass());

        List<FieldDefinition> fieldDefs = conf.getFieldDefinitions();
        List<FieldDefinition> expectedFieldDefs = Lists.newArrayList(
                new FieldDefinition("field1", "col:qual1", IndexerConf.DEFAULT_VALUE_SOURCE, IndexerConf.DEFAULT_FIELD_TYPE));
        assertEquals(expectedFieldDefs, fieldDefs);

        List<DocumentExtractDefinition> extractDefs = conf.getDocumentExtractDefinitions();
        List<DocumentExtractDefinition> expectedExtractDefs = Lists.newArrayList(
                new DocumentExtractDefinition(null, "col:qual2", ValueSource.VALUE, "application/octet-stream"));
        assertEquals(expectedExtractDefs, extractDefs);
    }
}
