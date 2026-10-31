// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes finished job executions once they are older than the configured retention. Scheduled as a
 * {@code SINGLE_INSTANCE} cron job by {@link JobMaintenanceCron}.
 *
 * <p>One run deletes in batches of {@link JobRetentionConfig#batchSize()}, each its own short
 * transaction, and stops at the first batch that comes back short (nothing more qualified that no other
 * transaction held) or
 * after {@link #MAX_BATCHES_PER_RUN} batches, whichever is first. The cap keeps one run from
 * working for hours after a long gap; the next run continues. At the default batch size a run
 * removes at most 100 000 executions, and the default cadence of 15 minutes allows about nine
 * million a day.
 *
 * <p>A failed batch fails the run, so the cron tracking record shows it, and the next run starts
 * again from the oldest qualifying execution.
 */
@Singleton
public final class JobRetentionService {

    /** The most batches one run deletes before it leaves the rest to the next run. */
    static final int MAX_BATCHES_PER_RUN = 100;

    private static final Logger log = LoggerFactory.getLogger(JobRetentionService.class);

    private final PgJobRepository repository;
    private final JobRetentionConfig config;

    /**
     * Creates the retention service.
     *
     * @param repository the PostgreSQL job repository that owns the delete
     * @param config     the retention age and batch size
     */
    @Inject
    public JobRetentionService(PgJobRepository repository, JobRetentionConfig config) {
        this.repository = repository;
        this.config = config.validate();
    }

    /**
     * Runs one retention pass.
     *
     * @return a future that completes when the pass is done; fails with the repository's cause if a
     *         batch fails
     */
    public Future<Void> purgeFinishedExecutions() {
        return purgeBatches(0, 0L);
    }

    private Future<Void> purgeBatches(int batches, long deleted) {
        Future<Integer> batch;
        try {
            batch = repository.purgeTerminalExecutions(
                    Duration.ofSeconds(config.retentionSeconds()), config.batchSize());
        } catch (RuntimeException e) {
            batch = Future.failedFuture(e);
        }
        return batch.compose(removed -> {
            int count = removed == null ? 0 : removed;
            long total = deleted + count;
            int done = batches + 1;
            if (count >= config.batchSize() && done < MAX_BATCHES_PER_RUN) {
                return purgeBatches(done, total);
            }
            if (total > 0) {
                log.info(
                        "Job execution retention removed {} finished executions in {} batches{}",
                        total,
                        done,
                        count >= config.batchSize() ? " (run cap reached; the next run continues)" : "");
            } else {
                log.debug("Job execution retention removed 0 executions");
            }
            return Future.succeededFuture();
        });
    }
}
