/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search;

import org.junit.After;
import org.junit.Before;
import org.opensearch.action.ActionFuture;
import org.opensearch.action.ActionListener;
import org.opensearch.action.LatchedActionListener;
import org.opensearch.action.search.CreatePitAction;
import org.opensearch.action.search.CreatePitRequest;
import org.opensearch.action.search.CreatePitResponse;
import org.opensearch.action.search.DeletePitAction;
import org.opensearch.action.search.DeletePitInfo;
import org.opensearch.action.search.DeletePitRequest;
import org.opensearch.action.search.DeletePitResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.search.builder.PointInTimeBuilder;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.opensearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;

/**
 * Multi node integration tests for delete PIT use cases
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 2)
public class DeletePitMultiNodeTests extends OpenSearchIntegTestCase {

    @Before
    public void setupIndex() throws ExecutionException, InterruptedException {
        createIndex("index", Settings.builder().put("index.number_of_shards", 5).put("index.number_of_replicas", 1).build());
        client().prepareIndex("index").setId("1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).execute().get();
        ensureGreen();
    }

    @After
    public void clearIndex() {
        client().admin().indices().prepareDelete("index").get();
    }

    private CreatePitResponse createPitOnIndex(String index) throws ExecutionException, InterruptedException {
        CreatePitRequest request = new CreatePitRequest(TimeValue.timeValueDays(1), true);
        request.setIndices(new String[] { index });
        ActionFuture<CreatePitResponse> execute = client().execute(CreatePitAction.INSTANCE, request);
        return execute.get();
    }

    public void testDeletePit() throws Exception {
        CreatePitRequest request = new CreatePitRequest(TimeValue.timeValueDays(1), true);
        request.setIndices(new String[] { "index" });
        ActionFuture<CreatePitResponse> execute = client().execute(CreatePitAction.INSTANCE, request);
        CreatePitResponse pitResponse = execute.get();
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());
        execute = client().execute(CreatePitAction.INSTANCE, request);
        pitResponse = execute.get();
        pitIds.add(pitResponse.getId());
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        ActionFuture<DeletePitResponse> deleteExecute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = deleteExecute.get();
        assertEquals(2, deletePITResponse.getDeletePitResults().size());
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }
        /**
         * Checking deleting the same PIT id again results in succeeded
         */
        deleteExecute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        deletePITResponse = deleteExecute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }
    }

    public void testDeletePitWithValidAndDeletedIds() throws Exception {
        CreatePitRequest request = new CreatePitRequest(TimeValue.timeValueDays(1), true);
        request.setIndices(new String[] { "index" });
        ActionFuture<CreatePitResponse> execute = client().execute(CreatePitAction.INSTANCE, request);
        CreatePitResponse pitResponse = execute.get();
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());

        /**
         * Delete Pit #1
         */
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        ActionFuture<DeletePitResponse> deleteExecute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = deleteExecute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }
        execute = client().execute(CreatePitAction.INSTANCE, request);
        pitResponse = execute.get();
        pitIds.add(pitResponse.getId());
        /**
         * Delete PIT with both Ids #1 (which is deleted) and #2 (which is present)
         */
        deletePITRequest = new DeletePitRequest(pitIds);
        deleteExecute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        deletePITResponse = deleteExecute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }
    }

    public void testDeletePitWithValidAndInvalidIds() throws Exception {
        CreatePitRequest request = new CreatePitRequest(TimeValue.timeValueDays(1), true);
        request.setIndices(new String[] { "index" });
        ActionFuture<CreatePitResponse> execute = client().execute(CreatePitAction.INSTANCE, request);
        CreatePitResponse pitResponse = execute.get();
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());
        pitIds.add("nondecodableid");
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        ActionFuture<DeletePitResponse> deleteExecute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        Exception e = assertThrows(ExecutionException.class, () -> deleteExecute.get());
        assertThat(e.getMessage(), containsString("invalid id"));
    }

    public void testDeleteAllPits() throws Exception {
        createPitOnIndex("index");
        createIndex("index1", Settings.builder().put("index.number_of_shards", 5).put("index.number_of_replicas", 1).build());
        client().prepareIndex("index1").setId("1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).execute().get();
        ensureGreen();
        createPitOnIndex("index1");
        DeletePitRequest deletePITRequest = new DeletePitRequest("_all");

        /**
         * When we invoke delete again, returns success after clearing the remaining readers. Asserting reader context
         * not found exceptions don't result in failures ( as deletion in one node is successful )
         */
        ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = execute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertThat(deletePitInfo.getPitId(), not(blankOrNullString()));
            assertTrue(deletePitInfo.isSuccessful());
        }
        client().admin().indices().prepareDelete("index1").get();
    }

    public void testDeletePitWhileNodeDrop() throws Exception {
        CreatePitResponse pitResponse = createPitOnIndex("index");
        createIndex("index1", Settings.builder().put("index.number_of_shards", 5).put("index.number_of_replicas", 1).build());
        client().prepareIndex("index1").setId("1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).execute().get();
        ensureGreen();
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());
        CreatePitResponse pitResponse1 = createPitOnIndex("index1");
        pitIds.add(pitResponse1.getId());
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        internalCluster().restartRandomDataNode(new InternalTestCluster.RestartCallback() {
            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
                try {
                    DeletePitResponse deletePITResponse = execute.get();
                    for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
                        assertTrue(pitIds.contains(deletePitInfo.getPitId()));
                        assertFalse(deletePitInfo.isSuccessful());
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return super.onNodeStopped(nodeName);
            }
        });

        ensureGreen();
        /**
         * When we invoke delete again, returns success after clearing the remaining readers. Asserting reader context
         * not found exceptions don't result in failures ( as deletion in one node is successful )
         */
        ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = execute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }
        client().admin().indices().prepareDelete("index1").get();
    }

    public void testDeleteAllPitsWhileNodeDrop() throws Exception {
        createPitOnIndex("index");
        createIndex("index1", Settings.builder().put("index.number_of_shards", 5).put("index.number_of_replicas", 1).build());
        client().prepareIndex("index1").setId("1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).execute().get();
        ensureGreen();
        DeletePitRequest deletePITRequest = new DeletePitRequest("_all");
        internalCluster().restartRandomDataNode(new InternalTestCluster.RestartCallback() {
            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
                try {
                    DeletePitResponse deletePITResponse = execute.get();
                    for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
                        assertThat(deletePitInfo.getPitId(), not(blankOrNullString()));
                        assertFalse(deletePitInfo.isSuccessful());
                    }
                } catch (Exception e) {
                    assertTrue(e.getMessage().contains("Node not connected"));
                }
                return super.onNodeStopped(nodeName);
            }
        });

        ensureGreen();
        /**
         * When we invoke delete again, returns success after clearing the remaining readers. Asserting reader context
         * not found exceptions don't result in failures ( as deletion in one node is successful )
         */
        ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = execute.get();
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertThat(deletePitInfo.getPitId(), not(blankOrNullString()));
            assertTrue(deletePitInfo.isSuccessful());
        }
        client().admin().indices().prepareDelete("index1").get();
    }

    public void testDeleteWhileSearch() throws Exception {
        CreatePitResponse pitResponse = createPitOnIndex("index");
        ensureGreen();
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        Thread[] threads = new Thread[5];
        CountDownLatch latch = new CountDownLatch(threads.length);
        final AtomicBoolean deleted = new AtomicBoolean(false);

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                latch.countDown();
                try {
                    latch.await();
                    for (int j = 0; j < 30; j++) {
                        client().prepareSearch()
                            .setSize(2)
                            .setPointInTime(new PointInTimeBuilder(pitResponse.getId()).setKeepAlive(TimeValue.timeValueDays(1)))
                            .execute()
                            .get();
                    }
                } catch (Exception e) {
                    /**
                     * assert for exception once delete pit goes through. throw error in case of any exeption before that.
                     */
                    if (deleted.get() == true) {
                        if (!e.getMessage().contains("all shards failed")) throw new AssertionError(e);
                        return;
                    }
                    throw new AssertionError(e);
                }
            });
            threads[i].setName("opensearch[node_s_0][search]");
            threads[i].start();
        }
        ActionFuture<DeletePitResponse> execute = client().execute(DeletePitAction.INSTANCE, deletePITRequest);
        DeletePitResponse deletePITResponse = execute.get();
        deleted.set(true);
        for (DeletePitInfo deletePitInfo : deletePITResponse.getDeletePitResults()) {
            assertTrue(pitIds.contains(deletePitInfo.getPitId()));
            assertTrue(deletePitInfo.isSuccessful());
        }

        for (Thread thread : threads) {
            thread.join();
        }
    }

    public void testtConcurrentDeletes() throws InterruptedException, ExecutionException {
        CreatePitResponse pitResponse = createPitOnIndex("index");
        ensureGreen();
        int concurrentRuns = randomIntBetween(20, 50);
        List<String> pitIds = new ArrayList<>();
        pitIds.add(pitResponse.getId());
        DeletePitRequest deletePITRequest = new DeletePitRequest(pitIds);
        AtomicInteger numDeleteAcknowledged = new AtomicInteger();
        TestThreadPool testThreadPool = null;
        try {
            testThreadPool = new TestThreadPool(DeletePitMultiNodeTests.class.getName());
            List<Runnable> operationThreads = new ArrayList<>();
            CountDownLatch countDownLatch = new CountDownLatch(concurrentRuns);
            for (int i = 0; i < concurrentRuns; i++) {
                Runnable thread = () -> {
                    logger.info("Triggering pit delete --->");
                    LatchedActionListener listener = new LatchedActionListener<>(new ActionListener<DeletePitResponse>() {
                        @Override
                        public void onResponse(DeletePitResponse deletePitResponse) {
                            if (deletePitResponse.getDeletePitResults().get(0).isSuccessful()) {
                                numDeleteAcknowledged.incrementAndGet();
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {}
                    }, countDownLatch);
                    client().execute(DeletePitAction.INSTANCE, deletePITRequest, listener);
                };
                operationThreads.add(thread);
            }
            TestThreadPool finalTestThreadPool = testThreadPool;
            operationThreads.forEach(runnable -> finalTestThreadPool.executor("generic").execute(runnable));
            countDownLatch.await();
            assertEquals(concurrentRuns, numDeleteAcknowledged.get());
        } finally {
            ThreadPool.terminate(testThreadPool, 500, TimeUnit.MILLISECONDS);
        }
    }

}
