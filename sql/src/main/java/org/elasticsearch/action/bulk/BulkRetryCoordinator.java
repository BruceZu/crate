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

package org.elasticsearch.action.bulk;

import com.google.common.util.concurrent.SettableFuture;
import io.crate.action.ActionListeners;
import io.crate.executor.transport.ShardRequest;
import io.crate.executor.transport.ShardResponse;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

/**
 * coordinates bulk operation retries for one node
 */
public class BulkRetryCoordinator {

    private static final ESLogger LOGGER = Loggers.getLogger(BulkRetryCoordinator.class);
    private static final int RETRY_DELAY_INCREMENT = 100;
    private static final int MAX_RETRY_DELAY = 1000;

    private final ReadWriteLock retryLock;
    private final AtomicInteger currentDelay;

    private final ScheduledExecutorService retryExecutorService;

    public BulkRetryCoordinator(Settings settings) {
        this.retryExecutorService = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory(settings, getClass().getSimpleName()));
        this.retryLock = new ReadWriteLock();
        this.currentDelay = new AtomicInteger(0);
    }

    public ReadWriteLock retryLock() {
        return retryLock;
    }

    public void retry(final int retryCount,
                      final ShardRequest request,
                      final BulkRequestExecutor executor,
                      boolean repeatingRetry,
                      ActionListener<ShardResponse> listener) {
        trace(String.format("doRetry: %d", retryCount));
        final RetryBulkActionListener retryBulkActionListener = new RetryBulkActionListener(listener);
        if (repeatingRetry) {
            ShardResponse response = null;
            int counter = retryCount;
            while (response == null) {
                try {
                    Thread.sleep(Math.min(MAX_RETRY_DELAY, currentDelay.getAndAdd(RETRY_DELAY_INCREMENT)));
                } catch (InterruptedException e) {
                    retryBulkActionListener.onFailure(e);
                    return;
                }
                counter++;
                SettableFuture<ShardResponse> future = SettableFuture.create();
                executor.execute(request, ActionListeners.wrap(future));
                try {
                    response = future.get();
                } catch (Throwable e) {
                    LOGGER.debug("Failed to get shard response: [{}]", e.getMessage(), e.getCause());
                    retryBulkActionListener.onFailure(e);
                    return;
                }
            }
            LOGGER.info("We got a shard response after {} retries: {}", counter, response);
            // we got a response - pass it to the retryBulkActionListener!
            retryBulkActionListener.onResponse(response);
        } else {
            // new retries will be spawned in new thread because they can block
            retryExecutorService.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            LOGGER.trace("retry thread [{}] started", Thread.currentThread().getName());
                            // will block if other retries/writer are active
                            try {
                                retryLock.acquireWriteLock();
                            } catch (InterruptedException e) {
                                Thread.interrupted();
                            }
                            LOGGER.trace("retry thread [{}] executing", Thread.currentThread().getName());
                            executor.execute(request, retryBulkActionListener);
                        }
                    }, currentDelay.getAndAdd(RETRY_DELAY_INCREMENT), TimeUnit.MILLISECONDS);
        }
    }

    private void trace(String message, Object ... args) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("BulkRetryCoordinator: active retries: {} - {}",
                    retryLock.activeWriters(), String.format(Locale.ENGLISH, message, args));
        }
    }

    public void close() {
        if (!retryExecutorService.isTerminated()) {
            retryExecutorService.shutdown();
            try {
                retryExecutorService.awaitTermination(currentDelay.get() + 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                retryExecutorService.shutdownNow();
            }
        }
    }

    public void shutdown() {
        retryExecutorService.shutdownNow();
    }

    private class RetryBulkActionListener implements ActionListener<ShardResponse> {

        private final ActionListener<ShardResponse> listener;

        private RetryBulkActionListener(ActionListener<ShardResponse> listener) {
            this.listener = listener;
        }

        private void reset() {
            currentDelay.set(0);
            retryLock.releaseWriteLock();
        }

        @Override
        public void onResponse(ShardResponse response) {
            reset();
            listener.onResponse(response);
        }

        @Override
        public void onFailure(Throwable e) {
            reset();
            listener.onFailure(e);
        }
    }


    /**
     * A {@link Semaphore} based read/write lock allowing multiple readers,
     * no reader will block others, and only 1 active writer. Writers take
     * precedence over readers, a writer will block all readers.
     * Compared to a {@link ReadWriteLock}, no lock is owned by a thread.
     */
    static class ReadWriteLock {
        private final Semaphore readLock = new Semaphore(1, true);
        private final Semaphore writeLock = new Semaphore(1, true);
        private final AtomicInteger activeWriters = new AtomicInteger(0);
        private final AtomicInteger waitingReaders = new AtomicInteger(0);

        public ReadWriteLock() {
        }

        public void acquireWriteLock() throws InterruptedException {
            // check readLock permits to prevent deadlocks
            if (activeWriters.getAndIncrement() == 0 && readLock.availablePermits() == 1) {
                // draining read permits, so all reads will block
                readLock.drainPermits();
            }
            writeLock.acquire();
        }

        public void releaseWriteLock() {
            if (activeWriters.decrementAndGet() == 0) {
                // unlock all readers
                readLock.release(waitingReaders.getAndSet(0)+1);
            }
            writeLock.release();
        }

        public void acquireReadLock() throws InterruptedException {
            // only acquire permit if writers are active
            if(activeWriters.get() > 0) {
                waitingReaders.getAndIncrement();
                readLock.acquire();
            }
        }

        public int activeWriters() {
            return activeWriters.get();
        }

    }
}
