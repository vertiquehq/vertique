// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster-wide maintenance operations for the inbox/outbox tables. Scheduled as
 * {@code SINGLE_INSTANCE} cron jobs by {@link OutboxMaintenanceCron}.
 *
 * <ol>
 *   <li>{@link #recoverStaleLeases()} — resets entries stuck in {@code PROCESSING} state beyond
 *       the configured lease timeout. Idempotent UPDATE; safe under any cron cadence.</li>
 *   <li>{@link #cleanup()} — deletes old published, dead-letter, and inbox rows, and the receipts of
 *       administrative commands, in batches according to the configured retention windows.</li>
 * </ol>
 *
 * <h2>Cleanup failure contract</h2>
 *
 * <p>{@link #cleanup()} performs independent operations, labelled {@code published},
 * {@code deadLetter} and {@code inbox}, plus {@code operations} (receipt retention) when the service is
 * built with an {@link OutboxAdminOperationCleanup}, and reports a single composite outcome to the cron
 * timeline:
 * <ul>
 *   <li>Starts all cleanups concurrently and <strong>never short-circuits</strong> — every
 *       cleanup call always runs (subject to whatever the repository's synchronous
 *       contract permits).</li>
 *   <li>Returns success only when all of them succeed.</li>
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
    private final OutboxAdminOperationCleanup adminOperationCleanup;
    private final InboxOutboxAdminConfig adminConfig;

    /**
     * Creates a maintenance service that runs three cleanup outcomes ({@code published},
     * {@code deadLetter}, {@code inbox}) and no receipt retention. Dependency injection uses the
     * constructor that also takes the receipt cleanup; this one stays for callers that build the service
     * directly.
     *
     * @param outboxRepository repository for outbox-side maintenance (reclaimStale, cleanupPublished,
     *                         cleanupDeadLetter)
     * @param inboxRepository  repository for inbox-side maintenance (cleanup)
     * @param relayConfig      relay configuration (used for {@link OutboxRelayConfig#leaseTimeoutMs()}
     *                         when resetting stale leases)
     * @param cleanupConfig    cleanup configuration (retention windows and batch size)
     */
    public OutboxMaintenanceService(
            OutboxRepository outboxRepository,
            InboxRepository inboxRepository,
            OutboxRelayConfig relayConfig,
            InboxOutboxCleanupConfig cleanupConfig) {
        this.outboxRepository = outboxRepository;
        this.inboxRepository = inboxRepository;
        this.relayConfig = relayConfig;
        this.cleanupConfig = cleanupConfig;
        this.adminOperationCleanup = null;
        this.adminConfig = null;
    }

    /**
     * Creates a maintenance service that also deletes administration receipts past their retention window
     * as a fourth cleanup outcome labelled {@code operations}.
     *
     * @param outboxRepository      repository for outbox-side maintenance (reclaimStale, cleanupPublished,
     *                              cleanupDeadLetter)
     * @param inboxRepository       repository for inbox-side maintenance (cleanup)
     * @param relayConfig           relay configuration (used for {@link OutboxRelayConfig#leaseTimeoutMs()}
     *                              when resetting stale leases)
     * @param cleanupConfig         cleanup configuration (retention windows and batch size)
     * @param adminOperationCleanup store method that deletes administration receipts
     * @param adminConfig           administration configuration (receipt retention window)
     */
    @Inject
    public OutboxMaintenanceService(
            OutboxRepository outboxRepository,
            InboxRepository inboxRepository,
            OutboxRelayConfig relayConfig,
            InboxOutboxCleanupConfig cleanupConfig,
            OutboxAdminOperationCleanup adminOperationCleanup,
            InboxOutboxAdminConfig adminConfig) {
        this.outboxRepository = outboxRepository;
        this.inboxRepository = inboxRepository;
        this.relayConfig = relayConfig;
        this.cleanupConfig = cleanupConfig;
        this.adminOperationCleanup = Objects.requireNonNull(adminOperationCleanup, "adminOperationCleanup");
        this.adminConfig = Objects.requireNonNull(adminConfig, "adminConfig");
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
     * Runs one cleanup cycle across published, dead-letter, and inbox tables, and the administration
     * receipts when this service has a receipt cleanup. See the class-level "Cleanup failure contract" for
     * outcome semantics.
     *
     * @return a future that succeeds when every cleanup succeeds; otherwise fails with an
     *         {@link OutboxMaintenanceException} carrying every underlying cause as a suppressed
     *         exception
     */
    public Future<Void> cleanup() {
        List<String> labels = new ArrayList<>(List.of("published", "deadLetter", "inbox"));
        List<Future<Integer>> outcomes = new ArrayList<>();
        outcomes.add(outboxRepository.cleanupPublished(
                cleanupConfig.publishedRetentionDays(), cleanupConfig.cleanupBatchSize()));
        outcomes.add(outboxRepository.cleanupDeadLetter(
                cleanupConfig.deadLetterRetentionDays(), cleanupConfig.cleanupBatchSize()));
        outcomes.add(inboxRepository.cleanup(cleanupConfig.inboxRetentionDays(), cleanupConfig.cleanupBatchSize()));
        if (adminOperationCleanup != null) {
            labels.add("operations");
            outcomes.add(adminOperationCleanup.cleanupOperations(
                    adminConfig.operationRetentionDays(), cleanupConfig.cleanupBatchSize()));
        }

        CompositeFuture joined = Future.join(new ArrayList<Future<?>>(outcomes));
        return joined.transform(ar -> {
            List<Throwable> failures = new ArrayList<>();
            List<String> failedLabels = new ArrayList<>();
            for (int idx = 0; idx < joined.size(); idx++) {
                Throwable cause = joined.cause(idx);
                if (cause != null) {
                    log.warn("Outbox cleanup '{}' failed: {}", labels.get(idx), cause.getMessage());
                    failures.add(cause);
                    failedLabels.add(labels.get(idx));
                } else {
                    Integer rows = joined.resultAt(idx);
                    if (rows != null && rows > 0) {
                        log.info("Outbox cleanup '{}' removed {} rows", labels.get(idx), rows);
                    } else {
                        log.debug("Outbox cleanup '{}' removed 0 rows", labels.get(idx));
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
                    "outbox cleanup failed for [" + labelList + "] (" + failures.size() + " of " + labels.size() + "): "
                            + failures.get(0).getMessage(),
                    failures.get(0));
            for (int i = 1; i < failures.size(); i++) {
                composite.addSuppressed(failures.get(i));
            }
            return Future.failedFuture(composite);
        });
    }
}
