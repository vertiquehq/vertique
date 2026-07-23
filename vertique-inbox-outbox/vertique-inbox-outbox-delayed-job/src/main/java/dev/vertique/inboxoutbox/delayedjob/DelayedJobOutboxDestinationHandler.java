// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.job.delayed.DelayedJob;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OutboxDestinationHandler} that relays outbox entries to the delayed-job queue.
 *
 * <p>During the outbox relay phase, this handler is selected for entries whose
 * {@link DestinationType} is {@link DestinationType#DELAYED_JOB}. It reads the execution
 * defaults from {@link dev.vertique.inboxoutbox.OutboxDeliveryMetadata#delayedJob()} — a
 * {@link DelayedJobControl} snapshot persisted at publish time by
 * {@link TransactionalDelayedJobPublisher} (FR-TM-039) — and enqueues a {@link DelayedJob}
 * via {@link DelayedJobService} using the standalone (non-transactional) enqueue path
 * (FR-TM-065), since the relay operates outside the original write transaction.
 *
 * <p>Durable propagation context is forwarded from {@link OutboxEnvelope#metadata()} directly
 * into {@link DelayedJob#metadata()} so context captured at publish time (FR-CTX-175) survives
 * the relay hop and is decoded by {@code DelayedJobPoller.dispatch} on the consume side
 * (FR-CTX-177). The envelope {@code headers} are application/transport-only and are not
 * forwarded to the job.
 *
 * <p>Error classification:
 * <ul>
 *   <li>{@link OutboxPublishResult#unresolvable(String)} — the target id is not registered;
 *       the relay will back off and retry after the handler is eventually deployed.</li>
 *   <li>{@link OutboxPublishResult#permanent(String, Throwable)} — the {@code delayedJob}
 *       delivery snapshot is absent (authoring bug), or the handler name fails
 *       {@link DelayedJobService} validation; retrying would not help.</li>
 *   <li>{@link OutboxPublishResult#retryable(String, Throwable)} — any other enqueue failure,
 *       typically a transient infrastructure error.</li>
 * </ul>
 *
 * @see TransactionalDelayedJobPublisher
 * @see TransactionalMessagingDelayedJobModule
 */
@Slf4j
@Singleton
public class DelayedJobOutboxDestinationHandler implements OutboxDestinationHandler {

    // --- Dependencies ---

    private final DelayedJobService delayedJobService;
    private final DelayedJobTargetResolver resolver;

    /**
     * Creates a new delayed-job outbox destination handler.
     *
     * @param delayedJobService the service used to enqueue jobs during the relay phase
     * @param resolver          the resolver used to look up target metadata by id
     */
    @Inject
    DelayedJobOutboxDestinationHandler(DelayedJobService delayedJobService, DelayedJobTargetResolver resolver) {
        this.delayedJobService = delayedJobService;
        this.resolver = resolver;
    }

    // --- OutboxDestinationHandler ---

    /**
     * Returns {@link DestinationType#DELAYED_JOB}, indicating this handler delivers entries
     * that target the delayed-job queue.
     *
     * @return {@link DestinationType#DELAYED_JOB}
     */
    @Override
    public DestinationType destinationType() {
        return DestinationType.DELAYED_JOB;
    }

    /**
     * Declares that this node may only claim {@code DELAYED_JOB} outbox rows whose
     * {@code destination} column value is a delayed-job handler name locally registered with the
     * injected {@link DelayedJobTargetResolver}.
     *
     * <p>The supplier delegates to {@link DelayedJobTargetResolver#supportedTargetIds()}, which is
     * evaluated lazily at each claim cycle so it always reflects the handlers registered at the time
     * of the claim. Delayed-job targets are node-local — only a node whose resolver knows about a
     * given handler name can enqueue and execute the job — so using {@link ClaimScope#all()} here
     * would cause every relay node to compete for rows it cannot deliver.
     *
     * @return a {@link ClaimScope.Destinations} scope backed by the resolver's supported target ids
     */
    @Override
    public ClaimScope claimScope() {
        return ClaimScope.destinations(resolver::supportedTargetIds);
    }

    /**
     * Relays the outbox envelope to the delayed-job queue.
     *
     * <p>The destination field of the envelope is used to resolve the target metadata. Scheduling
     * defaults (queue, priority, maxAttempts) are read from the persisted
     * {@link DelayedJobControl} snapshot in
     * {@code envelope.metadata().delivery().delayedJob()} (FR-TM-064). A missing snapshot is a
     * permanent authoring error — retrying would not help. Durable propagation context from
     * {@code envelope.metadata().context()} is forwarded verbatim into {@link DelayedJob#metadata()}
     * (FR-CTX-177). The job is enqueued via the standalone (non-transactional)
     * {@link DelayedJobService#enqueue(DelayedJob)} path (FR-TM-065).
     *
     * <p>The returned {@link Future} always completes successfully with an
     * {@link OutboxPublishResult} — it never propagates a failed future.
     *
     * @param envelope the outbox entry to deliver, including payload and structured delivery metadata
     * @return a {@link Future} that completes with the delivery outcome
     */
    @Override
    public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
        // 1. Resolve target
        ResolvedDelayedJobTarget target;
        try {
            target = resolver.resolve(envelope.destination());
        } catch (IllegalArgumentException e) {
            return Future.succeededFuture(
                    OutboxPublishResult.unresolvable("Unknown delayed-job target: " + envelope.destination()));
        }

        // 2. Read snapshotted defaults from metadata.delivery.delayedJob (FR-TM-064).
        //    Absent snapshot = authoring bug; permanent failure, do not retry.
        DelayedJobControl snapshot = envelope.metadata() != null
                ? envelope.metadata().delivery().delayedJob().orElse(null)
                : null;
        if (snapshot == null) {
            return Future.succeededFuture(OutboxPublishResult.permanent(
                    "Missing delayedJob delivery snapshot in metadata — authoring bug", null));
        }

        // 3. Build DelayedJob — unwrap scalar payloads stored via PayloadCodec. The durable
        //    propagation context captured at publish is forwarded verbatim from the outbox row's
        //    metadata into the replacement delayed job so downstream handlers observe the same
        //    context (FR-CTX-178).
        Object payload = (envelope.payload() instanceof io.vertx.core.json.JsonObject jo)
                ? PayloadCodec.decode(jo)
                : envelope.payload();
        DurableMetadata context =
                envelope.metadata() != null ? envelope.metadata().context() : DurableMetadata.empty();
        DelayedJob job = DelayedJob.builder()
                .handler(target.handlerName())
                .payload(payload)
                .queue(snapshot.queue())
                .priority(snapshot.priority())
                .maxAttempts(snapshot.maxAttempts())
                .runAt(envelope.scheduledAt())
                .metadata(context)
                .build();

        // 4. Enqueue — standalone, NOT transactional (FR-TM-065)
        return delayedJobService
                .enqueue(job)
                .map(id -> (OutboxPublishResult) OutboxPublishResult.success())
                .recover(err -> {
                    if (err instanceof IllegalArgumentException) {
                        return Future.succeededFuture(OutboxPublishResult.permanent(err.getMessage(), err));
                    }
                    return Future.succeededFuture(OutboxPublishResult.retryable(err.getMessage(), err));
                });
    }
}
