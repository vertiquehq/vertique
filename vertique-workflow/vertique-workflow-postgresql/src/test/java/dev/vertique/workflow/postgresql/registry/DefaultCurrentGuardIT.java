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
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Fixture;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.IdLockBarrier;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Node;
import dev.vertique.workflow.postgresql.registry.DefinitionRaceFixtures.Stored;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that the default scope's current version cannot be deprecated or retired while the default's record names
 * it, including against a concurrent activation, and that clearing the default's record allows decommissioning.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DefaultCurrentGuardIT {

    /** The one contract that every document of these tests names. */
    @WorkflowContract
    public interface GuardContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_default_current_guard_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String QA = "qa";
    private static final String GLOBEX = "globex";

    @Test
    @DisplayName("deprecating the default current version is refused until another version is activated")
    void deprecatingDefaultCurrentIsRefusedUntilAnotherIsActivated(Vertx vertx) {
        String id = "guard-deprecate-current";
        DefinitionKey versionOne = new DefinitionKey(DEFAULT, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given versions 1 and 2 published, the default's record on version 1, and no record for qa or globex
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 2);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));

            // When version 1 is deprecated
            Throwable refused =
                    ActivationFixtures.failureOf(() -> scenario.service().deprecate(versionOne));

            // Then it is refused as an invalid transition naming the key, and the row stays available
            assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(refusal.key()).isEqualTo(versionOne);
            });
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");

            // When version 2 becomes the default's current version and version 1 is deprecated again
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 2));
            await(scenario.service().deprecate(versionOne));

            // Then the deprecation succeeds and the record still targets version 2
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "DEPRECATED");
            assertThat(scenario.activations(DEFAULT, id))
                    .extracting(DefinitionActivation::targetScopeKey, DefinitionActivation::version)
                    .containsExactly(tuple(DEFAULT, 2L));
            assertThat(scenario.activations(QA, id)).isEmpty();
            assertThat(scenario.activations(GLOBEX, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("deprecating a version only a scope pins succeeds and that scope's starts fail clearly")
    void deprecatingVersionOnlyAScopePinsSucceedsAndItsStartsFail(Vertx vertx) {
        String id = "guard-scope-pin";
        DefinitionKey versionOne = new DefinitionKey(DEFAULT, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given the default's record on version 2 and qa's record on the default's version 1
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 2);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 2));
            await(scenario.service().activate(QA, id, DEFAULT, 1));

            // When version 1 is deprecated and qa starts without a version
            Throwable deprecation =
                    ActivationFixtures.failureOf(() -> scenario.service().deprecate(versionOne));
            Throwable qaStart = scenario.startFailure(QA, id);

            // Then the deprecation succeeds, qa's start fails as a deprecated version, and no instance is created
            assertThat(deprecation).isNull();
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "DEPRECATED");
            assertThat(qaStart).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.DEPRECATED);
                assertThat(refusal.key()).isEqualTo(versionOne);
            });
            assertThat(database.instanceCount(id)).isZero();

            // And a start in the default scope still runs version 2
            assertThat(scenario.start(DEFAULT, id)).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("a deprecation and an activation queued on the id lock leave the record on an available target")
    void deprecationAndActivationSerializeUnderTheIdLock(Vertx vertx) {
        String id = "guard-serialize";
        DefinitionKey versionOne = new DefinitionKey(DEFAULT, id, 1);

        try (Fixture fixture = new Fixture(vertx, db)) {
            // Given two nodes, versions 1 and 2 published, and the default's record on version 1
            Node publisher = fixture.node();
            Node activator = fixture.node();
            ActivationFixtures.clearActivations(fixture.admin(), id);
            fixture.ownRows(id);
            fixture.onClose(() -> ActivationFixtures.clearActivations(fixture.admin(), id));
            for (long version = 1; version <= 2; version++) {
                await(publisher
                        .service()
                        .load(
                                DEFAULT,
                                ActivationFixtures.document(id, version, GuardContract.class),
                                DocumentFormat.YAML,
                                source("serialize-" + version, Instant.parse("2026-10-21T12:00:00Z"))));
                await(publisher.service().publish(new DefinitionKey(DEFAULT, id, version)));
            }
            await(publisher.service().activate(DEFAULT, id, DEFAULT, 1));

            // And a deprecation of version 1 and an activation of version 2 are both queued behind the id lock
            IdLockBarrier barrier = fixture.holdIdLock(id);
            Future<Void> deprecationCall = publisher.service().deprecate(versionOne);
            Future<Void> activationCall = activator.service().activate(DEFAULT, id, DEFAULT, 2);
            barrier.awaitQueued(2);

            // When the lock is released
            barrier.release();
            Throwable activationFailure = DefinitionRaceFixtures.failureOf(activationCall);
            Throwable deprecationFailure = DefinitionRaceFixtures.failureOf(deprecationCall);

            // Then the activation succeeds
            assertThat(activationFailure).isNull();

            // And the deprecation either ran second and succeeded, or ran first and was refused as invalid
            if (deprecationFailure != null) {
                assertThat(deprecationFailure)
                        .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                            assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                            assertThat(refusal.key()).isEqualTo(versionOne);
                        });
            }

            // And in both cases the default's record targets version 2 and version 2 is still available
            List<DefinitionActivation> records = await(activator.service().activations(DEFAULT)).stream()
                    .filter(activation -> activation.definitionId().equals(id))
                    .toList();
            assertThat(records)
                    .extracting(DefinitionActivation::targetScopeKey, DefinitionActivation::version)
                    .containsExactly(tuple(DEFAULT, 2L));
            assertThat(fixture.stored(id))
                    .extracting(Stored::version, Stored::status)
                    .containsExactly(
                            tuple(1L, deprecationFailure == null ? "DEPRECATED" : "AVAILABLE"), tuple(2L, "AVAILABLE"));
        }
    }

    @Test
    @DisplayName("deactivating the default clears its record, then deprecate and retire succeed")
    void deactivateDefaultClearsDefaultCurrentThenDeprecateAndRetireSucceed(Vertx vertx) {
        String id = "decom-order";
        DefinitionKey key = new DefinitionKey(DEFAULT, id, 1);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given version 1 as the only version, published and activated in the default scope
            Boot node = database.node(none(), new BootObservation("guard-unused", 1, "unused", false));
            Scenario scenario = new Scenario(database, node, GuardContract.class);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));

            // When version 1 is deprecated while it is the default's current version
            Throwable refused =
                    ActivationFixtures.failureOf(() -> scenario.service().deprecate(key));

            // Then it is refused as an invalid transition naming the key
            assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
                assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
                assertThat(refusal.key()).isEqualTo(key);
            });

            // When the default is deactivated for the id
            await(scenario.service().deactivateDefault(id));

            // Then no record of the id remains
            assertThat(scenario.activations(null, id)).isEmpty();

            // When version 1 is deprecated, its status time is moved past the cooling bound, and it is retired
            await(scenario.service().deprecate(key));
            long coolingSeconds = node.app().workflowDefinitionsConfig().maxStalenessSeconds();
            database.ageStatus(key, coolingSeconds + 60);
            await(scenario.service().retire(key));

            // Then the row is retired
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "RETIRED");

            // When a start without a version runs in qa and in the default scope
            Throwable qa = scenario.startFailure(QA, id);
            Throwable defaultScope = scenario.startFailure(DEFAULT, id);

            // Then both fail typed naming the start scope and the id, and no instance was written
            assertNoCurrentVersion(qa, QA, id);
            assertNoCurrentVersion(defaultScope, DEFAULT, id);
            assertThat(database.instanceCount(id)).isZero();
        }
    }

    private static void assertNoCurrentVersion(Throwable failure, String startScope, String id) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowNoCurrentVersionException.class, refusal -> {
            assertThat(refusal.scopeKey()).isEqualTo(startScope);
            assertThat(refusal.definitionId()).isEqualTo(id);
        });
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }

    private static Scenario scenario(DefinitionScenario database) {
        Boot node = database.node(none(), new BootObservation("guard-unused", 1, "unused", false));
        return new Scenario(database, node, GuardContract.class);
    }
}
