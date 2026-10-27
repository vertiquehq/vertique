// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
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
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ExecutorMarksGraph.MarksGraph;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.ContractC;
import dev.vertique.workflow.postgresql.registry.FailingScopeRowIT.Deployment;
import dev.vertique.workflow.postgresql.registry.failing.v1.FinanceDecision;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Verifies that the executor decides whether a scope row is failing from its own build of the row's plan: a state type
 * the default scope's no longer accepts fails new starts and lets every other command run, a name binding that no class
 * resolves defers the command as not runnable, and the outcome is written to the row after the executor's own write
 * has committed, without that write ever changing the outcome.
 *
 * <p>Each proof records its starts on a first deployment while the version still builds, with that deployment's
 * executor undeployed, so that the commands reach the executor of the second deployment only after the break. The
 * second deployment's propagation period is far longer than a proof, so no poll evaluates the row: only the executor
 * can mark it.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ExecutorMarksIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_marks_it")
            .withMigration("classpath:db/migration/workflow");

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration SETTLE = Duration.ofSeconds(20);
    private static final Instant LOADED_AT = Instant.parse("2026-10-27T12:00:00Z");

    /** The property names of the purchase states and of the document state; no failure message may repeat them. */
    private static final String[] STATE_DETAILS = {
        "orderId", "costCenter", "reservationId", "authId", "trackingNumber", "PurchaseState", "OrderDocState"
    };

    @Test
    @DisplayName("a state type the default scope no longer accepts fails new starts and lets signals apply")
    void stateTypeNotExtendedFailsNewStartsAndAppliesSignals(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db);
                ExecutorLog log = new ExecutorLog()) {
            own(database);

            // Given acme's row active with one instance waiting for the approval signal, and with the executor
            // stopped a second start of the row recorded
            Node first =
                    node(database, vertx, new WorkflowDefinitionSqlObserver(), Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, false);
            WorkflowInstanceId waiting = startAndSettle(first, "waiting-order");
            assertThat(instanceOf(database, waiting).status()).isEqualTo("WAITING");
            stopExecutor(first);
            WorkflowInstanceId fresh = recordStart(first, "fresh-order");
            assertThat(commandOf(database, fresh, "START").state()).isEqualTo("PENDING");
            first.close();

            // When the node restarts with a default state type that acme's no longer extends, an approval signal for
            // the waiting instance is recorded and the restarted executor runs both commands
            Node second = node(database, vertx, new WorkflowDefinitionSqlObserver(), Deployment.laterWith(PURCHASE));
            await(second.graph()
                    .workflowOperations()
                    .signal(waiting, "inventory.reserved", new InventoryReservedDoc("reservation-1"), "approved-1"));
            await(second.graph().executionProbe().awaitAllIdle(IDLE_TIMEOUT));

            // Then the new instance failed with the failing-definition reason, which names the rule and the exception
            // in the instance's record or in the log, without any state detail, and a history entry records the failure
            InstanceRow failed = instanceOf(database, fresh);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType() + " " + failed.errorMessage())
                    .contains("STATE_TYPE_NOT_EXTENDED")
                    .doesNotContain(STATE_DETAILS);
            assertThat(historyOf(database, fresh)).contains("FAILED");
            assertThat(failed.errorType() + " " + failed.errorMessage() + " " + String.join(" ", log.warnings()))
                    .contains("WorkflowDefinitionFailingException");

            // And its start command failed
            assertThat(commandOf(database, fresh, "START").state()).isEqualTo("FAILED");

            // And the signal was applied: its command completed and the old instance advanced
            assertThat(commandOf(database, waiting, "SIGNAL").state()).isEqualTo("COMPLETE");
            assertThat(instanceOf(database, waiting).status()).isEqualTo("COMPLETED");

            // And the row's mark reads the rule
            awaitCondition("the mark of the row", () -> "STATE_TYPE_NOT_EXTENDED".equals(failureReason(database, key)));
        }
    }

    @Test
    @DisplayName("a failed mark write leaves the segment outcome unchanged and a later start writes the mark")
    void failedMarkWriteLeavesTheSegmentOutcomeUnchanged(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database);

            // Given acme's row active and two starts of it recorded while the executor was stopped, the second held
            // back until the test lets it run
            Node first =
                    node(database, vertx, new WorkflowDefinitionSqlObserver(), Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, false);
            stopExecutor(first);
            WorkflowInstanceId one = recordStart(first, "first-order");
            WorkflowInstanceId two = recordStart(first, "second-order");
            holdBack(database, two);
            first.close();

            // And a restarted node, whose default state type acme's no longer extends, whose next write of a failing
            // mark fails
            WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();
            observer.failNextFailureMarkWrite();
            node(database, vertx, observer, Deployment.laterWith(PURCHASE));

            // When the first start executes
            awaitCondition("the first start to fail", () -> "FAILED"
                    .equals(commandOf(database, one, "START").state()));
            awaitCondition("the mark write to be tried", () -> observer.injectedFailureMarkWrites() == 1);

            // Then the instance failed with the failing reason and the command failed, as without the failed write
            InstanceRow failed = instanceOf(database, one);
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorType() + " " + failed.errorMessage()).contains("STATE_TYPE_NOT_EXTENDED");
            assertThat(historyOf(database, one)).contains("FAILED");
            assertThat(commandOf(database, one, "START").state()).isEqualTo("FAILED");

            // And the stored mark is still null
            assertThat(failureReason(database, key)).isNull();

            // When the second start executes
            release(database, two);
            awaitCondition("the second start to fail", () -> "FAILED"
                    .equals(commandOf(database, two, "START").state()));

            // Then the instance failed the same way and the second execution wrote the mark
            assertThat(instanceOf(database, two).status()).isEqualTo("FAILED");
            awaitCondition("the mark of the row", () -> "STATE_TYPE_NOT_EXTENDED".equals(failureReason(database, key)));

            // And exactly one write was tried for each of the two executions
            assertThat(observer.failureMarkWrites()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an unresolved name binding defers the command as not runnable and marks the row after the deferral")
    void unresolvedBindingDefersTheCommandAndMarksTheRow(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database);

            // Given acme's row, whose document names the decision class by its binary name, active, and one start of
            // it recorded while the executor was stopped
            Node first =
                    node(database, vertx, new WorkflowDefinitionSqlObserver(), Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, true);
            WorkflowDefinitionRecord published = FailingScopeRowIT.listed(first.boot(), key);
            assertThat(published.nameBinding()).containsKey(FinanceDecision.class.getName());
            stopExecutor(first);
            WorkflowInstanceId instance = recordStart(first, "order-1");
            assertThat(commandOf(database, instance, "START").state()).isEqualTo("PENDING");
            first.close();

            // When the executor of a node whose catalog omits the decision class peeks the command
            Node second = node(
                    database,
                    vertx,
                    new WorkflowDefinitionSqlObserver(),
                    Deployment.sameStateWithoutDecision(PURCHASE));
            boolean markedBeforeDeferral = awaitMarkObservingDeferral(database, instance, key);

            // Then the command is deferred with no attempt spent and the instance has not started
            CommandRow deferred = commandOf(database, instance, "START");
            assertThat(deferred.state()).isEqualTo("DEFERRED");
            assertThat(deferred.attempts()).isZero();
            assertThat(deferred.deferralCount()).isEqualTo(1);
            assertThat(instanceOf(database, instance).status()).isEqualTo("PENDING");

            // And no plan of the row is cached or registered
            assertThat(second.graph().planCache().resident(key)).isEmpty();
            assertThat(second.graph().workflowRegistry().find(key)).isEmpty();

            // And the row's mark reads the rule, and was never seen before the deferral had committed
            assertThat(failureReason(database, key)).isEqualTo("NAME_BINDING_UNRESOLVED");
            assertThat(markedBeforeDeferral).isFalse();
        }
    }

    // --- Nodes ---

    /** A running graph with the boot that owns it. */
    private record Node(Boot boot, MarksGraph graph) {

        void close() {
            boot.close();
        }
    }

    /**
     * Starts a node of the deployment over a pool observed by the observer. Propagation is set so far apart that no
     * poll runs during a proof, and the executor looks for work every 100 ms.
     */
    private static Node node(
            DefinitionScenario database, Vertx vertx, WorkflowDefinitionSqlObserver observer, Deployment deployment) {
        JsonObject config = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", new JsonObject().put("maxStalenessSeconds", 3600))
                                .put("executor", new JsonObject().put("pollIntervalMs", 100)));
        Boot boot = database.nodeOf(pool -> ExecutorMarksGraph.build(
                vertx,
                observer.observe(pool),
                config,
                FailingScopeRowIT.class,
                deployment.code(),
                deployment.catalog()));
        return new Node(boot, (MarksGraph) boot.app());
    }

    /** Undeploys the node's executor, so that what is recorded afterwards is not run. */
    private static void stopExecutor(Node node) {
        await(node.graph().verticleDeployer().undeploy(WorkflowExecutor.DEPLOYMENT_NAME));
    }

    /** Loads, publishes and activates acme's document of the purchase id and returns its key. */
    private static DefinitionKey publishAndActivateAcme(Node node, boolean financeReview) {
        await(node.graph()
                .definitionService()
                .load(ACME, document(financeReview), DocumentFormat.YAML, source("acme-marks", LOADED_AT)));
        DefinitionKey key = new DefinitionKey(ACME, PURCHASE, 1);
        await(node.graph().definitionService().publish(key));
        await(node.graph().definitionService().activate(ACME, PURCHASE, ACME, 1));
        return key;
    }

    /** Records a start in acme and returns the instance without waiting for the executor. */
    private static WorkflowInstanceId recordStart(Node node, String orderId) {
        String idempotencyKey = "start-" + UUID.randomUUID();
        return await(node.graph()
                .workflowOperations()
                .start(FailingScopeRowIT.command(ACME, PURCHASE, orderId, idempotencyKey, null, null)));
    }

    /** Records a start in acme and waits until the executor has nothing left to run for it. */
    private static WorkflowInstanceId startAndSettle(Node node, String orderId) {
        WorkflowInstanceId instance = recordStart(node, orderId);
        await(node.graph().executionProbe().awaitIdle(instance, IDLE_TIMEOUT));
        return instance;
    }

    /** Clears the purchase id's rows now and again when the scenario closes. */
    private static void own(DefinitionScenario database) {
        FailingScopeRowIT.own(database, PURCHASE);
    }

    /**
     * A document that waits for the approval signal and then completes, or, with a finance review, goes on to a human
     * task whose decision payload is the decision class named by its binary name.
     */
    private static byte[] document(boolean financeReview) {
        String afterApproval = financeReview ? "finance-review" : "done";
        String review = financeReview ? """
                  - id: finance-review
                    type: human-task
                    taskType: approval
                    assignment:
                      mode: role
                      value: reviewers
                    decisions:
                      - name: approve
                        payloadType: %s
                        applicator: doc.order.keepState
                        next: done
                """.formatted(FinanceDecision.class.getName()) : "";
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
                    next: %s
                %s  - id: done
                    type: complete
                """.formatted(PURCHASE, ContractC.class.getName(), afterApproval, review)
                .getBytes(StandardCharsets.UTF_8);
    }

    // --- Rows ---

    private record InstanceRow(String status, String errorType, String errorMessage) {}

    private record CommandRow(String state, int attempts, int deferralCount) {}

    private static InstanceRow instanceOf(DefinitionScenario database, WorkflowInstanceId instance) {
        Row row = await(database.admin()
                        .preparedQuery("SELECT status, error_type, error_message FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(instance.value())))
                .iterator()
                .next();
        return new InstanceRow(row.getString("status"), row.getString("error_type"), row.getString("error_message"));
    }

    private static CommandRow commandOf(DefinitionScenario database, WorkflowInstanceId instance, String kind) {
        Row row = await(database.admin()
                        .preparedQuery("SELECT state, attempts, deferral_count FROM workflow_commands"
                                + " WHERE workflow_id = $1 AND kind = $2 ORDER BY seq LIMIT 1")
                        .execute(Tuple.of(instance.value(), kind)))
                .iterator()
                .next();
        return new CommandRow(row.getString("state"), row.getInteger("attempts"), row.getInteger("deferral_count"));
    }

    private static List<String> historyOf(DefinitionScenario database, WorkflowInstanceId instance) {
        List<String> entries = new ArrayList<>();
        for (Row row : await(database.admin()
                .preparedQuery("SELECT entry_type FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(instance.value())))) {
            entries.add(row.getString("entry_type"));
        }
        return entries;
    }

    /** The stored failure rule name of the row, or {@code null} when the row is not marked. */
    private static String failureReason(DefinitionScenario database, DefinitionKey key) {
        return await(database.admin()
                        .preparedQuery("""
                                SELECT failure_reason FROM workflow_definitions
                                WHERE scope_key = $1 AND definition_id = $2 AND definition_version = $3
                                """)
                        .execute(Tuple.of(key.scopeKey(), key.definitionId(), key.definitionVersion())))
                .iterator()
                .next()
                .getString("failure_reason");
    }

    /** Pushes the availability of the instance's commands an hour away, so that no executor takes them. */
    private static void holdBack(DefinitionScenario database, WorkflowInstanceId instance) {
        database.execute(
                "UPDATE workflow_commands SET available_at = now() + interval '1 hour' WHERE workflow_id = $1",
                Tuple.of(instance.value()));
    }

    /** Makes the instance's commands available now. */
    private static void release(DefinitionScenario database, WorkflowInstanceId instance) {
        database.execute(
                "UPDATE workflow_commands SET available_at = now() WHERE workflow_id = $1", Tuple.of(instance.value()));
    }

    /**
     * Waits until the row is marked and the command deferred, reading the mark and the command's state in one statement
     * each time, and returns whether any read showed the mark while the command had not yet been deferred.
     */
    private static boolean awaitMarkObservingDeferral(
            DefinitionScenario database, WorkflowInstanceId instance, DefinitionKey key) {
        boolean[] early = {false};
        String[] seen = {"nothing read"};
        try {
            awaitCondition("the row to be marked and its command deferred", () -> {
                Row row = await(database.admin()
                                .preparedQuery("""
                                        SELECT d.failure_reason AS mark, c.state AS command_state
                                        FROM workflow_definitions d, workflow_commands c
                                        WHERE d.scope_key = $1 AND d.definition_id = $2 AND d.definition_version = $3
                                          AND c.workflow_id = $4 AND c.kind = 'START'
                                        """)
                                .execute(Tuple.of(
                                        key.scopeKey(), key.definitionId(), key.definitionVersion(), instance.value())))
                        .iterator()
                        .next();
                String mark = row.getString("mark");
                String state = row.getString("command_state");
                seen[0] = "mark " + mark + ", command " + state;
                if (mark != null && !"DEFERRED".equals(state)) early[0] = true;
                return mark != null && "DEFERRED".equals(state);
            });
        } catch (AssertionError timeout) {
            throw new AssertionError(timeout.getMessage() + "; last read: " + seen[0], timeout);
        }
        return early[0];
    }

    /** Polls the condition until it holds or the settle time passes, and fails with the description then. */
    private static void awaitCondition(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + SETTLE.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

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
