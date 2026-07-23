// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;

/**
 * Typed authoring API for recording delayed-job side effects in the outbox table within a database
 * transaction.
 *
 * <p>Accepts typed delayed-job contract interfaces and serializes their payloads into the outbox
 * table alongside a snapshot of effective execution defaults (queue, priority, maxAttempts) captured
 * at write time (FR-TM-039). The relay later reads these snapshots when enqueueing the job, ensuring
 * that jobs are executed with the defaults that were in effect when the outbox entry was created,
 * even if the contract configuration changes between write and relay time.
 *
 * <p>Example usage within a transactional business operation:
 * <pre>{@code
 * Future<Void> createOrder(Order order, SqlClient tx) {
 *     return orderRepository.save(order, tx)
 *         .compose(ignored -> txPublisher.publish(SendConfirmationEmailJob.class, new EmailPayload(order.email()), tx))
 *         .mapEmpty();
 * }
 * }</pre>
 *
 * @see DelayedJobOutboxDestinationHandler
 * @see TransactionalMessagingDelayedJobModule
 */
@Singleton
public class TransactionalDelayedJobPublisher {

    // --- Dependencies ---

    private final DelayedJobTargetResolver targetResolver;
    private final OutboxService outboxService;

    /**
     * Creates a new transactional delayed-job publisher.
     *
     * @param targetResolver the resolver used to look up contract metadata at write time
     * @param outboxService  the outbox service for recording the entry within the transaction
     */
    @Inject
    TransactionalDelayedJobPublisher(DelayedJobTargetResolver targetResolver, OutboxService outboxService) {
        this.targetResolver = targetResolver;
        this.outboxService = outboxService;
    }

    // --- Public API ---

    /**
     * Records a delayed-job side effect in the outbox table for immediate execution.
     *
     * <p>Execution defaults (queue, priority, maxAttempts) are resolved from the contract and
     * snapshotted into {@link OutboxEntry#delayedJob()} at write time (FR-TM-039).
     *
     * @param <P>      the payload type; must be Jackson-serializable
     * @param contract the typed delayed-job contract interface class
     * @param payload  the job payload to serialize into the outbox entry
     * @param tx       the open database transaction to use for the outbox insert
     * @return a {@link Future} that completes with the surrogate primary key of the inserted entry,
     *         or a failed future if the contract is not registered
     */
    public <P> Future<Long> publish(Class<? extends DelayedJobClient<P>> contract, P payload, SqlClient tx) {
        return publish(contract, payload, null, tx);
    }

    /**
     * Records a delayed-job side effect in the outbox table, scheduled for a specific time.
     *
     * <p>The relay will not claim the entry until the wall-clock time passes {@code scheduledAt}.
     * Execution defaults (queue, priority, maxAttempts) are resolved from the contract and
     * snapshotted into {@link OutboxEntry#delayedJob()} at write time (FR-TM-039). They are
     * persisted in {@code metadata.delivery.delayedJob} and read back at relay time by
     * {@code DelayedJobOutboxDestinationHandler}. Application headers (if any) remain in
     * {@link OutboxEntry#headers()} unchanged.
     *
     * @param <P>         the payload type; must be Jackson-serializable
     * @param contract    the typed delayed-job contract interface class
     * @param payload     the job payload to serialize into the outbox entry
     * @param scheduledAt optional instant at which the job should become eligible for execution;
     *                    {@code null} means eligible immediately after the transaction commits
     * @param tx          the open database transaction to use for the outbox insert
     * @return a {@link Future} that completes with the surrogate primary key of the inserted entry,
     *         or a failed future if the contract is not registered
     */
    public <P> Future<Long> publish(
            Class<? extends DelayedJobClient<P>> contract, P payload, Instant scheduledAt, SqlClient tx) {
        ResolvedDelayedJobTarget target;
        try {
            target = targetResolver.resolve(contract);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        // Snapshot effective defaults at write time (FR-TM-039) — stored in metadata, not headers
        DelayedJobControl delayedJob = new DelayedJobControl(target.queue(), target.priority(), target.maxAttempts());

        OutboxEntry entry = OutboxEntry.builder()
                .destinationType(DestinationType.DELAYED_JOB)
                .destination(target.targetId())
                .eventType(target.targetId()) // handler name is the natural event type
                .payload(payload)
                .scheduledAt(scheduledAt)
                .delayedJob(delayedJob)
                .build();

        return outboxService.publish(tx, entry);
    }
}
