// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the per-scope activation records: which version a start without a version runs, how a scope's record is
 * written, replaced and cleared, and which activations are refused.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ActivationRecordsIT {

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface ActivationContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_activation_records_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    private static final String QA = "qa";

    @Test
    @DisplayName("an unconfigured scope follows the default's record and moves with it")
    void unconfiguredScopeFollowsDefaultAndMovesWithIt(Vertx vertx) {
        String id = "act-follow-default";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given versions 1 and 2 published in the default scope and the default activated on version 1
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 2);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));

            // When a start runs in qa, the default moves to version 2, and qa and the default start again
            Pin firstQa = scenario.start(QA, id);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 2));
            Pin laterQa = scenario.start(QA, id);
            Pin laterDefault = scenario.start(DEFAULT, id);

            // Then qa ran version 1 and then moved with the default to version 2, without a record of its own
            assertThat(firstQa).isEqualTo(new Pin(QA, DEFAULT, id, 1));
            assertThat(laterQa).isEqualTo(new Pin(QA, DEFAULT, id, 2));
            assertThat(laterDefault).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));
            assertThat(scenario.activations(QA, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("a scope's activation of a default version selects it until the scope follows again")
    void scopeActivationOfDefaultVersionSelectsItUntilFollow(Vertx vertx) {
        String id = "act-scope-default-version";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given version 1 current in the default scope and version 2 published but not current
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 2);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));

            // When qa activates the default's version 2, and qa and the default start
            await(scenario.service().activate(QA, id, DEFAULT, 2));
            Pin qa = scenario.start(QA, id);
            Pin defaultScope = scenario.start(DEFAULT, id);

            // Then qa runs version 2 while the default keeps version 1, and both records are listed
            assertThat(qa).isEqualTo(new Pin(QA, DEFAULT, id, 2));
            assertThat(defaultScope).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 1));
            assertThat(scenario.activations(null, id))
                    .extracting(
                            DefinitionActivation::scopeKey,
                            DefinitionActivation::targetScopeKey,
                            DefinitionActivation::version)
                    .containsExactlyInAnyOrder(tuple(DEFAULT, DEFAULT, 1L), tuple(QA, DEFAULT, 2L));

            // When qa follows again and starts
            await(scenario.service().follow(QA, id));
            Pin followed = scenario.start(QA, id);

            // Then qa runs the default's version 1 and its record is gone
            assertThat(followed).isEqualTo(new Pin(QA, DEFAULT, id, 1));
            assertThat(scenario.activations(QA, id)).isEmpty();
        }
    }

    @Test
    @DisplayName("a scope's activation of its own version selects it and the other scopes keep the default")
    void scopeActivationOfOwnVersionSelectsIt(Vertx vertx) {
        String id = "act-own-version";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given version 1 current in the default scope and acme's own version 1 with another step graph
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));
            scenario.publishOwnGraph(ACME, id, 1);

            // When acme activates its own version and acme and globex start
            await(scenario.service().activate(ACME, id, ACME, 1));
            Pin acme = scenario.start(ACME, id);
            Pin globex = scenario.start(GLOBEX, id);

            // Then acme runs its own version and globex the default's
            assertThat(acme).isEqualTo(new Pin(ACME, ACME, id, 1));
            assertThat(globex).isEqualTo(new Pin(GLOBEX, DEFAULT, id, 1));

            // When acme follows again and starts
            await(scenario.service().follow(ACME, id));
            Pin followed = scenario.start(ACME, id);

            // Then acme runs the default's version 1
            assertThat(followed).isEqualTo(new Pin(ACME, DEFAULT, id, 1));
        }
    }

    @Test
    @DisplayName("invalid activations are refused and change nothing")
    void invalidActivationsAreRefusedAndChangeNothing(Vertx vertx) {
        String id = "act-invalid";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given version 1 current, version 2 a candidate and version 3 deprecated in the default scope,
            // and globex's own published version 1
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 3);
            scenario.load(DEFAULT, id, 2);
            await(scenario.service().deprecate(new DefinitionKey(DEFAULT, id, 3)));
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));
            scenario.publishOwnGraph(GLOBEX, id, 1);
            List<DefinitionActivation> before = scenario.activations(null, id);
            assertThat(before).hasSize(1);

            // When each invalid call is made
            Throwable candidate = failure(() -> scenario.service().activate(QA, id, DEFAULT, 2));
            Throwable deprecated = failure(() -> scenario.service().activate(QA, id, DEFAULT, 3));
            Throwable otherScope = failure(() -> scenario.service().activate(ACME, id, GLOBEX, 1));
            Throwable defaultToOther = failure(() -> scenario.service().activate(DEFAULT, id, GLOBEX, 1));
            Throwable followDefault = failure(() -> scenario.service().follow(DEFAULT, id));

            // Then a target that is not available is refused as an invalid transition naming the target key
            assertRefusedTarget(candidate, new DefinitionKey(DEFAULT, id, 2), id);
            assertRefusedTarget(deprecated, new DefinitionKey(DEFAULT, id, 3), id);

            // And a target scope that is neither the scope nor the default, and following the default, are
            // refused as illegal arguments
            assertThat(otherScope).isInstanceOf(IllegalArgumentException.class);
            assertThat(defaultToOther).isInstanceOf(IllegalArgumentException.class);
            assertThat(followDefault).isInstanceOf(IllegalArgumentException.class);

            // And no record changed
            assertThat(scenario.activations(null, id)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("reinstating a deprecated version makes it available and leaves the current version alone")
    void reinstateOfDeprecatedVersionMakesItAvailableNotCurrent(Vertx vertx) {
        String id = "act-reinstate";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given versions 1 and 2 published, version 2 current by record and version 1 deprecated
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(DEFAULT, id, 1, 2);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 2));
            await(scenario.service().deprecate(new DefinitionKey(DEFAULT, id, 1)));

            // When version 1 is reinstated and a start runs without a version
            await(scenario.service().reinstate(new DefinitionKey(DEFAULT, id, 1)));
            Pin start = scenario.start(DEFAULT, id);

            // Then version 1 is available, the record still targets version 2, and the start runs version 2
            assertThat(scenario.statuses(DEFAULT, id)).containsEntry(1L, "AVAILABLE");
            assertThat(scenario.activations(DEFAULT, id))
                    .extracting(DefinitionActivation::targetScopeKey, DefinitionActivation::version)
                    .containsExactly(tuple(DEFAULT, 2L));
            assertThat(start).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));
        }
    }

    @Test
    @DisplayName("a start without a current version fails typed and never borrows another scope's version")
    void startWithoutCurrentVersionFailsTyped(Vertx vertx) {
        String id = "act-acme-only";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given a document published and activated in acme only, with no default-scope row of the id
            Scenario scenario = scenario(database);
            scenario.own(id);
            scenario.publish(ACME, id, 1);
            await(scenario.service().activate(ACME, id, ACME, 1));

            // When a start without a version runs in globex and in the default scope
            Throwable globex = scenario.startFailure(GLOBEX, id);
            Throwable defaultScope = scenario.startFailure(DEFAULT, id);

            // Then both fail typed naming the start scope and the id, and no instance was written
            assertNoCurrentVersion(globex, GLOBEX, id);
            assertNoCurrentVersion(defaultScope, DEFAULT, id);
            assertThat(database.instanceCount(id)).isZero();

            // When the same start runs in acme
            Pin acme = scenario.start(ACME, id);

            // Then it runs acme's own version
            assertThat(acme).isEqualTo(new Pin(ACME, ACME, id, 1));
        }
    }

    @Test
    @DisplayName("a code definition activated in a scope runs there without a load")
    void codeDefinitionActivatedInScopeRunsThereWithoutALoad(Vertx vertx) {
        String id = "act-code";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given code versions 1 and 2 registered at startup, and nothing loaded into qa
            ActivationFixtures.clearActivations(database.admin(), id);
            database.ownRows(id);
            database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), id));
            Boot node = database.node(
                    none(),
                    new BootObservation("activation-unused", 1, "unused", false),
                    List.of(new CodeWorkflow(id, 1), new CodeWorkflow(id, 2)));
            Scenario scenario = new Scenario(database, node, ActivationContract.class);

            // When qa activates the default's version 1, and qa and the default start without a version
            await(scenario.service().activate(QA, id, DEFAULT, 1));
            Pin qa = scenario.start(QA, id);
            Pin defaultScope = scenario.start(DEFAULT, id);

            // Then qa runs version 1 in its own start scope, the default runs the highest version, and qa owns no row
            assertThat(qa).isEqualTo(new Pin(QA, DEFAULT, id, 1));
            assertThat(defaultScope).isEqualTo(new Pin(DEFAULT, DEFAULT, id, 2));
            assertThat(await(scenario.service().list(new DefinitionFilter(QA, null, null))))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("deactivating the default leaves other records alone and is a no-op without a record")
    void deactivateDefaultLeavesOtherRecordsAndIsNoOpWithoutRecord(Vertx vertx) {
        String id = "act-deactivate";
        String otherId = "act-deactivate-other";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given a default record on version 1 and a qa record on the default's version 2 of one id,
            // no record for acme, and a second id without a default record
            Scenario scenario = scenario(database);
            scenario.own(id, otherId);
            scenario.publish(DEFAULT, id, 1, 2);
            scenario.publish(DEFAULT, otherId, 1);
            await(scenario.service().activate(DEFAULT, id, DEFAULT, 1));
            await(scenario.service().activate(QA, id, DEFAULT, 2));
            List<DefinitionActivation> before = scenario.activations(null, id);
            assertThat(before).hasSize(2);

            // When the default is deactivated for the id without a record
            Throwable noRecord = failure(() -> scenario.service().deactivateDefault(otherId));

            // Then it succeeds and no record changed
            assertThat(noRecord).isNull();
            assertThat(scenario.activations(null, id)).isEqualTo(before);
            assertThat(scenario.activations(null, otherId)).isEmpty();

            // When following is requested for the default scope
            Throwable follow = failure(() -> scenario.service().follow(DEFAULT, id));

            // Then it is refused as an illegal argument and no record changed
            assertThat(follow).isInstanceOf(IllegalArgumentException.class);
            assertThat(scenario.activations(null, id)).isEqualTo(before);

            // When the default is deactivated for the id that has a default record
            Throwable cleared = failure(() -> scenario.service().deactivateDefault(id));

            // Then the default record is gone and the qa record is unchanged
            assertThat(cleared).isNull();
            assertThat(scenario.activations(null, id))
                    .extracting(
                            DefinitionActivation::scopeKey,
                            DefinitionActivation::targetScopeKey,
                            DefinitionActivation::version)
                    .containsExactly(tuple(QA, DEFAULT, 2L));
            assertThat(scenario.activations(QA, id))
                    .containsExactlyElementsOf(before.stream()
                            .filter(activation -> activation.scopeKey().equals(QA))
                            .toList());
        }
    }

    private static Throwable failure(Supplier<Future<Void>> call) {
        return ActivationFixtures.failureOf(call::get);
    }

    private static void assertRefusedTarget(Throwable failure, DefinitionKey target, String id) {
        assertThat(failure).isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, refusal -> {
            assertThat(refusal.reason()).isEqualTo(Reason.INVALID_TRANSITION);
            assertThat(refusal.key()).isEqualTo(target);
            assertThat(refusal.getMessage()).contains(id);
        });
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
        Boot node = database.node(none(), new BootObservation("activation-unused", 1, "unused", false));
        return new Scenario(database, node, ActivationContract.class);
    }

    private record CodeState(String value) {}

    /** A code definition whose only step is its end step. */
    private static final class CodeWorkflow implements WorkflowDefinition<CodeState, Object> {

        private final String definitionId;
        private final long version;

        private CodeWorkflow(String definitionId, long version) {
            this.definitionId = definitionId;
            this.version = version;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) ActivationContract.class;
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
        public void define(WorkflowBuilder<CodeState> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> new CodeState(start.orderId()))
                    .initialStep("done")
                    .complete("done");
        }
    }
}
