// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.history.WorkflowHistoryEventPublisher;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.AcceptingTaskReservations;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.engine.testsupport.EmptySignalWaits;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.types.WorkflowTypeNames;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the admission of a delivery by business key whose match went stale: the instance it found no longer awaits
 * the signal, so the delivery is told that nothing matches and nothing is recorded.
 */
@SuppressWarnings("unchecked")
class WorkflowEngineStaleCorrelatedDeliveryTest {

    private static final String DEFINITION_ID = "order";
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    /** The contract class a correlated signal is addressed to. */
    private interface Contract {}

    private final SqlClient tx = ActiveTransactionClient.create();
    private final WorkflowInstanceRepository<SqlClient> instances = mock();
    private final WorkflowDedupRepository<SqlClient> dedup = mock();
    private final WorkflowHistoryRepository<SqlClient> history = mock();
    private final EmptyCommandRepository commands = new EmptyCommandRepository();
    private final WorkflowRegistry registry = mock(WorkflowRegistry.class);
    private final WorkflowTypeNames typeNames = WorkflowEngineFactory.registryTypeNames(registry);
    private final WorkflowEngine engine = engine();

    private WorkflowEngine engine() {
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(dedup.findCorrelated(any(), any(), any())).thenReturn(Future.succeededFuture(Optional.empty()));
        WorkflowHistoryEventPublisher publisher = new WorkflowHistoryEventPublisher(
                Set.of(), history, mock(Pool.class), commands, CommandActors.ambient());
        return new WorkflowEngine(
                mock(WorkflowTransactionRunner.class),
                registry,
                instances,
                history,
                dedup,
                EmptySignalWaits.create(),
                mock(RecorderRouter.class),
                mock(dev.vertique.workflow.timer.TimerStore.class),
                mock(TaskStore.class),
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
                typeNames,
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
    @DisplayName("a delivery by business key whose matched instance no longer declares the signal answers no match and"
            + " writes nothing")
    void staleMatchWhoseDefinitionDoesNotDeclareTheSignalAnswersNoMatchAndWritesNothing() {
        // Given an instance found by its business key, pinned to a definition that declares only another signal
        String contractName = typeNames.contractNameOf(Contract.class);
        WorkflowInstance found = waiting();
        when(instances.findLiveByBusinessKey(any(), any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(List.of(found)));
        when(instances.findById(found.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(found)));
        when(instances.findDefinitionContract(any(), any()))
                .thenReturn(Future.succeededFuture(Optional.of(contractName)));
        commands.declaredSignalNames(List.of("another"));

        // When the signal is delivered by business key
        CorrelatedSignal signal = CorrelatedSignal.of(Contract.class, "approve", "K-1", "payload", "d-1");
        Future<CorrelatedSignalReceipt> outcome = engine.signalByBusinessKey(signal, tx);

        // Then the delivery answers no match for the signal, with nothing claimed or recorded
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.cause())
                .isInstanceOf(WorkflowSignalNoMatchException.class)
                .hasMessageContaining("approve");
        assertThat(commands.recordedSignals()).isEmpty();
        verify(dedup, never()).claimOrResolveCorrelated(any(), any(), any(), any());
    }

    private static WorkflowInstance waiting() {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "wait",
                WaitType.SIGNAL,
                "approve",
                null,
                "{}",
                null,
                null,
                NOW,
                NOW,
                null);
    }
}
