// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the contract a start replay compares against when its node has no plan for the pinned version: the
 * contract the durable registry stores for the pinned definition, and a refusal when the registry holds no row.
 */
class WorkflowEngineReplayContractTest {

    private static final String ID = "order";
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final DefinitionKey PIN = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 2);

    private final WorkflowRegistry registry = mock(WorkflowRegistry.class);
    private final WorkflowInstanceRepository<SqlClient> instances = mock();
    private final WorkflowInstanceId existing = new WorkflowInstanceId(UUID.randomUUID());
    private final SqlClient tx = ActiveTransactionClient.create();
    private final WorkflowEngineHandle engine = engineWithReplayableStart();

    @Test
    @DisplayName("a replay of the contract stored for the pinned version returns the existing instance")
    void sameContractAsAnotherVersionReturnsTheInstance() {
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.of("orders")));

        assertThat(engine.start(start("orders"), tx).result()).isEqualTo(existing);
    }

    @Test
    @DisplayName("a replay of another contract than the one stored for the pinned version is an idempotency conflict")
    void otherContractIsAConflict() {
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.of("orders")));

        assertThat(engine.start(start("billing"), tx).cause()).isInstanceOf(WorkflowIdempotencyConflictException.class);
    }

    @Test
    @DisplayName("a replay of a version the registry stores no row for is refused as not runnable")
    void unknownContractIsNotRunnable() {
        when(instances.findDefinitionContract(PIN, tx)).thenReturn(Future.succeededFuture(Optional.empty()));

        assertThat(engine.start(start("orders"), tx).cause())
                .isInstanceOfSatisfying(WorkflowVersionNotRunnableException.class, failure -> {
                    assertThat(failure.definitionKey()).isEqualTo(PIN);
                });
    }

    private static StartCommand start(String contractName) {
        return new StartCommand(ID, contractName, "payload", "k1", null, null, null, null, null);
    }

    /** An engine whose start dedup finds an instance pinned to version 2, which its registry has no plan for. */
    private WorkflowEngineHandle engineWithReplayableStart() {
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
        return WorkflowEngineFactory.create(WorkflowEngineParts.builder()
                .txRunner(mock(WorkflowTransactionRunner.class))
                .registry(registry)
                .instances(instances)
                .history(mock(WorkflowHistoryRepository.class))
                .dedup(dedup)
                .branchTokens(mock(BranchTokenRepository.class))
                .joinStates(mock(JoinStateRepository.class))
                .timerStore(mock(dev.vertique.workflow.timer.TimerStore.class))
                .taskStore(mock(dev.vertique.workflow.tasks.TaskStore.class))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .recorders(Set.<WorkflowSideEffectRecorder<SqlClient>>of())
                .optionalIntentKinds(WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS)
                .migrationRegistry(Optional.empty())
                .propagator(null)
                .typeNames(WorkflowEngineFactory.registryTypeNames(registry))
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
    }
}
