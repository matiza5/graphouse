package ru.yandex.market.graphouse.search.tree;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.yandex.market.graphouse.search.MetricSearch;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Dmitry Andreev <a href="mailto:AndreevDm@yandex-team.ru"></a>
 * @date 31/01/2017
 */
public class DirContentBatcher {

    private static final Logger log = LogManager.getLogger();

    private final MetricSearch metricSearch;

    private final int maxBatchSize;
    private final int batchAggregationTimeMillis;

    private final AtomicReference<Batch> currentBatch = new AtomicReference<>();
    private final Semaphore requestSemaphore;

    private final ScheduledExecutorService executorService;


    public DirContentBatcher(MetricSearch metricSearch, int maxParallelRequests,
                             int maxBatchSize, int batchAggregationTimeMillis) {
        this.metricSearch = metricSearch;
        this.maxBatchSize = maxBatchSize;
        this.batchAggregationTimeMillis = batchAggregationTimeMillis;
        executorService = Executors.newScheduledThreadPool(maxParallelRequests);
        requestSemaphore = new Semaphore(maxParallelRequests, true);
    }


    public DirContent loadDirContent(MetricDir dir) throws Exception {

        /*
         * If we have available permit we run immediately, otherwise create pending batch.
         */

        if (requestSemaphore.tryAcquire()) {
            try {
                return metricSearch.loadDirsContent(Collections.singleton(dir)).get(dir);
            } finally {
                requestSemaphore.release();
            }
        }

        Batch dirBatch = currentBatch.updateAndGet(
            batch -> {
                if (batch == null || batch.size() >= maxBatchSize) {
                    batch = createNewBatch();
                }
                batch.addToBatch(dir);
                return batch;
            });
        return dirBatch.getResult(dir);
    }

    private Batch createNewBatch() {
        Batch batch = new Batch();
        executorService.schedule(batch, batchAggregationTimeMillis, TimeUnit.MILLISECONDS);
        return batch;
    }

    private class Batch implements Runnable {

        private Map<MetricDir, SettableFuture<DirContent>> requests = new ConcurrentHashMap<>();

        @Override
        public void run() {
            requestSemaphore.acquireUninterruptibly();
            try {
                currentBatch.getAndUpdate(batch -> (batch == this) ? null : batch); // Removing this batch from current
                Map<MetricDir, DirContent> dirsContent = metricSearch.loadDirsContent(requests.keySet());

                for (Map.Entry<MetricDir, DirContent> dirDirContentEntry : dirsContent.entrySet()) {
                    requests.remove(dirDirContentEntry.getKey()).set(dirDirContentEntry.getValue());
                }

                if (!requests.isEmpty()) {
                    log.error(requests.size() + " requests without data for dirs: " + requests.entrySet());
                    throw new IllegalStateException("No data for dirs");
                }
            } catch (Exception e) {
                log.error("Failed to load content for dirs: " + requests.keySet(), e);

                for (SettableFuture<DirContent> settableFuture : requests.values()) {
                    settableFuture.setException(e);
                }
            } finally {
                requestSemaphore.release();
            }
        }

        private void addToBatch(MetricDir dir) {
            requests.computeIfAbsent(dir, metricDir -> SettableFuture.create());
        }

        private DirContent getResult(MetricDir dir) throws Exception {
            Future<DirContent> future = requests.get(dir);
            Preconditions.checkNotNull(future);
            return future.get();
        }

        private int size() {
            return requests.size();
        }
    }
}
