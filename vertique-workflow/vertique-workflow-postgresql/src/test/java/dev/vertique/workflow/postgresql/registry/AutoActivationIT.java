// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.IdLockBarrier;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.PendingBoot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a code version or a packaged document version that first becomes available is activated in the
 * default scope in the same transaction, only when it does not opt out and is higher than the default's current
 * version, and that a restart neither repeats the activation nor undoes an operator's choice.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class AutoActivationIT {

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface AutoContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_auto_activation_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String QA = "qa";
    private static final String GLOBEX = "globex";
    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    @DisplayName("an opted-out version is available but not current")
    void optedOutVersionIsAvailableButNotCurrent(Vertx vertx) {
        String id = "auto-order";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given code version 1 and a version 2 that opts out of activation on deploy
            // When the component starts on an empty database
            Scenario scenario = scenario(database, boot(database, none(), code(id, 1), optedOut(id, 2)));

            // Then both rows are available, the default's record names version 1, and a start runs version 1
            assertThat(scenario.statuses(DEFAULT, id))
                    .containsEntry(1L, "AVAILABLE")
                    .containsEntry(2L, "AVAILABLE");
            assertThat(scenario.activations(DEFAULT, id))
                    .extracting(
                            DefinitionActivation::scopeKey,
                            DefinitionActivation::targetScopeKey,
                            DefinitionActivation::version)
                    .containsExactly(tuple(DEFAULT, DEFAULT, 1L));
            assertThat(scenario.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));
        }
    }

    @Test
    @DisplayName("a new version becomes the default's current version on its first publication")
    void newVersionBecomesDefaultCurrentOnFirstPublication(Vertx vertx) {
        String id = "auto-new-version";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a database booted once with version 1
            Boot first = boot(database, none(), code(id, 1));
            Scenario firstScenario = scenario(database, first);
            List<DefinitionActivation> afterFirstBoot = firstScenario.activations(DEFAULT, id);
            first.close();

            // When the component reboots with versions 1 and 2
            Scenario secondScenario = scenario(database, boot(database, none(), code(id, 1), code(id, 2)));

            // Then the record named version 1 after the first boot and names version 2 after the reboot
            assertThat(afterFirstBoot).extracting(DefinitionActivation::version).containsExactly(1L);
            assertThat(secondScenario.activations(DEFAULT, id))
                    .extracting(DefinitionActivation::version)
                    .containsExactly(2L);
            assertThat(secondScenario.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("an operator's activation survives a restart")
    void operatorActivationSurvivesRestart(Vertx vertx) {
        String id = "auto-operator";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given versions 1 and 2 booted, so that version 2 is current, and version 1 activated by an operator
            Boot first = boot(database, none(), code(id, 1), code(id, 2));
            Scenario firstScenario = scenario(database, first);
            await(firstScenario.service().activate(DEFAULT, id, DEFAULT, 1));
            List<DefinitionActivation> beforeRestart = firstScenario.activations(DEFAULT, id);
            first.close();

            // When the component restarts with the same code
            Scenario restarted = scenario(database, boot(database, none(), code(id, 1), code(id, 2)));

            // Then the record still names version 1 with the same activation time, and a start runs version 1
            assertThat(beforeRestart).extracting(DefinitionActivation::version).containsExactly(1L);
            assertThat(restarted.activations(DEFAULT, id)).isEqualTo(beforeRestart);
            assertThat(restarted.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));
        }
    }

    @Test
    @DisplayName("the highest first-published version wins in either registration order")
    void highestFirstPublishedVersionWinsInEitherOrder(Vertx vertx) {
        String ascending = "auto-order-ascending";
        String descending = "auto-order-descending";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, ascending, descending);

            // Given one component that contributes versions 1 and 2 in that order and another in the order 2 and 1
            // When each starts on an empty set of rows
            Scenario scenario = scenario(
                    database,
                    boot(
                            database,
                            none(),
                            code(ascending, 1),
                            code(ascending, 2),
                            code(descending, 2),
                            code(descending, 1)));

            // Then both ids have a default record that names version 2
            assertThat(scenario.activations(DEFAULT, ascending))
                    .extracting(DefinitionActivation::version)
                    .containsExactly(2L);
            assertThat(scenario.activations(DEFAULT, descending))
                    .extracting(DefinitionActivation::version)
                    .containsExactly(2L);
        }
    }

    @Test
    @DisplayName("a backported lower version stays available and not current")
    void backportedLowerVersionStaysNotCurrent(Vertx vertx) {
        String id = "auto-backport";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a database booted with version 2 only, so that the record names version 2
            Boot first = boot(database, none(), code(id, 2));
            Scenario firstScenario = scenario(database, first);
            List<DefinitionActivation> beforeBackport = firstScenario.activations(DEFAULT, id);
            first.close();

            // When the component reboots with versions 1 and 2
            Scenario rebooted = scenario(database, boot(database, none(), code(id, 1), code(id, 2)));

            // Then version 1 is available, and the record still names version 2 and was not rewritten
            assertThat(beforeBackport).extracting(DefinitionActivation::version).containsExactly(2L);
            assertThat(rebooted.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");
            assertThat(rebooted.activations(DEFAULT, id)).isEqualTo(beforeBackport);
        }
    }

    @Test
    @DisplayName("packaged documents auto-activate and documents loaded through the service do not")
    void packagedDocumentsAutoActivateAndServiceDocumentsDoNot(Vertx vertx) {
        String packaged = "pack-doc";
        String packagedOptedOut = "pack-off";
        String serviceLoaded = "svc-doc";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, packaged, packagedOptedOut, serviceLoaded);

            // Given a packaged document without a header and one with activateOnDeploy false
            WorkflowDefinitionSource documents = () -> List.of(
                    resource(ActivationFixtures.document(packaged, 1, AutoContract.class), packaged),
                    resource(
                            withHeader(
                                    ActivationFixtures.document(packagedOptedOut, 1, AutoContract.class),
                                    1,
                                    "activateOnDeploy: false"),
                            packagedOptedOut));

            // When the component starts
            Scenario scenario = scenario(database, boot(database, documents));

            // Then the first document is current, and the second is available without a record
            assertThat(scenario.activations(DEFAULT, packaged))
                    .extracting(DefinitionActivation::version)
                    .containsExactly(1L);
            assertThat(scenario.statuses(DEFAULT, packagedOptedOut)).containsEntry(1L, "AVAILABLE");
            assertThat(scenario.activations(DEFAULT, packagedOptedOut)).isEmpty();

            // When a document whose header says activateOnDeploy true is loaded through the service and published
            await(scenario.service()
                    .load(
                            DEFAULT,
                            withHeader(
                                    ActivationFixtures.document(serviceLoaded, 1, AutoContract.class),
                                    1,
                                    "activateOnDeploy: true"),
                            DocumentFormat.YAML,
                            source(serviceLoaded, LOADED_AT)));
            String afterLoad = scenario.statuses(DEFAULT, serviceLoaded).get(1L);
            await(scenario.service().publish(new DefinitionKey(DEFAULT, serviceLoaded, 1)));

            // Then it was a candidate after the load, and it is available after the publish, still without a record
            assertThat(afterLoad).isEqualTo("CANDIDATE");
            assertThat(scenario.statuses(DEFAULT, serviceLoaded)).containsEntry(1L, "AVAILABLE");
            assertThat(scenario.activations(DEFAULT, serviceLoaded)).isEmpty();
        }
    }

    @Test
    @DisplayName("a definition whose every version opts out leaves no current version")
    void everyVersionOptedOutLeavesNoCurrentVersion(Vertx vertx) {
        String id = "opt-all";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given versions 1 and 2 that both opt out
            // When the component starts and a start without a version runs in default and in qa
            Scenario scenario = scenario(database, boot(database, none(), optedOut(id, 1), optedOut(id, 2)));
            Throwable defaultStart = scenario.startFailure(DEFAULT, id);
            Throwable qaStart = scenario.startFailure(QA, id);

            // Then no record exists, both starts fail as having no current version, and no instance is written
            assertThat(scenario.activations(null, id)).isEmpty();
            assertNoCurrentVersion(defaultStart, DEFAULT, id);
            assertNoCurrentVersion(qaStart, QA, id);
            assertThat(database.instanceCount(id)).isZero();
        }
    }

    @Test
    @DisplayName("two nodes booting a new version write one default record")
    void concurrentFirstBootWritesOneDefaultRecord(Vertx vertx) {
        String id = "auto-concurrent";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a database booted with version 1, and the id lock held by the test
            boot(database, none(), code(id, 1)).close();
            WorkflowDefinitionSqlObserver observerA = new WorkflowDefinitionSqlObserver();
            WorkflowDefinitionSqlObserver observerB = new WorkflowDefinitionSqlObserver();
            IdLockBarrier barrier = IdLockBarrier.hold(database.admin(), id);
            try {
                // When two components with versions 1 and 2 start and queue on the lock
                PendingBoot nodeA =
                        database.begin(none(), unobserved(), List.of(code(id, 1), code(id, 2)), observerA, 10);
                PendingBoot nodeB =
                        database.begin(none(), unobserved(), List.of(code(id, 1), code(id, 2)), observerB, 10);
                barrier.awaitQueued(2);
                barrier.release();
                Boot a = nodeA.await();
                Boot b = nodeB.await();

                // Then both start steps completed, the record names version 2, and one node wrote it
                assertThat(a.failure()).isNull();
                assertThat(b.failure()).isNull();
                assertThat(defaultRecordVersion(database, id)).contains(2L);
                assertThat((observerA.activationWrites() > 0 ? 1 : 0) + (observerB.activationWrites() > 0 ? 1 : 0))
                        .as("nodes that wrote the default record")
                        .isEqualTo(1);
            } finally {
                barrier.release();
            }
        }
    }

    @Test
    @DisplayName("a staged version runs in qa while the default keeps version 1")
    void stagedVersionRunsInQaWhileDefaultKeepsVersionOne(Vertx vertx) {
        String id = "staged-order";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given version 1 and a version 2 that opts out, started on an empty database
            Scenario scenario = scenario(database, boot(database, none(), code(id, 1), optedOut(id, 2)));

            // When the default starts without a version
            Pin firstDefault = scenario.start(DEFAULT, id);

            // Then it runs version 1
            assertThat(firstDefault).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));

            // When qa activates the default's version 2 and qa starts
            await(scenario.service().activate(QA, id, DEFAULT, 2));
            Pin qa = scenario.start(QA, id);

            // Then qa runs version 2
            assertThat(qa).isEqualTo(new Pin(QA, DEFAULT, id, 2));

            // When the default starts on the explicit version 2
            Pin explicit = scenario.startVersion(DEFAULT, id, 2);

            // Then it runs version 2
            assertThat(explicit).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));

            // When the default activates version 2 and globex starts without a version
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 2));
            Pin globex = scenario.start(GLOBEX, id);

            // Then globex follows the default to version 2
            assertThat(globex).isEqualTo(new Pin(GLOBEX, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("a deactivated default fails starts immediately and stays cleared across a restart")
    void deactivatedDefaultFailsStartsImmediatelyAndStaysClearedAcrossRestart(Vertx vertx) {
        String id = "decom-auto";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given version 1 booted on an empty database, so that the default's record names it
            Boot first = boot(database, none(), code(id, 1));
            Scenario firstScenario = scenario(database, first);

            // When the default is deactivated and starts run in default and in qa
            await(firstScenario.service().deactivateDefault(id));
            Throwable defaultStart = firstScenario.startFailure(DEFAULT, id);
            Throwable qaStart = firstScenario.startFailure(QA, id);

            // Then both fail as having no current version although the version is still available
            assertNoCurrentVersion(defaultStart, DEFAULT, id);
            assertNoCurrentVersion(qaStart, QA, id);
            assertThat(firstScenario.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");

            // When the component restarts with the same code and the two starts run again
            first.close();
            Scenario restarted = scenario(database, boot(database, none(), code(id, 1)));
            Throwable restartedDefaultStart = restarted.startFailure(DEFAULT, id);
            Throwable restartedQaStart = restarted.startFailure(QA, id);

            // Then both still fail, no record exists, and no instance was written
            assertNoCurrentVersion(restartedDefaultStart, DEFAULT, id);
            assertNoCurrentVersion(restartedQaStart, QA, id);
            assertThat(restarted.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");
            assertThat(restarted.activations(null, id)).isEmpty();
            assertThat(defaultRecordVersion(database, id)).isEmpty();
            assertThat(database.instanceCount(id)).isZero();
        }
    }

    @Test
    @DisplayName("a failure between the publication and the activation leaves neither")
    void failureBetweenInsertAndActivationLeavesNeither(Vertx vertx) {
        String id = "atomic-order";
        WorkflowDefinitionSqlObserver observer = new WorkflowDefinitionSqlObserver();

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given code version 1, and a store whose statement that writes the default record fails once
            observer.failNextActivationWrite();

            // When the component starts on an empty database
            Boot failed = database.attempt(none(), unobserved(), List.of(code(id, 1)), observer, 10);

            // Then the injected failure was met, the start failed, and neither a row nor a record was left
            assertThat(observer.injectedActivationFailures()).isEqualTo(1);
            assertThat(failed.failure()).isNotNull();
            assertThat(database.rowCount(id)).isZero();
            assertThat(defaultRecordVersion(database, id)).isEmpty();
            failed.close();

            // When the component starts again without a failure
            Scenario scenario = scenario(database, boot(database, none(), code(id, 1)));

            // Then the version is available and the record names it, published and activated together
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");
            assertThat(scenario.activations(DEFAULT, id))
                    .extracting(DefinitionActivation::version)
                    .containsExactly(1L);
        }
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static Boot boot(
            DefinitionScenario database, WorkflowDefinitionSource source, WorkflowDefinition<?, ?>... code) {
        return database.node(source, unobserved(), List.of(code));
    }

    private static Scenario scenario(DefinitionScenario database, Boot node) {
        return new Scenario(database, node, AutoContract.class);
    }

    private static BootObservation unobserved() {
        return new BootObservation("auto-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static DefinitionResource resource(byte[] document, String label) {
        return new DefinitionResource(document, DocumentFormat.YAML, source("packaged-" + label, LOADED_AT));
    }

    /** The document with one more header line after its version line. */
    private static byte[] withHeader(byte[] document, long version, String header) {
        String text = new String(document, StandardCharsets.UTF_8);
        String anchor = "definitionVersion: " + version + "\n";
        if (!text.contains(anchor)) throw new IllegalStateException("The document has no version line");
        return text.replace(anchor, anchor + header + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The version that the default scope's record of the id names, read straight from the table. */
    private static Optional<Long> defaultRecordVersion(DefinitionScenario database, String id) {
        for (Row row : await(database.admin().preparedQuery("""
                        SELECT definition_version FROM workflow_definition_activations
                        WHERE scope_key = 'default' AND definition_id = $1
                        """).execute(Tuple.of(id)))) {
            return Optional.of(row.getLong("definition_version"));
        }
        return Optional.empty();
    }

    private static void assertNoCurrentVersion(Throwable failure, String startScope, String id) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowNoCurrentVersionException.class, refusal -> {
            assertThat(refusal.scopeKey()).isEqualTo(startScope);
            assertThat(refusal.definitionId()).isEqualTo(id);
        });
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
            return (Class<Object>) (Class<?>) AutoContract.class;
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
