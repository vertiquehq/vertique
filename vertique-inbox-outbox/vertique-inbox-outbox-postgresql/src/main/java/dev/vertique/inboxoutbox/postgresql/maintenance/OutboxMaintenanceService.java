// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster-wide maintenance operations for the inbox/outbox tables. Scheduled as
 * {@code SINGLE_INSTANCE} cron jobs by {@link OutboxMaintenanceServiceImpl}.
 *
 * <ol>
 *   <li>{@link #recoverStaleLeases()} — resets entries stuck in {@code PROCESSING} state beyond
 *       the configured lease timeout. Idempotent UPDATE; safe under any cron cadence.</li>
 *   <li>{@link #cleanup()} — deletes old published, dead-letter, and inbox rows in batches
 *       according to the configured retention windows.</li>
 * </ol>
 *
 * <h2>Cleanup failure contract</h2>
 *
 * <p>{@link #cleanup()} performs three independent operations and reports a single composite
 * outcome to the cron timeline:
 * <ul>
 *   <li>Starts all three cleanups concurrently and <strong>never short-circuits</strong> — all
 *       three repository calls always run (subject to whatever the repository's synchronous
 *       contract permits).</li>
 *   <li>Returns success only when all three succeed.</li>
 *   <li>Returns failure with an {@link OutboxMaintenanceException} carrying every underlying
 *       cause as a suppressed exception when one or more fail.</li>
 * </ul>
 *
 * <p>This makes the cron tracking record accurately reflect partial failures — silently logging
 * and returning success would mark failed maintenance as successful in {@code cron_execution}.
 */
@Singleton
public final class OutboxMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenanceService.class);

    private final OutboxRepository outboxRepository;
    private final InboxRepository inboxRepository;
    private final OutboxRelayConfig relayConfig;
    private final InboxOutboxCleanupConfig cleanupConfig;

    /**
     * Creates a new maintenance service.
     *
     * @param outboxRepository repository for outbox-side maintenance (reclaimStale, cleanupPublished,
     *                         cleanupDeadLetter)
     * @param inboxRepository  repository for inbox-side maintenance (cleanup)
     * @param relayConfig      relay configuration (used for {@link OutboxRelayConfig#leaseTimeoutMs()}
     *                         when resetting stale leases)
     * @param cleanupConfig    cleanup configuration (retention windows and batch size)
     */
    @Inject
    public OutboxMaintenanceService(
            OutboxRepository outboxRepository,
            InboxRepository inboxRepository,
            OutboxRelayConfig relayConfig,
            InboxOutboxCleanupConfig cleanupConfig) {
        this.outboxRepository = outboxRepository;
        this.inboxRepository = inboxRepository;
        this.relayConfig = relayConfig;
        this.cleanupConfig = cleanupConfig;
    }

    /**
     * Resets outbox entries stuck in {@code PROCESSING} beyond the configured lease timeout.
     *
     * @return a future that completes when the reclaim is done; fails with the repository's cause
     *         on error
     */
    public Future<Void> recoverStaleLeases() {
        return outboxRepository.reclaimStale(Duration.ofMillis(relayConfig.leaseTimeoutMs()));
    }

    /**
     * Runs one cleanup cycle across published, dead-letter, and inbox tables. See the
     * class-level "Cleanup failure contract" for outcome semantics.
     *
     * @return a future that succeeds when all three cleanups succeed; otherwise fails with an
     *         {@link OutboxMaintenanceException} carrying every underlying cause as a suppressed
     *         exception
     */
    public Future<Void> cleanup() {
        Future<Integer> published = outboxRepository.cleanupPublished(
                cleanupConfig.publishedRetentionDays(), cleanupConfig.cleanupBatchSize());
        Future<Integer> deadLetter = outboxRepository.cleanupDeadLetter(
                cleanupConfig.deadLetterRetentionDays(), cleanupConfig.cleanupBatchSize());
        Future<Integer> inbox =
                inboxRepository.cleanup(cleanupConfig.inboxRetentionDays(), cleanupConfig.cleanupBatchSize());

        CompositeFuture joined = Future.join(published, deadLetter, inbox);
        return joined.transform(ar -> {
            String[] labels = {"published", "deadLetter", "inbox"};
            List<Throwable> failures = new ArrayList<>();
            List<String> failedLabels = new ArrayList<>();
            for (int idx = 0; idx < joined.size(); idx++) {
                Throwable cause = joined.cause(idx);
                if (cause != null) {
                    log.warn("Outbox cleanup '{}' failed: {}", labels[idx], cause.getMessage());
                    failures.add(cause);
                    failedLabels.add(labels[idx]);
                } else {
                    Integer rows = joined.resultAt(idx);
                    if (rows != null && rows > 0) {
                        log.info("Outbox cleanup '{}' removed {} rows", labels[idx], rows);
                    } else {
                        log.debug("Outbox cleanup '{}' removed 0 rows", labels[idx]);
                    }
                }
            }
            if (failures.isEmpty()) {
                return Future.succeededFuture();
            }
            // Wire the first failure as the primary cause so log formatters and the cron tracking
            // record's error_type column surface a concrete repository error rather than only the
            // composite wrapper. Remaining failures attach as suppressed.
            String labelList = String.join(", ", failedLabels);
            OutboxMaintenanceException composite = new OutboxMaintenanceException(
                    "outbox cleanup failed for [" + labelList + "] (" + failures.size() + " of 3): "
                            + failures.get(0).getMessage(),
                    failures.get(0));
            for (int i = 1; i < failures.size(); i++) {
                composite.addSuppressed(failures.get(i));
            }
            return Future.failedFuture(composite);
        });
    }
}
