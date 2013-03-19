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

import static com.ngdata.hbaseindexer.metrics.IndexerMetricsUtil.metricName;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;

/**
 * Writes updates (new documents and deletes) to a SolrServer.
 * <p>
 * There are two main pieces of functionality that this class provides, both related to error handling in Solr:
 * <h3>Selective swallowing of errors</h3>
 * If a write to Solr throws an exception signifying that the underlying problem is related to Solr, then the exception
 * will be thrown up the stack. The intention of this behaviour is to allow the write to be re-tried indefinitely until
 * the Solr issue is resolved.
 * <p>
 * If a write to Solr throws an exception signifying that the underlying problem lies with the document being written,
 * then the exception will be logged, but otherwise ignored. The intention of this behaviour is to stop a single bad
 * document from holding up the whole indexing process for other documents.
 * 
 * <h3>Individual retry of documents</h3>
 * If a single document in a batch causes an exception to be thrown that is related to the document itself, then each
 * update will be retried individually.
 */
public class SolrWriter {

    private Log log = LogFactory.getLog(getClass());
    private SolrServer solrServer;
    private Meter indexAddMeter;
    private Meter indexDeleteMeter;
    private Meter solrAddErrorMeter;
    private Meter solrDeleteErrorMeter;
    private Meter documentAddErrorMeter;
    private Meter documentDeleteErrorMeter;

    public SolrWriter(String indexName, SolrServer solrServer) {
        this.solrServer = solrServer;
        
        indexAddMeter = Metrics.newMeter(metricName(getClass(), "Index adds", indexName), "Documents added to Solr index",
                TimeUnit.SECONDS);
        indexDeleteMeter = Metrics.newMeter(metricName(getClass(), "Index deletes", indexName),
                "Documents deleted from Solr index", TimeUnit.SECONDS);
        solrAddErrorMeter = Metrics.newMeter(metricName(getClass(), "Solr add errors", indexName),
                "Documents not added to Solr due to Solr errors", TimeUnit.SECONDS);
        solrDeleteErrorMeter = Metrics.newMeter(metricName(getClass(), "Solr delete errors", indexName),
                "Documents not deleted from Solr due to Solr errors", TimeUnit.SECONDS);
        documentAddErrorMeter = Metrics.newMeter(metricName(getClass(), "Document add errors", indexName),
                "Documents not added to Solr due to document errors", TimeUnit.SECONDS);
        documentDeleteErrorMeter = Metrics.newMeter(metricName(getClass(), "Document delete errors", indexName),
                "Documents not deleted from Solr due to document errors", TimeUnit.SECONDS);

    }

    private boolean isDocumentIssue(SolrException solrException) {
        return solrException.code() == ErrorCode.BAD_REQUEST.code;
    }

    private void logOrThrowSolrException(SolrException solrException) {
        if (isDocumentIssue(solrException)) {
            log.error("Error updating Solr", solrException);
        } else {
            throw solrException;
        }
    }

    /**
     * Write a list of documents to Solr.
     * <p>
     * If a server occurs while writing the update, the exception will be thrown up the stack. If one or more of the
     * documents contain issues, the error will be logged and swallowed, with all other updates being performed.
     */
    public void add(Collection<SolrInputDocument> inputDocuments) throws SolrServerException, IOException {
        try {
            solrServer.add(inputDocuments);
            indexAddMeter.mark(inputDocuments.size());
        } catch (SolrException e) {
            if (isDocumentIssue(e)) {
                retryAddsIndividually(inputDocuments);
            } else {
                solrAddErrorMeter.mark(inputDocuments.size());
                throw e;
            }
        }
    }

    private void retryAddsIndividually(Collection<SolrInputDocument> inputDocuments) throws SolrServerException,
            IOException {
        for (SolrInputDocument inputDocument : inputDocuments) {
            try {
                solrServer.add(inputDocument);
                indexAddMeter.mark();
            } catch (SolrException e) {
                logOrThrowSolrException(e);
                // No exception thrown through, so we can update the metric
                documentAddErrorMeter.mark();
            }
        }
    }

    /**
     * Delete a list of documents ids from Solr.
     * <p>
     * If a server occurs while performing the delete, the exception will be thrown up the stack. If one or more of the
     * deletes cause issues, the error will be logged and swallowed, with all other updates being performed.
     */
    public void deleteById(List<String> idsToDelete) throws SolrServerException, IOException {
        try {
            solrServer.deleteById(idsToDelete);
            indexDeleteMeter.mark(idsToDelete.size());
        } catch (SolrException e) {
            if (isDocumentIssue(e)) {
                retryDeletesIndividually(idsToDelete);
            } else {
                solrDeleteErrorMeter.mark(idsToDelete.size());
                throw e;
            }
        }
    }

    private void retryDeletesIndividually(List<String> idsToDelete) throws SolrServerException, IOException {
        for (String idToDelete : idsToDelete) {
            try {
                solrServer.deleteById(idToDelete);
                indexDeleteMeter.mark();
            } catch (SolrException e) {
                logOrThrowSolrException(e);
                // No exception thrown through, so we can update the metric
                documentDeleteErrorMeter.mark();
            }
        }
    }

}
