/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport;

import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.Streamer;
import io.crate.action.job.JobRequest;
import io.crate.action.job.JobResponse;
import io.crate.action.job.TransportJobAction;
import io.crate.breaker.RamAccountingContext;
import io.crate.executor.JobTask;
import io.crate.executor.TaskResult;
import io.crate.executor.callbacks.OperationFinishedStatsTablesCallback;
import io.crate.jobs.JobContextService;
import io.crate.jobs.PageDownstreamContext;
import io.crate.metadata.table.TableInfo;
import io.crate.operation.*;
import io.crate.operation.collect.StatsTables;
import io.crate.planner.node.ExecutionNode;
import io.crate.planner.node.ExecutionNodeVisitor;
import io.crate.planner.node.ExecutionNodes;
import io.crate.planner.node.dql.CollectNode;
import io.crate.planner.node.dql.MergeNode;
import io.crate.types.DataTypes;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.threadpool.ThreadPool;

import javax.annotation.Nonnull;
import java.util.*;


public class ExecutionNodesTask extends JobTask {

    private static final ESLogger LOGGER = Loggers.getLogger(ExecutionNodesTask.class);

    private final TransportJobAction transportJobAction;
    private final ExecutionNode[] executionNodes;
    private final List<ListenableFuture<TaskResult>> results;
    private final boolean hasDirectResponse;
    private final SettableFuture<TaskResult> result;
    private final JobContextService jobContextService;
    private final PageDownstreamFactory pageDownstreamFactory;
    private final StatsTables statsTables;
    private final ThreadPool threadPool;
    private final TransportCloseContextNodeAction transportCloseContextNodeAction;
    private final CircuitBreaker circuitBreaker;
    private MergeNode mergeNode;

    protected ExecutionNodesTask(UUID jobId,
                                 JobContextService jobContextService,
                                 PageDownstreamFactory pageDownstreamFactory,
                                 StatsTables statsTables,
                                 ThreadPool threadPool,
                                 TransportJobAction transportJobAction,
                                 TransportCloseContextNodeAction transportCloseContextNodeAction,
                                 CircuitBreaker circuitBreaker,
                                 MergeNode mergeNode,
                                 ExecutionNode... executionNodes) {
        super(jobId);
        this.jobContextService = jobContextService;
        this.pageDownstreamFactory = pageDownstreamFactory;
        this.statsTables = statsTables;
        this.threadPool = threadPool;
        this.transportCloseContextNodeAction = transportCloseContextNodeAction;
        this.circuitBreaker = circuitBreaker;
        this.mergeNode = mergeNode;
        this.transportJobAction = transportJobAction;
        this.executionNodes = executionNodes;
        result = SettableFuture.create();
        results = ImmutableList.<ListenableFuture<TaskResult>>of(result);
        hasDirectResponse = hasDirectResponse(executionNodes);
    }

    public void mergeNode(MergeNode mergeNode) {
        assert this.mergeNode == null : "can only overwrite mergeNode if it was null";
        this.mergeNode = mergeNode;
    }

    @Override
    public void start() {
        assert mergeNode != null : "mergeNode must not be null";
        RamAccountingContext ramAccountingContext = trackOperation();

        RowDownstream rowDownstream = new QueryResultRowDownstream(result, jobId(), mergeNode.executionNodeId(), jobContextService);
        PageDownstream finalMergePageDownstream = pageDownstreamFactory.createMergeNodePageDownstream(
                mergeNode,
                rowDownstream,
                ramAccountingContext,
                Optional.of(threadPool.executor(ThreadPool.Names.SEARCH))
        );

        Streamer<?>[] streamers = DataTypes.getStreamer(mergeNode.inputTypes());
        PageDownstreamContext pageDownstreamContext = new PageDownstreamContext(
                finalMergePageDownstream,
                streamers,
                executionNodes[executionNodes.length - 1].executionNodes().size()
        );
        if (!hasDirectResponse) {
            jobContextService.initializeFinalMerge(jobId(), mergeNode.executionNodeId(), pageDownstreamContext);
        }

        Map<String, Collection<ExecutionNode>> nodesByServer = groupExecutionNodesByServer(executionNodes);
        if (nodesByServer.size() == 0) {
            pageDownstreamContext.finish();
            return;
        }

        addCloseContextCallback(transportCloseContextNodeAction, executionNodes, nodesByServer.keySet());
        int idx = 0;
        for (Map.Entry<String, Collection<ExecutionNode>> entry : nodesByServer.entrySet()) {
            String serverNodeId = entry.getKey();
            Collection<ExecutionNode> executionNodes = entry.getValue();

            if (serverNodeId.equals(TableInfo.NULL_NODE_ID)) {
                serverNodeId = "_local";
                // TODO: for this to work the TransportJobAction needs to integrate handlerSideDataCollect operation ?
            }

            JobRequest request = new JobRequest(jobId(), executionNodes);
            if (hasDirectResponse) {
                transportJobAction.execute(serverNodeId, request, new DirectResponseListener(idx, streamers, pageDownstreamContext));
            } else {
                transportJobAction.execute(serverNodeId, request, new FailureOnlyResponseListener(result));
            }
            idx++;
        }
    }

    private RamAccountingContext trackOperation() {
        UUID mergeOperationId = UUID.randomUUID();
        RamAccountingContext ramAccountingContext = RamAccountingContext.forExecutionNode(circuitBreaker, mergeNode, mergeOperationId);
        statsTables.operationStarted(mergeOperationId, jobId(), "local merge in execution task");
        Futures.addCallback(result, new OperationFinishedStatsTablesCallback<>(mergeOperationId, statsTables, ramAccountingContext));
        return ramAccountingContext;
    }

    @Override
    public List<ListenableFuture<TaskResult>> result() {
        return results;
    }

    @Override
    public void upstreamResult(List<ListenableFuture<TaskResult>> result) {
        throw new UnsupportedOperationException("ExecutionNodesTask doesn't support upstreamResult");
    }

    private void addCloseContextCallback(TransportCloseContextNodeAction transportCloseContextNodeAction,
                                         final ExecutionNode[] executionNodes,
                                         final Set<String> server) {
        final ContextCloser contextCloser = new ContextCloser(transportCloseContextNodeAction);
        Futures.addCallback(result, new FutureCallback<TaskResult>() {
            @Override
            public void onSuccess(TaskResult result) {
                closeContext();
            }

            @Override
            public void onFailure(@Nonnull Throwable t) {
                closeContext();
            }

            private void closeContext() {
                if (server.isEmpty()) {
                    return;
                }
                for (ExecutionNode executionNode : executionNodes) {
                    contextCloser.process(executionNode, server);
                }
            }
        });
    }

    static boolean hasDirectResponse(ExecutionNode[] executionNodes) {
        for (ExecutionNode executionNode : executionNodes) {
            if (ExecutionNodes.hasDirectResponseDownstream(executionNode.downstreamNodes())) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Collection<ExecutionNode>> groupExecutionNodesByServer(ExecutionNode[] executionNodes) {
        ArrayListMultimap<String, ExecutionNode> executionNodesGroupedByServer = ArrayListMultimap.create();
        for (ExecutionNode executionNode : executionNodes) {
            for (String server : executionNode.executionNodes()) {
                executionNodesGroupedByServer.put(server, executionNode);
            }
        }
        return executionNodesGroupedByServer.asMap();
    }

    private static class DirectResponseListener implements ActionListener<JobResponse> {

        private final int bucketIdx;
        private final Streamer<?>[] streamer;
        private final PageDownstreamContext pageDownstreamContext;

        public DirectResponseListener(int bucketIdx, Streamer<?>[] streamer, PageDownstreamContext pageDownstreamContext) {
            this.bucketIdx = bucketIdx;
            this.streamer = streamer;
            this.pageDownstreamContext = pageDownstreamContext;
        }

        @Override
        public void onResponse(JobResponse jobResponse) {
            jobResponse.streamers(streamer);
            if (jobResponse.directResponse().isPresent()) {
                pageDownstreamContext.setBucket(bucketIdx, jobResponse.directResponse().get(), true, new PageConsumeListener() {
                    @Override
                    public void needMore() {
                        // can't page with directResult
                        finish();
                    }

                    @Override
                    public void finish() {
                        pageDownstreamContext.finish();
                    }
                });
            } else {
                pageDownstreamContext.failure(new IllegalStateException("expected directResponse but didn't get one"));
            }
        }

        @Override
        public void onFailure(Throwable e) {
            pageDownstreamContext.failure(e);
        }
    }

    private static class FailureOnlyResponseListener implements ActionListener<JobResponse> {

        private final SettableFuture<TaskResult> result;

        public FailureOnlyResponseListener(SettableFuture<TaskResult> result) {
            this.result = result;
        }

        @Override
        public void onResponse(JobResponse jobResponse) {
            if (jobResponse.directResponse().isPresent()) {
                result.setException(new IllegalStateException("Got a directResponse but didn't expect one"));
            }
        }

        @Override
        public void onFailure(Throwable e) {
            result.setException(e);
        }
    }

    private static class ContextCloser extends ExecutionNodeVisitor<Set<String>, Void> {

        private final TransportCloseContextNodeAction transportCloseContextNodeAction;

        public ContextCloser(TransportCloseContextNodeAction transportCloseContextNodeAction) {
            this.transportCloseContextNodeAction = transportCloseContextNodeAction;
        }

        @Override
        public Void visitCollectNode(final CollectNode node, Set<String> nodeIds) {
            if (node.keepContextForFetcher()) {
                LOGGER.trace("closing job context {} on {} nodes", node.jobId().get(), nodeIds.size());
                for (final String nodeId : nodeIds) {
                    transportCloseContextNodeAction.execute(nodeId, new NodeCloseContextRequest(node.jobId().get()), new ActionListener<NodeCloseContextResponse>() {
                        @Override
                        public void onResponse(NodeCloseContextResponse nodeCloseContextResponse) {
                        }

                        @Override
                        public void onFailure(Throwable e) {
                            LOGGER.warn("Closing job context {} failed on node {} with: {}", node.jobId().get(), nodeId, e.getMessage());
                        }
                    });
                }
            }
            return null;
        }
    }
}
