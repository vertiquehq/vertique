// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import dev.vertique.security.runtime.IdentitySnapshotCodec;
import dev.vertique.security.runtime.IdentitySnapshotContext;
import dev.vertique.security.runtime.IdentitySnapshotDurableDecoder;
import dev.vertique.security.runtime.IdentitySnapshotDurableEncoder;
import dev.vertique.security.runtime.SnapshotFreshnessPolicy;
import dev.vertique.security.runtime.SnapshotHmac;
import dev.vertique.workflow.delayed.WorkflowTimerCarriers;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Durable-bind tests for {@link WorkflowTimerRecoveryService}'s terminal recovery paths.
 *
 * <p>Reconciliation of a {@code DEAD_LETTER} / {@code SUCCEEDED} / {@code CANCELLED} delayed-job
 * calls {@link TransactionalTimerCallbacks#timerFiringFailed}, which emits {@code WORKFLOW_FAILED}
 * through the engine's outbox-event recorder. The recorder captures ambient durable context via
 * {@code mergeCaptured(...)}. Without rebinding the timer row's metadata, the cron sweep's empty
 * ambient context would be persisted on the {@code workflow_events} outbox row instead of the
 * timer's original context — breaking durable propagation for downstream consumers.
 *
 * <p>This test asserts that the recovery service binds {@code row.metadata()} on the holder
 * before invoking {@code timerFiringFailed}, so a {@code mergeCaptured(DurableMetadata.empty(), DELAYED_JOB)}
 * call inside the callback's transaction body sees the timer's original durable values.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowTimerRecoveryDurableBindTest {

    /**
     * Minimal {@link ContextValue} wrapper for a locale string, used as the durable context type in
     * this test so that the codec's type parameter satisfies the {@code <T extends ContextValue>}
     * bound introduced by the upcoming holder write-path constraint.
     *
     * @param value the locale string (e.g. {@code "fr_FR-row"})
     */
    record StringCtx(String value) implements ContextValue {}

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String LOCALE_KEY = "x-locale";

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    private static DurableContextPropagator propagatorWithStringEncoder(DefaultContextHolder holder) {
        DurableContextMetadataEncoder<StringCtx> encoder = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return LOCALE_KEY;
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of(LOCALE_KEY, new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataDecoder<StringCtx> decoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return LOCALE_KEY;
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(LOCALE_KEY)
                        .map(body -> body.getString("value"))
                        .map(StringCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(encoder), Set.of(decoder));
        return new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
    }

    @Test
    @DisplayName(
            "DEAD_LETTER recovery binds row.metadata() before timerFiringFailed so the callback observes the typed value")
    void deadLetterRebindsDurableContext(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = propagatorWithStringEncoder(holder);

        Pool pool = org.mockito.Mockito.mock();
        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        JobRepository jobRepository = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        TransactionalTimerCallbacks<SqlClient> callbacks = org.mockito.Mockito.mock();
        SqlConnection conn = org.mockito.Mockito.mock();

        // Mock pool.withTransaction to invoke the lambda with the test connection.
        when(pool.withTransaction(any())).thenAnswer(inv -> {
            java.util.function.Function<SqlConnection, Future<Object>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        UUID workflowId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        TimerRecord row = new TimerRecord(
                timerId,
                new WorkflowInstanceId(workflowId),
                "step-1",
                FIXED_NOW.minusSeconds(60),
                TimerStatus.SCHEDULED,
                jobId,
                FIXED_NOW.minusSeconds(120),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                DurableMetadata.of(LOCALE_KEY, new JsonObject().put("value", "fr_FR-row")));

        JobExecution deadJob = new JobExecution(
                jobId,
                "wf-timer",
                JobType.DELAYED,
                "workflow-timer-handler",
                "default",
                JobState.DEAD_LETTER,
                3,
                3,
                io.vertx.core.json.JsonObject.of(),
                0,
                null,
                FIXED_NOW.minusSeconds(120),
                FIXED_NOW.minusSeconds(120),
                FIXED_NOW.minusSeconds(60),
                FIXED_NOW.minusSeconds(30),
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());

        // Use AtomicReference so the lambda's captured mergeCaptured result survives back to the
        // assertion block.
        java.util.concurrent.atomic.AtomicReference<DurableMetadata> capturedInsideCallback =
                new java.util.concurrent.atomic.AtomicReference<>();

        when(jobRepository.findById(jobId)).thenReturn(Future.succeededFuture(Optional.of(deadJob)));
        when(timerStore.findRecoverableScheduled(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Future.succeededFuture(java.util.List.of(row)));
        when(callbacks.timerFiringFailed(any(), eq(timerId), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Inside the callback's tx body, capture the holder's view via mergeCaptured.
                    // This mirrors what the production WorkflowEventSideEffectRecorder does when
                    // WORKFLOW_FAILED is emitted by timerFiringFailed.
                    DurableMetadata merged =
                            propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB);
                    capturedInsideCallback.set(merged);
                    return Future.succeededFuture(TimerFiringResult.APPLIED);
                });

        dev.vertique.context.InboundExecutionContextScope inboundExecutionContextScope =
                new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, Set.of());
        WorkflowTimerRecoveryService service = new WorkflowTimerRecoveryService(
                pool,
                timerStore,
                jobRepository,
                fireJob,
                () -> callbacks,
                WorkflowTimerRecoveryConfig.defaults(),
                FIXED_CLOCK,
                inboundExecutionContextScope);

        runOnDuplicated(vertx, v -> service.reconcile()
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    DurableMetadata captured = capturedInsideCallback.get();
                    assertThat(captured.body(LOCALE_KEY).map(body -> body.getString("value")))
                            .as("mergeCaptured inside timerFiringFailed must observe the rebound row metadata")
                            .contains("fr_FR-row");
                    // After the reconcile scope closes, the holder should return to its pre-call
                    // state (no StringCtx binding installed by the test setup, so empty).
                    assertThat(holder.current(StringCtx.class))
                            .as("durable scope must close after reconcile so the cron sweep's holder state is restored")
                            .isEmpty();
                    ctx.completeNow();
                })));
    }

    // --- Orphan re-enqueue carrier verification (P2.S0 commit 4: workflow-timer carrier binding) ---

    @Test
    @DisplayName("orphan recovery reconstructs the expected workflow-timer carrier from the row's own columns "
            + "and verifies row.metadata() against it before re-enqueueing")
    void orphanReEnqueueVerifiesAgainstReproducedTimerCarrier(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        java.util.concurrent.atomic.AtomicReference<DurableDecodeContext> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        DurableContextMetadataDecoder<StringCtx> recordingDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return LOCALE_KEY;
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                observed.set(context);
                return metadata.body(LOCALE_KEY)
                        .map(body -> body.getString("value"))
                        .map(StringCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(recordingDecoder));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        Pool pool = org.mockito.Mockito.mock();
        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        JobRepository jobRepository = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        TransactionalTimerCallbacks<SqlClient> callbacks = org.mockito.Mockito.mock();
        SqlConnection conn = org.mockito.Mockito.mock();

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            java.util.function.Function<SqlConnection, Future<Object>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        UUID workflowId = UUID.randomUUID();
        UUID timerId = UUID.randomUUID();
        UUID oldExecId = UUID.randomUUID();
        UUID newExecId = UUID.randomUUID();

        TimerRecord row = new TimerRecord(
                timerId,
                new WorkflowInstanceId(workflowId),
                "step-1",
                FIXED_NOW.minusSeconds(60),
                TimerStatus.SCHEDULED,
                oldExecId,
                FIXED_NOW.minusSeconds(120),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                DurableMetadata.of(LOCALE_KEY, new JsonObject().put("value", "carrier-recovery")));

        when(timerStore.findRecoverableScheduled(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Future.succeededFuture(java.util.List.of(row)));
        when(jobRepository.findById(oldExecId)).thenReturn(Future.succeededFuture(Optional.empty()));
        when(fireJob.enqueue(any(), any(), any())).thenReturn(Future.succeededFuture(newExecId));
        when(timerStore.updateExecutionId(eq(timerId), eq(newExecId), any())).thenReturn(Future.succeededFuture());

        dev.vertique.context.InboundExecutionContextScope inboundExecutionContextScope =
                new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, Set.of());
        WorkflowTimerRecoveryService service = new WorkflowTimerRecoveryService(
                pool,
                timerStore,
                jobRepository,
                fireJob,
                () -> callbacks,
                WorkflowTimerRecoveryConfig.defaults(),
                FIXED_CLOCK,
                inboundExecutionContextScope);

        runOnDuplicated(vertx, v -> service.reconcile()
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    DurableDecodeContext decodeContext = observed.get();
                    assertThat(decodeContext)
                            .as("orphan recovery must decode/verify row.metadata() before re-enqueueing — the "
                                    + "decoder was never invoked, meaning no carrier verification happened")
                            .isNotNull();
                    assertThat(decodeContext.carrier())
                            .as("recovery must thread the reproduced workflow-timer carrier into the verify/bind step")
                            .isPresent();
                    DurableCarrierDescriptor carrier = decodeContext.carrier().orElseThrow();
                    assertThat(carrier.target().kind())
                            .as("target kind must identify the workflow-timer carrier")
                            .isEqualTo("workflow-timer");
                    assertThat(carrier.carrierId())
                            .as("carrierId must equal the row's own timerId, reproduced from workflow_timers columns")
                            .isEqualTo(timerId.toString());
                    assertThat(carrier.target().address())
                            .as("target address must equal the row's own workflowId's raw stable UUID value — "
                                    + "never the record's auto-toString() — reproduced from workflow_timers columns")
                            .isEqualTo(workflowId.toString());
                    ctx.completeNow();
                })));
    }

    // --- F1 real-codec proof: verify-bind-re-encode of a genuine identity snapshot (P2.S0 security review) ---

    @Test
    @DisplayName("orphan recovery re-encodes a genuinely verified identity snapshot for the new execution carrier "
            + "with the REAL codec, preserving capturedAt, and never throws IllegalStateException (F1)")
    void orphanReEnqueueReEncodesVerifiedIdentitySnapshotWithRealCodec(Vertx vertx, VertxTestContext ctx) {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        // The codec's clock is fixed at the SAME instant as the recovery service's own FIXED_CLOCK
        // (rather than the codec's real-system-clock default) so decode-time freshness checks are
        // deterministic regardless of the real wall-clock date the test suite runs on — otherwise a
        // snapshot captured at a fixed test instant could look stale (or impossibly future-dated)
        // depending on how much real time has passed since this class's FIXED_NOW constant.
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(
                hmac,
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), FIXED_CLOCK));
        IdentitySnapshotDurableEncoder identityEncoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder identityDecoder = new IdentitySnapshotDurableDecoder(codec);

        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(identityEncoder), Set.of(identityDecoder));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        Pool pool = org.mockito.Mockito.mock();
        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        JobRepository jobRepository = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        TransactionalTimerCallbacks<SqlClient> callbacks = org.mockito.Mockito.mock();
        SqlConnection conn = org.mockito.Mockito.mock();

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            java.util.function.Function<SqlConnection, Future<Object>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        UUID workflowId = UUID.randomUUID();
        WorkflowInstanceId workflowInstanceId = new WorkflowInstanceId(workflowId);
        UUID timerId = UUID.randomUUID();
        UUID oldExecId = UUID.randomUUID();
        UUID newExecId = UUID.randomUUID();

        // The row's persisted metadata carries a genuinely signed-and-verifiable snapshot bound to
        // this exact timer's reproducible workflow-timer carrier — exactly what
        // WorkflowTimerSideEffectRecorder signs at timer-create time.
        DurableCarrierDescriptor timerCarrier = WorkflowTimerCarriers.of(timerId, workflowInstanceId);
        Instant capturedAt = FIXED_NOW.minusSeconds(600);
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                capturedAt,
                Optional.empty(),
                java.util.List.of(),
                "rest:authenticated",
                capturedAt);
        IdentitySnapshot originalSnapshot = new IdentitySnapshot(
                2,
                content,
                new SnapshotCarrierBinding(timerCarrier.carrierId(), timerCarrier.target()),
                capturedAt,
                capturedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));
        byte[] signed = codec.encode(originalSnapshot);
        DurableMetadata rowMetadata = DurableMetadata.of(
                "identity-snapshot",
                new JsonObject()
                        .put("snapshot", java.util.Base64.getEncoder().encodeToString(signed))
                        .put("present", true));

        TimerRecord row = new TimerRecord(
                timerId,
                workflowInstanceId,
                "step-1",
                FIXED_NOW.minusSeconds(60),
                TimerStatus.SCHEDULED,
                oldExecId,
                FIXED_NOW.minusSeconds(120),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                rowMetadata);

        when(timerStore.findRecoverableScheduled(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Future.succeededFuture(java.util.List.of(row)));
        when(jobRepository.findById(oldExecId)).thenReturn(Future.succeededFuture(Optional.empty()));

        // The new execution row's own carrier — DISTINCT from the workflow-timer carrier — mirrors
        // the real DurableCarrierDescriptor DelayedJobService.toExecution allocates for the
        // replacement delayed-job row.
        DurableCarrierDescriptor newExecutionCarrier = new DurableCarrierDescriptor(
                newExecId.toString(),
                new DurableTarget(DispatchBoundary.DELAYED_JOB, "job.delayed.workflow-timer-fire", Optional.empty()));
        java.util.concurrent.atomic.AtomicReference<DurableMetadata> reEncodedMetadata =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(fireJob.enqueue(any(), any(), any())).thenAnswer(inv -> {
            // Mirrors what the real DelayedJobService.toExecution does at enqueue time inside the
            // scope reenqueueOrphan installs: capture whatever identity context is currently bound
            // (the verified snapshot decoded off row.metadata() against the workflow-timer carrier)
            // and re-encode it for THIS new execution row's own carrier.
            DurableMetadata merged = propagator.mergeCaptured(
                    DurableMetadata.empty(), DispatchBoundary.DELAYED_JOB, newExecutionCarrier);
            reEncodedMetadata.set(merged);
            return Future.succeededFuture(newExecId);
        });
        when(timerStore.updateExecutionId(eq(timerId), eq(newExecId), any())).thenReturn(Future.succeededFuture());

        dev.vertique.context.InboundExecutionContextScope inboundExecutionContextScope =
                new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, Set.of());
        WorkflowTimerRecoveryService service = new WorkflowTimerRecoveryService(
                pool,
                timerStore,
                jobRepository,
                fireJob,
                () -> callbacks,
                WorkflowTimerRecoveryConfig.defaults(),
                FIXED_CLOCK,
                inboundExecutionContextScope);

        runOnDuplicated(vertx, v -> service.reconcile()
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    DurableMetadata merged = reEncodedMetadata.get();
                    assertThat(merged)
                            .as("orphan re-enqueue must have re-encoded through the bound identity context")
                            .isNotNull();
                    assertThat(merged.has("identity-snapshot"))
                            .as("re-enqueue must produce a re-signed identity-snapshot namespace for the new "
                                    + "execution row — no IllegalStateException on re-encode of a verified"
                                    + " receive-side context")
                            .isTrue();

                    ContextDecodeResult<IdentitySnapshotContext> decoded = identityDecoder.decode(
                            merged,
                            new DurableDecodeContext(DispatchBoundary.DELAYED_JOB, Optional.of(newExecutionCarrier)));
                    assertThat(decoded.value())
                            .as("the re-signed envelope must decode and verify against the NEW execution carrier")
                            .isPresent();
                    IdentitySnapshotContext reconstructed = decoded.value().orElseThrow();
                    assertThat(reconstructed.verified())
                            .as("recovery's re-encode must produce a verified re-signed snapshot, not an"
                                    + " unverifiable one")
                            .isTrue();
                    assertThat(reconstructed.snapshot().orElseThrow().content().capturedAt())
                            .as("capturedAt must be preserved unchanged across the recovery re-encode (anti-renewal"
                                    + " freshness anchor)")
                            .isEqualTo(capturedAt);
                    assertThat(reconstructed.snapshot().orElseThrow().carrier().carrierId())
                            .as("the re-signed envelope must be bound to the NEW execution carrier, not the"
                                    + " original workflow-timer carrier")
                            .isEqualTo(newExecId.toString());
                    ctx.completeNow();
                })));
    }

    // --- Terminal recovery carrier verification (P2.S0 F2: terminal-path carrier consistency) ---

    @Test
    @DisplayName("DEAD_LETTER terminal recovery binds row.metadata() against the workflow-timer carrier so a "
            + "genuinely signed identity snapshot VERIFIES instead of binding unverifiable")
    void terminalRecoveryBindsIdentityForWorkflowTimerCarrier(Vertx vertx, VertxTestContext ctx) {
        SnapshotHmac hmac = new SnapshotHmac(Map.of("key-1", "super-secret-signing-key-material"), "key-1");
        // Same fixed-clock rationale as orphanReEnqueueReEncodesVerifiedIdentitySnapshotWithRealCodec: keep
        // decode-time freshness checks deterministic regardless of the real wall-clock date the suite runs on.
        IdentitySnapshotCodec codec = new IdentitySnapshotCodec(
                hmac,
                new SnapshotFreshnessPolicy(Optional.empty(), Optional.empty(), Duration.ofSeconds(30), FIXED_CLOCK));
        IdentitySnapshotDurableEncoder identityEncoder = new IdentitySnapshotDurableEncoder(codec);
        IdentitySnapshotDurableDecoder identityDecoder = new IdentitySnapshotDurableDecoder(codec);

        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(identityEncoder), Set.of(identityDecoder));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        Pool pool = org.mockito.Mockito.mock();
        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        JobRepository jobRepository = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        TransactionalTimerCallbacks<SqlClient> callbacks = org.mockito.Mockito.mock();
        SqlConnection conn = org.mockito.Mockito.mock();

        when(pool.withTransaction(any())).thenAnswer(inv -> {
            java.util.function.Function<SqlConnection, Future<Object>> fn = inv.getArgument(0);
            return fn.apply(conn);
        });

        UUID workflowId = UUID.randomUUID();
        WorkflowInstanceId workflowInstanceId = new WorkflowInstanceId(workflowId);
        UUID timerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        // The row's persisted metadata carries a genuinely signed-and-verifiable snapshot bound to this
        // exact timer's reproducible workflow-timer carrier — exactly what WorkflowTimerSideEffectRecorder
        // signs at timer-create time. The terminal recovery path must reproduce that same carrier to
        // verify it, not bind the boundary-only (unbound-sentinel) carrier.
        DurableCarrierDescriptor timerCarrier = WorkflowTimerCarriers.of(timerId, workflowInstanceId);
        Instant capturedAt = FIXED_NOW.minusSeconds(600);
        IdentitySnapshotContent content = new IdentitySnapshotContent(
                new PrincipalRef(PrincipalType.SERVICE, "svc-scheduler", Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                capturedAt,
                Optional.empty(),
                java.util.List.of(),
                "rest:authenticated",
                capturedAt);
        IdentitySnapshot originalSnapshot = new IdentitySnapshot(
                2,
                content,
                new SnapshotCarrierBinding(timerCarrier.carrierId(), timerCarrier.target()),
                capturedAt,
                capturedAt.plusSeconds(3600),
                new SnapshotIntegrity("HmacSHA256", "key-1", "placeholder"));
        byte[] signed = codec.encode(originalSnapshot);
        DurableMetadata rowMetadata = DurableMetadata.of(
                "identity-snapshot",
                new JsonObject()
                        .put("snapshot", java.util.Base64.getEncoder().encodeToString(signed))
                        .put("present", true));

        TimerRecord row = new TimerRecord(
                timerId,
                workflowInstanceId,
                "step-1",
                FIXED_NOW.minusSeconds(60),
                TimerStatus.SCHEDULED,
                jobId,
                FIXED_NOW.minusSeconds(120),
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                rowMetadata);

        JobExecution deadJob = new JobExecution(
                jobId,
                "wf-timer",
                JobType.DELAYED,
                "workflow-timer-handler",
                "default",
                JobState.DEAD_LETTER,
                3,
                3,
                io.vertx.core.json.JsonObject.of(),
                0,
                null,
                FIXED_NOW.minusSeconds(120),
                FIXED_NOW.minusSeconds(120),
                FIXED_NOW.minusSeconds(60),
                FIXED_NOW.minusSeconds(30),
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());

        when(jobRepository.findById(jobId)).thenReturn(Future.succeededFuture(Optional.of(deadJob)));
        when(timerStore.findRecoverableScheduled(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(Future.succeededFuture(java.util.List.of(row)));

        // Use AtomicReference so the lambda's captured holder read survives back to the assertion block.
        java.util.concurrent.atomic.AtomicReference<IdentitySnapshotContext> observedContext =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(callbacks.timerFiringFailed(any(), eq(timerId), any(), any(), any()))
                .thenAnswer(inv -> {
                    // Inside the callback's tx body, read whatever identity context is currently bound on the
                    // holder — this mirrors what a downstream consumer (e.g. the outbox-event recorder's
                    // mergeCaptured, or IdentitySnapshotReconstructionInitializer) would observe while the
                    // terminal WORKFLOW_FAILED emission runs.
                    observedContext.set(
                            holder.current(IdentitySnapshotContext.class).orElse(null));
                    return Future.succeededFuture(TimerFiringResult.APPLIED);
                });

        dev.vertique.context.InboundExecutionContextScope inboundExecutionContextScope =
                new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, Set.of());
        WorkflowTimerRecoveryService service = new WorkflowTimerRecoveryService(
                pool,
                timerStore,
                jobRepository,
                fireJob,
                () -> callbacks,
                WorkflowTimerRecoveryConfig.defaults(),
                FIXED_CLOCK,
                inboundExecutionContextScope);

        runOnDuplicated(vertx, v -> service.reconcile()
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    IdentitySnapshotContext captured = observedContext.get();
                    assertThat(captured)
                            .as("terminal recovery must bind an IdentitySnapshotContext for the callback to observe")
                            .isNotNull();
                    assertThat(captured.verified())
                            .as("the row's snapshot was signed for the workflow-timer carrier — terminal recovery "
                                    + "must reproduce that same carrier so the decoder verifies it, not bind the "
                                    + "unbound sentinel carrier (which would leave the snapshot unverifiable and "
                                    + "lose the original identity)")
                            .isTrue();
                    assertThat(captured.snapshot()).isPresent();
                    assertThat(captured.snapshot().orElseThrow().content().capturedAt())
                            .as("capturedAt must be preserved unchanged — proves the ORIGINAL signed snapshot"
                                    + " verified, not a re-signed one")
                            .isEqualTo(capturedAt);
                    ctx.completeNow();
                })));
    }
}
