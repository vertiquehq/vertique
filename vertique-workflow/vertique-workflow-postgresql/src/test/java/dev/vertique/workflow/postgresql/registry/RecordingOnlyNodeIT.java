// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.executor.WorkflowExecutor;
import dev.vertique.workflow.exception.WorkflowConfigurationException;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.WorkflowDefinitionSqlObserver.Execution;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the recording-only node role: a node configured with {@code workflow.executor.enabled=false} starts against
 * a database whose live code rows it has no code for, deploys no executor, builds no plan and loads no workflow class,
 * and still records starts, completions and reassignments, serves queries and the lifecycle operations that need no
 * build; the operations that build a plan are refused with a typed node-role error.
 *
 * <p>An executor node boots from a fixture compiled into a class loader that no other node can see, so a recording-only
 * node on the same database has no workflow state, payload or definition class to load. A probe records the class names
 * that node asks its context class loader for.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class RecordingOnlyNodeIT {

    /** The contract that the documents of these tests name; it declares the signals they wait for. */
    @WorkflowContract
    public interface DocumentContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recording_only_node_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ACME = "acme";
    private static final String OTHER_CONTRACT = "billing";
    private static final Instant LOADED_AT = Instant.parse("2026-10-28T12:00:00Z");
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROMPT = Duration.ofSeconds(2);
    private static final String FIXTURE_PACKAGE = RecordingOnlyFixture.PACKAGE + ".";

    @Test
    @DisplayName("a recording-only node starts over live code rows it has no code for and deploys no executor")
    void recordingOnlyNodeStartsWithLiveCodeRowsAndNoWorkflowClasses(Vertx vertx) {
        String id = "role-startup";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id);
            RecordingOnlyFixture code = fixture(scenario);

            // Given an executor node E booted with the fixture's definition, which writes its live code row
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));
            assertThat(scenario.stored(id, 1).kind()).isEqualTo("CODE");
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");
            assertThat(definitionWrites(executor.sql())).as("E's own writes").isNotEmpty();

            // And a node R configured recording-only whose class loader cannot see the fixture's classes
            assertThat(fixtureClassIsVisibleToTheTestLoader(RecordingOnlyFixture.className("OrderState")))
                    .isFalse();

            // When R runs its startup steps
            Running recording = nodes.recordingNode();

            // Then startup succeeds, although the live code row has no code on R
            assertThat(recording.failure()).isNull();

            // And R's registry reports no code definitions and the statement log shows no reconciliation write by R
            assertThat(recording.graph().workflowRegistry().codeDefinitions()).isEmpty();
            assertThat(definitionWrites(recording.sql())).isEmpty();
            assertThat(scenario.stored(id, 1).status()).isEqualTo("AVAILABLE");

            // And R contributes no workflow-executor deployment while E contributes exactly one
            assertThat(executorDeployments(recording.graph())).isEmpty();
            assertThat(executorDeployments(executor.graph())).hasSize(1);

            // And R runs no executor instance, while E runs one
            assertThat(recording.graph().verticleDeployer().deploymentId(WorkflowExecutor.DEPLOYMENT_NAME))
                    .isNull();
            assertThat(executor.graph().verticleDeployer().deploymentId(WorkflowExecutor.DEPLOYMENT_NAME))
                    .isNotNull();

            // And R asked for no fixture class
            assertThat(recording.probe().contextsCreatedDuringCarryTheProbe(vertx))
                    .isTrue();
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();
        }
    }

    @Test
    @DisplayName("a recording-only node records a start that an executor runs, and claims nothing")
    void recordingOnlyNodeRecordsAStartWithoutRunningWorkflowCode(Vertx vertx) {
        String id = "role-record-start";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id);
            RecordingOnlyFixture code = fixture(scenario);

            // Given executor node E and recording-only node R on one database, with E's executor not deployed
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));
            Running recording = nodes.recordingNode();
            await(executor.graph().verticleDeployer().undeploy(WorkflowExecutor.DEPLOYMENT_NAME));

            // When R records a start in its own transaction and commits
            WorkflowInstanceId started =
                    recording.call(() -> recording.graph().workflowOperations().start(start(id, "k1", null)));

            // Then the commit holds a PENDING instance pinned to the selected version and one START command
            assertThat(instanceOf(scenario, started)).isEqualTo(new InstanceRow("PENDING", 1L));
            assertThat(commandsOf(scenario, started)).singleElement().satisfies(command -> {
                assertThat(command.kind()).isEqualTo("START");
                assertThat(command.state()).isEqualTo("PENDING");
            });

            // And R claims nothing: given many polling intervals, the command is untouched
            sleep(1_500);
            assertThat(commandsOf(scenario, started)).singleElement().satisfies(command -> {
                assertThat(command.state()).isEqualTo("PENDING");
                assertThat(command.claimedBy()).isNull();
                assertThat(command.attempts()).isZero();
                assertThat(command.deferrals()).isZero();
            });
            assertThat(instanceOf(scenario, started).status()).isEqualTo("PENDING");

            // And R runs no executor instance, and loaded no fixture class
            assertThat(recording.graph().verticleDeployer().deploymentId(WorkflowExecutor.DEPLOYMENT_NAME))
                    .isNull();
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();

            // When E's executor is deployed
            deployExecutor(executor);
            await(executor.graph().executionProbe().awaitIdle(started, IDLE_TIMEOUT));

            // Then the instance waits at its first wait and the command is complete
            assertThat(instanceOf(scenario, started).status()).isEqualTo("WAITING");
            assertThat(commandsOf(scenario, started)).singleElement().satisfies(command -> {
                assertThat(command.state()).isEqualTo("COMPLETE");
            });
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();
        }
    }

    @Test
    @DisplayName("reassigning a task on a recording-only node completes synchronously and is logged")
    void taskReassignmentOnRecordingOnlyNodeCompletesSynchronously(Vertx vertx) {
        String id = "role-reassign";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id);
            RecordingOnlyFixture code = fixture(scenario);

            // Given an instance waiting at its human task, run by E, which is then idle
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));
            Running recording = nodes.recordingNode();
            WorkflowInstanceId instance = startAndSettle(executor, id, "k1", null);
            UUID task = openTask(scenario, instance);
            Rows before = rowsOf(scenario, instance);
            assertThat(assigneeOf(scenario, task)).isEqualTo("reviewers");

            // When R reassigns the task
            TaskReassignmentCommand reassignment = new TaskReassignmentCommand(
                    task,
                    new TaskAssignment.Role("legal"),
                    new WorkflowActor.User("manager"),
                    "re-1",
                    "specialist needed");
            TaskMutationResult result = recording.call(() -> recording
                    .graph()
                    .pool()
                    .withTransaction(tx -> recording.graph().taskCallbacks().taskReassigned(reassignment, tx)));

            // Then the future completed with the new assignee already committed
            assertThat(result).isEqualTo(TaskMutationResult.APPLIED);
            assertThat(assigneeOf(scenario, task)).isEqualTo("legal");

            // And no command row was written, and the history has a TASK_REASSIGNED entry
            Rows after = rowsOf(scenario, instance);
            assertThat(after.commands()).isEqualTo(before.commands());
            assertThat(after.history()).isEqualTo(before.history() + 1);
            assertThat(historyTypesOf(scenario, instance)).contains("TASK_REASSIGNED");
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();
        }
    }

    @Test
    @DisplayName("invalid completions are refused in the caller's transaction on a recording-only node")
    void invalidCompletionsAreRefusedInTheCallersTransactionOnRecordingOnlyNode(Vertx vertx) {
        String id = "role-invalid-completion";
        String stableId = "role-invalid-completion-stable";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id, stableId);
            RecordingOnlyFixture code = fixture(scenario);

            // Given two open tasks: one offering approve with a decision schema that requires a comment, and one
            // recorded with version stability at subject version 3
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false), code.definition(stableId, true));
            Running recording = nodes.recordingNode();
            WorkflowInstanceId plain = startAndSettle(executor, id, "k1", null);
            WorkflowInstanceId stable =
                    startAndSettle(executor, stableId, "k2", new WorkflowSubjectRef("Article", "art-1", "3"));
            UUID plainTask = openTask(scenario, plain);
            UUID stableTask = openTask(scenario, stable);
            Rows plainBefore = rowsOf(scenario, plain);
            Rows stableBefore = rowsOf(scenario, stable);

            // When R completes the first naming reject, then with approve and a payload lacking the comment, and the
            // second with reviewed subject version 2
            Throwable unoffered = recording.failureOf(
                    () -> complete(recording, plainTask, "reject", Map.of("comment", "no"), "c1", null));
            Throwable missingComment =
                    recording.failureOf(() -> complete(recording, plainTask, "approve", Map.of(), "c2", null));
            Throwable stale = recording.failureOf(
                    () -> complete(recording, stableTask, "approve", Map.of("comment", "ok"), "c3", "2"));

            // Then each fails in the caller's transaction with the typed error
            assertThat(unoffered).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(missingComment).isInstanceOf(WorkflowInvalidPayloadException.class);
            assertThat(stale).isInstanceOf(WorkflowStaleSubjectVersionException.class);

            // And no command row, no deduplication row and no task status change survive
            assertThat(rowsOf(scenario, plain)).isEqualTo(plainBefore);
            assertThat(rowsOf(scenario, stable)).isEqualTo(stableBefore);
            assertThat(taskStatusOf(scenario, plainTask)).isEqualTo("OPEN");
            assertThat(taskStatusOf(scenario, stableTask)).isEqualTo("OPEN");

            // And R loaded no fixture class
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();
        }
    }

    @Test
    @DisplayName("an invalid or oversized decision payload is refused without waiting for the execution lock")
    void invalidDecisionPayloadIsRefusedWithoutWaitingForTheExecutionLock(Vertx vertx) {
        String id = "role-before-lock";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id);
            RecordingOnlyFixture code = fixture(scenario);

            // Given an open task whose decision schema requires a comment, a recording bound of 1024 bytes and a
            // lock timeout of 5 seconds
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));
            Running recording = nodes.recordingNode(1024);
            WorkflowInstanceId instance = startAndSettle(executor, id, "k1", null);
            UUID task = openTask(scenario, instance);
            Rows before = rowsOf(scenario, instance);

            // And a second session that holds the instance's execution lock
            holdingExecutionLock(scenario, instance, () -> {
                // When R completes the task with a payload lacking the comment, then with an oversized payload
                Throwable missing = failureWithin(complete(recording, task, "approve", Map.of(), "c1", null), PROMPT);
                Throwable oversized = failureWithin(
                        complete(recording, task, "approve", Map.of("comment", "x".repeat(2048)), "c2", null), PROMPT);

                // Then both fail with the payload refusal well under the lock timeout, with no wait for the lock
                assertThat(missing).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(oversized).isInstanceOf(WorkflowInvalidPayloadException.class);
                assertThat(ungrantedAdvisoryLocks(scenario)).isZero();
                return null;
            });

            // And nothing was recorded
            assertThat(rowsOf(scenario, instance)).isEqualTo(before);
            assertThat(taskStatusOf(scenario, task)).isEqualTo("OPEN");
        }
    }

    @Test
    @DisplayName("operations that build a plan are refused with the node-role error, the others succeed")
    void buildDependentOperationsAreRefusedWithTheNodeRoleError(Vertx vertx) {
        String toActivate = "role-ops-activate";
        String toDeprecate = "role-ops-deprecate";
        String candidate = "role-ops-candidate";
        String toReinstate = "role-ops-reinstate";
        String loadedHere = "role-ops-loaded-here";
        List<String> ids = List.of(toActivate, toDeprecate, candidate, toReinstate, loadedHere);

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, ids.toArray(String[]::new));
            RecordingOnlyFixture code = fixture(scenario);

            // Given an executor that stored an available document to activate, an available one to deprecate, a
            // candidate and a deprecated version that is past its cooling time
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code);
            AppComponent seed = executor.graph();
            for (String documentId : List.of(toActivate, toDeprecate, candidate, toReinstate)) {
                await(seed.definitionService()
                        .load(ACME, document(documentId), DocumentFormat.YAML, source(documentId, LOADED_AT)));
            }
            for (String documentId : List.of(toActivate, toDeprecate, toReinstate)) {
                await(seed.definitionService().publish(key(documentId)));
            }
            await(seed.definitionService().deprecate(key(toReinstate)));
            scenario.ageStatus(key(toReinstate), 60);
            assertThat(statusOf(scenario, toReinstate)).isEqualTo("DEPRECATED");
            assertThat(statusOf(scenario, candidate)).isEqualTo("CANDIDATE");

            // And the recording-only node R
            Running recording = nodes.recordingNode();
            AppComponent r = recording.graph();
            String rowsBefore = definitionRowsOf(scenario, ids);

            // When R calls document load, publication, reinstatement, WorkflowRegistry.load and each of the four
            // WorkflowPayloadSchemas methods
            List<Refusal> refusals = List.of(
                    new Refusal("load", () -> r.definitionService()
                            .load(ACME, document(loadedHere), DocumentFormat.YAML, source(loadedHere, LOADED_AT))),
                    new Refusal("publish", () -> r.definitionService().publish(key(candidate))),
                    new Refusal("reinstate", () -> r.definitionService().reinstate(key(toReinstate))),
                    new Refusal("load", () -> r.workflowRegistry().load(key(toActivate))),
                    new Refusal(
                            "describe",
                            () -> recording.schema().payloadSchemas().describe(key(toActivate))),
                    new Refusal("decision", () -> recording
                            .schema()
                            .payloadSchemas()
                            .decision(key(toActivate), "wait-paid", "approve")),
                    new Refusal("validateDecision", () -> recording
                            .schema()
                            .payloadSchemas()
                            .validateDecision(key(toActivate), "wait-paid", "approve", new JsonObject())),
                    new Refusal("validateStart", () -> recording
                            .schema()
                            .payloadSchemas()
                            .validateStart(key(toActivate), new JsonObject())));
            List<String> unrefused = new ArrayList<>();
            for (Refusal refusal : refusals) {
                Throwable failure = recording.failureOf(refusal.call());

                // Then each fails with the node-role error that names the operation and the setting, not the document
                if (!(failure instanceof WorkflowNodeRoleException role)) {
                    unrefused.add(refusal.operation() + " -> " + failure);
                    continue;
                }
                String message = role.getMessage().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
                assertThat(message)
                        .as("message of %s", refusal.operation())
                        .contains(refusal.operation().toLowerCase(Locale.ROOT))
                        .contains("workflow.executor.enabled=false")
                        .doesNotContain("initialstep");
                assertThat(role).isInstanceOf(WorkflowConfigurationException.class);
            }
            assertThat(unrefused)
                    .as("operations not refused with the node-role error")
                    .isEmpty();

            // And the rows are unchanged, and nothing was stored for the document R was asked to load
            assertThat(definitionRowsOf(scenario, ids)).isEqualTo(rowsBefore);
            assertThat(scenario.rowCount(loadedHere)).isZero();

            // When R activates, deprecates and retires
            await(r.definitionService().activate(ACME, toActivate, ACME, 1));
            await(r.definitionService().deprecate(key(toDeprecate)));
            await(r.definitionService().retire(key(toReinstate)));

            // Then each succeeds
            assertThat(activationsOf(scenario, toActivate)).isEqualTo(1);
            assertThat(statusOf(scenario, toDeprecate)).isEqualTo("DEPRECATED");
            assertThat(statusOf(scenario, toReinstate)).isEqualTo("RETIRED");
        }
    }

    @Test
    @DisplayName("the schema producer is required only on executor nodes")
    void schemaProducerIsRequiredOnlyOnExecutorNodes(Vertx vertx) {
        String id = "role-schema-existing";
        String unwritten = "role-schema-unwritten";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id, unwritten);
            RecordingOnlyFixture code = fixture(scenario);

            // Given executor node E with the schema module, and two graphs built without it: a recording-only node R'
            // and an executor node E' that also deploys a definition that has no row yet
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));

            // When each runs its startup steps
            Running recording = nodes.recordingNode(withoutSchemaModule());
            Running unsupported = nodes.executorNodeWithoutSchemaModule(
                    code, code.definition(id, false), code.definition(unwritten, false));

            // Then R' starts and records a start that E runs
            assertThat(recording.failure()).isNull();
            WorkflowInstanceId started =
                    recording.call(() -> recording.graph().workflowOperations().start(start(id, "k1", null)));
            await(executor.graph().executionProbe().awaitIdle(started, IDLE_TIMEOUT));
            assertThat(instanceOf(scenario, started).status()).isEqualTo("WAITING");

            // And E' fails at startup naming the module and the setting
            assertThat(unsupported.failure()).isNotNull();
            WorkflowConfigurationException refusal =
                    causeOf(unsupported.failure(), WorkflowConfigurationException.class);
            assertThat(refusal.getMessage())
                    .contains("vertique-workflow-schema")
                    .contains("workflow.executor.enabled");

            // And E' wrote no definition row
            assertThat(scenario.rowCount(unwritten)).isZero();
            assertThat(definitionWrites(unsupported.sql())).isEmpty();
        }
    }

    @Test
    @DisplayName("a contract-aware query checks the pinned row's contract without a plan, on every node")
    void contractAwareQueryChecksThePinnedRowWithoutAPlan(Vertx vertx) {
        String id = "role-query-code";
        String documentId = "role-query-document";

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            own(scenario, id, documentId);
            RecordingOnlyFixture code = fixture(scenario);

            // Given an instance of the fixture definition (contract orders) started and run by E to its first wait,
            // and a document instance E ran to its first wait
            Nodes nodes = new Nodes(vertx, scenario);
            Running executor = nodes.executorNode(code, code.definition(id, false));
            WorkflowInstanceId instance = startAndSettle(executor, id, "k1", null);
            await(executor.graph()
                    .definitionService()
                    .load(ACME, document(documentId), DocumentFormat.YAML, source(documentId, LOADED_AT)));
            await(executor.graph().definitionService().publish(key(documentId)));
            WorkflowInstanceId documentInstance = await(executor.graph()
                    .workflowOperations()
                    .start(new StartCommand(
                            documentId,
                            DocumentContract.class.getName(),
                            new PlaceOrderDoc("order-1"),
                            "doc-k1",
                            null,
                            null,
                            1L,
                            ACME,
                            ACME)));
            await(executor.graph().executionProbe().awaitIdle(documentInstance, IDLE_TIMEOUT));
            assertThat(instanceOf(scenario, documentInstance).status()).isEqualTo("WAITING");

            // And R, with nothing cleared before the calls: no class requested, no plan cache event
            Running recording = nodes.recordingNode();
            int cacheEventsBefore = recording.cache().events().size();

            // When R queries the code instance as orders, then as billing
            WorkflowView view = recording.call(
                    () -> recording.graph().workflowOperations().query(instance, RecordingOnlyFixture.CONTRACT_NAME));
            Throwable mismatch = recording.failureOf(
                    () -> recording.graph().workflowOperations().query(instance, OTHER_CONTRACT));

            // Then the first returns the instance's view
            assertThat(view.instance().id()).isEqualTo(instance);

            // And the second fails with the mismatch naming the definition id, version and both contract names
            assertThat(mismatch).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(((WorkflowDefinitionContractMismatchException) mismatch).definitionKey())
                    .isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, id, 1));
            assertThat(mismatch.getMessage())
                    .contains(id)
                    .contains("v1")
                    .contains(RecordingOnlyFixture.CONTRACT_NAME)
                    .contains(OTHER_CONTRACT);

            // And R loaded no fixture class, built no plan and holds no definition
            assertThat(recording.probe().requestedWithPrefix(FIXTURE_PACKAGE)).isEmpty();
            assertThat(recording.cache().events()).hasSize(cacheEventsBefore);
            assertThat(recording.graph().workflowRegistry().codeDefinitions()).isEmpty();

            // And the same holds for the document instance, whose plan R could build from the stored document
            WorkflowView documentView = recording.call(() ->
                    recording.graph().workflowOperations().query(documentInstance, DocumentContract.class.getName()));
            Throwable documentMismatch = recording.failureOf(
                    () -> recording.graph().workflowOperations().query(documentInstance, OTHER_CONTRACT));
            assertThat(documentView.instance().id()).isEqualTo(documentInstance);
            assertThat(documentMismatch).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(recording.cache().events()).hasSize(cacheEventsBefore);

            // When a second executor, whose plan cache does not hold the document's plan, answers the same queries
            Running cold = nodes.executorNode(code, code.definition(id, false));
            DefinitionKey documentKey = key(documentId);
            assertThat(cold.graph().planCache().resident(documentKey)).isEmpty();
            WorkflowView coldView = cold.call(
                    () -> cold.graph().workflowOperations().query(documentInstance, DocumentContract.class.getName()));
            Throwable coldMismatch =
                    cold.failureOf(() -> cold.graph().workflowOperations().query(documentInstance, OTHER_CONTRACT));

            // Then it answers from the pinned row as well, and builds no plan to do it
            assertThat(coldView.instance().id()).isEqualTo(documentInstance);
            assertThat(coldMismatch).isInstanceOf(WorkflowDefinitionContractMismatchException.class);
            assertThat(cold.graph().planCache().resident(documentKey)).isEmpty();
        }
    }

    // --- Nodes ---

    /** The recording-only node of the proofs that has no schema module, the executor graph's counterpart. */
    private static NodeOption withoutSchemaModule() {
        return NodeOption.WITHOUT_SCHEMA_MODULE;
    }

    private enum NodeOption {
        WITH_SCHEMA_MODULE,
        WITHOUT_SCHEMA_MODULE
    }

    /** One application graph over a pool of its own, with the probes that observe it. */
    private record Running(
            Boot boot,
            RecordingOnlyGraph.Node graph,
            WorkflowDefinitionSqlObserver sql,
            RecordingCacheObserver cache,
            ClassLoadProbe probe) {

        Throwable failure() {
            return boot.failure();
        }

        RecordingOnlyGraph.SchemaNode schema() {
            return (RecordingOnlyGraph.SchemaNode) graph;
        }

        /** Waits for the future of the call, made with the probe installed on the calling thread. */
        <T> T call(Supplier<Future<T>> call) {
            return await(probe.during(call));
        }

        /** The failure of the call, whether it throws or fails its future. */
        Throwable failureOf(Supplier<Future<?>> call) {
            return ActivationFixtures.failureOf(() -> probe.during(call));
        }
    }

    /** Starts the graphs of one scenario on its database. */
    private record Nodes(Vertx vertx, DefinitionScenario scenario) {

        /** An executor node (the default role) with the schema module, which deploys the given definitions. */
        Running executorNode(RecordingOnlyFixture code, WorkflowDefinition<?, ?>... definitions) {
            return start(new JsonObject(), true, true, code, definitions);
        }

        /** An executor node without the schema module; it is expected to refuse to start. */
        Running executorNodeWithoutSchemaModule(RecordingOnlyFixture code, WorkflowDefinition<?, ?>... definitions) {
            return start(new JsonObject(), false, false, code, definitions);
        }

        /** A recording-only node with the schema module, which deploys no definition. */
        Running recordingNode() {
            return recordingNode(NodeOption.WITH_SCHEMA_MODULE);
        }

        /** A recording-only node, with or without the schema module, which deploys no definition. */
        Running recordingNode(NodeOption option) {
            return start(recordingOnly(null), option == NodeOption.WITH_SCHEMA_MODULE, true, null);
        }

        /** A recording-only node with the schema module and the given bound on the size of a payload. */
        Running recordingNode(int maxPayloadBytes) {
            return start(recordingOnly(maxPayloadBytes), true, true, null);
        }

        private Running start(
                JsonObject config,
                boolean schemaModule,
                boolean mustStart,
                RecordingOnlyFixture code,
                WorkflowDefinition<?, ?>... definitions) {
            WorkflowDefinitionSqlObserver sql = new WorkflowDefinitionSqlObserver();
            RecordingCacheObserver cache = new RecordingCacheObserver();
            ClassLoadProbe probe = new ClassLoadProbe();
            List<WorkflowDefinition<?, ?>> deployed = List.of(definitions);
            List<WorkflowTypeCatalogEntry> catalog = code == null ? List.of() : code.catalog();
            Function<Pool, AppComponent> graph = pool -> schemaModule
                    ? RecordingOnlyGraph.withSchemaModule(
                            vertx, sql.observe(pool), config, RecordingOnlyNodeIT.class, deployed, catalog, cache)
                    : RecordingOnlyGraph.withoutSchemaModule(
                            vertx, sql.observe(pool), config, RecordingOnlyNodeIT.class, deployed, catalog, cache);
            Boot boot = probe.during(() -> mustStart ? scenario.nodeOf(graph) : scenario.attemptOf(graph));
            return new Running(boot, (RecordingOnlyGraph.Node) boot.app(), sql, cache, probe);
        }
    }

    /** The configuration of a recording-only node, optionally with a bound on the size of a payload. */
    private static JsonObject recordingOnly(Integer maxPayloadBytes) {
        JsonObject workflow = new JsonObject().put("executor", new JsonObject().put("enabled", false));
        if (maxPayloadBytes != null) {
            workflow.put(
                    "recording", new JsonObject().put("lockTimeoutMs", 5000).put("maxPayloadBytes", maxPayloadBytes));
        }
        return new JsonObject().put("workflow", workflow);
    }

    private static RecordingOnlyFixture fixture(DefinitionScenario scenario) {
        RecordingOnlyFixture code = RecordingOnlyFixture.compile();
        scenario.onClose(code::close);
        return code;
    }

    private static void own(DefinitionScenario scenario, String... ids) {
        ActivationFixtures.clearActivations(scenario.admin(), ids);
        scenario.ownRows(ids);
        scenario.onClose(() -> ActivationFixtures.clearActivations(scenario.admin(), ids));
    }

    private static List<VerticleDeployment> executorDeployments(AppComponent graph) {
        return graph.verticleDeployments().stream()
                .filter(deployment -> WorkflowExecutor.DEPLOYMENT_NAME.equals(deployment.name()))
                .toList();
    }

    /** Deploys only the executor entry of the node's deployments through the node's deployer. */
    private static void deployExecutor(Running node) {
        VerticleDeployment executor =
                executorDeployments(node.graph()).stream().findFirst().orElseThrow();
        await(node.graph().verticleDeployer().deploy(executor));
    }

    private static boolean fixtureClassIsVisibleToTheTestLoader(String name) {
        try {
            Class.forName(name, false, RecordingOnlyNodeIT.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** The statements of an observed pool that insert, update or delete rows of the definition or activation tables. */
    private static List<String> definitionWrites(WorkflowDefinitionSqlObserver observer) {
        return observer.executions().stream()
                .map(Execution::sql)
                .map(sql -> sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip())
                .filter(sql -> sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete"))
                .filter(sql -> sql.contains("workflow_definitions") || sql.contains("workflow_definition_activations"))
                .toList();
    }

    // --- Recording ---

    private static StartCommand start(String definitionId, String key, WorkflowSubjectRef subject) {
        return new StartCommand(
                definitionId,
                RecordingOnlyFixture.CONTRACT_NAME,
                Map.of("id", "order-" + key),
                key,
                null,
                subject,
                null,
                null,
                null);
    }

    /** Records a start through the node and waits until the executor has nothing left to run for the instance. */
    private static WorkflowInstanceId startAndSettle(
            Running node, String definitionId, String key, WorkflowSubjectRef subject) {
        WorkflowInstanceId started =
                node.call(() -> node.graph().workflowOperations().start(start(definitionId, key, subject)));
        await(node.graph().executionProbe().awaitIdle(started, IDLE_TIMEOUT));
        return started;
    }

    private static Future<TaskMutationResult> complete(
            Running node, UUID task, String decision, Map<String, ?> payload, String key, String reviewedVersion) {
        TaskCompletionCommand command = new TaskCompletionCommand(
                task, decision, payload, key, new WorkflowActor.User("reviewer"), reviewedVersion);
        return node.probe().during(() -> node.graph()
                .pool()
                .withTransaction(tx -> node.graph().taskCallbacks().taskCompleted(command, tx)));
    }

    // --- Documents ---

    private static DefinitionKey key(String documentId) {
        return new DefinitionKey(ACME, documentId, 1);
    }

    /** A document that waits for a signal and then completes. */
    private static byte[] document(String id) {
        return """
                definitionId: %s
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: doc.order.fromPlaceOrder
                initialStep: wait-paid
                steps:
                  - id: wait-paid
                    type: wait-signal
                    signal: inventory.reserved
                    payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                    stateReducer: doc.order.applyInventoryReserved
                    next: done
                  - id: done
                    type: complete
                """.formatted(
                        id,
                        OrderDocState.class.getName(),
                        DocumentContract.class.getName(),
                        PlaceOrderDoc.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** One refused call and the word its message must carry. */
    private record Refusal(String operation, Supplier<Future<?>> call) {}

    // --- Rows ---

    private record InstanceRow(String status, long version) {}

    private record CommandRow(String kind, String state, String claimedBy, int attempts, int deferrals) {}

    /** Everything a refused call must leave as it was. */
    private record Rows(long commands, long dedup, long history, String tasks) {}

    private static InstanceRow instanceOf(DefinitionScenario scenario, WorkflowInstanceId id) {
        Row row = one(scenario, "SELECT status, definition_version FROM workflow_instances WHERE id = $1", id.value());
        return new InstanceRow(row.getString("status"), row.getLong("definition_version"));
    }

    private static List<CommandRow> commandsOf(DefinitionScenario scenario, WorkflowInstanceId id) {
        List<CommandRow> commands = new ArrayList<>();
        for (Row row : await(scenario.admin()
                .preparedQuery("SELECT kind, state, claimed_by, attempts, deferral_count FROM workflow_commands"
                        + " WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            commands.add(new CommandRow(
                    row.getString("kind"),
                    row.getString("state"),
                    row.getString("claimed_by"),
                    row.getInteger("attempts"),
                    row.getInteger("deferral_count")));
        }
        return commands;
    }

    private static Rows rowsOf(DefinitionScenario scenario, WorkflowInstanceId id) {
        String tasks = joined(
                scenario,
                "SELECT task_id::text || ':' || status || ':' || assignee_key AS item FROM workflow_tasks"
                        + " WHERE workflow_id = $1 ORDER BY task_id",
                id.value());
        return new Rows(
                count(scenario, "SELECT count(*) AS total FROM workflow_commands WHERE workflow_id = $1", id.value()),
                count(scenario, "SELECT count(*) AS total FROM workflow_dedup WHERE workflow_id = $1", id.value()),
                count(scenario, "SELECT count(*) AS total FROM workflow_history WHERE workflow_id = $1", id.value()),
                tasks);
    }

    private static UUID openTask(DefinitionScenario scenario, WorkflowInstanceId instance) {
        return one(
                        scenario,
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'",
                        instance.value())
                .getUUID("task_id");
    }

    private static String taskStatusOf(DefinitionScenario scenario, UUID task) {
        return one(scenario, "SELECT status FROM workflow_tasks WHERE task_id = $1", task)
                .getString("status");
    }

    private static String assigneeOf(DefinitionScenario scenario, UUID task) {
        return one(scenario, "SELECT assignee_key FROM workflow_tasks WHERE task_id = $1", task)
                .getString("assignee_key");
    }

    private static List<String> historyTypesOf(DefinitionScenario scenario, WorkflowInstanceId id) {
        List<String> types = new ArrayList<>();
        for (Row row : await(scenario.admin()
                .preparedQuery("SELECT entry_type FROM workflow_history WHERE workflow_id = $1 ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            types.add(row.getString("entry_type"));
        }
        return types;
    }

    private static String statusOf(DefinitionScenario scenario, String documentId) {
        return one(
                        scenario,
                        "SELECT status FROM workflow_definitions WHERE scope_key = $1 AND definition_id = $2"
                                + " AND definition_version = 1",
                        ACME,
                        documentId)
                .getString("status");
    }

    private static long activationsOf(DefinitionScenario scenario, String documentId) {
        return count(
                scenario,
                "SELECT count(*) AS total FROM workflow_definition_activations WHERE definition_id = $1",
                documentId);
    }

    /** The rows of the definition ids as the proofs compare them: identity, status, hash and document digest. */
    private static String definitionRowsOf(DefinitionScenario scenario, List<String> ids) {
        List<String> items = new ArrayList<>();
        for (Row row : await(scenario.admin()
                .preparedQuery("SELECT scope_key || '/' || definition_id || '/' || definition_version || ' ' || status"
                        + " || ' ' || plan_hash || ' ' || COALESCE(document_digest, '') AS item"
                        + " FROM workflow_definitions WHERE definition_id = ANY($1) ORDER BY 1")
                .execute(Tuple.of(ids.toArray(String[]::new))))) {
            items.add(row.getString("item"));
        }
        return String.join("\n", items);
    }

    private static long ungrantedAdvisoryLocks(DefinitionScenario scenario) {
        return count(scenario, "SELECT count(*) AS total FROM pg_locks WHERE locktype = 'advisory' AND NOT granted");
    }

    private static Row one(DefinitionScenario scenario, String sql, Object... parameters) {
        return await(scenario.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))
                .iterator()
                .next();
    }

    private static long count(DefinitionScenario scenario, String sql, Object... parameters) {
        return one(scenario, sql, parameters).getLong("total");
    }

    private static String joined(DefinitionScenario scenario, String sql, Object... parameters) {
        List<String> items = new ArrayList<>();
        for (Row row : await(scenario.admin().preparedQuery(sql).execute(Tuple.from(List.of(parameters))))) {
            items.add(row.getString("item"));
        }
        return String.join(",", items);
    }

    // --- Locks and waiting ---

    /** Holds the instance's execution lock in a transaction of the test while the action runs. */
    private static <T> T holdingExecutionLock(DefinitionScenario scenario, WorkflowInstanceId id, Supplier<T> action) {
        int[] key = executionLockKey(id);
        SqlConnection connection = await(scenario.admin().getConnection());
        Transaction transaction = await(connection.begin());
        try {
            await(connection
                    .preparedQuery("SELECT pg_advisory_xact_lock($1::int4, $2::int4)")
                    .execute(Tuple.of(key[0], key[1])));
            return action.get();
        } finally {
            await(transaction.rollback());
            await(connection.close());
        }
    }

    /** The two integers of an instance's execution lock: the first eight bytes of a hash of its id. */
    private static int[] executionLockKey(WorkflowInstanceId id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("vertique.workflow.execution-lock:" + id.value()).getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            return new int[] {buffer.getInt(), buffer.getInt()};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static Throwable failureWithin(Future<?> future, Duration timeout) {
        try {
            future.toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("The call did not fail within " + timeout + ", so it waited for the lock", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for a failure", e);
        }
        throw new AssertionError("Expected the call to fail but it succeeded");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return type.cast(cause);
        }
        throw new AssertionError("Expected a " + type.getSimpleName() + " in the cause chain", failure);
    }
}
