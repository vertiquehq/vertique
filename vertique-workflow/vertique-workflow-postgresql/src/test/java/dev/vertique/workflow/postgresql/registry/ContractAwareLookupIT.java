// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a start, an explicit-version start, a replay, a load and an activation select or refuse a definition
 * by the contract of the client when a scope and the default scope hold the same id.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ContractAwareLookupIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    /** The contract of the clients that the default scope's code definitions serve. */
    @WorkflowContract("lookup-c")
    public interface ContractC extends OrderDocumentSignals {}

    /** The other contract: its clients are served by a scope's own definitions. */
    @WorkflowContract("lookup-d")
    public interface ContractD extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_contract_aware_lookup_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    private static final String X = "lookup-x";
    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    @DisplayName("a later default id that overlaps a scope's own keeps both, each for the clients of its contract")
    void laterDefaultOverlapKeepsScopeOwnDefinitionPerContract(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, X);

            // Given a first boot without a default X, where acme loads, publishes and activates its own X version 1
            // under D
            Boot first = database.node(none(), observation());
            new Scenario(database, first, ContractD.class).publish(ACME, X, 1);
            await(first.app().definitionService().activate(ACME, X, ACME, 1));
            first.close();

            // When a second boot adds the default's code X version 1 under C
            Boot second = database.node(none(), observation(), code(X, ContractC.class, 1));

            // Then startup succeeded, acme starts its own X for a D client and the default's X for a C client
            assertThat(startAs(database, second, ContractD.class, ACME, X)).isEqualTo(new Pin(ACME, ACME, X, 1));
            assertThat(startAs(database, second, ContractC.class, ACME, X)).isEqualTo(new Pin(ACME, DEFAULT, X, 1));

            // And globex starts the default's X for a C client and has no current version for a D client
            assertThat(startAs(database, second, ContractC.class, GLOBEX, X)).isEqualTo(new Pin(GLOBEX, DEFAULT, X, 1));
            assertNoCurrentVersion(startFailureAs(second, ContractD.class, GLOBEX, X, null, null, "g-1"), GLOBEX);

            // When acme loads and publishes its X version 2 under D
            new Scenario(database, second, ContractD.class).publish(ACME, X, 2);

            // Then acme holds both of its versions beside the default's version
            assertThat(database.stored(ACME, X, 2).status()).isEqualTo("AVAILABLE");
            assertThat(database.stored(DEFAULT, X, 1).status()).isEqualTo("AVAILABLE");

            // When acme tries to activate the default's version 1
            Throwable refused = failureOf(() -> second.app().definitionService().activate(ACME, X, DEFAULT, 1));

            // Then it is refused because the default's X names another contract than acme's own X
            assertThat(refused).isInstanceOf(WorkflowDefinitionContractMismatchException.class);

            // When acme follows the default again
            await(second.app().definitionService().follow(ACME, X));

            // Then a D client in acme has no current version and a C client starts the default's version 1
            assertNoCurrentVersion(startFailureAs(second, ContractD.class, ACME, X, null, null, "a-2"), ACME);
            assertThat(startAs(database, second, ContractC.class, ACME, X)).isEqualTo(new Pin(ACME, DEFAULT, X, 1));
        }
    }

    @Test
    @DisplayName("a scope cannot load a default id under another contract, whatever the status of the default's row")
    void scopeLoadOfDefaultIdUnderAnotherContractIsRefused(Vertx vertx) {
        String available = "lookup-y";
        String candidate = "lookup-z";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, available, candidate);

            // Given the default holds lookup-y under C as AVAILABLE and lookup-z under C only as a CANDIDATE
            Boot node = database.node(none(), observation());
            Scenario asC = new Scenario(database, node, ContractC.class);
            asC.publish(DEFAULT, available, 1);
            asC.load(DEFAULT, candidate, 1);

            for (String id : List.of(available, candidate)) {
                // When acme loads the id under D
                Throwable refused = failureOf(() -> node.app()
                        .definitionService()
                        .load(
                                ACME,
                                ActivationFixtures.document(id, 1, ContractD.class),
                                DocumentFormat.YAML,
                                source("acme-" + id, LOADED_AT)));

                // Then the load is refused naming acme and the id, and acme holds no row of it
                assertThat(refused)
                        .as("load of %s under D", id)
                        .isInstanceOfSatisfying(WorkflowDefinitionContractMismatchException.class, mismatch -> {
                            assertThat(mismatch.definitionKey().scopeKey()).isEqualTo(ACME);
                            assertThat(mismatch.definitionKey().definitionId()).isEqualTo(id);
                        });
                assertThat(database.findStored(ACME, id, 1))
                        .as("acme row of %s", id)
                        .isEmpty();
                assertThat(database.rowCount(id)).as("rows of %s", id).isEqualTo(1);

                // When acme loads the id under C
                await(node.app()
                        .definitionService()
                        .load(
                                ACME,
                                ActivationFixtures.document(id, 1, ContractC.class),
                                DocumentFormat.YAML,
                                source("acme-c-" + id, LOADED_AT)));

                // Then the load succeeds
                assertThat(database.stored(ACME, id, 1).status()).isEqualTo("CANDIDATE");
            }
        }
    }

    @Test
    @DisplayName("a load waiting on the id lock sees a default row committed meanwhile")
    void scopeLoadWaitingOnIdLockSeesDefaultRowCommittedMeanwhile(Vertx vertx) throws Exception {
        String id = "lookup-w";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);
            Boot node = database.node(none(), observation());
            Connection transaction = null;
            try {
                // Given a separate transaction that holds the id lock and an uncommitted default CANDIDATE row of the
                // id under C
                transaction = holdIdLockWithUncommittedRow(id);

                // When acme loads a document of the id under D and the load waits on the lock
                Future<?> loading = node.app()
                        .definitionService()
                        .load(
                                ACME,
                                ActivationFixtures.document(id, 1, ContractD.class),
                                DocumentFormat.YAML,
                                source("acme-" + id, LOADED_AT));
                database.awaitAdvisory(id, "ExclusiveLock", false, 1);
                assertThat(loading.isComplete()).isFalse();
                commit(transaction);
                transaction = null;

                // Then the load fails because the committed default row is read under the lock
                assertThat(DefinitionScenario.failureOf(loading))
                        .isInstanceOf(WorkflowDefinitionContractMismatchException.class);
                assertThat(database.findStored(ACME, id, 1)).isEmpty();
            } finally {
                if (transaction != null) {
                    transaction.rollback();
                    transaction.close();
                }
            }
        }
    }

    @Test
    @DisplayName("an explicit version is chosen from the client's contract and never from another contract's default")
    void explicitVersionSelectsByContract(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, X);

            // Given acme's own X versions 1 and 2 under D, published before the default ships its X versions 1 and 2
            // under C
            Boot node = shipDefaultAfterScopeOwn(database, 2, 2);

            // When a C client in acme starts with the default's version 2, with acme named as the target, and a D
            // client in acme starts with version 2
            Pin cClient = startAs(database, node, ContractC.class, ACME, X, 2L, null, "k-c");
            Throwable cClientNamingAcme = startFailureAs(node, ContractC.class, ACME, X, 2L, ACME, "k-c-acme");
            Pin dClient = startAs(database, node, ContractD.class, ACME, X, 2L, null, "k-d");

            // Then the C client runs the default's version 2, naming acme's version is refused with the typed error
            // and the D client runs acme's version 2
            assertThat(cClient).isEqualTo(new Pin(ACME, DEFAULT, X, 2));
            assertThat(cClientNamingAcme)
                    .isInstanceOfSatisfying(
                            WorkflowDefinitionContractMismatchException.class,
                            mismatch -> assertThat(mismatch.definitionKey()).isEqualTo(new DefinitionKey(ACME, X, 2)));
            assertThat(dClient).isEqualTo(new Pin(ACME, ACME, X, 2));
        }
    }

    @Test
    @DisplayName("a replay by a client of another contract than the pinned definition conflicts")
    void replayAcrossContractsOfOneIdConflicts(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, X);

            // Given the definitions of the explicit-version proof and a D client in acme that started with key k-1
            Boot node = shipDefaultAfterScopeOwn(database, 2, 2);
            assertThat(startAs(database, node, ContractD.class, ACME, X, null, null, "k-1"))
                    .isEqualTo(new Pin(ACME, ACME, X, 2));

            // When a C client in acme starts with the same key, without and with an explicit version
            Throwable withoutVersion = startFailureAs(node, ContractC.class, ACME, X, null, null, "k-1");
            Throwable withVersion = startFailureAs(node, ContractC.class, ACME, X, 2L, null, "k-1");

            // Then both conflict on the key and no second instance exists
            assertThat(withoutVersion).isInstanceOf(WorkflowIdempotencyConflictException.class);
            assertThat(withVersion).isInstanceOf(WorkflowIdempotencyConflictException.class);
            assertThat(database.instanceCount(X)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an explicit version never falls back to a default definition of another contract")
    void explicitVersionFallingBackToDefaultOfAnotherContractIsRefused(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, X);

            // Given the default's X versions 1 and 2 under C, acme's own X version 1 under D published before them,
            // and globex without an X
            Boot node = shipDefaultAfterScopeOwn(database, 1, 2);

            // When a D client in globex starts with version 2, a D client in the default scope starts with version 1
            Throwable fromGlobex = startFailureAs(node, ContractD.class, GLOBEX, X, 2L, null, "g-d");
            Throwable fromDefault = startFailureAs(node, ContractD.class, DEFAULT, X, 1L, null, "d-d");

            // Then both fail with the typed error naming the default's key and both contracts
            assertMismatch(fromGlobex, new DefinitionKey(DEFAULT, X, 2));
            assertMismatch(fromDefault, new DefinitionKey(DEFAULT, X, 1));

            // And no instance and no start deduplication row exist for them
            assertThat(database.instanceCount(X)).isZero();
            assertThat(database.dedupCount(X)).isZero();

            // When a C client in globex starts with version 2
            Pin control = startAs(database, node, ContractC.class, GLOBEX, X, 2L, null, "g-c");

            // Then it runs the default's version 2
            assertThat(control).isEqualTo(new Pin(GLOBEX, DEFAULT, X, 2));
        }
    }

    @Test
    @DisplayName("a scope that owns only a deprecated version still owns the id")
    void scopeOwningOnlyADeprecatedVersionStillOwnsTheId(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, X);

            // Given the default's X versions 1 and 2 under D, and acme's own X version 1 under D, published and then
            // deprecated, with no other acme version
            Boot node = database.node(none(), observation(), code(X, ContractD.class, 2));
            new Scenario(database, node, ContractD.class).publish(ACME, X, 1);
            await(node.app().definitionService().deprecate(new DefinitionKey(ACME, X, 1)));

            // When a D client in acme starts with version 1
            Throwable refused = startFailureAs(node, ContractD.class, ACME, X, 1L, null, "a-1");

            // Then the start is refused as deprecated, names acme's version, and starts nothing
            assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, lifecycle -> {
                assertThat(lifecycle.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(lifecycle.key()).isEqualTo(new DefinitionKey(ACME, X, 1));
            });
            assertThat(database.instanceCount(X)).isZero();
        }
    }

    /**
     * Boots without a default X, publishes and activates acme's own X versions under D, restarts with the default's
     * code X versions under C and returns the second node.
     */
    private static Boot shipDefaultAfterScopeOwn(DefinitionScenario database, long acmeVersions, long defaultVersions) {
        Boot first = database.node(none(), observation());
        Scenario asD = new Scenario(database, first, ContractD.class);
        asD.publish(ACME, X, LongStream.rangeClosed(1, acmeVersions).toArray());
        await(first.app().definitionService().activate(ACME, X, ACME, acmeVersions));
        first.close();
        return database.node(none(), observation(), code(X, ContractC.class, defaultVersions));
    }

    private static Pin startAs(DefinitionScenario database, Boot node, Class<?> contract, String scope, String id) {
        return startAs(database, node, contract, scope, id, null, null, "key-" + java.util.UUID.randomUUID());
    }

    /** Starts as a client of the contract and returns where the instance is pinned. */
    private static Pin startAs(
            DefinitionScenario database,
            Boot node,
            Class<?> contract,
            String scope,
            String id,
            Long version,
            String targetScope,
            String idempotencyKey) {
        StartCommand command = command(contract, scope, id, version, targetScope, idempotencyKey);
        var instance = await(node.app().workflowOperations().start(command));
        await(node.app().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
        Row row = await(database.admin().preparedQuery("""
                                SELECT scope_key, definition_scope_key, definition_id, definition_version
                                FROM workflow_instances WHERE id = $1
                                """).execute(Tuple.of(instance.value())))
                .iterator()
                .next();
        return new Pin(
                row.getString("scope_key"),
                row.getString("definition_scope_key"),
                row.getString("definition_id"),
                row.getLong("definition_version"));
    }

    /** Starts as a client of the contract and returns the failure. */
    private static Throwable startFailureAs(
            Boot node,
            Class<?> contract,
            String scope,
            String id,
            Long version,
            String targetScope,
            String idempotencyKey) {
        StartCommand command = command(contract, scope, id, version, targetScope, idempotencyKey);
        return failureOf(() -> node.app().workflowOperations().start(command));
    }

    private static StartCommand command(
            Class<?> contract, String scope, String id, Long version, String targetScope, String idempotencyKey) {
        return new StartCommand(
                id,
                contract.getAnnotation(WorkflowContract.class).value(),
                new PlaceOrderDoc("order-" + idempotencyKey),
                idempotencyKey,
                null,
                null,
                version,
                scope,
                targetScope);
    }

    private static void assertNoCurrentVersion(Throwable failure, String startScope) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowNoCurrentVersionException.class, refusal -> {
            assertThat(refusal.scopeKey()).isEqualTo(startScope);
            assertThat(refusal.definitionId()).isEqualTo(X);
        });
    }

    private static void assertMismatch(Throwable failure, DefinitionKey key) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowDefinitionContractMismatchException.class, mismatch -> {
            assertThat(mismatch.definitionKey()).isEqualTo(key);
            assertThat(mismatch.getMessage()).contains("lookup-c").contains("lookup-d");
        });
    }

    /** Takes the id's advisory lock in its own transaction and inserts a default CANDIDATE row under C. */
    private static Connection holdIdLockWithUncommittedRow(String id) throws SQLException {
        Connection transaction = DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
        try {
            transaction.setAutoCommit(false);
            try (PreparedStatement lock = transaction.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, DefinitionIdLock.lockKey(id));
                lock.execute();
            }
            DefinitionTableRows.insertCandidateCode(transaction, id, 1, "lookup-c");
            return transaction;
        } catch (SQLException | RuntimeException failure) {
            transaction.close();
            throw failure;
        }
    }

    private static void commit(Connection transaction) throws SQLException {
        transaction.commit();
        transaction.close();
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static List<WorkflowDefinition<?, ?>> code(String id, Class<?> contract, long versions) {
        return LongStream.rangeClosed(1, versions)
                .<WorkflowDefinition<?, ?>>mapToObj(version -> new CodeWorkflow(id, version, contract))
                .toList();
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static BootObservation observation() {
        return new BootObservation("lookup-unused", 1, "unused", false);
    }

    /** A code definition whose only step is its end step. */
    private static final class CodeWorkflow implements WorkflowDefinition<OrderDocState, Object> {

        private final String definitionId;
        private final long version;
        private final Class<?> contract;

        private CodeWorkflow(String definitionId, long version, Class<?> contract) {
            this.definitionId = definitionId;
            this.version = version;
            this.contract = contract;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) contract;
        }

        @Override
        public Class<OrderDocState> stateType() {
            return OrderDocState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<OrderDocState> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> OrderDocState.fromPayload(start))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
