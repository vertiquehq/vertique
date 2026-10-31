// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.history.WorkflowHistoryEventPublisher;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.AcceptingTaskReservations;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.history.WorkflowHistoryChange;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.history.WorkflowHistoryFailure.Action;
import dev.vertique.workflow.history.WorkflowHistoryListener;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every public engine entry that records history in a transaction runs inside the history publisher: a
 * refused call reports its own action once, with the instance it concerns when it names one.
 */
@SuppressWarnings("unchecked")
class WorkflowEngineHistoryAnnouncementTest {

    private static final String ID = "order";
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    /** The contract class a correlated signal is addressed to. */
    private interface Contract {}

    private final WorkflowInstanceId instance = new WorkflowInstanceId(UUID.randomUUID());
    private final WorkflowInstanceId existing = new WorkflowInstanceId(UUID.randomUUID());
    private final SqlClient tx = ActiveTransactionClient.create();
    private final WorkflowInstanceRepository<SqlClient> instances = mock();
    private final WorkflowDedupRepository<SqlClient> dedup = mock();
    private final WorkflowHistoryRepository<SqlClient> history = mock();
    private final TaskStore<SqlClient> tasks = mock();
    private final WorkflowSignalWaitRepository<SqlClient> signalWaits = waitsWithNoOpenRecord();
    private final List<WorkflowHistoryFailure> failures = new ArrayList<>();
    private final List<WorkflowHistoryChange> changes = new ArrayList<>();
    private final WorkflowEngine engine = engine();

    private WorkflowEngine engine() {
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(instances.findById(any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.findSignal(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.findStart(WorkflowScopes.DEFAULT, ID, "replayed", tx))
                .thenReturn(Future.succeededFuture(Optional.of(existing)));
        when(tasks.findById(any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        WorkflowHistoryListener listener = new WorkflowHistoryListener() {
            @Override
            public void onHistoryChanged(WorkflowHistoryChange change) {
                changes.add(change);
            }

            @Override
            public void onHistoryFailed(WorkflowHistoryFailure failure) {
                failures.add(failure);
            }
        };
        EmptyCommandRepository commands = new EmptyCommandRepository();
        WorkflowHistoryEventPublisher publisher = new WorkflowHistoryEventPublisher(
                Set.of(listener), history, mock(Pool.class), commands, CommandActors.ambient());
        WorkflowRegistry registry = mock(WorkflowRegistry.class);
        return new WorkflowEngine(
                mock(WorkflowTransactionRunner.class),
                registry,
                instances,
                history,
                dedup,
                signalWaits,
                mock(RecorderRouter.class),
                mock(dev.vertique.workflow.timer.TimerStore.class),
                tasks,
                new FingerprintCanonicalizer(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(BranchTokenRepository.class),
                mock(JoinStateRepository.class),
                mock(BranchTransitionEngine.class),
                mock(BranchCompensationOrchestrator.class),
                null,
                Optional.<WorkflowMigrationRegistry>empty(),
                mock(WorkflowEventEmitter.class),
                mock(WorkflowHistoryRecorder.class),
                mock(ReminderScheduler.class),
                mock(CompensationService.class),
                null,
                new PayloadConstraintCheck(Optional.empty()),
                WorkflowEngineFactory.registryTypeNames(registry),
                Optional.<WorkflowStateCompatibility>empty(),
                mock(WorkflowExecutionProbe.class),
                commands,
                () -> null,
                WorkflowRecordingConfig.defaults(),
                new AcceptingTaskReservations(),
                Optional.empty(),
                key -> Optional.empty(),
                null,
                CommandActors.ambient(),
                publisher);
    }

    @Test
    @DisplayName("a refused signal reports SIGNAL with its instance")
    void signalIsWrapped() {
        assertThat(engine.signal(instance, "go", "payload", "k", tx).failed()).isTrue();

        assertReported(Action.SIGNAL, instance);
    }

    @Test
    @DisplayName("a refused delivery by key reports SIGNAL without an instance")
    void deliveryByKeyIsWrapped() {
        // Given an engine with a history listener bound and a wait repository that finds no open record for the key
        CorrelatedSignal signal = CorrelatedSignal.of(Contract.class, "go", "K-1", "payload", "d-1");

        // When the signal is delivered by key in the active transaction
        assertThat(engine.signalByKey(signal, tx).failed()).isTrue();

        // Then the listener received one SIGNAL failure with an error type and no instance, and no change
        assertReported(Action.SIGNAL, null);
    }

    @Test
    @DisplayName("a refused cancel reports CANCEL with its instance")
    void cancelIsWrapped() {
        assertThat(engine.cancel(instance, "reason", tx).failed()).isTrue();

        assertReported(Action.CANCEL, instance);
    }

    @Test
    @DisplayName("a refused retry reports RETRY with its instance")
    void retryIsWrapped() {
        assertThat(engine.retry(instance, tx).failed()).isTrue();

        assertReported(Action.RETRY, instance);
    }

    @Test
    @DisplayName("a refused migration reports MIGRATE with its instance, in both forms")
    void migrateIsWrapped() {
        assertThat(engine.migrate(instance, 2L, tx).failed()).isTrue();
        assertThat(engine.migrate(instance, "other", 2L, tx).failed()).isTrue();

        assertThat(failures).hasSize(2);
        assertThat(failures).allSatisfy(failure -> {
            assertThat(failure.action()).isEqualTo(Action.MIGRATE);
            assertThat(failure.instanceId()).isEqualTo(instance);
        });
    }

    @Test
    @DisplayName("a refused task completion reports TASK_COMPLETE, and a refused reassignment TASK_REASSIGN")
    void taskActionsAreWrapped() {
        TaskCompletionCommand completion = new TaskCompletionCommand(
                UUID.randomUUID(), "approve", "payload", "k1", new WorkflowActor.User("u-1"), null);
        TaskReassignmentCommand reassignment = new TaskReassignmentCommand(
                UUID.randomUUID(), new TaskAssignment.Role("auditors"), new WorkflowActor.User("u-1"), "k2", null);

        assertThat(engine.taskCompleted(completion, tx).failed()).isTrue();
        assertThat(engine.taskReassigned(reassignment, tx).failed()).isTrue();

        assertThat(failures)
                .extracting(WorkflowHistoryFailure::action)
                .containsExactly(Action.TASK_COMPLETE, Action.TASK_REASSIGN);
        assertThat(failures).extracting(WorkflowHistoryFailure::instanceId).containsOnlyNulls();
    }

    @Test
    @DisplayName("a start that replays an existing instance announces nothing, and a refused one reports START")
    void startIsWrapped() {
        when(instances.findById(existing, tx)).thenReturn(Future.succeededFuture(Optional.of(pinned())));
        StartCommand replay = new StartCommand(ID, "orders", "payload", "replayed", null, null, null, null, null);

        assertThat(engine.start(replay, tx).failed()).isTrue();

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().action()).isEqualTo(Action.START);
        assertThat(changes).isEmpty();
    }

    /** A wait repository whose every read finds nothing, so a delivery by key matches no open record. */
    @SuppressWarnings("rawtypes")
    private static WorkflowSignalWaitRepository<SqlClient> waitsWithNoOpenRecord() {
        return mock(WorkflowSignalWaitRepository.class, invocation -> {
            Type returned = invocation.getMethod().getGenericReturnType();
            if (!Future.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return null;
            }
            Type element = ((ParameterizedType) returned).getActualTypeArguments()[0];
            Class<?> raw = element instanceof ParameterizedType parameterized
                    ? (Class<?>) parameterized.getRawType()
                    : (Class<?>) element;
            if (List.class.isAssignableFrom(raw)) {
                return Future.succeededFuture(List.of());
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return Future.succeededFuture(Optional.empty());
            }
            return Future.succeededFuture();
        });
    }

    private void assertReported(Action action, WorkflowInstanceId id) {
        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().action()).isEqualTo(action);
        assertThat(failures.getFirst().instanceId()).isEqualTo(id);
        assertThat(failures.getFirst().errorType()).isNotNull();
        assertThat(changes).isEmpty();
    }

    private WorkflowInstance pinned() {
        return new WorkflowInstance(
                existing,
                ID,
                2L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "wait",
                null,
                null,
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                null);
    }
}
