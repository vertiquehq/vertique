// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.AcceptingTaskReservations;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the contract a start replay compares against on an engine with a durable registry, when its node has no
 * plan for the pinned version: the stored row read through the start transaction, and a refusal when there is no
 * row, without a fallback to another version.
 */
class WorkflowEngineReplayStoredContractTest {

    private static final String ID = "order";
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final DefinitionKey PIN = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 2);

    private final WorkflowRegistry registry = mock(WorkflowRegistry.class);
    private final WorkflowDefinitionRegistryStore store = mock(WorkflowDefinitionRegistryStore.class);
    private final WorkflowInstanceRepository<SqlClient> instances = mock();
    private final WorkflowInstanceId existing = new WorkflowInstanceId(UUID.randomUUID());
    private final SqlClient tx = ActiveTransactionClient.create();
    private final WorkflowEngine engine = engineWithReplayableStart();

    @Test
    @DisplayName("a replay reads the stored contract through the start transaction and takes no second connection")
    void readsTheStoredContractThroughTheStartTransaction() {
        when(store.find(PIN)).thenReturn(Future.failedFuture(new AssertionError("a second connection was taken")));
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.of("orders")));

        assertThat(engine.start(start("orders"), tx).result()).isEqualTo(existing);
        verify(store, never()).find(PIN);
    }

    @Test
    @DisplayName("a replay of another contract than the stored one is an idempotency conflict")
    void otherContractIsAConflict() {
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.of("orders")));

        assertThat(engine.start(start("billing"), tx).cause()).isInstanceOf(WorkflowIdempotencyConflictException.class);
    }

    @Test
    @DisplayName("a replay whose pin has no stored row is refused")
    void missingRowIsNotRunnable() {
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertThat(engine.start(start("orders"), tx).cause())
                .isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                    assertThat(failure.definitionKey()).isEqualTo(PIN);
                });
    }

    private static StartCommand start(String contractName) {
        return new StartCommand(ID, contractName, "payload", "k1", null, null, null, null, null);
    }

    /** An engine with a durable registry whose start dedup finds an instance pinned to a version it has no plan for. */
    private WorkflowEngine engineWithReplayableStart() {
        WorkflowDedupRepository<SqlClient> dedup = mock();
        when(dedup.findStart(WorkflowScopes.DEFAULT, ID, "k1", tx))
                .thenReturn(Future.succeededFuture(Optional.of(existing)));
        WorkflowInstance pinned = new WorkflowInstance(
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
        when(instances.findById(existing, tx)).thenReturn(Future.succeededFuture(Optional.of(pinned)));
        return new WorkflowEngine(
                mock(WorkflowTransactionRunner.class),
                registry,
                instances,
                mock(WorkflowHistoryRepository.class),
                dedup,
                mock(RecorderRouter.class),
                mock(dev.vertique.workflow.timer.TimerStore.class),
                mock(dev.vertique.workflow.tasks.TaskStore.class),
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
                new EmptyCommandRepository(),
                new AcceptingTaskReservations(),
                () -> null,
                null);
    }
}
