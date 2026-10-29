// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.ActivationChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionOperation;
import dev.vertique.workflow.state.HistoryActor;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what the registry reports to a lifecycle listener against a real database: the changes of a sequence of
 * lifecycle calls, a refused transition, and the versions that startup records, among them the difference between an
 * insert that became the default and one that left a higher default as it was.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DefinitionLifecycleEventsIT {

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface EventsContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_events_it")
            .withMigration("classpath:db/migration/workflow");

    private static final HistoryActor BOOTSTRAP = new HistoryActor(HistoryActor.Kind.SYSTEM, null, "bootstrap");

    @Test
    @DisplayName("a publish, activate, deprecate, reinstate and retire sequence reports one change per call")
    void aSequenceOfCallsIsReportedInOrder(Vertx vertx) {
        String id = "events-sequence";
        String doc = "events-sequence-doc";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id, doc);
            Scenario scenario = scenario(database, boot(database, code(id, 1), code(id, 2)));
            RecordingDefinitionListener events = scenario.app().definitionEvents();
            events.clear();

            // Given code versions 1 and 2 recorded by startup, and a document candidate of another definition
            scenario.load(DEFAULT, doc, 1);

            // When the document is published and activated, and code version 1 is deprecated, reinstated, deprecated
            // and
            // retired
            await(scenario.service().publish(key(doc, 1)));
            await(scenario.service().activate(DEFAULT, doc, DEFAULT, 1));
            await(scenario.service().deprecate(key(id, 1)));
            await(scenario.service().reinstate(key(id, 1)));
            await(scenario.service().deprecate(key(id, 1)));
            await(scenario.service().retire(key(id, 1)));

            // Then each call was reported once, in order, with its own change id and no failure
            List<DefinitionLifecycleChange> changes = events.changes();
            assertThat(changes)
                    .extracting(DefinitionLifecycleChangeAssert::describe)
                    .containsExactly(
                            "PUBLISH " + doc + "@1",
                            "ACTIVATE " + doc + "@1",
                            "DEPRECATE " + id + "@1",
                            "REINSTATE " + id + "@1",
                            "DEPRECATE " + id + "@1",
                            "RETIRE " + id + "@1");
            assertThat(changes).extracting(DefinitionLifecycleChange::changeId).doesNotHaveDuplicates();
            ActivationChange activation = (ActivationChange) changes.get(1);
            assertThat(activation.scopeKey()).isEqualTo(DEFAULT);
            assertThat(activation.targetScopeKey()).isEqualTo(DEFAULT);
            assertThat(activation.version()).isEqualTo(1L);
            assertThat(changes)
                    .allSatisfy(change -> assertThat(change.actor().kind()).isEqualTo(HistoryActor.Kind.NONE));
            assertThat(events.failures()).isEmpty();
        }
    }

    @Test
    @DisplayName("a transition the registry refuses is reported as a failure with its reason")
    void aRefusedTransitionIsReportedAsAFailure(Vertx vertx) {
        String id = "events-refused";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);
            Scenario scenario = scenario(database, boot(database, code(id, 1)));
            RecordingDefinitionListener events = scenario.app().definitionEvents();
            events.clear();

            // Given version 1, which is the default's current version
            // When it is deprecated, and a version that does not exist is retired
            Throwable current = DefinitionScenario.failureOf(scenario.service().deprecate(key(id, 1)));
            Throwable missing = DefinitionScenario.failureOf(scenario.service().retire(key(id, 9)));

            // Then both refusals reach the caller and are reported as failures of the same operations, with no change
            assertThat(current).isNotNull();
            assertThat(missing).isNotNull();
            assertThat(events.changes()).isEmpty();
            assertThat(events.failures()).hasSize(2);
            assertThat(events.failures().get(0).reason()).isEqualTo(Reason.INVALID_TRANSITION);
            assertThat(((VersionChange) events.failures().get(0).attempted()).operation())
                    .isEqualTo(VersionOperation.DEPRECATE);
            assertThat(events.failures().get(1).reason()).isEqualTo(Reason.INVALID_TRANSITION);
            assertThat(((VersionChange) events.failures().get(1).attempted()).key())
                    .isEqualTo(key(id, 9));
            assertThat(events.failures().get(1).errorType()).isEqualTo("WorkflowDefinitionLifecycleException");
        }
    }

    @Test
    @DisplayName("startup reports a first publication, and an activation only when the default took effect")
    void startupReportsFirstPublications(Vertx vertx) {
        String id = "events-bootstrap";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a first start with version 2 only
            Boot first = boot(database, code(id, 2));
            List<DefinitionLifecycleChange> firstStart =
                    first.app().definitionEvents().changes();
            first.close();

            // When the component restarts with versions 1 and 2, and a version 3 that opts out of activation
            Boot second = boot(database, code(id, 1), code(id, 2), optedOut(id, 3));
            List<DefinitionLifecycleChange> secondStart =
                    second.app().definitionEvents().changes();

            // Then the first start published and activated version 2, by the system with the reason bootstrap
            assertThat(firstStart)
                    .extracting(DefinitionLifecycleChangeAssert::describe)
                    .containsExactly("PUBLISH " + id + "@2", "ACTIVATE " + id + "@2");
            assertThat(firstStart).allSatisfy(change -> {
                assertThat(change.actor()).isEqualTo(BOOTSTRAP);
                assertThat(change.attribution()).isEqualTo(HistoryAttribution.VERIFIED);
            });
            // ... and the restart published the lower and the opted-out version without activating either, and
            // reported nothing for the version it already held
            assertThat(secondStart)
                    .extracting(DefinitionLifecycleChangeAssert::describe)
                    .containsExactlyInAnyOrder("PUBLISH " + id + "@1", "PUBLISH " + id + "@3");
            assertThat(second.app().definitionEvents().failures()).isEmpty();
        }
    }

    /** Names a change by its operation and the version or scope it concerns. */
    private static final class DefinitionLifecycleChangeAssert {
        static String describe(DefinitionLifecycleChange change) {
            return switch (change) {
                case VersionChange version ->
                    version.operation() + " " + version.key().definitionId() + "@"
                            + version.key().definitionVersion();
                case ActivationChange activation ->
                    activation.operation() + " " + activation.definitionId() + "@" + activation.version();
            };
        }
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static DefinitionKey key(String id, long version) {
        return new DefinitionKey(DEFAULT, id, version);
    }

    private static Boot boot(DefinitionScenario database, WorkflowDefinition<?, ?>... code) {
        WorkflowDefinitionSource none = List::of;
        return database.node(none, new BootObservation("events-unused", 1, "unused", false), List.of(code));
    }

    private static Scenario scenario(DefinitionScenario database, Boot node) {
        return new Scenario(database, node, EventsContract.class);
    }

    private static WorkflowDefinition<?, ?> code(String definitionId, long version) {
        return new CodeWorkflow(definitionId, version, true);
    }

    private static WorkflowDefinition<?, ?> optedOut(String definitionId, long version) {
        return new CodeWorkflow(definitionId, version, false);
    }

    private record CodeState(String value) {}

    /** A code definition whose only step is its end step, optionally opting out of activation on deploy. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;
        private final boolean activateOnDeploy;

        private CodeWorkflow(String definitionId, long version, boolean activateOnDeploy) {
            this.definitionId = definitionId;
            this.version = version;
            this.activateOnDeploy = activateOnDeploy;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) EventsContract.class;
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
            return version;
        }

        @Override
        public boolean activateOnDeploy() {
            return activateOnDeploy;
        }

        @Override
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> new CodeState(start.orderId()))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
