// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.ACME;
import static dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.PURCHASE;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.ContractC;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.Deployment;
import dev.vertique.workflow.postgresql.registry.StartSelectionGraph.SelectionGraph;
import dev.vertique.workflow.postgresql.registry.WorkflowDefinitionSqlObserver.StatementGate;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * Verifies that a start is recorded from one read of the database, made on the caller's transaction: the next start
 * on any node sees a lifecycle change another node committed, a replay returns before the read, a version deprecated
 * after the read is still refused at the insert, the payload is checked against the stored schema and bounds before
 * the execution lock, a failing version's start is recorded and fails at the executor, and the freshness of an
 * activation follows the caller's isolation level.
 *
 * <p>Every node is built with propagation so far apart that no poll runs during a proof, so a node can only know
 * another node's change by reading the database. A node whose executor is stopped records starts that stay
 * {@code PENDING}.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class StartSelectionIT {

    /** The contract of every document of these proofs. */
    @WorkflowContract
    public interface SelectionContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_start_selection_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SETTLE = Duration.ofSeconds(10);
    private static final Instant LOADED_AT = Instant.parse("2026-10-27T12:00:00Z");

    /** The caller's isolation level and the version its start pins when the other node activates version 2 mid-way. */
    enum Isolation {
        REPEATABLE_READ("REPEATABLE READ", 1L),
        READ_COMMITTED("READ COMMITTED", 2L);

        private final String sql;
        private final long pinnedVersion;

        Isolation(String sql, long pinnedVersion) {
            this.sql = sql;
            this.pinnedVersion = pinnedVersion;
        }
    }

    @Test
    @DisplayName("the next start on another node sees the lifecycle change the first node committed")
    void nextStartOnAnotherNodeSeesTheLifecycleChange(Vertx vertx) {
        String id = "selection-lifecycle";
        DefinitionKey versionTwo = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given nodes A and B on one database, version 2 current in the default scope and version 3 published,
            // B started after that and holding the plans of both versions
            Node a = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(a, id, 2, 3);
            await(a.service().activate(DEFAULT, id, DEFAULT, 2));
            Node b = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            warm(b, id, 2, 3);
            Nodes.stopExecutors(a, b);

            // When A activates version 3 and deprecates version 2, and B records a start without a version and then an
            // explicit start of version 2
            await(a.service().activate(DEFAULT, id, DEFAULT, 3));
            await(a.service().deprecate(versionTwo));
            int beforeStart = b.observer().statements().size();
            Future<WorkflowInstanceId> noVersion = b.ops().start(command(id, "lifecycle-1", null));
            Throwable noVersionFailure = failureOf(noVersion);
            List<String> readsOfTheStart = selections(b.observer(), beforeStart);
            Throwable explicit = failureOf(b.ops().start(command(id, "lifecycle-2", 2L)));

            // Then B's instance is PENDING, pinned to version 3, with a start command, decided by one selection read
            assertThat(noVersionFailure)
                    .as("a start without a version on the other node")
                    .isNull();
            WorkflowInstanceId started = noVersion.result();
            assertThat(Rows.instance(database, started)).satisfies(instance -> {
                assertThat(instance.status()).isEqualTo("PENDING");
                assertThat(instance.version()).isEqualTo(3L);
            });
            assertThat(Rows.commandStates(database, started, "START")).containsExactly("PENDING");
            assertThat(readsOfTheStart).hasSize(1);

            // And the explicit start of version 2 is refused as a deprecated version, leaving no instance or command
            assertThat(explicit).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(refusal.key()).isEqualTo(versionTwo);
            });
            assertThat(database.instanceCount(id)).isEqualTo(1);
            assertThat(Rows.commandCount(database, id)).isEqualTo(1);
            assertThat(database.dedupCount(id)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a replay returns the existing instance while it is pending and after its version is deprecated")
    void replayReturnsTheExistingInstanceWhilePendingOrAfterDeprecation(Vertx vertx) {
        String id = "selection-replay";
        DefinitionKey versionOne = new DefinitionKey(DEFAULT, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given versions 1 and 2 published, version 2 current, and a start of version 1 recorded with the key
            // whose command is still pending because the executor is stopped
            Node node = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(node, id, 1, 2);
            await(node.service().activate(DEFAULT, id, DEFAULT, 2));
            Nodes.stopExecutors(node);
            StartCommand start = command(id, "k", 1L);
            WorkflowInstanceId first = await(node.ops().start(start));

            // When the same start is replayed, then its version is deprecated and the start is replayed again
            int beforePendingReplay = node.observer().statements().size();
            WorkflowInstanceId pendingReplay = await(node.ops().start(start));
            List<String> readsOfPendingReplay = selections(node.observer(), beforePendingReplay);
            await(node.service().deprecate(versionOne));
            int beforeDeprecatedReplay = node.observer().statements().size();
            WorkflowInstanceId deprecatedReplay = await(node.ops().start(start));
            List<String> readsOfDeprecatedReplay = selections(node.observer(), beforeDeprecatedReplay);

            // Then both replays return the first instance, and one instance and one command exist
            assertThat(pendingReplay).isEqualTo(first);
            assertThat(deprecatedReplay).isEqualTo(first);
            assertThat(database.instanceCount(id)).isEqualTo(1);
            assertThat(Rows.commandStates(database, first, "START")).containsExactly("PENDING");

            // And neither replay issued a selection statement before returning
            assertThat(readsOfPendingReplay).isEmpty();
            assertThat(readsOfDeprecatedReplay).isEmpty();
        }
    }

    @Test
    @DisplayName("a version deprecated between the selection read and the insert is refused by admission")
    void deprecationBetweenReadAndInsertIsRefusedByAdmission(Vertx vertx) {
        String id = "selection-admission";
        DefinitionKey versionOne = new DefinitionKey(DEFAULT, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given versions 1 and 2 published with version 2 current, and a start of version 1 held after its
            // selection statement
            Node a = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(a, id, 1, 2);
            await(a.service().activate(DEFAULT, id, DEFAULT, 2));
            Node b = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            warm(b, id, 1, 2);
            Nodes.stopExecutors(a, b);
            StatementGate gate = b.observer().holdNextResultOf(StartSelectionIT::isSelection);
            Future<WorkflowInstanceId> start = b.ops().start(command(id, "gated", 1L));
            awaitCondition(
                    "the start to issue its selection statement or to finish",
                    () -> gate.reached().isComplete() || start.isComplete());
            boolean selectionIssued = gate.reached().isComplete();

            Throwable refusal;
            boolean deprecationCommitted = false;
            try {
                // When another transaction deprecates version 1 and commits, and the gate opens
                if (selectionIssued) {
                    Future<Void> deprecation = a.service().deprecate(versionOne);
                    awaitCondition("the deprecation to complete", deprecation::isComplete);
                    deprecationCommitted = deprecation.succeeded();
                }
            } finally {
                gate.release();
            }
            refusal = failureOf(start);

            // Then the start issued a selection statement, and the deprecation committed without waiting for the
            // start, which held no lock yet
            assertThat(selectionIssued)
                    .as("the start issued a selection statement before it took any lock")
                    .isTrue();
            assertThat(deprecationCommitted).isTrue();

            // And the start fails with the admission refusal naming the deprecated version
            assertThat(refusal).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(failure.key()).isEqualTo(versionOne);
                assertThat(failure.getMessage()).contains("DEPRECATED");
            });

            // And no instance, command or dedup row survives
            assertThat(database.instanceCount(id)).isZero();
            assertThat(Rows.commandCount(database, id)).isZero();
            assertThat(database.dedupCount(id)).isZero();
        }
    }

    @Test
    @DisplayName("an invalid start payload is refused against the stored schema and a row without a schema is recorded")
    void invalidStartPayloadIsRefusedAgainstTheStoredSchema(Vertx vertx) {
        String id = "selection-schema";
        DefinitionKey withSchema = new DefinitionKey(DEFAULT, id, 1);
        DefinitionKey withoutSchema = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given a version whose row stores a start schema requiring orderId, and a second row written before the
            // schema was stored, which has none
            Node node = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(node, id, 1, 2);
            Rows.setStartSchema(database, withSchema, requiringOrderId());
            Rows.clearSchema(database, withoutSchema);
            Nodes.stopExecutors(node);

            // When a start without orderId is recorded for each
            Throwable invalid = failureOf(node.ops().start(command(id, "with-schema", 1L, Map.of())));
            long instancesAfterRefusal = database.instanceCount(id);
            WorkflowInstanceId recorded = await(node.ops().start(command(id, "without-schema", 2L, Map.of())));

            // Then the first fails naming the definition and the missing property, and nothing was recorded for it
            assertThat(invalid).isInstanceOfSatisfying(WorkflowInvalidPayloadException.class, refusal -> {
                assertThat(refusal.getMessage()).contains(id);
                assertThat(refusal.getMessage() + " " + refusal.violations()).contains("orderId");
            });
            assertThat(instancesAfterRefusal).isZero();
            assertThat(Rows.commandCount(database, id)).isEqualTo(1);
            assertThat(database.dedupCount(id)).isEqualTo(1);

            // And the second is recorded PENDING for the executor to validate later
            assertThat(Rows.instance(database, recorded)).satisfies(instance -> {
                assertThat(instance.status()).isEqualTo("PENDING");
                assertThat(instance.version()).isEqualTo(2L);
            });
            assertThat(Rows.commandStates(database, recorded, "START")).containsExactly("PENDING");
        }
    }

    @Test
    @DisplayName("an oversized start payload is refused before the execution lock is taken")
    void oversizedStartPayloadIsRefusedBeforeTheLock(Vertx vertx) {
        String id = "selection-size";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given a payload limit of 1024 bytes and a valid version whose row stores a schema
            JsonObject recording = new JsonObject().put("maxPayloadBytes", 1024);
            Node node =
                    Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false), recording);
            publish(node, id, 1);
            Rows.setStartSchema(database, new DefinitionKey(DEFAULT, id, 1), new JsonObject().put("type", "object"));
            Nodes.stopExecutors(node);

            // And a payload of exactly 1024 encoded bytes, which the limit admits
            WorkflowInstanceId atLimit =
                    await(node.ops().start(command(id, "at-limit", 1L, Payloads.ofEncodedSize(1024))));
            assertThat(Rows.instance(database, atLimit).status()).isEqualTo("PENDING");
            long commandsBefore = Rows.commandCount(database, id);
            long dedupBefore = database.dedupCount(id);

            // When a payload of 1025 encoded bytes is recorded
            int statementsBefore = node.observer().statements().size();
            Throwable oversized =
                    failureOf(node.ops().start(command(id, "over-limit", 1L, Payloads.ofEncodedSize(1025))));
            List<String> statements = new ArrayList<>(node.observer()
                    .statements()
                    .subList(statementsBefore, node.observer().statements().size()));

            // Then the start is refused naming the size limit
            assertThat(oversized).isInstanceOfSatisfying(WorkflowInvalidPayloadException.class, refusal -> {
                assertThat(refusal.getMessage()).contains("maxPayloadBytes").contains("1024");
            });

            // And the call issued at most the replay lookup and the selection read: no lock, dedup claim or insert
            assertThat(statements)
                    .noneMatch(sql -> normalized(sql).contains("advisory"))
                    .noneMatch(sql -> normalized(sql).contains("lock_timeout"))
                    .noneMatch(sql -> normalized(sql).startsWith("insert"))
                    .noneMatch(sql -> normalized(sql).startsWith("update"))
                    .noneMatch(sql -> normalized(sql).startsWith("delete"));
            assertThat(statements.stream().filter(StartSelectionIT::isSelection).count())
                    .isLessThanOrEqualTo(1);

            // And no instance, command or dedup row was added
            assertThat(database.instanceCount(id)).isEqualTo(1);
            assertThat(Rows.commandCount(database, id)).isEqualTo(commandsBefore);
            assertThat(database.dedupCount(id)).isEqualTo(dedupBefore);
        }
    }

    @Test
    @DisplayName("a start of a failing version is recorded and fails at the executor")
    void startOfAFailingVersionIsRecordedAndFailsAtTheExecutor(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db);
                ExecutorLog log = new ExecutorLog()) {
            FailingScopeRowIT.own(database, PURCHASE);

            // Given acme's version active and building on the first deployment
            Node first = Nodes.start(database, vertx, FailingScopeRowIT.class, Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first);
            first.close();

            // And a second deployment whose default state type acme's no longer extends, which has reconciled and so
            // knows the version as failing, with its executor stopped
            Node second = Nodes.start(database, vertx, FailingScopeRowIT.class, Deployment.laterWith(PURCHASE));
            Nodes.stopExecutors(second);
            Rows.markFailing(database, key, "STATE_TYPE_NOT_EXTENDED");

            // When a start without a version is recorded in acme
            Future<WorkflowInstanceId> recording = second.ops()
                    .start(FailingScopeRowIT.command(ACME, PURCHASE, "order-1", "failing-start", null, null));
            Throwable recordingFailure = failureOf(recording);

            // Then the call succeeds and commits a PENDING instance and a START command
            assertThat(recordingFailure)
                    .as("recording a failing version's start")
                    .isNull();
            WorkflowInstanceId recorded = recording.result();
            assertThat(Rows.instance(database, recorded).status()).isEqualTo("PENDING");
            assertThat(Rows.commandStates(database, recorded, "START")).containsExactly("PENDING");

            // When an executor starts and the system is idle
            Node third = Nodes.start(database, vertx, FailingScopeRowIT.class, Deployment.laterWith(PURCHASE));
            await(third.graph().executionProbe().awaitIdle(recorded, IDLE_TIMEOUT));

            // Then the instance failed with the failing-definition reason, and its start command failed
            Rows.Instance failed = Rows.instance(database, recorded);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType() + " " + failed.errorMessage() + " " + String.join(" ", log.warnings()))
                    .contains("WorkflowDefinitionFailingException")
                    .contains("STATE_TYPE_NOT_EXTENDED");
            assertThat(Rows.commandStates(database, recorded, "START")).containsExactly("FAILED");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Isolation.class)
    @DisplayName("activation freshness follows the isolation level of the caller's transaction")
    void activationFreshnessFollowsTheCallersIsolationLevel(Isolation level, Vertx vertx) {
        String id = "selection-isolation";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given nodes A and B, version 1 current in the default scope and version 2 published, and B's caller
            // transaction open at the level with its snapshot taken by reading the current version
            Node a = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(a, id, 1, 2);
            await(a.service().activate(DEFAULT, id, DEFAULT, 1));
            Node b = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            warm(b, id, 1, 2);
            Nodes.stopExecutors(a, b);
            IsolatedCaller caller = IsolatedCaller.begin(database, level.sql);
            try {
                assertThat(caller.currentVersion(id)).isEqualTo(1L);

                // When A activates version 2 and commits, and B then records a start without a version in the same
                // transaction and commits
                await(a.service().activate(DEFAULT, id, DEFAULT, 2));
                WorkflowInstanceId recorded = await(
                        b.graph().transactionalOperations().start(command(id, "isolated", null), caller.connection()));
                caller.commit();

                // Then the instance is pinned to the version the level lets the caller see
                assertThat(Rows.instance(database, recorded).version()).isEqualTo(level.pinnedVersion);

                // And exactly one instance and one START command exist, and no error was raised
                assertThat(database.instanceCount(id)).isEqualTo(1);
                assertThat(Rows.commandStates(database, recorded, "START")).containsExactly("PENDING");
                assertThat(Rows.commandCount(database, id)).isEqualTo(1);
            } finally {
                caller.rollbackIfOpen();
            }
        }
    }

    @Test
    @DisplayName("the selection read ships the schemas of an available version only")
    @SuppressWarnings("unchecked")
    void selectionReadShipsTheSchemasOfAnAvailableVersionOnly(Vertx vertx) {
        String id = "selection-shipped";
        DefinitionKey available = new DefinitionKey(DEFAULT, id, 1);
        DefinitionKey deprecated = new DefinitionKey(DEFAULT, id, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given an available and a deprecated version, both storing a start schema
            Node node = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(node, id, 1, 2);
            await(node.service().deprecate(deprecated));
            Rows.setStartSchema(database, available, requiringOrderId());
            Rows.setStartSchema(database, deprecated, requiringOrderId());

            // When the selection read runs
            var read =
                    await(((dev.vertique.workflow.engine.spi.TransactionalDefinitionReads<io.vertx.sqlclient.SqlClient>)
                                    node.graph().definitionRegistryStore())
                            .selectionRead(DEFAULT, id, database.admin()));

            // Then the available version carries its schemas and the deprecated one only its status
            var byVersion = read.versions().stream()
                    .collect(java.util.stream.Collectors.toMap(v -> v.key().definitionVersion(), v -> v));
            assertThat(byVersion.get(1L).startSchema()).isNotNull();
            assertThat(byVersion.get(1L).signalNames()).isNotNull();
            assertThat(byVersion.get(2L).status().name()).isEqualTo("DEPRECATED");
            assertThat(byVersion.get(2L).startSchema()).isNull();
            assertThat(byVersion.get(2L).signalNames()).isNull();
        }
    }

    @Test
    @DisplayName("an oversized idempotency key or business key is refused before the selection read")
    void oversizedKeysAreRefusedBeforeTheSelectionRead(Vertx vertx) {
        String id = "selection-keys";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            FailingScopeRowIT.own(database, id);

            // Given a published version
            Node node = Nodes.start(database, vertx, StartSelectionIT.class, Deployment.firstWith(null, false));
            publish(node, id, 1);
            Nodes.stopExecutors(node);
            String longKey = "k".repeat(256);

            // When starts with a 256-character idempotency key and with a 256-character business key are recorded
            int statementsBefore = node.observer().statements().size();
            Throwable idempotency = failureOf(node.ops().start(command(id, longKey, 1L)));
            Throwable business = failureOf(node.ops()
                    .start(new StartCommand(
                            id,
                            SelectionContract.class.getName(),
                            new PlaceOrderDoc("order"),
                            "business-key-test",
                            longKey,
                            null,
                            1L,
                            null,
                            null)));
            List<String> reads = selections(node.observer(), statementsBefore);

            // Then both are refused without repeating the key, and no selection read, instance or command was made
            assertThat(idempotency).isInstanceOfSatisfying(WorkflowInvalidPayloadException.class, refusal -> {
                assertThat(refusal.getMessage())
                        .contains("idempotency key", "255")
                        .doesNotContain(longKey);
            });
            assertThat(business).isInstanceOfSatisfying(WorkflowInvalidPayloadException.class, refusal -> {
                assertThat(refusal.getMessage()).contains("business key", "255").doesNotContain(longKey);
            });
            assertThat(reads).isEmpty();
            assertThat(database.instanceCount(id)).isZero();
        }
    }

    // --- Nodes ---

    /** A running graph with the boot that owns it and the observer of its pool. */
    private record Node(Boot boot, SelectionGraph graph, WorkflowDefinitionSqlObserver observer) {

        dev.vertique.workflow.definition.service.WorkflowDefinitionService service() {
            return graph.definitionService();
        }

        dev.vertique.workflow.ops.WorkflowOperations ops() {
            return graph.workflowOperations();
        }

        void close() {
            boot.close();
        }
    }

    private static final class Nodes {

        private Nodes() {}

        /** Starts a node of the deployment with propagation too far apart for a poll to run during a proof. */
        static Node start(DefinitionScenario database, Vertx vertx, Class<?> testClass, Deployment deployment) {
            return start(database, vertx, testClass, deployment, new JsonObject());
        }

        /** Starts a node like {@link #start(DefinitionScenario, Vertx, Class, Deployment)} with recording config. */
        static Node start(
                DefinitionScenario database,
                Vertx vertx,
                Class<?> testClass,
                Deployment deployment,
                JsonObject recording) {
            JsonObject config = new JsonObject()
                    .put(
                            "workflow",
                            new JsonObject()
                                    .put("executor", new JsonObject().put("pollIntervalMs", 100))
                                    .put("recording", recording));
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            Boot boot = database.nodeOf(pool -> StartSelectionGraph.build(
                    vertx, observer.observe(pool), config, testClass, deployment.code(), deployment.catalog()));
            return new Node(boot, (SelectionGraph) boot.app(), observer);
        }

        /** Undeploys the executors of the nodes, so that what they record afterwards is not run. */
        static void stopExecutors(Node... nodes) {
            for (Node node : nodes) {
                await(node.graph().verticleDeployer().undeploy(WorkflowExecutor.DEPLOYMENT_NAME));
            }
        }
    }

    // --- Definitions ---

    /** Loads and publishes the versions of the default scope. */
    private static void publish(Node node, String id, long... versions) {
        for (long version : versions) {
            await(node.service()
                    .load(
                            DEFAULT,
                            ActivationFixtures.document(id, version, SelectionContract.class),
                            DocumentFormat.YAML,
                            source(id + "-" + version, LOADED_AT)));
            await(node.service().publish(new DefinitionKey(DEFAULT, id, version)));
        }
    }

    /**
     * Makes the node hold the plans of the versions, as a node that has served them would. A start follows the
     * database whatever a node holds.
     */
    private static void warm(Node node, String id, long... versions) {
        for (long version : versions) {
            await(node.graph().workflowRegistry().load(new DefinitionKey(DEFAULT, id, version)));
        }
    }

    private static DefinitionKey publishAndActivateAcme(Node node) {
        await(node.service().load(ACME, acmeDocument(), DocumentFormat.YAML, source("acme-selection", LOADED_AT)));
        DefinitionKey key = new DefinitionKey(ACME, PURCHASE, 1);
        await(node.service().publish(key));
        await(node.service().activate(ACME, PURCHASE, ACME, 1));
        return key;
    }

    /** A document that waits for the approval signal and then completes. */
    private static byte[] acmeDocument() {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: dev.vertique.workflow.definition.it.fixture.OrderDocState
                contract: %s
                startPayloadType: dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-approval
                steps:
                  - id: wait-approval
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: done
                  - id: done
                    type: complete
                """.formatted(PURCHASE, ContractC.class.getName()).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject requiringOrderId() {
        return new JsonObject("""
                {"type":"object","required":["orderId"],"properties":{"orderId":{"type":"string"}}}""");
    }

    // --- Starts ---

    private static StartCommand command(String id, String idempotencyKey, Long version) {
        return command(id, idempotencyKey, version, new PlaceOrderDoc("order-" + idempotencyKey));
    }

    private static StartCommand command(String id, String idempotencyKey, Long version, Object payload) {
        return new StartCommand(
                id, SelectionContract.class.getName(), payload, idempotencyKey, null, null, version, null, null);
    }

    // --- Statements ---

    /**
     * Whether the statement is the selection read: a read that names both the definition rows and the activation
     * records.
     */
    private static boolean isSelection(String sql) {
        String text = normalized(sql);
        return text.contains("workflow_definitions")
                && text.contains("workflow_definition_activations")
                && !text.startsWith("insert")
                && !text.startsWith("update")
                && !text.startsWith("delete");
    }

    private static List<String> selections(WorkflowDefinitionSqlObserver observer, int from) {
        List<String> statements = observer.statements();
        return statements.subList(from, statements.size()).stream()
                .filter(StartSelectionIT::isSelection)
                .toList();
    }

    private static String normalized(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static void awaitCondition(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    // --- Payloads ---

    private static final class Payloads {

        private Payloads() {}

        /** A start payload whose encoded JSON is exactly the given number of UTF-8 bytes. */
        static PlaceOrderDoc ofEncodedSize(int encodedBytes) {
            int overhead = Json.encodeToBuffer(new PlaceOrderDoc("")).length();
            PlaceOrderDoc payload = new PlaceOrderDoc("x".repeat(encodedBytes - overhead));
            int actual = Json.encodeToBuffer(payload).length();
            if (actual != encodedBytes) {
                throw new IllegalStateException("The payload encodes to " + actual + " bytes, not " + encodedBytes);
            }
            return payload;
        }
    }

    // --- Rows ---

    private static final class Rows {

        private Rows() {}

        record Instance(String status, long version, String errorType, String errorMessage) {}

        static Instance instance(DefinitionScenario database, WorkflowInstanceId instance) {
            Row row = await(database.admin().preparedQuery("""
                                    SELECT status, definition_version, error_type, error_message
                                    FROM workflow_instances WHERE id = $1
                                    """).execute(Tuple.of(instance.value())))
                    .iterator()
                    .next();
            return new Instance(
                    row.getString("status"),
                    row.getLong("definition_version"),
                    row.getString("error_type"),
                    row.getString("error_message"));
        }

        /** The states of the instance's commands of the kind, in the order they were recorded. */
        static List<String> commandStates(DefinitionScenario database, WorkflowInstanceId instance, String kind) {
            List<String> states = new ArrayList<>();
            for (Row row : await(database.admin()
                    .preparedQuery(
                            "SELECT state FROM workflow_commands WHERE workflow_id = $1 AND kind = $2 ORDER BY seq")
                    .execute(Tuple.of(instance.value(), kind)))) {
                states.add(row.getString("state"));
            }
            return states;
        }

        /** Counts the commands of every instance of the definition id. */
        static long commandCount(DefinitionScenario database, String definitionId) {
            return await(database.admin().preparedQuery("""
                                    SELECT count(*) AS total FROM workflow_commands c
                                    JOIN workflow_instances i ON i.id = c.workflow_id
                                    WHERE i.definition_id = $1
                                    """).execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("total");
        }

        /** Stores the start schema of the row, as a row written by a node that builds the plan would. */
        static void setStartSchema(DefinitionScenario database, DefinitionKey key, JsonObject schema) {
            database.execute("""
                    UPDATE workflow_definitions SET start_schema_json = $4
                    WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                    """, Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), schema));
        }

        /** Clears the start schema and the signal names of the row, as a row written before they were stored. */
        static void clearSchema(DefinitionScenario database, DefinitionKey key) {
            database.execute("""
                    UPDATE workflow_definitions SET start_schema_json = NULL, signal_names_json = NULL
                    WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                    """, Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion()));
        }

        /** Stores a failure mark on the row, as an executor that built it would. */
        static void markFailing(DefinitionScenario database, DefinitionKey key, String reason) {
            database.execute("""
                    UPDATE workflow_definitions SET failure_reason = $4
                    WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                    """, Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion(), reason));
        }
    }

    // --- Caller transactions ---

    /** A caller-owned transaction on a genuine connection at an isolation level, closed idempotently. */
    private static final class IsolatedCaller {

        private final SqlConnection connection;
        private final Transaction transaction;
        private boolean done;

        private IsolatedCaller(SqlConnection connection, Transaction transaction) {
            this.connection = connection;
            this.transaction = transaction;
        }

        /** Opens the transaction at the level; its snapshot is taken by the first read. */
        static IsolatedCaller begin(DefinitionScenario database, String isolationLevel) {
            SqlConnection connection = await(database.admin().getConnection());
            try {
                Transaction transaction = await(connection.begin());
                await(connection
                        .query("SET TRANSACTION ISOLATION LEVEL " + isolationLevel)
                        .execute());
                return new IsolatedCaller(connection, transaction);
            } catch (RuntimeException | Error failure) {
                await(connection.close());
                throw failure;
            }
        }

        SqlConnection connection() {
            return connection;
        }

        /** Reads the default scope's current version through a plain statement, which takes the snapshot. */
        long currentVersion(String definitionId) {
            return await(connection.preparedQuery("""
                                    SELECT definition_version FROM workflow_definition_activations
                                    WHERE scope_key = 'default' AND definition_id = $1
                                    """).execute(Tuple.of(definitionId)))
                    .iterator()
                    .next()
                    .getLong("definition_version");
        }

        void commit() {
            if (done) return;
            done = true;
            try {
                await(transaction.commit());
            } finally {
                await(connection.close());
            }
        }

        void rollbackIfOpen() {
            if (done) return;
            done = true;
            try {
                await(transaction.rollback().recover(ignored -> Future.succeededFuture()));
            } finally {
                await(connection.close());
            }
        }
    }

    // --- Logs ---

    /** Collects what the whole process logs at warning level or above while it is open. */
    private static final class ExecutorLog implements AutoCloseable {

        private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        ExecutorLog() {
            appender.start();
            root.addAppender(appender);
        }

        /** The records at warning level or above, each with the class names and messages of its throwables. */
        List<String> warnings() {
            List<String> lines = new ArrayList<>();
            for (ILoggingEvent event : new ArrayList<>(appender.list)) {
                if (!event.getLevel().isGreaterOrEqual(Level.WARN)) continue;
                StringBuilder line = new StringBuilder(event.getFormattedMessage());
                for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                    line.append(" | ").append(proxy.getClassName()).append(": ").append(proxy.getMessage());
                }
                lines.add(line.toString());
            }
            return lines;
        }

        @Override
        public void close() {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
