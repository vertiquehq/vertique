// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.await;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.state.WorkflowInstance;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that an instance migrates across scopes only to a definition of the same contract whose state
 * type extends the source's, and only into its start scope or the default scope.
 *
 * <p>The state comparison is the schema module's, obtained from the public modules through a small Dagger component,
 * which also provides the registry that applies the same comparison when definitions register. An instance that is
 * pinned to a definition of another scope is made the way the scoped pin proofs make it: it is started in the default
 * scope on an explicit version and its row is then stamped with the definition scope, the start scope and the plan
 * hash of that definition.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class CrossScopeMigrationIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_cross_scope_migrate_test")
            .withMigration("classpath:db/migration/workflow");

    private static final String ID = "cm-x";
    private static final String DEFAULT = WorkflowScopes.DEFAULT;
    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";

    // --- Fixture types ---

    record StartPayload(String id) {}

    /** State of the default definitions. */
    record Base(String id, int amount) {}

    /** State that extends {@link Base} by one property. */
    record Added(String id, int amount, String note) {}

    @WorkflowContract("cm-c")
    interface ContractC {

        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    @WorkflowContract("cm-d")
    interface ContractD {

        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** A definition that waits for the signal {@code go}, with the given state type and contract. */
    private static final class Waiting<S, C> implements WorkflowDefinition<S, C> {
        private final Class<S> stateType;
        private final Class<C> contract;
        private final long version;
        private final java.util.function.Function<String, S> initial;

        Waiting(Class<S> stateType, Class<C> contract, long version, java.util.function.Function<String, S> initial) {
            this.stateType = stateType;
            this.contract = contract;
            this.version = version;
            this.initial = initial;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<S> stateType() {
            return stateType;
        }

        @Override
        public String definitionId() {
            return ID;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<S> wf) {
            wf.init(StartPayload.class, start -> initial.apply(start.id()))
                    .initialStep("wait")
                    .waitForSignal("wait", "go", String.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("done")
                    .build()
                    .complete("done");
        }
    }

    private static WorkflowDefinition<Base, ContractC> defaultCode(long version) {
        return new Waiting<>(Base.class, ContractC.class, version, id -> new Base(id, 0));
    }

    private static WorkflowDefinition<Added, ContractC> acmeDocument() {
        return new Waiting<>(Added.class, ContractC.class, 1L, id -> new Added(id, 0, null));
    }

    /** A handler that records every context it runs with. */
    private static final class Recording<S, T> implements WorkflowMigrationHandler<S, T> {
        private final String sourceScope;
        private final long from;
        private final String targetScope;
        private final long to;
        private final Class<S> sourceState;
        private final Class<T> targetState;
        private final java.util.function.BiFunction<S, MigrationContext, T> conversion;
        final List<MigrationContext> contexts = new CopyOnWriteArrayList<>();

        Recording(
                String sourceScope,
                long from,
                String targetScope,
                long to,
                Class<S> sourceState,
                Class<T> targetState,
                java.util.function.BiFunction<S, MigrationContext, T> conversion) {
            this.sourceScope = sourceScope;
            this.from = from;
            this.targetScope = targetScope;
            this.to = to;
            this.sourceState = sourceState;
            this.targetState = targetState;
            this.conversion = conversion;
        }

        @Override
        public String sourceScope() {
            return sourceScope;
        }

        @Override
        public String definitionId() {
            return ID;
        }

        @Override
        public long fromDefinitionVersion() {
            return from;
        }

        @Override
        public String targetScope() {
            return targetScope;
        }

        @Override
        public long targetDefinitionVersion() {
            return to;
        }

        @Override
        public Class<S> sourceStateType() {
            return sourceState;
        }

        @Override
        public Class<T> targetStateType() {
            return targetState;
        }

        @Override
        public MigrationResult<T> migrate(S state, MigrationContext ctx) {
            contexts.add(ctx);
            return MigrationResult.anchorAtInitial(conversion.apply(state, ctx));
        }
    }

    private static Recording<Base, Base> baseToBase(String sourceScope, long from, String targetScope, long to) {
        return new Recording<>(sourceScope, from, targetScope, to, Base.class, Base.class, (state, ctx) -> state);
    }

    // --- Wiring of the schema comparison ---

    @Module
    abstract static class JsonBindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }
    }

    @Singleton
    @Component(modules = {WorkflowCoreModule.class, WorkflowSchemaModule.class, JsonBindings.class})
    interface Comparison {

        WorkflowRegistry registry();

        Optional<WorkflowStateCompatibility> compatibility();
    }

    // --- Harness ---

    /** An engine over its own pool, the registry it resolves from and the handlers it migrates with. */
    private record Harness(Vertx vertx, Pool pool, WorkflowRegistry registry, WorkflowEngineHandle engine) {

        void close() {
            pool.close();
            vertx.close();
        }

        String planHash(String scope, long version) {
            return registry.resolve(new DefinitionKey(scope, ID, version))
                    .plan()
                    .planHash();
        }
    }

    private static Harness open(Consumer<WorkflowRegistry> definitions, WorkflowMigrationHandler<?, ?>... handlers) {
        Vertx vertx = Vertx.vertx();
        DbPoolConfig config = db.toPoolConfig();
        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(5))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        Comparison comparison = DaggerCrossScopeMigrationIT_Comparison.create();
        WorkflowRegistry registry = comparison.registry();
        definitions.accept(registry);
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        WorkflowEngineHandle engine = PgWorkflowEngineTestSupport.createWithStateCompatibility(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, mapper),
                new PgWorkflowHistoryRepository(pool, mapper),
                new PgWorkflowDedupRepository(pool, mapper),
                new PgTimerStore(mapper),
                new PgTaskStore(pool, mapper),
                Clock.systemUTC(),
                new DefaultWorkflowMigrationRegistry(Set.of(handlers), registry),
                comparison.compatibility());
        return new Harness(vertx, pool, registry, engine);
    }

    @BeforeEach
    void truncateTables() throws Exception {
        Harness harness = open(registry -> {});
        try {
            await(harness.pool()
                    .query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                            + " workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
                    .execute());
        } finally {
            harness.close();
        }
    }

    /** Starts an instance on default version 1 that waits for its signal, then pins it as the scenario describes. */
    private static WorkflowInstanceId waitingPinnedTo(
            Harness harness, String definitionScope, String startScope, String planHash) throws Exception {
        StartCommand start = new StartCommand(
                ID, "cm-c", new StartPayload("i-1"), "cm-" + UUID.randomUUID(), null, null, 1L, null, null);
        WorkflowInstanceId id = await(harness.engine().start(start));
        await(harness.engine().executionProbe().awaitIdle(id, IDLE_TIMEOUT));
        ScopedApprovalFixture.stampScope(harness.pool(), id, definitionScope, startScope, planHash);
        return id;
    }

    private static WorkflowInstance row(Harness harness, WorkflowInstanceId id) throws Exception {
        return await(harness.engine().query(id)).instance();
    }

    // --- Tests ---

    @Test
    @DisplayName("a cross-scope migration requires the target state type to extend the source state type")
    void crossScopeMigrationRequiresTargetStateToExtendSource() throws Exception {
        // Given: default versions 1 and 2 with state Base, an acme definition of version 1 with state Added of the same
        // contract, and handlers default 1 to default 2, default 1 to acme 1 and acme 1 to default 2
        Recording<Base, Base> defaultToDefault = baseToBase(DEFAULT, 1L, DEFAULT, 2L);
        Recording<Base, Added> defaultToAcme = new Recording<>(
                DEFAULT,
                1L,
                ACME,
                1L,
                Base.class,
                Added.class,
                (state, ctx) -> new Added(state.id(), state.amount(), "n"));
        Recording<Added, Base> acmeToDefault = new Recording<>(
                ACME, 1L, DEFAULT, 2L, Added.class, Base.class, (state, ctx) -> new Base(state.id(), state.amount()));
        Harness harness = open(
                registry -> {
                    registry.register(defaultCode(1L));
                    registry.register(defaultCode(2L));
                    registry.register(ACME, acmeDocument());
                },
                defaultToDefault,
                defaultToAcme,
                acmeToDefault);
        try {
            // and: instance I1 started in acme and pinned to default version 1, instance I2 started in acme and
            // pinned to acme version 1, both waiting for a signal
            String acmeHash = harness.planHash(ACME, 1L);
            WorkflowInstanceId i1 = waitingPinnedTo(harness, DEFAULT, ACME, harness.planHash(DEFAULT, 1L));
            WorkflowInstanceId i2 = waitingPinnedTo(harness, ACME, ACME, acmeHash);

            // When: I1 migrates to acme version 1 and I2 migrates to default version 2
            await(harness.engine().migrate(i1, ACME, 1L));
            await(harness.engine().executionProbe().awaitIdle(i1, IDLE_TIMEOUT));
            Throwable refusal = failureOf(() -> harness.engine().migrate(i2, DEFAULT, 2L));

            // Then: I1 is pinned to acme version 1 with the plan hash of the acme definition
            WorkflowInstance migrated = row(harness, i1);
            assertThat(migrated.definitionScopeKey()).isEqualTo(ACME);
            assertThat(migrated.definitionVersion()).isEqualTo(1L);
            assertThat(migrated.planHash()).isEqualTo(acmeHash);

            // and: the handler between the two scopes ran with both scopes in its context, the other did not
            assertThat(defaultToAcme.contexts).hasSize(1);
            assertThat(defaultToAcme.contexts.get(0).sourceScope()).isEqualTo(DEFAULT);
            assertThat(defaultToAcme.contexts.get(0).targetScope()).isEqualTo(ACME);
            assertThat(defaultToDefault.contexts).isEmpty();

            // and: I2 is refused naming the property that the target lacks, without running its handler
            assertThat(refusal).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refusal.getMessage()).contains("note");
            assertThat(acmeToDefault.contexts).isEmpty();

            // and: I2 keeps its pin
            WorkflowInstance unchanged = row(harness, i2);
            assertThat(unchanged.definitionScopeKey()).isEqualTo(ACME);
            assertThat(unchanged.definitionVersion()).isEqualTo(1L);
            assertThat(unchanged.planHash()).isEqualTo(acmeHash);
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("a migration to another contract or to a scope that is not visible is refused")
    void migrationToOtherContractOrForeignScopeIsRefused() throws Exception {
        // Given: acme holds its own version 1 under contract D, loaded before the default code ships, the default
        // code holds version 1 under contract C, globex holds version 1 under contract C, and handlers from the
        // default version 1 to each of them
        Recording<Base, Base> toAcme = baseToBase(DEFAULT, 1L, ACME, 1L);
        Recording<Base, Base> toGlobex = baseToBase(DEFAULT, 1L, GLOBEX, 1L);
        Harness harness = open(
                registry -> {
                    registry.register(ACME, new Waiting<>(Base.class, ContractD.class, 1L, id -> new Base(id, 0)));
                    registry.register(defaultCode(1L));
                    registry.register(GLOBEX, defaultCode(1L));
                },
                toAcme,
                toGlobex);
        try {
            // and: an instance started in acme and pinned to the default version 1
            String defaultHash = harness.planHash(DEFAULT, 1L);
            WorkflowInstanceId id = waitingPinnedTo(harness, DEFAULT, ACME, defaultHash);

            // When: it migrates to the acme definition of contract D, then to the globex definition
            Throwable otherContract = failureOf(() -> harness.engine().migrate(id, ACME, 1L));
            Throwable foreignScope = failureOf(() -> harness.engine().migrate(id, GLOBEX, 1L));

            // Then: the first is refused for the contract
            assertThat(otherContract).isInstanceOf(WorkflowDefinitionContractMismatchException.class);

            // and: the second is refused naming globex as not visible from acme
            assertThat(foreignScope).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(foreignScope).isNotInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(foreignScope.getMessage()).contains(GLOBEX).contains(ACME);

            // and: no handler ran and the pin is unchanged
            assertThat(toAcme.contexts).isEmpty();
            assertThat(toGlobex.contexts).isEmpty();
            WorkflowInstance unchanged = row(harness, id);
            assertThat(unchanged.definitionScopeKey()).isEqualTo(DEFAULT);
            assertThat(unchanged.definitionVersion()).isEqualTo(1L);
            assertThat(unchanged.planHash()).isEqualTo(defaultHash);
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("a refused migration names the definition key and no state class name")
    void stateRefusalOfAMigrationNamesNoStateClass() throws Exception {
        // Given: default versions 1 and 2 with state Base, an acme definition with state Added of the same contract,
        // and the handler from acme to default version 2
        Recording<Added, Base> acmeToDefault = new Recording<>(
                ACME, 1L, DEFAULT, 2L, Added.class, Base.class, (state, ctx) -> new Base(state.id(), state.amount()));
        Harness harness = open(
                registry -> {
                    registry.register(defaultCode(1L));
                    registry.register(defaultCode(2L));
                    registry.register(ACME, acmeDocument());
                },
                acmeToDefault);
        try {
            // and: an instance started in acme and pinned to the acme definition
            WorkflowInstanceId id = waitingPinnedTo(harness, ACME, ACME, harness.planHash(ACME, 1L));

            // When: it migrates to the default version 2, whose state does not extend the source's
            Throwable refusal = failureOf(() -> harness.engine().migrate(id, DEFAULT, 2L));

            // Then: the refusal names the instance's definition and the missing property, and no state class
            assertThat(refusal).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refusal.getMessage()).contains(ID).contains("note");
            assertThat(refusal.getMessage()).doesNotContain("Base", "Added");
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("a refused migration repeats a long caller-supplied target scope only in bounded form")
    void refusalOfAMigrationBoundsTheTargetScope() throws Exception {
        // Given: an instance started in acme and pinned to the default version 1
        Harness harness = open(registry -> registry.register(defaultCode(1L)));
        try {
            WorkflowInstanceId id = waitingPinnedTo(harness, DEFAULT, ACME, harness.planHash(DEFAULT, 1L));

            // When: it migrates to a target scope that is long and holds a line break
            String hostile = "line\nforged " + "z".repeat(5000);
            Throwable refusal = failureOf(() -> harness.engine().migrate(id, hostile, 1L));

            // Then: the refusal does not repeat the scope whole, nor any line break of it
            assertThat(refusal).isInstanceOf(WorkflowDefinitionException.class);
            assertThat(refusal.getMessage()).doesNotContain(hostile, "z".repeat(100), "\n");
            assertThat(refusal.getMessage().length()).isLessThan(1000);
        } finally {
            harness.close();
        }
    }
}
