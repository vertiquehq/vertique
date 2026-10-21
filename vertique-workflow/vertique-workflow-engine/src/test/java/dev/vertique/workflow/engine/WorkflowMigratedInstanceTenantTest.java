// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that the instance snapshot a migration hands to the transition driver keeps the
 * instance's start scope and definition scope.
 *
 * <p>The {@link MigrationExecutor} is built over mocked repositories with a registered migration
 * handler and a mocked {@link WorkflowTransitionDriver} whose argument is captured after the
 * instance has been re-pinned.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class WorkflowMigratedInstanceTenantTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-migrated-scope-test";
    private static final String SOURCE_HASH = "plan-hash-v1";
    private static final String TARGET_HASH = "plan-hash-v2";

    record SourceState(String name) {}

    record TargetState(String name, boolean migrated) {}

    private static RuntimeWorkflow runtimeWorkflow(long version, String planHash, String stepId) {
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID,
                version,
                planHash,
                Object.class.getName(),
                stepId,
                List.of(new CompleteNode(stepId)),
                null);
        WorkflowCallbackRegistry callbacks = mock();
        return RuntimeWorkflow.of(
                plan, "test-contract", Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    private static WorkflowInstance waitingInstance(String scopeKey, String definitionScopeKey) {
        Instant created = Instant.parse("2026-05-08T09:00:00Z");
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                SOURCE_HASH,
                3L,
                WorkflowStatus.WAITING,
                "business-key-1",
                null,
                "source-step",
                WaitType.SIGNAL,
                "go",
                null,
                "{\"name\":\"alice\"}",
                null,
                null,
                created,
                created,
                null,
                scopeKey,
                definitionScopeKey);
    }

    private static final WorkflowMigrationHandler<SourceState, TargetState> HANDLER = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return DEFINITION_ID;
        }

        @Override
        public long fromDefinitionVersion() {
            return 1L;
        }

        @Override
        public long targetDefinitionVersion() {
            return 2L;
        }

        @Override
        public Class<SourceState> sourceStateType() {
            return SourceState.class;
        }

        @Override
        public Class<TargetState> targetStateType() {
            return TargetState.class;
        }

        @Override
        public MigrationResult<TargetState> migrate(SourceState sourceState, MigrationContext ctx) {
            return MigrationResult.continueAt(new TargetState(sourceState.name(), true), "target-step");
        }
    };

    /** The collaborators of one migration, with the snapshots they received. */
    private record Migrated(WorkflowInstance persisted, WorkflowInstance driven, boolean succeeded) {}

    /** Migrates the instance from version 1 to version 2 through a {@link MigrationExecutor}. */
    private static Migrated migrate(WorkflowInstance source) {
        WorkflowMigrationRegistry migrations = mock();
        when(migrations.find(DEFINITION_ID, 1L, 2L)).thenReturn(Optional.<WorkflowMigrationHandler<?, ?>>of(HANDLER));
        WorkflowRegistry registry = mock();
        when(registry.resolvePinned(DEFINITION_ID, 1L)).thenReturn(runtimeWorkflow(1L, SOURCE_HASH, "source-step"));
        RuntimeWorkflow target = runtimeWorkflow(2L, TARGET_HASH, "target-step");
        when(registry.resolvePinned(DEFINITION_ID, 2L)).thenReturn(target);
        WorkflowInstanceRepository<SqlClient> instances = mock();
        when(instances.findById(eq(source.id()), any())).thenReturn(Future.succeededFuture(Optional.of(source)));
        when(instances.migratePinAndState(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        WorkflowHistoryRepository<SqlClient> history = mock();
        when(history.listRecentByInstance(eq(source.id()), anyInt(), any()))
                .thenReturn(Future.succeededFuture(List.of()));
        when(history.nextSequence(eq(source.id()), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());
        BranchTokenRepository<SqlClient> branchTokens = mock();
        when(branchTokens.findActiveByWorkflow(eq(source.id()), any())).thenReturn(Future.succeededFuture(List.of()));
        WorkflowTransitionDriver driver = mock();
        when(driver.driveTransitions(any(), any(), any())).thenReturn(Future.succeededFuture());

        MigrationExecutor executor = new MigrationExecutor(
                Optional.of(migrations),
                registry,
                instances,
                history,
                branchTokens,
                driver,
                FIXED_CLOCK,
                WorkflowContextBinder.noop());

        SqlClient tx = ActiveTransactionClient.create();
        Future<Void> outcome = executor.migrate(source.id(), 2L, tx);

        ArgumentCaptor<WorkflowInstance> persisted = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(instances).migratePinAndState(persisted.capture(), eq(3L), any());
        ArgumentCaptor<WorkflowInstance> driven = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(driver).driveTransitions(driven.capture(), eq(target), any());
        return new Migrated(persisted.getValue(), driven.getValue(), outcome.succeeded());
    }

    @Test
    @DisplayName("the snapshot driven after a migration keeps the instance scopes")
    void migratedSnapshotDrivenAfterMigrationKeepsTheInstanceScopes() {
        // Given a waiting instance started in t1 on a definition of scope t2, and a registered migration from
        // version 1 to 2
        WorkflowInstance stamped = waitingInstance("t1", "t2");

        // When the instance is migrated
        Migrated migrated = migrate(stamped);

        // Then the snapshot handed to the transition driver carries t1 with the re-pinned version, step and state
        assertThat(migrated.succeeded()).isTrue();
        WorkflowInstance driven = migrated.driven();
        assertThat(driven.scopeKey()).isEqualTo("t1");
        assertThat(driven.definitionScopeKey()).isEqualTo("t2");
        assertThat(driven.definitionVersion()).isEqualTo(2L);
        assertThat(driven.planHash()).isEqualTo(TARGET_HASH);
        assertThat(driven.currentStepId()).isEqualTo("target-step");
        assertThat(driven.version()).isEqualTo(4L);
        assertThat(driven.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(driven.stateJson()).isEqualTo("{\"name\":\"alice\",\"migrated\":true}");
        // And the snapshot written to the row is the same re-pinned snapshot
        assertThat(migrated.persisted().scopeKey()).isEqualTo("t1");
        assertThat(migrated.persisted().definitionScopeKey()).isEqualTo("t2");

        // Given a waiting instance in the default scope
        WorkflowInstance unscoped = waitingInstance(WorkflowScopes.DEFAULT, WorkflowScopes.DEFAULT);

        // When the instance is migrated
        Migrated migratedUnscoped = migrate(unscoped);

        // Then the driven snapshot stays in the default scope
        assertThat(migratedUnscoped.succeeded()).isTrue();
        assertThat(migratedUnscoped.driven().scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(migratedUnscoped.driven().definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(migratedUnscoped.driven().definitionVersion()).isEqualTo(2L);
    }
}
