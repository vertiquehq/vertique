// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.registry.WorkflowContributor;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Workflow definitions that exist twice, once in the default scope and once in the {@code acme} scope, with the
 * same id and version but different step graphs, so that an instance's pinned plan can be told from the other
 * scope's plan by what it does.
 *
 * <p>Both graphs of one id share their step ids and their initial state up to the first wait, so a row started
 * in the default scope is a valid row for the {@code acme} plan once its definition scope and plan hash are
 * stamped. Every step mutator appends {@code <scope>:<step>} to the state's trail, so the state of an instance
 * records which plan ran each step.
 *
 * <p>Three definition ids keep the scenarios apart in one registry:
 * <ul>
 *   <li>{@link #APPROVAL_ID} version 1: wait {@code submit}, task {@code review}, timer {@code cool-off}, then
 *       complete in the default scope and a further wait {@code finance-ok} in {@code acme}. The default task is
 *       assigned to role {@code clerk} and the {@code acme} task to role {@code finance}.</li>
 *   <li>{@link #FORK_ID} version 1: a fork whose branch {@code a} ends at once and whose branch {@code b} waits
 *       for a signal, then a join leading to {@code notify-default} or {@code notify-acme}.</li>
 *   <li>{@link #MIGRATION_ID} versions 1 and 2, with a migration handler from version 1 to version 2 that
 *       continues at step {@code review}.</li>
 * </ul>
 */
final class ScopedApprovalFixture {

    /** The scope that owns the second copy of every definition. */
    static final String ACME = "acme";

    static final String APPROVAL_ID = "purchase-approval";
    static final String FORK_ID = "purchase-approval-fork";
    static final String MIGRATION_ID = "purchase-approval-migration";

    private static final String DEFAULT_PREFIX = "default:";
    private static final String ACME_PREFIX = ACME + ":";

    private ScopedApprovalFixture() {}

    /** Start payload of every definition. */
    record StartApproval(String key) {}

    /** State of every definition: the start key and the trail of steps that ran. */
    record ApprovalState(String key, List<String> trail) {

        ApprovalState {
            trail = List.copyOf(trail);
        }

        ApprovalState with(String entry) {
            List<String> next = new ArrayList<>(trail);
            next.add(entry);
            return new ApprovalState(key, next);
        }
    }

    /** Contract of every definition; it declares each signal any of the graphs waits for. */
    interface ApprovalContract {

        @WorkflowSignal("submit")
        Future<Void> submit(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("finance-ok")
        Future<Void> financeOk(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("sig-b")
        Future<Void> sigB(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** A code-first definition whose body is supplied by the declaration. */
    static final class ApprovalDefinition implements WorkflowDefinition<ApprovalState, ApprovalContract> {
        private final String definitionId;
        private final long definitionVersion;
        private final Consumer<WorkflowBuilder<ApprovalState>> body;

        ApprovalDefinition(String definitionId, long definitionVersion, Consumer<WorkflowBuilder<ApprovalState>> body) {
            this.definitionId = definitionId;
            this.definitionVersion = definitionVersion;
            this.body = body;
        }

        @Override
        public Class<ApprovalContract> contract() {
            return ApprovalContract.class;
        }

        @Override
        public Class<ApprovalState> stateType() {
            return ApprovalState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return definitionVersion;
        }

        @Override
        public void define(WorkflowBuilder<ApprovalState> workflow) {
            body.accept(workflow);
        }
    }

    /**
     * Contributes every definition of the fixture: the default graphs through {@code register(definition)} and
     * the {@code acme} graphs through {@code register(scopeKey, definition)}.
     *
     * @return the contributor
     */
    static WorkflowContributor contributor() {
        return registry -> {
            registry.register(approval(DEFAULT_PREFIX, "clerk", false));
            registry.register(ACME, approval(ACME_PREFIX, "finance", true));
            registry.register(fork(DEFAULT_PREFIX, "notify-default"));
            registry.register(ACME, fork(ACME_PREFIX, "notify-acme"));
            registry.register(migrationV1());
            registry.register(ACME, migrationV1());
            registry.register(migrationV2(DEFAULT_PREFIX, false));
            registry.register(ACME, migrationV2(ACME_PREFIX, true));
        };
    }

    /** Migrates version 1 of {@link #MIGRATION_ID} to version 2 in the default scope, continuing at step {@code review}. */
    static final WorkflowMigrationHandler<ApprovalState, ApprovalState> MIGRATION = migration("default");

    /** Migrates version 1 of {@link #MIGRATION_ID} to version 2 in {@link #ACME}, continuing at step {@code review}. */
    static final WorkflowMigrationHandler<ApprovalState, ApprovalState> ACME_MIGRATION = migration(ACME);

    private static WorkflowMigrationHandler<ApprovalState, ApprovalState> migration(String scope) {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String sourceScope() {
                return scope;
            }

            @Override
            public String definitionId() {
                return MIGRATION_ID;
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
            public Class<ApprovalState> sourceStateType() {
                return ApprovalState.class;
            }

            @Override
            public Class<ApprovalState> targetStateType() {
                return ApprovalState.class;
            }

            @Override
            public MigrationResult<ApprovalState> migrate(ApprovalState sourceState, MigrationContext ctx) {
                return MigrationResult.continueAt(sourceState.with("migrated"), "review");
            }
        };
    }

    /**
     * Stamps an instance row as pinned to a plan of another scope, the way a scoped start will leave it: the row
     * gets the definition scope and the plan hash of that scope's plan.
     *
     * @param pool the pool of the database that holds the instance
     * @param id the instance
     * @param definitionScopeKey the definition scope to pin
     * @param planHash the plan hash of the plan of that scope
     * @throws Exception if the update fails
     */
    static void stampScope(Pool pool, WorkflowInstanceId id, String definitionScopeKey, String planHash)
            throws Exception {
        pool.preparedQuery("UPDATE workflow_instances SET definition_scope_key = $1, plan_hash = $2 WHERE id = $3")
                .execute(Tuple.of(definitionScopeKey, planHash, id.value()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    /**
     * Stamps an instance row as pinned to a plan of another scope and as started in a given scope: the row gets the
     * definition scope and the plan hash of that scope's plan, and the start scope.
     *
     * @param pool the pool of the database that holds the instance
     * @param id the instance
     * @param definitionScopeKey the definition scope to pin
     * @param startScopeKey the scope the instance was started in
     * @param planHash the plan hash of the plan of the definition scope
     * @throws Exception if the update fails
     */
    static void stampScope(
            Pool pool, WorkflowInstanceId id, String definitionScopeKey, String startScopeKey, String planHash)
            throws Exception {
        pool.preparedQuery("UPDATE workflow_instances SET definition_scope_key = $1, scope_key = $2, plan_hash = $3"
                        + " WHERE id = $4")
                .execute(Tuple.of(definitionScopeKey, startScopeKey, planHash, id.value()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    // --- Graphs ---

    /**
     * wait {@code submit}, task {@code review}, timer {@code cool-off}, then complete, or in the {@code acme}
     * graph wait {@code finance-ok} before completing.
     */
    private static WorkflowDefinition<ApprovalState, ApprovalContract> approval(
            String prefix, String reviewRole, boolean waitsForFinance) {
        return new ApprovalDefinition(APPROVAL_ID, 1L, wf -> {
            wf.init(StartApproval.class, p -> new ApprovalState(p.key(), List.of()))
                    .initialStep("submit")
                    .waitForSignal("submit", "submit", String.class)
                    .onSignal((s, p) -> s.with(prefix + "submit"))
                    .toStepOnSignal("review")
                    .build()
                    .task("review")
                    .assignToRole(reviewRole)
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s.with(prefix + "review"))
                    .toStep("cool-off")
                    .build()
                    .timer("cool-off", Duration.ofMinutes(5))
                    .toStep(waitsForFinance ? "finance-ok" : "done");
            if (waitsForFinance) {
                wf.waitForSignal("finance-ok", "finance-ok", String.class)
                        .onSignal((s, p) -> s.with(prefix + "finance-ok"))
                        .toStepOnSignal("done")
                        .build();
            }
            wf.complete("done");
        });
    }

    /**
     * fork: branch {@code a} ends at once, branch {@code b} waits for {@code sig-b}; the join leads to
     * {@code notifyStep}, which completes.
     */
    private static WorkflowDefinition<ApprovalState, ApprovalContract> fork(String prefix, String notifyStep) {
        return new ApprovalDefinition(FORK_ID, 1L, wf -> {
            wf.init(StartApproval.class, p -> new ApprovalState(p.key(), List.of()))
                    .fork("fork")
                    .branch("a", "a-done", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join")
                    .complete("a-done")
                    .waitForSignal("wait-b", "sig-b", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("b-done")
                    .build()
                    .complete("b-done")
                    .join("join")
                    .allRequired((s, results) -> s.with(prefix + "join"))
                    .toStep(notifyStep)
                    .onFailure(notifyStep)
                    .endJoin()
                    .complete(notifyStep);
            wf.initialStep("fork");
        });
    }

    /** Version 1 of the migrated definition: wait {@code submit}, then complete. Identical in both scopes. */
    private static WorkflowDefinition<ApprovalState, ApprovalContract> migrationV1() {
        return new ApprovalDefinition(
                MIGRATION_ID, 1L, wf -> wf.init(StartApproval.class, p -> new ApprovalState(p.key(), List.of()))
                        .initialStep("submit")
                        .waitForSignal("submit", "submit", String.class)
                        .onSignal((s, p) -> s.with("v1:submit"))
                        .toStepOnSignal("done")
                        .build()
                        .complete("done"));
    }

    /**
     * Version 2 of the migrated definition: wait {@code submit} at step {@code review}, then complete, or in the
     * {@code acme} graph wait {@code finance-ok} before completing.
     */
    private static WorkflowDefinition<ApprovalState, ApprovalContract> migrationV2(
            String prefix, boolean waitsForFinance) {
        return new ApprovalDefinition(MIGRATION_ID, 2L, wf -> {
            wf.init(StartApproval.class, p -> new ApprovalState(p.key(), List.of()))
                    .initialStep("review")
                    .waitForSignal("review", "submit", String.class)
                    .onSignal((s, p) -> s.with(prefix + "review"))
                    .toStepOnSignal(waitsForFinance ? "finance-ok" : "done")
                    .build();
            if (waitsForFinance) {
                wf.waitForSignal("finance-ok", "finance-ok", String.class)
                        .onSignal((s, p) -> s.with(prefix + "finance-ok"))
                        .toStepOnSignal("done")
                        .build();
            }
            wf.complete("done");
        });
    }
}
