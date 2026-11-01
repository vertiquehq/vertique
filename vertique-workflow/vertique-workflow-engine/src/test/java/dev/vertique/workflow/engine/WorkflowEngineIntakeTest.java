// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.history.WorkflowHistoryEventPublisher;
import dev.vertique.workflow.engine.intake.IntakeAdmission;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.AcceptingTaskReservations;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.engine.testsupport.EmptySignalWaits;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowUnavailableException;
import dev.vertique.workflow.history.WorkflowHistoryChange;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.RelayedSignals;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/**
 * Verifies the intake check of the engine's public signal forms and the relayed recording of signals that are already
 * durable: a refusing node refuses a new signal after every replay and terminal refusal and before anything is
 * written, and the relayed forms record the same signals with the same checks, guards and sanitization but never meet
 * the refusal.
 */
class WorkflowEngineIntakeTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String DEFINITION_ID = "intake-test";
    private static final String SECURE = "secure";
    private static final String CONTRACT = "intake-contract";

    private final AtomicBoolean refusing = new AtomicBoolean(true);
    private final SqlClient tx = ActiveTransactionClient.create();
    private final WorkflowInstanceRepository<SqlClient> instances = mock();
    private final WorkflowDedupRepository<SqlClient> dedup = mock();
    private final EmptyCommandRepository commands = new EmptyCommandRepository();
    private final WorkflowInstance waiting = instance(WorkflowStatus.WAITING);
    private final WorkflowHistoryRepository<SqlClient> history = mock();
    private final WorkflowEngine engine = engine(true, WorkflowRecordingConfig.defaults());

    /** Stands for an authenticated-only namespace a sender may not forge. */
    private record SecureCtx(String value) implements ContextValue {}

    private WorkflowEngine engine(boolean withPropagator, WorkflowRecordingConfig recording) {
        return engine(withPropagator, recording, commands, WorkflowHistoryEventPublisher.disabled());
    }

    private WorkflowEngine engine(
            boolean withPropagator,
            WorkflowRecordingConfig recording,
            WorkflowCommandRepository<SqlClient> commands,
            WorkflowHistoryEventPublisher historyPublisher) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = withPropagator
                ? new DurableContextPropagator(
                        new DurableContextMetadataRegistry(
                                Set.of(TenantCtxCodec.encoder()), Set.of(TenantCtxCodec.decoder(), secureDecoder())),
                        holder,
                        new ContextScopeBinder(holder))
                : null;
        WorkflowRegistry registry = mock();
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(instances.findById(any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(instances.findById(eq(waiting.id()), any())).thenReturn(Future.succeededFuture(Optional.of(waiting)));
        when(dedup.findSignal(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.claimOrResolveSignalScoped(any(), any(), any(), any())).thenReturn(Future.succeededFuture(true));
        when(instances.findDefinitionContract(any(), any())).thenReturn(Future.succeededFuture(Optional.of(CONTRACT)));
        WorkflowTransactionRunner<SqlClient> runner = mock();
        when(runner.inTransaction(any(), any())).thenAnswer(invocation -> {
            Function<SqlClient, Future<Object>> work = invocation.getArgument(1);
            return work.apply(tx);
        });
        IntakeAdmission admission = () -> !refusing.get();
        return (WorkflowEngine) WorkflowEngineFactory.create(WorkflowEngineParts.builder()
                .txRunner(runner)
                .registry(registry)
                .instances(instances)
                .history(history)
                .dedup(dedup)
                .signalWaits(EmptySignalWaits.create())
                .branchTokens(mock(BranchTokenRepository.class))
                .joinStates(mock(JoinStateRepository.class))
                .timerStore(mock(TimerStore.class))
                .taskStore(mock(TaskStore.class))
                .taskReservations(new AcceptingTaskReservations())
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .recorders(Set.<WorkflowSideEffectRecorder<SqlClient>>of())
                .optionalIntentKinds(WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS)
                .migrationRegistry(Optional.empty())
                .propagator(propagator)
                .typeNames(WorkflowEngineFactory.registryTypeNames(registry))
                .commands(commands)
                .recordingConfig(recording)
                .intakeAdmission(admission)
                .historyPublisher(historyPublisher)
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
    }

    @Nested
    @DisplayName("the public signal forms")
    class PublicForms {

        @Test
        @DisplayName("the engine reports the admission of the node to a transport that can hold a message back")
        void reportsTheAdmissionToATransport() {
            refusing.set(true);
            assertThat(engine.admitting()).isFalse();

            refusing.set(false);
            assertThat(engine.admitting()).isTrue();
        }

        @Test
        @DisplayName("a refusing node refuses every form with the retryable 503 refusal and writes nothing")
        void refusesEveryFormAndWritesNothing() {
            DurableMetadata carrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("t", "x"));

            var instanceLevel = engine.signal(waiting.id(), "go", Map.of(), "k1", tx);
            var branch = engine.signal(waiting.id(), "go", Map.of(), "k2", "fork", "branch", tx);
            var carried = engine.signal(waiting.id(), "go", Map.of(), "k3", null, null, carrier, tx);
            var nullCarrier = engine.signal(waiting.id(), "go", Map.of(), "k4", null, null, null, tx);
            var contractChecked = engine.signal(waiting.id(), CONTRACT, "go", Map.of(), "k5");

            for (Future<Void> refused : List.of(instanceLevel, branch, carried, nullCarrier, contractChecked)) {
                assertThat(refused.failed()).isTrue();
                assertThat(refused.cause())
                        .isInstanceOf(WorkflowIntakeRefusedException.class)
                        .isInstanceOf(WorkflowUnavailableException.class)
                        .hasMessageNotContaining("go")
                        .hasMessageNotContaining("k1");
            }
            assertThat(commands.recordedSignals()).isEmpty();
            verify(dedup, never()).claimOrResolveSignalScoped(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a node that admits records the same signals")
        void anAdmittingNodeRecords() {
            refusing.set(false);

            assertThat(engine.signal(waiting.id(), "go", Map.of(), "k1", tx).succeeded())
                    .isTrue();
            assertThat(engine.signal(waiting.id(), "go", Map.of(), "k2", "fork", "branch", tx)
                            .succeeded())
                    .isTrue();

            assertThat(commands.recordedSignals()).hasSize(2);
        }

        @Test
        @DisplayName(
                "a committed duplicate succeeds, a missing or ended instance, an oversized payload and another contract keep their refusal")
        void replaysAndTerminalRefusalsKeepTheirOutcome() {
            // Given a signal that was recorded before the pressure rose, an ended instance and a missing one
            WorkflowEngine small = engine(false, new WorkflowRecordingConfig(5000L, 1024));
            WorkflowInstance recorded = instance(WorkflowStatus.WAITING);
            when(dedup.findSignal(eq(recorded.id()), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
            WorkflowInstance ended = instance(WorkflowStatus.COMPLETED);
            when(instances.findById(eq(ended.id()), any())).thenReturn(Future.succeededFuture(Optional.of(ended)));
            String oversized = "x".repeat(2048);

            // When each is signalled to a refusing node
            var replay = engine.signal(recorded.id(), "go", Map.of(), "replayed", tx);
            var endedInstance = engine.signal(ended.id(), "go", Map.of(), "k", tx);
            var missing = engine.signal(new WorkflowInstanceId(UUID.randomUUID()), "go", Map.of(), "k", tx);
            var tooLarge = small.signal(waiting.id(), "go", oversized, "k", tx);
            var otherContract = engine.signal(waiting.id(), "other-contract", "go", Map.of(), "k");

            // Then the replay succeeds and the others fail with their own refusal, not the intake refusal
            assertThat(replay.succeeded()).isTrue();
            assertThat(endedInstance.cause()).isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(missing.cause()).isInstanceOf(WorkflowInstanceNotFoundException.class);
            assertThat(tooLarge.cause()).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(otherContract.cause()).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(commands.recordedSignals()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a correlated delivery")
    class CorrelatedDeliveries {

        private interface Contract {}

        private final CorrelatedSignal signal = CorrelatedSignal.of(Contract.class, "go", "K-1", Map.of(), "d-1");

        private void matches(List<WorkflowInstance> found) {
            when(dedup.findCorrelated(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
            when(instances.findLiveByBusinessKey(any(), any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(found));
        }

        @Test
        @DisplayName("is refused by a refusing node with the retryable 503 refusal, and writes nothing")
        void isRefusedAndWritesNothing() {
            // Given one instance that carries the key
            matches(List.of(waiting));
            WorkflowCommandRepository<SqlClient> lockWatching = spy(commands);
            WorkflowEngine watched = engine(
                    true, WorkflowRecordingConfig.defaults(), lockWatching, WorkflowHistoryEventPublisher.disabled());

            // When the signal is delivered to a refusing node
            var refused = watched.signalByBusinessKey(signal, tx);

            // Then the delivery is refused, without the key in the failure, and nothing is locked or recorded
            assertThat(refused.cause())
                    .isInstanceOf(WorkflowIntakeRefusedException.class)
                    .hasMessageNotContaining("K-1");
            assertThat(commands.recordedSignals()).isEmpty();
            verify(lockWatching, never()).lockInstance(any(), any());
            verify(dedup, never()).claimOrResolveCorrelated(any(), any(), any(), any());
        }

        @Test
        @DisplayName("keeps a committed duplicate, a miss, an ambiguity and an oversized payload on a refusing node")
        void keepsReplayAndTerminalRefusals() {
            // Given a miss, an ambiguity, an oversized payload and a delivery recorded before the pressure rose
            WorkflowEngine small = engine(false, new WorkflowRecordingConfig(5000L, 1024));
            CorrelatedSignal oversized = CorrelatedSignal.of(Contract.class, "go", "K-1", "x".repeat(2048), "d-1");

            // When each is delivered to a refusing node
            matches(List.of());
            var miss = engine.signalByBusinessKey(signal, tx);
            matches(List.of(waiting, instance(WorkflowStatus.WAITING)));
            var ambiguous = engine.signalByBusinessKey(signal, tx);
            matches(List.of(waiting));
            var tooLarge = small.signalByBusinessKey(oversized, tx);
            when(dedup.findCorrelated(any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(waiting.id())));
            var replay = engine.signalByBusinessKey(signal, tx);

            // Then each answers as it would on a node that admits
            assertThat(miss.cause()).isInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(ambiguous.cause()).isInstanceOf(WorkflowSignalAmbiguousException.class);
            assertThat(tooLarge.cause()).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(replay.result().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.DUPLICATE);
            assertThat(commands.recordedSignals()).isEmpty();
        }
    }

    @Nested
    @DisplayName("with a history listener bound")
    class WithAHistoryListener {

        private final List<WorkflowHistoryFailure> failures = new CopyOnWriteArrayList<>();
        private final WorkflowHistoryListener listener = new WorkflowHistoryListener() {
            @Override
            public void onHistoryChanged(WorkflowHistoryChange change) {}

            @Override
            public void onHistoryFailed(WorkflowHistoryFailure failure) {
                failures.add(failure);
            }
        };

        @SuppressWarnings("unchecked")
        private final WorkflowCommandRepository<SqlClient> lockWatching =
                mock(WorkflowCommandRepository.class, AdditionalAnswers.delegatesTo(commands));

        private final WorkflowEngine listening = engine(
                true,
                WorkflowRecordingConfig.defaults(),
                lockWatching,
                new WorkflowHistoryEventPublisher(
                        Set.of(listener),
                        history,
                        mock(Pool.class),
                        lockWatching,
                        new CommandActors(new DefaultContextHolder(), new DefaultWorkflowActorMapper(), Set.of())));

        @Test
        @DisplayName("a refused signal takes no execution lock and reports the refusal as a failed action")
        void aRefusedSignalTakesNoLockAndIsReported() {
            var refused = listening.signal(waiting.id(), "go", Map.of(), "k1", tx);

            assertThat(refused.cause()).isInstanceOf(WorkflowIntakeRefusedException.class);
            verify(lockWatching, never()).lockInstance(any(), any());
            verify(history, never()).nextSequence(any(), any());
            assertThat(failures).hasSize(1).first().satisfies(failure -> {
                assertThat(failure.action()).isEqualTo(WorkflowHistoryFailure.Action.SIGNAL);
                assertThat(failure.instanceId()).isEqualTo(waiting.id());
                assertThat(failure.errorType()).isEqualTo("WorkflowIntakeRefusedException");
            });
            assertThat(commands.recordedSignals()).isEmpty();
        }

        @Test
        @DisplayName("a replay and the terminal refusals answer as before, without the lock, and report only failures")
        void replaysAndTerminalRefusalsAreUnchanged() {
            WorkflowInstance recorded = instance(WorkflowStatus.WAITING);
            when(dedup.findSignal(eq(recorded.id()), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
            WorkflowInstance ended = instance(WorkflowStatus.COMPLETED);
            when(instances.findById(eq(ended.id()), any())).thenReturn(Future.succeededFuture(Optional.of(ended)));

            var replay = listening.signal(recorded.id(), "go", Map.of(), "replayed", tx);
            var endedInstance = listening.signal(ended.id(), "go", Map.of(), "k", tx);
            var missing = listening.signal(new WorkflowInstanceId(UUID.randomUUID()), "go", Map.of(), "k", tx);

            assertThat(replay.succeeded()).isTrue();
            assertThat(endedInstance.cause()).isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(missing.cause()).isInstanceOf(WorkflowInstanceNotFoundException.class);
            verify(lockWatching, never()).lockInstance(any(), any());
            assertThat(failures)
                    .extracting(WorkflowHistoryFailure::errorType)
                    .containsExactlyInAnyOrder("WorkflowSignalRejectedException", "WorkflowInstanceNotFoundException");
        }

        @Test
        @DisplayName("a node that admits still takes the lock before the range, as before")
        void anAdmittingNodeStillTakesTheLock() {
            refusing.set(false);

            var recorded = listening.signal(waiting.id(), "go", Map.of(), "k1", tx);

            assertThat(recorded.succeeded()).isTrue();
            verify(lockWatching, atLeastOnce()).lockInstance(eq(waiting.id()), any());
            assertThat(failures).isEmpty();
        }
    }

    @Nested
    @DisplayName("the relayed recording")
    class Relayed {

        private final RelayedSignals<SqlClient> relayed = engine.relayedSignals();

        @Test
        @DisplayName("records both forms on a node that refuses, as the public forms record on one that admits")
        void recordsWhileRefusing() {
            DurableMetadata carrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("t", "x"));

            var instanceLevel = relayed.signal(waiting.id(), "go", Map.of(), "k1", null, null, tx);
            var branch = relayed.signal(waiting.id(), "go", Map.of(), "k2", "fork", "branch", tx);
            var carried = relayed.signal(waiting.id(), "go", Map.of(), "k3", null, null, carrier, tx);

            assertThat(instanceLevel.succeeded()).isTrue();
            assertThat(branch.succeeded()).isTrue();
            assertThat(carried.succeeded()).isTrue();
            assertThat(commands.recordedSignals()).hasSize(3);
            assertThat(commands.recordedSignals().get(1).forkStepId()).isEqualTo("fork");
            assertThat(commands.recordedSignals().get(1).branchId()).isEqualTo("branch");
            assertThat(commands.recordedSignals().get(2).explicitCarrierJson()).contains(TenantCtxCodec.NAMESPACE);
        }

        @Test
        @DisplayName("keeps the transaction guard, the branch guard and the carrier requirement of the public forms")
        void keepsTheGuards() {
            var notATransaction = relayed.signal(waiting.id(), "go", Map.of(), "k", null, null, mock(SqlClient.class));
            var mixedBranch = relayed.signal(waiting.id(), "go", Map.of(), "k", "fork", null, tx);
            var mixedCarried = relayed.signal(
                    waiting.id(),
                    "go",
                    Map.of(),
                    "k",
                    null,
                    "branch",
                    DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject()),
                    tx);
            var carrierWithoutPropagator = engine(false, WorkflowRecordingConfig.defaults())
                    .relayedSignals()
                    .signal(
                            waiting.id(),
                            "go",
                            Map.of(),
                            "k",
                            null,
                            null,
                            DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject()),
                            tx);

            assertThat(notATransaction.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(mixedBranch.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(mixedCarried.cause()).isInstanceOf(IllegalArgumentException.class);
            assertThat(carrierWithoutPropagator.cause()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(commands.recordedSignals()).isEmpty();
        }

        @Test
        @DisplayName("strips an authenticated-only namespace from the carrier, as the public form does")
        void sanitizesTheCarrierLikeThePublicForm() {
            refusing.set(false);
            DurableMetadata forged = DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("t", "x"))
                    .with(SECURE, new JsonObject().put("value", "FORGED"));

            engine.signal(waiting.id(), "go", Map.of(), "public", null, null, forged, tx);
            relayed.signal(waiting.id(), "go", Map.of(), "relayed", null, null, forged, tx);

            assertThat(commands.recordedSignals()).hasSize(2).allSatisfy(recorded -> assertThat(
                            recorded.explicitCarrierJson())
                    .contains(TenantCtxCodec.NAMESPACE)
                    .doesNotContain(SECURE)
                    .doesNotContain("FORGED"));
        }

        @Test
        @DisplayName("keeps a committed duplicate and the terminal refusals of the public forms")
        void keepsReplayAndTerminalRefusals() {
            WorkflowInstance recorded = instance(WorkflowStatus.WAITING);
            when(dedup.findSignal(eq(recorded.id()), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
            WorkflowInstance ended = instance(WorkflowStatus.CANCELLED);
            when(instances.findById(eq(ended.id()), any())).thenReturn(Future.succeededFuture(Optional.of(ended)));

            assertThat(relayed.signal(recorded.id(), "go", Map.of(), "replayed", null, null, tx)
                            .succeeded())
                    .isTrue();
            assertThat(relayed.signal(ended.id(), "go", Map.of(), "k", null, null, tx)
                            .cause())
                    .isInstanceOf(WorkflowSignalRejectedException.class);
            assertThat(commands.recordedSignals()).isEmpty();
        }
    }

    // --- Fixtures ---

    private static DurableContextMetadataDecoder<SecureCtx> secureDecoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<SecureCtx> type() {
                return SecureCtx.class;
            }

            @Override
            public String namespace() {
                return SECURE;
            }

            @Override
            public ContextDecodeResult<SecureCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(SECURE)
                        .map(body -> body.getString("value"))
                        .map(SecureCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }

            @Override
            public boolean acceptsExplicitCarrier() {
                return false;
            }
        };
    }

    private static WorkflowInstance instance(WorkflowStatus status) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                status,
                null,
                null,
                "wait",
                WaitType.SIGNAL,
                "go",
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                null);
    }
}
