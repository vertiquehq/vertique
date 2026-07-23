// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.exception.DurableEncodeRejectedException;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for enqueueing delayed jobs into the persistent job queue.
 *
 * <p>Supports two enqueue modes:
 * <ul>
 *   <li>{@link #enqueue(DelayedJob)} — standalone enqueue using the default connection pool.</li>
 *   <li>{@link #enqueue(DelayedJob, SqlClient)} — transactional enqueue that participates in an
 *       existing database transaction. This overload requires a {@link PgJobRepository} dependency,
 *       which is an intentional coupling to enable atomic enqueue within a PostgreSQL transaction.</li>
 * </ul>
 *
 * <p>Jobs are always persisted in {@link JobState#ENQUEUED} state. When {@link DelayedJob#runAt()}
 * is in the future, the {@code scheduled_at} column holds that future time. The
 * {@link dev.vertique.job.JobRepository#claimNextJob(String, int)} query already filters by
 * {@code scheduled_at <= NOW()}, so future-scheduled jobs automatically become claimable at the
 * right time without a separate SCHEDULED state or timer-based state transition.
 *
 * <p>The handler address stored on the {@link JobExecution} is resolved at enqueue time from the
 * {@link DelayedJobHandlerRegistrar}'s handler map. This ensures that the persisted address
 * matches the actual event bus address (from {@code ServiceMethodMeta.address()}) rather than
 * a synthetic {@code job.delayed.<name>} address.
 *
 * <p>At enqueue time, {@link #toExecution(DelayedJob)} calls
 * {@link DurableContextPropagator#mergeCaptured(DurableMetadata, String)} with
 * {@link DispatchBoundary#DELAYED_JOB} to merge any currently-bound ambient durable context into
 * the caller-supplied {@link DelayedJob#metadata()}, satisfying FR-CTX-177. The merged document is
 * persisted in {@code job_executions.metadata JSONB} and later decoded by
 * {@code DelayedJobPoller.dispatch} on the consume side.
 */
@Slf4j
@Singleton
public class DelayedJobService {

    // --- Constants ---

    /**
     * Allowed characters in a handler name: alphanumeric, dot, underscore, hyphen; 1–128 chars.
     * Prevents handler names from being used as event-bus address injection vectors.
     */
    private static final Pattern VALID_HANDLER_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");

    /**
     * The identity-snapshot durable-context namespace. Mirrors
     * {@code IdentitySnapshotDurableEncoder.NAMESPACE} in {@code vertique-security-runtime}, which is
     * package-private and thus not importable here (and importing it would invert the module
     * dependency). Identity carriage MUST flow through {@link #enqueue(DelayedJob)} →
     * {@link DurableContextPropagator#mergeCaptured(DurableMetadata, String, DurableCarrierDescriptor)}
     * so the framework binds the snapshot to this row's carrier (F5 row binding, PRD identity-002
     * §14.6/A9). A premerged identity blob is unbindable — it would carry whatever carrier its
     * producer signed rather than the delayed-job row's — and is therefore a replay vector, so
     * {@link #enqueuePremerged(DelayedJob)} rejects it (fail closed) rather than silently stripping it.
     */
    private static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    /**
     * Durable-target kind for a delayed-job row carrier. Shared by the schedule side
     * ({@link #toExecution(DelayedJob)}) and the poll side ({@code DelayedJobPoller.dispatch}) so the
     * signed carrier and the expected carrier match on {@code target.kind}. Reuses the boundary string
     * value; the two happen to coincide for the delayed-job boundary.
     */
    private static final String DELAYED_JOB_TARGET_KIND = DispatchBoundary.DELAYED_JOB;

    // --- Dependencies ---

    private final JobRepository repository;
    private final PgJobRepository pgRepository;
    private final Map<String, String> handlerAddresses;
    private final DurableContextPropagator propagator;
    private final DelayedJobExceptionMapper exceptionMapper;

    /**
     * Creates a new delayed job service.
     *
     * @param repository      the job repository for standalone enqueueing
     * @param pgRepository    the PostgreSQL repository for transactional enqueueing
     * @param registrar       the handler registrar whose address map is used to resolve handler names
     *                        to real event bus addresses at enqueue time
     * @param propagator      the durable context propagator used to merge currently-bound ambient
     *                        durable context into the job's metadata at enqueue time (FR-CTX-177)
     * @param exceptionMapper the stage-2 exception mapper that translates data-access failures into
     *                        delayed-job-domain exceptions at the enqueue boundary
     */
    @Inject
    public DelayedJobService(
            JobRepository repository,
            PgJobRepository pgRepository,
            DelayedJobHandlerRegistrar registrar,
            DurableContextPropagator propagator,
            DelayedJobExceptionMapper exceptionMapper) {
        this.repository = repository;
        this.pgRepository = pgRepository;
        this.handlerAddresses = registrar.handlerAddresses();
        this.propagator = propagator;
        this.exceptionMapper = exceptionMapper;
    }

    /**
     * Enqueues a delayed job using the default connection pool.
     *
     * <p>The job is always created in {@link JobState#ENQUEUED} state, regardless of whether
     * {@link DelayedJob#runAt()} is in the future. The {@code scheduled_at} column holds the
     * future time, and {@link JobRepository#claimNextJob(String, int)} already filters by
     * {@code scheduled_at <= NOW()}, so future-scheduled jobs become claimable at the right
     * time with zero jitter — no separate state-transition timer is needed.
     *
     * @param job the job to enqueue
     * @return a future of the saved execution ID, or a failed future if the handler name is invalid
     *         or unknown, or if a durable-context encoder rejects the enclosing capture operation
     *         (e.g. a durably-carried identity snapshot that would already be expired by
     *         {@link DelayedJob#runAt()}, F5 doomed-expiry detection)
     */
    public Future<UUID> enqueue(DelayedJob job) {
        try {
            validateJob(job);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        JobExecution execution;
        try {
            execution = toExecution(job);
        } catch (DurableEncodeRejectedException e) {
            return Future.failedFuture(e);
        }
        log.debug(
                "Enqueueing delayed job '{}' handler='{}' address='{}' state={}",
                execution.jobId(),
                job.handler(),
                execution.handler(),
                execution.state());
        return repository
                .save(execution)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "delayed job enqueue")));
    }

    /**
     * Enqueues a delayed job within an existing database transaction.
     *
     * <p>Use this variant to atomically enqueue a job alongside other database writes within
     * the same transaction — for example, when creating an entity and scheduling a follow-up
     * notification in a single atomic operation.
     *
     * @param job    the job to enqueue
     * @param client the SQL client (connection or transaction) to execute against
     * @return a future of the saved execution ID, or a failed future if the handler name is invalid
     *         or unknown, or if a durable-context encoder rejects the enclosing capture operation
     *         (e.g. a durably-carried identity snapshot that would already be expired by
     *         {@link DelayedJob#runAt()}, F5 doomed-expiry detection)
     */
    public Future<UUID> enqueue(DelayedJob job, SqlClient client) {
        try {
            validateJob(job);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        JobExecution execution;
        try {
            execution = toExecution(job);
        } catch (DurableEncodeRejectedException e) {
            return Future.failedFuture(e);
        }
        log.debug(
                "Enqueueing delayed job '{}' handler='{}' address='{}' state={} (transactional)",
                execution.jobId(),
                job.handler(),
                execution.handler(),
                execution.state());
        return pgRepository
                .save(execution, client)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "delayed job enqueue transactional")));
    }

    /**
     * Enqueues a delayed job using the default connection pool, persisting {@link DelayedJob#metadata()}
     * as-is — without running {@link DurableContextPropagator#mergeCaptured}.
     *
     * <p><strong>Invoked only by {@link DelayedJobClientProxy} and the generated
     * {@code {Contract}_DelayedJobProxy}</strong> when {@link DelayedJobOptions#premergedMetadata()} is
     * non-{@code null}. Normal callers must use {@link #enqueue(DelayedJob)} so that ambient durable
     * context is captured automatically. {@code public} (rather than package-private) so the generated
     * proxy — which lands in the contract's own package — can call it; see ADR-0071.
     *
     * <p>The caller is responsible for supplying a fully merged metadata document. No collision
     * detection runs; the document is written verbatim into {@code job_executions.metadata JSONB}.
     *
     * <p><strong>Invariant:</strong> {@code job.metadata()} must be a document produced by the
     * framework's durable-context capture/merge step (via {@link DelayedJobOptions#premergedMetadata()}),
     * never a hand-built map. Bypassing that step with arbitrary metadata silently drops ambient durable
     * context and is unsupported.
     *
     * <p><strong>Fail-closed on identity carriage (F5, PRD identity-002 §14.6/A9).</strong> A
     * pre-merged document carrying the identity-snapshot namespace is rejected with a failed future:
     * identity carriage must flow through {@link #enqueue(DelayedJob)} so the framework binds the
     * snapshot to this row's carrier. See {@link #validatePremergedMetadata(DelayedJob)}.
     *
     * @param job the job to enqueue; {@link DelayedJob#metadata()} is persisted as-is
     * @return a future of the saved execution ID, or a failed future if the handler name is invalid or
     *         unknown, or if the pre-merged metadata carries the identity-snapshot namespace
     */
    public Future<UUID> enqueuePremerged(DelayedJob job) {
        try {
            validateJob(job);
            validatePremergedMetadata(job);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        JobExecution execution = toExecutionWithMetadata(job, job.metadata(), UUID.randomUUID());
        log.debug(
                "Enqueueing pre-merged delayed job '{}' handler='{}' address='{}' state={} (no-recapture)",
                execution.jobId(),
                job.handler(),
                execution.handler(),
                execution.state());
        return repository
                .save(execution)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "delayed job enqueuePremerged")));
    }

    /**
     * Enqueues a delayed job within an existing database transaction, persisting
     * {@link DelayedJob#metadata()} as-is — without running
     * {@link DurableContextPropagator#mergeCaptured}.
     *
     * <p><strong>Invoked only by {@link DelayedJobClientProxy} and the generated
     * {@code {Contract}_DelayedJobProxy}</strong> when {@link DelayedJobOptions#premergedMetadata()} is
     * non-{@code null}. Normal callers must use {@link #enqueue(DelayedJob, SqlClient)} so that ambient
     * durable context is captured automatically. {@code public} (rather than package-private) so the
     * generated proxy — which lands in the contract's own package — can call it; see ADR-0071.
     *
     * <p>The caller is responsible for supplying a fully merged metadata document. No collision
     * detection runs; the document is written verbatim into {@code job_executions.metadata JSONB}.
     *
     * <p><strong>Fail-closed on identity carriage (F5, PRD identity-002 §14.6/A9).</strong> A
     * pre-merged document carrying the identity-snapshot namespace is rejected with a failed future —
     * see {@link #validatePremergedMetadata(DelayedJob)}.
     *
     * @param job    the job to enqueue; {@link DelayedJob#metadata()} is persisted as-is
     * @param client the SQL client (connection or transaction) to execute against
     * @return a future of the saved execution ID, or a failed future if the handler name is invalid or
     *         unknown, or if the pre-merged metadata carries the identity-snapshot namespace
     */
    public Future<UUID> enqueuePremerged(DelayedJob job, SqlClient client) {
        try {
            validateJob(job);
            validatePremergedMetadata(job);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }
        JobExecution execution = toExecutionWithMetadata(job, job.metadata(), UUID.randomUUID());
        log.debug(
                "Enqueueing pre-merged delayed job '{}' handler='{}' address='{}' state={} (no-recapture, transactional)",
                execution.jobId(),
                job.handler(),
                execution.handler(),
                execution.state());
        return pgRepository
                .save(execution, client)
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "delayed job enqueuePremerged transactional")));
    }

    // --- Validation ---

    /**
     * Validates the handler name and {@code maxAttempts} fields of the given job.
     *
     * @param job the job to validate
     * @throws IllegalArgumentException if the handler name is invalid, unknown, or maxAttempts is
     *                                  out of the [1, 1000] range
     */
    private void validateJob(DelayedJob job) {
        if (job.handler() == null || !VALID_HANDLER_NAME.matcher(job.handler()).matches()) {
            throw new IllegalArgumentException(
                    "Invalid handler name: '" + job.handler() + "'. Must match pattern [a-zA-Z0-9._-]{1,128}");
        }
        if (!handlerAddresses.containsKey(job.handler())) {
            throw new IllegalArgumentException("Unknown delayed job handler: '" + job.handler()
                    + "'. Registered handlers: " + handlerAddresses.keySet());
        }
        if (job.maxAttempts() < 1 || job.maxAttempts() > 1000) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 1000, got: " + job.maxAttempts());
        }
    }

    /**
     * Rejects a pre-merged metadata document that carries the {@value #IDENTITY_SNAPSHOT_NAMESPACE}
     * namespace. Identity carriage must flow through {@link #enqueue(DelayedJob)} so the framework
     * binds the durable snapshot to this row's carrier (F5 row binding, PRD identity-002 §14.6/A9); a
     * caller-supplied identity blob would carry whatever carrier its producer signed rather than the
     * delayed-job row's, making it a replay/transplant vector. Fail closed rather than silently strip
     * so a misuse surfaces loudly.
     *
     * @param job the pre-merged job whose {@link DelayedJob#metadata()} is checked
     * @throws IllegalArgumentException if {@code job.metadata()} contains the
     *                                  {@value #IDENTITY_SNAPSHOT_NAMESPACE} namespace
     */
    private void validatePremergedMetadata(DelayedJob job) {
        if (job.metadata() != null && job.metadata().has(IDENTITY_SNAPSHOT_NAMESPACE)) {
            throw new IllegalArgumentException("premerged metadata must not carry the '" + IDENTITY_SNAPSHOT_NAMESPACE
                    + "' namespace: identity carriage must flow through enqueue() so the durable snapshot is bound to "
                    + "this delayed-job row's carrier (F5); a premerged identity blob is unbindable and thus a replay "
                    + "vector");
        }
    }

    // --- Conversion ---

    /**
     * Converts a {@link DelayedJob} into a {@link JobExecution}, merging ambient durable context
     * via {@link DurableContextPropagator#mergeCaptured(DurableMetadata, String)} with the
     * {@link DispatchBoundary#DELAYED_JOB} boundary (FR-CTX-177).
     *
     * <p>The execution is always created in {@link JobState#ENQUEUED} state. When
     * {@link DelayedJob#runAt()} is in the future, {@code scheduledAt} holds that future time.
     * The poller's {@code claimNextJob} query filters by {@code scheduled_at <= NOW()}, making
     * the job claimable only after the scheduled time passes — no separate SCHEDULED state or
     * state-transition timer is needed.
     *
     * <p>The handler field on the execution is set to the real event bus address resolved from
     * {@link DelayedJobHandlerRegistrar#handlerAddresses()}, not a synthetic address.
     *
     * <p>A caller-supplied namespace that collides with a captured encoder's namespace while that
     * encoder's type is currently bound throws {@link IllegalStateException} (FR-CTX-153).
     *
     * <p><strong>F5 row binding (PRD identity-002 §14.6/A9).</strong> The {@link JobExecution} id is
     * allocated <em>before</em> {@code mergeCaptured} and threaded into the encode context as a
     * {@link DurableCarrierDescriptor} ({@code carrierId} = this execution id; target
     * {@code (delayed-job, handler-address)}). A durable identity snapshot captured here is thus signed
     * for this exact row, so it cannot be transplanted onto another delayed-job row. The same id is
     * reused for the persisted execution — never a second UUID — so the signed carrier matches the row
     * the poller later dispatches.
     *
     * <p><strong>F5 doomed-expiry detection (PRD identity-002 §14.6/A9).</strong> The row's scheduled
     * fire time — {@link DelayedJob#runAt()}, or "now" when unset — is computed once here and threaded
     * into both the encode context (via {@link DurableContextPropagator#mergeCaptured(DurableMetadata,
     * String, DurableCarrierDescriptor, Instant)}) and the persisted execution's {@code scheduledAt},
     * so a durable-context encoder can detect that content it is about to sign would already be stale
     * by the time this row fires and reject the enqueue outright
     * ({@link dev.vertique.core.exception.DurableEncodeRejectedException}) rather than persisting a
     * doomed document.
     *
     * @param job the delayed job descriptor
     * @return the corresponding job execution record
     * @throws IllegalStateException if a caller-supplied metadata namespace collides with an
     *                               ambient durable context namespace that is currently bound
     *                               (FR-CTX-153)
     * @throws dev.vertique.core.exception.DurableEncodeRejectedException if a durable-context encoder
     *                               rejects the enclosing capture operation (F5 doomed-expiry
     *                               detection)
     */
    private JobExecution toExecution(DelayedJob job) {
        // Allocate the execution id up front so the durable carrier is bound to this exact row, and
        // reuse the same id for the persisted execution (single-id allocation, F5).
        UUID executionId = UUID.randomUUID();
        String address = handlerAddresses.get(job.handler());
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                executionId.toString(), new DurableTarget(DELAYED_JOB_TARGET_KIND, address, Optional.empty()));
        // Computed once and reused for both the encode-context fireTime and the persisted
        // scheduledAt, so the doomed-expiry check and the row's actual schedule never disagree.
        Instant scheduledAt = job.runAt() != null ? job.runAt() : Instant.now();
        DurableMetadata callerMetadata = job.metadata() != null ? job.metadata() : DurableMetadata.empty();
        DurableMetadata metadata =
                propagator.mergeCaptured(callerMetadata, DispatchBoundary.DELAYED_JOB, carrier, scheduledAt);
        return toExecutionWithMetadata(job, metadata, executionId, scheduledAt);
    }

    /**
     * Converts a {@link DelayedJob} into a {@link JobExecution} using the supplied
     * {@link DurableMetadata} document verbatim — no {@code mergeCaptured} is performed.
     *
     * <p>Used by {@link #enqueuePremerged(DelayedJob)} and
     * {@link #enqueuePremerged(DelayedJob, SqlClient)} to persist a caller-assembled,
     * already-merged metadata document without triggering a second capture pass or collision
     * detection. Computes {@code scheduledAt} from {@link DelayedJob#runAt()} (or "now" when unset)
     * itself, since these callers never compute it ahead of a {@code mergeCaptured} call the way
     * {@link #toExecution(DelayedJob)} does.
     *
     * @param job         the delayed job descriptor
     * @param metadata    the {@link DurableMetadata} document to persist as-is into
     *                    {@code job_executions.metadata}
     * @param executionId the pre-allocated execution id to persist; the caller allocates it up front
     *                    so the schedule-side durable carrier is bound to this exact row (F5 row
     *                    binding, PRD identity-002 §14.6/A9)
     * @return the corresponding job execution record
     */
    private JobExecution toExecutionWithMetadata(DelayedJob job, DurableMetadata metadata, UUID executionId) {
        return toExecutionWithMetadata(job, metadata, executionId, job.runAt() != null ? job.runAt() : Instant.now());
    }

    /**
     * Converts a {@link DelayedJob} into a {@link JobExecution} using the supplied
     * {@link DurableMetadata} document verbatim and a pre-computed {@code scheduledAt}, so
     * {@link #toExecution(DelayedJob)} can reuse the exact instant it already threaded into
     * {@link DurableContextPropagator#mergeCaptured(DurableMetadata, String, DurableCarrierDescriptor,
     * Instant)} as the row's fire time, rather than computing "now" a second time.
     *
     * @param job         the delayed job descriptor
     * @param metadata    the {@link DurableMetadata} document to persist as-is into
     *                    {@code job_executions.metadata}
     * @param executionId the pre-allocated execution id to persist; the caller allocates it up front
     *                    so the schedule-side durable carrier is bound to this exact row (F5 row
     *                    binding, PRD identity-002 §14.6/A9)
     * @param scheduledAt the resolved fire time to persist as {@code scheduledAt}
     * @return the corresponding job execution record
     */
    private JobExecution toExecutionWithMetadata(
            DelayedJob job, DurableMetadata metadata, UUID executionId, Instant scheduledAt) {
        String jobId = job.jobId() != null ? job.jobId() : "delayed-" + UUID.randomUUID();
        JobState state = JobState.ENQUEUED; // claimNextJob filters by scheduled_at <= NOW()
        String address = handlerAddresses.get(job.handler());

        return new JobExecution(
                executionId,
                jobId,
                JobType.DELAYED,
                address,
                job.queue(),
                state,
                0,
                job.maxAttempts(),
                job.payload(),
                job.priority(),
                null, // lockedBy
                scheduledAt,
                null, // enqueuedAt
                null, // startedAt
                null, // completedAt
                null, // errorMessage
                null, // errorType
                ProgressSnapshot.EMPTY,
                Map.of(), // parameters
                Map.of(), // attributes
                metadata);
    }
}
