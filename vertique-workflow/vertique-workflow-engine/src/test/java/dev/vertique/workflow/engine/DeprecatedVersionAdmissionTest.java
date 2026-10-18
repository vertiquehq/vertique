// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Verifies that the engine refuses to admit new work onto a deprecated definition version, while
 * instances that already run on that version keep resuming.
 *
 * <p>The registry reports version 2 as deprecated and version 1 as the current version. The
 * scenario starts version 2 explicitly, migrates another instance onto it, signals an instance that
 * is pinned to it, and finally starts without naming a version.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DeprecatedVersionAdmissionTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "deprecation-admission-flow";
    private static final String WAIT_STEP = "wait-signal";
    private static final String COMPLETE_STEP = "complete";
    private static final String SIGNAL_NAME = "approve";
    private static final String PLAN_HASH_V1 = "plan-hash-v1";
    private static final String PLAN_HASH_V2 = "plan-hash-v2";
    private static final String PLAN_HASH_V3 = "plan-hash-v3";

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    /** Builds a runtime workflow that waits for {@code approve} and then completes. */
    private static RuntimeWorkflow runtimeWorkflow(long version, String planHash) {
        WaitSignalNode waitSignal = new WaitSignalNode(
                WAIT_STEP, SIGNAL_NAME, Object.class.getName(), new CallbackId("state-updater"), COMPLETE_STEP, null);
        List<WorkflowNode> nodes = List.of(waitSignal, new CompleteNode(COMPLETE_STEP));
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, version, planHash, Object.class.getName(), waitSignal.stepId(), nodes, null);
        WorkflowCallbackRegistry callbacks = mock(WorkflowCallbackRegistry.class);
        when(callbacks.stateUpdater(new CallbackId("state-updater"))).thenReturn((state, payload) -> Map.of());
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /** Builds an instance pinned to the given version and waiting for {@code approve}. */
    private static WorkflowInstance waitingInstance(long version, String planHash) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                version,
                planHash,
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                WAIT_STEP,
                WaitType.SIGNAL,
                SIGNAL_NAME,
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                null);
    }

    /** A migration handler from version 1 to the given target that anchors at the target's initial step. */
    private static WorkflowMigrationHandler<Object, Object> migrationFromV1(long target, AtomicInteger invocations) {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return DEFINITION_ID;
            }

            @Override
            public long fromDefinitionVersion() {
                return 1;
            }

            @Override
            public long targetDefinitionVersion() {
                return target;
            }

            @Override
            public Class<Object> sourceStateType() {
                return Object.class;
            }

            @Override
            public Class<Object> targetStateType() {
                return Object.class;
            }

            @Override
            public MigrationResult<Object> migrate(Object sourceState, MigrationContext ctx) {
                invocations.incrementAndGet();
                return MigrationResult.anchorAtInitial(Map.of());
            }
        };
    }

    /** Everything a scenario needs to drive the assembled engine and inspect its collaborators. */
    private record Harness(
            WorkflowEngineHandle engine,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowHistoryRepository<SqlClient> history,
            SqlClient tx,
            WorkflowInstance pinnedToDeprecated,
            WorkflowInstance onVersion1,
            AtomicInteger migrationsToVersion2,
            AtomicInteger migrationsToVersion3) {}

    /**
     * Assembles an engine whose registry reports version 1 as current, version 2 as deprecated and
     * version 3 as an ordinary registered version, with every collaborator write succeeding so only
     * the admission checks can stop a start or a migration.
     */
    private static Harness harness() {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();
        SqlClient tx = ActiveTransactionClient.create();

        RuntimeWorkflow version1 = runtimeWorkflow(1, PLAN_HASH_V1);
        RuntimeWorkflow version2 = runtimeWorkflow(2, PLAN_HASH_V2);
        RuntimeWorkflow version3 = runtimeWorkflow(3, PLAN_HASH_V3);
        when(registry.resolveCurrent(DEFINITION_ID)).thenReturn(version1);
        when(registry.resolvePinned(DEFINITION_ID, 1)).thenReturn(version1);
        when(registry.resolvePinned(DEFINITION_ID, 2)).thenReturn(version2);
        when(registry.resolvePinned(DEFINITION_ID, 3)).thenReturn(version3);
        when(registry.isDeprecated(DEFINITION_ID, 2)).thenReturn(true);
        when(registry.isDeprecated(DEFINITION_ID, 1)).thenReturn(false);
        when(registry.isDeprecated(DEFINITION_ID, 3)).thenReturn(false);

        WorkflowInstance pinnedToDeprecated = waitingInstance(2, PLAN_HASH_V2);
        WorkflowInstance onVersion1 = waitingInstance(1, PLAN_HASH_V1);
        when(instances.findById(pinnedToDeprecated.id(), tx))
                .thenReturn(Future.succeededFuture(Optional.of(pinnedToDeprecated)));
        when(instances.findById(onVersion1.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(onVersion1)));

        when(dedup.claimOrResolveStart(eq(DEFINITION_ID), any(), any(), any(), any()))
                .thenAnswer(invocation -> Future.succeededFuture(new StartDedupResult(
                        new WorkflowInstanceId(UUID.randomUUID()), true, "definitionVersion=unused")));
        when(dedup.claimOrResolveSignal(any(), any(), eq(tx))).thenReturn(Future.succeededFuture(true));
        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(instances.migratePinAndState(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());
        when(history.listRecentByInstance(any(), anyInt(), any())).thenReturn(Future.succeededFuture(List.of()));
        when(branchTokens.findActiveByWorkflow(any(), any())).thenReturn(Future.succeededFuture(List.of()));

        AtomicInteger toVersion2 = new AtomicInteger();
        AtomicInteger toVersion3 = new AtomicInteger();
        WorkflowMigrationHandler<Object, Object> handlerToVersion2 = migrationFromV1(2, toVersion2);
        WorkflowMigrationHandler<Object, Object> handlerToVersion3 = migrationFromV1(3, toVersion3);
        WorkflowMigrationRegistry migrations = new WorkflowMigrationRegistry() {
            @Override
            public Optional<WorkflowMigrationHandler<?, ?>> find(
                    String definitionId, long sourceVersion, long targetVersion) {
                if (!definitionId.equals(DEFINITION_ID) || sourceVersion != 1) return Optional.empty();
                if (targetVersion == 2) return Optional.of(handlerToVersion2);
                if (targetVersion == 3) return Optional.of(handlerToVersion3);
                return Optional.empty();
            }

            @Override
            public Collection<WorkflowMigrationHandler<?, ?>> all() {
                return List.of(handlerToVersion2, handlerToVersion3);
            }
        };

        WorkflowEngineHandle engine = WorkflowEngineFactory.create(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                FIXED_CLOCK,
                Set.<WorkflowSideEffectRecorder<SqlClient>>of(),
                WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
                Optional.of(migrations),
                null);
        return new Harness(
                engine, instances, dedup, history, tx, pinnedToDeprecated, onVersion1, toVersion2, toVersion3);
    }

    /** Resolves to the failure of the given future, or to {@code null} when it succeeded. */
    private static Future<Throwable> failureOf(Future<?> future) {
        return future.transform(result -> Future.succeededFuture(result.failed() ? result.cause() : null));
    }

    @Test
    @DisplayName("an explicit start and a migration target on a deprecated version fail while pinned instances resume")
    void refusesExplicitStartAndMigrationButResumesPins(Vertx vertx, VertxTestContext ctx) {
        // Given version 1 is current and version 2 is deprecated, with an instance pinned to each
        Harness harness = harness();
        WorkflowEngineHandle engine = harness.engine();
        WorkflowInstanceRepository<SqlClient> instances = harness.instances();
        WorkflowDedupRepository<SqlClient> dedup = harness.dedup();
        WorkflowHistoryRepository<SqlClient> history = harness.history();
        SqlClient tx = harness.tx();
        WorkflowInstance pinnedToDeprecated = harness.pinnedToDeprecated();
        WorkflowInstance onVersion1 = harness.onVersion1();

        StartCommand explicitStartOfDeprecated =
                new StartCommand(DEFINITION_ID, Map.of(), "start-" + UUID.randomUUID(), null, null, 2L);
        StartCommand startWithoutVersion =
                new StartCommand(DEFINITION_ID, Map.of(), "start-" + UUID.randomUUID(), null, null);

        runOnDuplicated(vertx, v -> {
            // When the deprecated version is started explicitly
            failureOf(engine.start(explicitStartOfDeprecated, tx))
                    .compose(startFailure -> {
                        // Then the start fails typed naming the deprecated version, and no instance row is written
                        assertThat(startFailure)
                                .as("an explicit start of a deprecated version must fail")
                                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                                    assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                                    assertThat(failure.key().definitionId()).isEqualTo(DEFINITION_ID);
                                    assertThat(failure.key().definitionVersion())
                                            .isEqualTo(2);
                                    assertThat(failure.getMessage()).contains(DEFINITION_ID);
                                });
                        verify(instances, never()).insert(any(), any());
                        verify(dedup, never()).claimOrResolveStart(any(), any(), any(), any(), any());
                        verify(history, never()).append(any(), any());

                        // When another instance is migrated onto the deprecated version
                        return failureOf(engine.migrate(onVersion1.id(), 2L, tx));
                    })
                    .compose(migrateFailure -> {
                        // Then the migration fails typed naming the deprecated version, and the instance stays pinned
                        assertThat(migrateFailure)
                                .as("a migration onto a deprecated version must fail")
                                .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                                    assertThat(failure.reason()).isEqualTo(Reason.DEPRECATED);
                                    assertThat(failure.key().definitionId()).isEqualTo(DEFINITION_ID);
                                    assertThat(failure.key().definitionVersion())
                                            .isEqualTo(2);
                                    assertThat(failure.getMessage()).contains(DEFINITION_ID);
                                });
                        verify(instances, never()).migratePinAndState(any(), anyLong(), any());
                        verify(instances, never()).updateOptimistic(any(), anyLong(), any());
                        verify(history, never()).append(any(), any());
                        verify(history, never()).listRecentByInstance(any(), anyInt(), any());
                        assertThat(harness.migrationsToVersion2())
                                .as("the migration handler must not run for a deprecated target")
                                .hasValue(0);

                        // When the instance already pinned to the deprecated version receives its signal
                        return failureOf(engine.signal(pinnedToDeprecated.id(), SIGNAL_NAME, Map.of(), "signal-1", tx));
                    })
                    .compose(signalFailure -> {
                        // Then the signal resumes it to completion
                        assertThat(signalFailure)
                                .as("a signal to an instance pinned to a deprecated version must still succeed")
                                .isNull();
                        ArgumentCaptor<WorkflowInstance> updates = ArgumentCaptor.forClass(WorkflowInstance.class);
                        verify(instances, atLeastOnce()).updateOptimistic(updates.capture(), anyLong(), any());
                        assertThat(updates.getAllValues())
                                .filteredOn(update -> update.id().equals(pinnedToDeprecated.id()))
                                .last()
                                .satisfies(update -> {
                                    assertThat(update.status()).isEqualTo(WorkflowStatus.COMPLETED);
                                    assertThat(update.definitionVersion()).isEqualTo(2);
                                });

                        // When a start does not name a version
                        return failureOf(engine.start(startWithoutVersion, tx));
                    })
                    .onComplete(outcome -> ctx.verify(() -> {
                        if (outcome.failed()) {
                            ctx.failNow(outcome.cause());
                            return;
                        }
                        // Then it succeeds on the current, non-deprecated version
                        assertThat(outcome.result())
                                .as("a start without a requested version must succeed")
                                .isNull();
                        ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
                        verify(instances).insert(inserted.capture(), any());
                        assertThat(inserted.getValue().definitionVersion()).isEqualTo(1);
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("an explicit start and a migration target on a version that is not deprecated are admitted")
    void admitsExplicitStartAndMigrationOnNonDeprecatedVersions(Vertx vertx, VertxTestContext ctx) {
        // Given version 1 is current, version 3 is registered and neither is deprecated
        Harness harness = harness();
        WorkflowEngineHandle engine = harness.engine();
        StartCommand explicitStartOfVersion1 =
                new StartCommand(DEFINITION_ID, Map.of(), "start-" + UUID.randomUUID(), null, null, 1L);

        runOnDuplicated(vertx, v -> {
            // When version 1 is started explicitly
            failureOf(engine.start(explicitStartOfVersion1, harness.tx()))
                    .compose(startFailure -> {
                        // Then the start succeeds: one dedup claim and an instance row pinned to version 1
                        assertThat(startFailure)
                                .as("an explicit start of a version that is not deprecated must succeed")
                                .isNull();
                        verify(harness.dedup(), times(1)).claimOrResolveStart(any(), any(), any(), any(), any());
                        ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
                        verify(harness.instances()).insert(inserted.capture(), any());
                        assertThat(inserted.getValue().definitionVersion()).isEqualTo(1);
                        assertThat(inserted.getValue().planHash()).isEqualTo(PLAN_HASH_V1);

                        // When an instance on version 1 is migrated to version 3
                        return failureOf(
                                harness.engine().migrate(harness.onVersion1().id(), 3L, harness.tx()));
                    })
                    .onComplete(outcome -> ctx.verify(() -> {
                        if (outcome.failed()) {
                            ctx.failNow(outcome.cause());
                            return;
                        }
                        // Then the migration succeeds: the handler ran once and the instance is re-pinned to version 3
                        assertThat(outcome.result())
                                .as("a migration onto a version that is not deprecated must succeed")
                                .isNull();
                        assertThat(harness.migrationsToVersion3()).hasValue(1);
                        assertThat(harness.migrationsToVersion2()).hasValue(0);
                        ArgumentCaptor<WorkflowInstance> migrated = ArgumentCaptor.forClass(WorkflowInstance.class);
                        verify(harness.instances()).migratePinAndState(migrated.capture(), anyLong(), any());
                        assertThat(migrated.getValue().id())
                                .isEqualTo(harness.onVersion1().id());
                        assertThat(migrated.getValue().definitionVersion()).isEqualTo(3);
                        assertThat(migrated.getValue().planHash()).isEqualTo(PLAN_HASH_V3);
                        ctx.completeNow();
                    }));
        });
    }
}
