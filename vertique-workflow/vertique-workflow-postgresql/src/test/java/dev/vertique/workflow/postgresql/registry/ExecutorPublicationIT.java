// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocState;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Stored;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that publishing a document builds its plan with the checks of an on-demand load and registers nothing: a
 * document that does not build is refused and stays a candidate, and a published document is built by the node that
 * first needs it, not by the node that published it.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ExecutorPublicationIT {

    /** The one contract that the code definition and every document of these tests name. */
    @WorkflowContract
    public interface PublishContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_executor_publication_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ACME = "acme";
    private static final String CACHE = "workflow-plans";
    private static final Instant LOADED_AT = Instant.parse("2026-10-28T12:00:00Z");
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(20);

    @Test
    @DisplayName("a document that does not build is refused and stays a candidate, and a valid one publishes")
    void documentThatDoesNotBuildIsRefusedAndStaysACandidate(Vertx vertx) {
        String stateId = "exec-state-extension";
        List<Candidate> defective = List.of(
                Candidates.unknownTarget("exec-unknown-target"),
                Candidates.undeclaredSignal("exec-undeclared-signal"),
                Candidates.reminderWithoutEvents("exec-reminder"),
                Candidates.stateNotExtended(stateId));
        Candidate valid = Candidates.valid("exec-valid");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows("exec-unknown-target", "exec-reminder", stateId, valid.id());

            // Given an executor whose default scope holds code of the state id, and four acme candidates: an unknown
            // step target, a reminder with no event module in the wiring, a state type that does not extend the
            // default scope's, and a valid document
            PublicationNodeGraph.Node node = node(vertx, scenario, List.of(new CodeWorkflow(stateId)));
            WorkflowDefinitionService service = node.definitionService();
            for (Candidate candidate : defective) store(scenario, service, candidate);
            store(scenario, service, valid);
            for (Candidate candidate : defective) {
                assertThat(scenario.stored(ACME, candidate.id(), 1).status()).isEqualTo("CANDIDATE");
            }

            // When each is published on the executor
            SoftAssertions soft = new SoftAssertions();
            for (Candidate candidate : defective) {
                Throwable refusal = failureOf(service.publish(candidate.key()));

                // Then the publication fails with a definition refusal that names the key and the rule
                soft.assertThat(refusal)
                        .as("publication of %s", candidate.id())
                        .isInstanceOf(WorkflowDefinitionException.class);
                soft.assertThat(describe(refusal))
                        .as("refusal of %s", candidate.id())
                        .contains(candidate.id())
                        .contains(candidate.rule());

                // And the row is still a candidate without a claim, and the plan cache does not hold the plan
                Stored row = scenario.stored(ACME, candidate.id(), 1);
                soft.assertThat(row.status()).as("row of %s", candidate.id()).isEqualTo("CANDIDATE");
                soft.assertThat(row.publishToken())
                        .as("claim of %s", candidate.id())
                        .isNull();
                soft.assertThat(node.planCache().resident(candidate.key()))
                        .as("cached plan of %s", candidate.id())
                        .isEmpty();
            }
            soft.assertAll();

            // And the valid candidate publishes to available without a claim
            await(service.publish(valid.key()));
            Stored published = scenario.stored(ACME, valid.id(), 1);
            assertThat(published.status()).isEqualTo("AVAILABLE");
            assertThat(published.publishToken()).isNull();
        }
    }

    @Test
    @DisplayName("publication registers nothing on the publishing node and another node loads the plan on first use")
    void publicationLeavesNoRegistrationAndLoadsOnFirstUse(Vertx vertx) {
        Candidate candidate = Candidates.valid("exec-first-use");

        try (DefinitionScenario scenario = new DefinitionScenario(vertx, db)) {
            scenario.ownRows(candidate.id());

            // Given two executors on one database, both started before the document exists
            RecordingCacheObserver eventsA = new RecordingCacheObserver();
            RecordingCacheObserver eventsB = new RecordingCacheObserver();
            PublicationNodeGraph.Node nodeA = node(vertx, scenario, List.of(), eventsA);
            PublicationNodeGraph.Node nodeB = node(vertx, scenario, List.of(), eventsB);

            // When the document is loaded and published on node A
            load(nodeA.definitionService(), candidate);
            await(nodeA.definitionService().publish(candidate.key()));

            // Then the row is available, and node A holds no registration of it
            assertThat(scenario.stored(ACME, candidate.id(), 1).status()).isEqualTo("AVAILABLE");
            assertThat(nodeA.workflowRegistry().codeDefinitions())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(candidate.id()));
            assertThat(eventsB.count(CACHE, CacheOutcome.MISS)).isZero();

            // When a start command of the version is executed on node B, which never saw the publication
            WorkflowInstanceId started = await(nodeB.workflowOperations().start(start(candidate)));
            PlanCacheFixture.awaitCondition("the instance to wait", IDLE_TIMEOUT, () -> "WAITING"
                    .equals(scenario.instanceStatus(started.value())));
            await(nodeB.executionProbe().awaitIdle(started, IDLE_TIMEOUT));
            PlanCacheFixture.awaitCondition(
                    "the plan load to be reported",
                    IDLE_TIMEOUT,
                    () -> loads(eventsA, eventsB, CacheOperation.LOAD, CacheOutcome.SUCCESS) > 0);

            // Then the node that ran the command, which is either of the two because both poll the one database,
            // loaded the plan through its plan cache with one miss and one load
            assertThat(loads(eventsA, eventsB, CacheOperation.GET, CacheOutcome.MISS))
                    .isEqualTo(1);
            assertThat(loads(eventsA, eventsB, CacheOperation.LOAD, CacheOutcome.SUCCESS))
                    .isEqualTo(1);

            // And the instance reached its wait, and node B holds no registration either
            assertThat(scenario.instanceStatus(started.value())).isEqualTo("WAITING");
            assertThat(nodeB.workflowRegistry().codeDefinitions())
                    .noneMatch(runtime -> runtime.plan().definitionId().equals(candidate.id()));
        }
    }

    /** How many events of the plan cache carried the operation and the outcome, summed over the nodes. */
    private static long loads(
            RecordingCacheObserver first,
            RecordingCacheObserver second,
            CacheOperation operation,
            CacheOutcome outcome) {
        return first.count(CACHE, operation, outcome) + second.count(CACHE, operation, outcome);
    }

    // --- Nodes ---

    private static PublicationNodeGraph.Node node(
            Vertx vertx, DefinitionScenario scenario, List<WorkflowDefinition<?, ?>> code) {
        return node(vertx, scenario, code, new RecordingCacheObserver());
    }

    private static PublicationNodeGraph.Node node(
            Vertx vertx,
            DefinitionScenario scenario,
            List<WorkflowDefinition<?, ?>> code,
            RecordingCacheObserver observer) {
        Boot boot = scenario.nodeOf(
                pool -> PublicationNodeGraph.node(vertx, pool, ExecutorPublicationIT.class, code, observer));
        return (PublicationNodeGraph.Node) boot.app();
    }

    /** Loads the candidate, or, when no load accepts it, stores it as an earlier release could have. */
    private static void store(DefinitionScenario scenario, WorkflowDefinitionService service, Candidate candidate) {
        if (candidate.loadable()) {
            load(service, candidate);
            return;
        }
        scenario.seedDocument(
                ACME,
                candidate.id(),
                1,
                "0".repeat(64),
                "CANDIDATE",
                new String(candidate.document(), StandardCharsets.UTF_8));
    }

    private static void load(WorkflowDefinitionService service, Candidate candidate) {
        await(service.load(ACME, candidate.document(), DocumentFormat.YAML, source(candidate.id(), LOADED_AT)));
    }

    private static StartCommand start(Candidate candidate) {
        return new StartCommand(
                candidate.id(),
                PublishContract.class.getName(),
                new PlaceOrderDoc("order-" + candidate.id()),
                "start-" + candidate.id(),
                null,
                null,
                1L,
                ACME,
                ACME);
    }

    /** The messages of the failure and of its causes, in order, in lower case with underscores read as spaces. */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        if (failure == null) return "no failure";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause.getMessage()).append(" | ");
        }
        return text.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // --- Fixtures ---

    /** An acme document, the rule that publishing it must name when it is refused, and its key. */
    private record Candidate(String id, byte[] document, String rule, boolean loadable) {

        Candidate(String id, byte[] document, String rule) {
            this(id, document, rule, true);
        }

        DefinitionKey key() {
            return new DefinitionKey(ACME, id, 1);
        }
    }

    /** The candidates of these tests. */
    private static final class Candidates {

        private Candidates() {}

        /** A document that waits for the approval signal and then completes. */
        static Candidate valid(String id) {
            return new Candidate(id, waitingDocument(id, "done", "inventory.reserved"), "");
        }

        /**
         * A document whose wait names a step that the document does not have. A load refuses it, so it is stored the
         * way an earlier release could have stored it.
         */
        static Candidate unknownTarget(String id) {
            return new Candidate(id, waitingDocument(id, "no-such-step", "inventory.reserved"), "no-such-step", false);
        }

        /** A document that waits for a signal that its contract does not declare. */
        static Candidate undeclaredSignal(String id) {
            return new Candidate(id, waitingDocument(id, "done", "no.such.signal"), "no.such.signal");
        }

        /** A document with a human task that declares a reminder, in a wiring with no module that carries events. */
        static Candidate reminderWithoutEvents(String id) {
            String yaml = """
                    definitionId: %s
                    definitionVersion: 1
                    stateType: %s
                    contract: %s
                    startPayloadType: %s
                    initialStateMapper: doc.order.fromPlaceOrder
                    initialStep: review
                    steps:
                      - id: review
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
                        reminders:
                          interval: PT1H
                      - id: done
                        type: complete
                    """.formatted(
                            id,
                            OrderDocState.class.getName(),
                            PublishContract.class.getName(),
                            PlaceOrderDoc.class.getName(),
                            PlaceOrderDoc.class.getName());
            return new Candidate(id, yaml.getBytes(StandardCharsets.UTF_8), "reminders");
        }

        /**
         * A document of the id that the default scope's code holds under the same contract with another state type,
         * which the document's state type does not extend.
         */
        static Candidate stateNotExtended(String id) {
            return new Candidate(id, waitingDocument(id, "done", "inventory.reserved"), "state type");
        }

        private static byte[] waitingDocument(String id, String next, String signal) {
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
                        signal: %s
                        payloadType: dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc
                        stateReducer: doc.order.applyInventoryReserved
                        next: %s
                      - id: done
                        type: complete
                    """.formatted(
                            id,
                            OrderDocState.class.getName(),
                            PublishContract.class.getName(),
                            PlaceOrderDoc.class.getName(),
                            signal,
                            next)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private record CodeState(String value) {}

    private record CodeStart(String value) {}

    /** A default-scope code definition whose state type is not the state type of the documents. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;

        private CodeWorkflow(String definitionId) {
            this.definitionId = definitionId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) PublishContract.class;
        }

        @Override
        public Class<CodeState> stateType() {
            return CodeState.class;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return 1;
        }

        @Override
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(CodeStart.class, start -> new CodeState(start.value()))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
