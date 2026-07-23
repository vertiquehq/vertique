// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Durable-context-capture tests for {@link WorkflowTimerSideEffectRecorder}. Complements the no-op-propagator
 * tests in {@link WorkflowTimerSideEffectRecorderTest} by running with a real
 * {@link DurableContextPropagator} backed by a registered string encoder/decoder pair, on a
 * duplicated Vert.x context.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Ambient durable context bound in the holder is captured by {@code mergeCaptured} into
 *       {@link TimerRecord#metadata()} (the recovery-state copy, signed for the workflow-timer
 *       carrier); the underlying delayed-job's {@link DelayedJobOptions#premergedMetadata()} is
 *       {@code null} — the timer-fire job goes through ordinary enqueue so
 *       {@code DelayedJobService} independently captures and signs its own execution carrier's
 *       durable context (P2.S0 commit 4, F5 row binding).</li>
 *   <li>An outer authoritative {@code bindFrom(empty, WORKFLOW)} scope clears the ambient
 *       context for the inner recorder call — {@link TimerRecord#metadata()} captures an empty
 *       map, proving branch-drive's leak-prevention contract.</li>
 * </ul>
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowTimerSideEffectRecorderDurableCaptureTest {

    /**
     * Minimal {@link ContextValue} wrapper for a locale string, used as the durable context type in
     * this test so that the codec's type parameter satisfies the {@code <T extends ContextValue>}
     * bound introduced by the upcoming holder write-path constraint.
     *
     * @param value the locale string (e.g. {@code "en_US"})
     */
    record StringCtx(String value) implements ContextValue {}

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String LOCALE_KEY = "x-locale";

    /** Runs the given task on a duplicated Vert.x context so the holder's write-side guard accepts the bind. */
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
    @DisplayName("ambient durable context is captured into TimerRecord.metadata; the timer-fire job enqueue "
            + "carries no premergedMetadata (P2.S0 commit 4: recorder split)")
    void recorderCapturesAmbientDurableContextIntoTimerRecordOnly(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = propagatorWithStringEncoder(holder);

        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        SqlClient tx = org.mockito.Mockito.mock();

        UUID fakeExecutionId = UUID.randomUUID();
        when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                .thenReturn(Future.succeededFuture(fakeExecutionId));
        when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

        WorkflowTimerSideEffectRecorder recorder =
                new WorkflowTimerSideEffectRecorder(timerStore, fireJob, FIXED_CLOCK, propagator, null);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope ambient = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                WorkflowSideEffectIntent intent = buildIntent(
                        Instant.parse("2026-05-08T11:00:00Z"), new WorkflowInstanceId(UUID.randomUUID()), "step-timer");

                recorder.record(intent, tx);

                ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
                ArgumentCaptor<DelayedJobOptions> optionsCaptor = ArgumentCaptor.forClass(DelayedJobOptions.class);
                verify(fireJob).enqueue(any(TimerFirePayload.class), optionsCaptor.capture(), eq(tx));
                verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));

                DurableMetadata persistedTimerMetadata = recordCaptor.getValue().metadata();
                DurableMetadata persistedJobMetadata = optionsCaptor.getValue().premergedMetadata();

                assertThat(persistedTimerMetadata.body(LOCALE_KEY).map(body -> body.getString("value")))
                        .as("ambient durable context must be written into TimerRecord.metadata")
                        .contains("en_US");
                assertThat(persistedJobMetadata)
                        .as("the timer-fire job enqueue must not carry premergedMetadata — DelayedJobService "
                                + "captures and signs the ambient context for the job's own execution carrier via "
                                + "ordinary enqueue, independently of the workflow-timer-carrier-signed "
                                + "TimerRecord.metadata copy")
                        .isNull();
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName(
            "outer authoritative bindFrom(empty, WORKFLOW) clears ambient context — recorder captures empty metadata")
    void recorderObservesAuthoritativeClear(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = propagatorWithStringEncoder(holder);

        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        SqlClient tx = org.mockito.Mockito.mock();

        UUID fakeExecutionId = UUID.randomUUID();
        when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                .thenReturn(Future.succeededFuture(fakeExecutionId));
        when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

        WorkflowTimerSideEffectRecorder recorder =
                new WorkflowTimerSideEffectRecorder(timerStore, fireJob, FIXED_CLOCK, propagator, null);

        runOnDuplicated(vertx, v -> {
            // Simulate a service-handler ambient String value that should NOT leak into the
            // branch-driven timer's metadata when the branch was forked with empty durable context.
            try (ContextHolder.Scope ambient = holder.bind(StringCtx.class, new StringCtx("fr_FR-LEAK"))) {
                try (ContextHolder.Scope branchScope =
                        propagator.bindFrom(DurableMetadata.empty(), DispatchBoundary.WORKFLOW)) {
                    WorkflowSideEffectIntent intent = buildIntent(
                            Instant.parse("2026-05-08T12:00:00Z"),
                            new WorkflowInstanceId(UUID.randomUUID()),
                            "step-timer-no-leak");

                    recorder.record(intent, tx);

                    ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
                    ArgumentCaptor<DelayedJobOptions> optionsCaptor = ArgumentCaptor.forClass(DelayedJobOptions.class);
                    verify(fireJob).enqueue(any(TimerFirePayload.class), optionsCaptor.capture(), eq(tx));
                    verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));

                    DurableMetadata persistedTimerMetadata =
                            recordCaptor.getValue().metadata();
                    DurableMetadata persistedJobMetadata =
                            optionsCaptor.getValue().premergedMetadata();

                    assertThat(persistedTimerMetadata.has(LOCALE_KEY))
                            .as("authoritative outer bindFrom must clear ambient — recorder captures empty metadata")
                            .isFalse();
                    assertThat(persistedJobMetadata)
                            .as("the timer-fire job enqueue must not carry premergedMetadata regardless of ambient "
                                    + "context — it is captured independently by DelayedJobService's ordinary "
                                    + "enqueue path, so neither column carries the ambient leak")
                            .isNull();
                }
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- Recovery-copy carrier threading (P2.S0 commit 4: workflow-timer carrier binding) ---

    @Test
    @DisplayName("workflow_timers recovery-copy metadata is captured via the carrier-threading mergeCaptured "
            + "overload, signed for the workflow-timer carrier — not the ambient/no-carrier default")
    void recoveryCopySignedForWorkflowTimerCarrier(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        java.util.concurrent.atomic.AtomicReference<DurableEncodeContext> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        DurableContextMetadataEncoder<StringCtx> recordingEncoder = new DurableContextMetadataEncoder<>() {
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
                observed.set(context);
                return DurableMetadata.of(LOCALE_KEY, new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of());
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        WorkflowTimerFireJob fireJob = org.mockito.Mockito.mock();
        SqlClient tx = org.mockito.Mockito.mock();

        UUID fakeExecutionId = UUID.randomUUID();
        when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                .thenReturn(Future.succeededFuture(fakeExecutionId));
        when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

        WorkflowTimerSideEffectRecorder recorder =
                new WorkflowTimerSideEffectRecorder(timerStore, fireJob, FIXED_CLOCK, propagator, null);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope ambient = holder.bind(StringCtx.class, new StringCtx("carrier-check"))) {
                WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
                WorkflowSideEffectIntent intent =
                        buildIntent(Instant.parse("2026-05-08T13:00:00Z"), workflowId, "step-timer-carrier");

                ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
                recorder.record(intent, tx);
                verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));
                UUID insertedTimerId = recordCaptor.getValue().timerId();

                DurableEncodeContext encodeContext = observed.get();
                assertThat(encodeContext)
                        .as("the recovery-copy write must invoke the encoder (proves mergeCaptured ran for "
                                + "TimerRecord.metadata)")
                        .isNotNull();
                assertThat(encodeContext.carrier())
                        .as("the recovery-copy write must thread a workflow-timer carrier into mergeCaptured — "
                                + "not the ambient/no-carrier default used today")
                        .isPresent();
                DurableCarrierDescriptor carrier = encodeContext.carrier().orElseThrow();
                assertThat(carrier.target().kind())
                        .as("target kind must identify the workflow-timer carrier distinctly from the "
                                + "delayed-job carrier")
                        .isEqualTo("workflow-timer");
                assertThat(carrier.carrierId())
                        .as("carrierId must equal the generated timerId so the carrier is fully reproducible "
                                + "from workflow_timers columns at recovery")
                        .isEqualTo(insertedTimerId.toString());
                assertThat(carrier.target().address())
                        .as("target address must equal the workflow id's raw stable UUID value — never the "
                                + "record's auto-toString() — so the carrier is fully reproducible from "
                                + "workflow_timers columns at recovery")
                        .isEqualTo(workflowId.value().toString());

                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    /**
     * Builds a minimal {@code WORKFLOW_TIMER} intent for the given fire time and step id.
     *
     * @param fireAt     the resolved fire instant (placed in the intent payload)
     * @param workflowId the workflow instance id
     * @param stepId     the step id (placed as the intent targetId)
     * @return the constructed intent
     */
    private static WorkflowSideEffectIntent buildIntent(Instant fireAt, WorkflowInstanceId workflowId, String stepId) {
        WorkflowSideEffectIntent.Correlation correlation =
                WorkflowSideEffectIntent.Correlation.singlePath(workflowId, 1L, "test-def", stepId);
        TimerIntentPayload payload = new TimerIntentPayload(fireAt, TimerPurpose.STANDALONE, null);
        return new WorkflowSideEffectIntent(IntentKind.WORKFLOW_TIMER, stepId, payload, Map.of(), correlation);
    }
}
