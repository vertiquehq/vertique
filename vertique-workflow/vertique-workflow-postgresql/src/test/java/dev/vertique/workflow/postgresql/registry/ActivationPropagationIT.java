// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
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
import dev.vertique.workflow.definition.service.CompiledDefinitionRef;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that an activation, a {@code follow} and a {@code deactivateDefault} committed on one node change which
 * version a start selects on every other node on the same database, through the peer's periodic reconciliation which
 * most tests trigger directly, and that activations survive a restart.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ActivationPropagationIT {

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface PropagationContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_activation_propagation_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String QA = "qa";
    private static final String ACME = "acme";
    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");
    private static final long STALENESS_SECONDS = 1;

    @Test
    @DisplayName("an activation on one node selects the version on a peer after one pass")
    void activationOnOneNodeSelectsVersionOnPeerAfterOnePass(Vertx vertx) {
        String id = "propagation-activation";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given nodes A and B whose code has version 1 and a version 2 that opts out, so version 1 is current
            Scenario a = scenario(database, boot(database, code(id, 1), optedOut(id, 2)));
            Scenario b = scenario(database, boot(database, code(id, 1), optedOut(id, 2)));

            // When A activates version 2 in qa
            await(a.service().activate(QA, id, DEFAULT, 2));

            // Then the next start in qa on B runs version 2 at once, because a start reads the database, and a start in
            // the default scope still runs version 1
            assertThat(b.start(QA, id)).isEqualTo(new Pin(QA, DEFAULT, id, 2));
            assertThat(b.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));

            // And a reconciliation of B changes nothing about what a start selects
            await(b.app().definitionPropagationPoller().pollNow());
            assertThat(b.start(QA, id)).isEqualTo(new Pin(QA, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("follow on one node removes the scope's pin on a peer")
    void followOnOneNodeRemovesScopePinOnPeer(Vertx vertx) {
        String id = "propagation-follow";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given nodes A and B, with qa activated on version 2 by A and applied by B
            Scenario a = scenario(database, boot(database, code(id, 1), optedOut(id, 2)));
            Scenario b = scenario(database, boot(database, code(id, 1), optedOut(id, 2)));
            await(a.service().activate(QA, id, DEFAULT, 2));
            await(b.app().definitionPropagationPoller().pollNow());
            Pin pinned = b.start(QA, id);

            // When A makes qa follow the default scope and B reconciles
            await(a.service().follow(QA, id));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then B ran version 2 before, and a start in qa on B now runs version 1, the default's
            assertThat(pinned).isEqualTo(new Pin(QA, DEFAULT, id, 2));
            assertThat(b.start(QA, id)).isEqualTo(new Pin(QA, DEFAULT, id, 1));
        }
    }

    @Test
    @DisplayName("a document published and activated on one node applies on a peer in one pass")
    void publishedDocumentAndItsActivationApplyInOnePass(Vertx vertx) {
        String id = "purchase-approval";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given nodes A and B whose code has purchase-approval version 1 current in the default scope
            Scenario a = scenario(database, boot(database, code(id, 1)));
            Scenario b = scenario(database, boot(database, code(id, 1)));

            // When A loads, publishes and activates the acme document of version 1, and B reconciles once
            CompiledDefinitionRef loaded = await(a.service()
                    .load(ACME, resource("activation-purchase-approval.workflow.yaml"), DocumentFormat.YAML, meta()));
            await(a.service().publish(new DefinitionKey(ACME, id, 1)));
            await(a.service().activate(ACME, id, ACME, 1));
            await(b.app().definitionPropagationPoller().pollNow());

            // Then a start in acme on B runs the acme document with its plan hash, and B reports no apply failure
            assertThat(b.start(ACME, id)).isEqualTo(new Pin(ACME, ACME, id, 1));
            assertThat(planHashOfStartedInstance(database, id, ACME)).isEqualTo(loaded.planHash());
            assertThat(b.app().definitionRegistryView().applyFailures()).isZero();
        }
    }

    @Test
    @DisplayName("activations survive a restart")
    void activationsSurviveRestart(Vertx vertx) {
        String id = "propagation-restart";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given node A with qa activated on version 2 of the default scope and acme on its own version 1
            Boot first = boot(database, code(id, 1), optedOut(id, 2));
            Scenario a = scenario(database, first);
            a.publish(ACME, id, 1);
            await(a.service().activate(QA, id, DEFAULT, 2));
            await(a.service().activate(ACME, id, ACME, 1));

            // When A closes and a new node starts on the same database, and one reconciliation has run, which restores
            // the records of the scopes other than the default one
            first.close();
            Scenario restarted = scenario(database, boot(database, code(id, 1), optedOut(id, 2)));
            await(restarted.app().definitionPropagationPoller().pollNow());

            // Then starts in qa and acme run the activated versions
            assertThat(restarted.start(QA, id)).isEqualTo(new Pin(QA, DEFAULT, id, 2));
            assertThat(restarted.start(ACME, id)).isEqualTo(new Pin(ACME, ACME, id, 1));
            assertThat(restarted.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));
        }
    }

    @Test
    @DisplayName("the peer's timer applies an activation within the staleness bound")
    void peerTimerAppliesActivationWithinStalenessBound(Vertx vertx) {
        String id = "propagation-timer";
        JsonObject config = new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("definitions", new JsonObject().put("maxStalenessSeconds", STALENESS_SECONDS)));

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given nodes A and B that reconcile every second, with version 1 current and a version 2 that opts out
            Scenario a = scenario(database, boot(database, config, code(id, 1), optedOut(id, 2)));
            Scenario b = scenario(database, boot(database, config, code(id, 1), optedOut(id, 2)));

            // When A activates version 2 in qa and the test never reconciles B
            await(a.service().activate(QA, id, DEFAULT, 2));
            long committed = System.nanoTime();

            // Then repeated starts in qa on B run version 2 before the bound plus slack has passed
            long deadline = committed + TimeUnit.SECONDS.toNanos(STALENESS_SECONDS + 3);
            Pin last = b.start(QA, id);
            while (last.version() != 2 && System.nanoTime() < deadline) last = b.start(QA, id);
            assertThat(last).isEqualTo(new Pin(QA, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("deactivateDefault on one node clears the default's current version on a peer")
    void deactivateDefaultOnOneNodeClearsDefaultCurrentOnPeer(Vertx vertx) {
        String id = "propagation-decom";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given nodes A and B whose code has version 1, auto-activated, so a start in qa on B runs it
            Scenario a = scenario(database, boot(database, code(id, 1)));
            Scenario b = scenario(database, boot(database, code(id, 1)));
            Pin beforeDeactivation = b.start(QA, id);

            // When A deactivates the default
            await(a.service().deactivateDefault(id));

            // Then B ran version 1 before, and its next starts in qa and in the default scope fail at once as having
            // no current version, because a start reads the database, creating no instance
            assertThat(beforeDeactivation).isEqualTo(new Pin(QA, DEFAULT, id, 1));
            long instances = database.instanceCount(id);
            Throwable qaStart = b.startFailure(QA, id);
            Throwable defaultStart = b.startFailure(DEFAULT, id);

            assertNoCurrentVersion(qaStart, QA, id);
            assertNoCurrentVersion(defaultStart, DEFAULT, id);
            assertThat(database.instanceCount(id)).isEqualTo(instances);
        }
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static Boot boot(DefinitionScenario database, WorkflowDefinition<?, ?>... code) {
        return database.node(none(), unobserved(), List.of(code));
    }

    private static Boot boot(DefinitionScenario database, JsonObject config, WorkflowDefinition<?, ?>... code) {
        return database.node(none(), unobserved(), List.of(code), config);
    }

    private static Scenario scenario(DefinitionScenario database, Boot node) {
        return new Scenario(database, node, PropagationContract.class);
    }

    private static BootObservation unobserved() {
        return new BootObservation("propagation-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static SourceMetadata meta() {
        return source("activation-purchase-approval", LOADED_AT);
    }

    private static byte[] resource(String name) {
        try (InputStream input =
                Thread.currentThread().getContextClassLoader().getResourceAsStream("definitions/it/" + name)) {
            if (input == null) throw new IllegalStateException("The document " + name + " is not on the classpath");
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the document " + name, e);
        }
    }

    /** The plan hash that the one instance of the id started in the scope is pinned to. */
    private static String planHashOfStartedInstance(DefinitionScenario database, String id, String startScope) {
        return await(database.admin()
                        .preparedQuery(
                                "SELECT plan_hash FROM workflow_instances WHERE definition_id = $1 AND scope_key = $2")
                        .execute(Tuple.of(id, startScope)))
                .iterator()
                .next()
                .getString("plan_hash");
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

    /** A code definition whose only step is its end step, optionally opting out of activation on deploy. */
    private static final class CodeWorkflow implements WorkflowDefinition<OrderDocState, Object> {

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
            return (Class<Object>) (Class<?>) PropagationContract.class;
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
        public boolean activateOnDeploy() {
            return activateOnDeploy;
        }

        @Override
        public void define(WorkflowBuilder<OrderDocState> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> OrderDocState.fromPayload(start))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
