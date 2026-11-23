// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies against a real database that the registry keeps who made the last change of a definition row and of an
 * activation record, next to the time of that change, and keeps nothing older: each registration, lifecycle call and
 * startup write names its caller, a later change replaces the earlier actor, a refused change leaves the actor as it
 * was, and a call with no identity clears the actor of an earlier change.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DefinitionLastChangeActorIT {

    /** The one contract that every document and code definition of these tests names. */
    @WorkflowContract
    public interface ActorContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_last_actor_it")
            .withMigration("classpath:db/migration/workflow");

    private static final WorkflowAttribution BOOTSTRAP = WorkflowAttribution.system("bootstrap");

    @Test
    @DisplayName("a document is attributed from its registration through publication, activation and retirement")
    void aDocumentIsAttributedThroughItsLifecycle(Vertx vertx) {
        String doc = "last-actor-doc";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, doc);
            Scenario scenario = scenario(database, boot(database));
            DefinitionKey key = key(doc, 1);

            // Given a document registered by a user
            Instant before = Instant.now().minusSeconds(1);
            as(vertx, scenario, user("alice"), () -> scenario.service()
                    .load(
                            DEFAULT,
                            ActivationFixtures.document(doc, 1, ActorContract.class),
                            DocumentFormat.YAML,
                            DefinitionScenario.source("last-actor", Instant.now())));

            // Then the candidate names the user and the time of the registration
            WorkflowDefinitionRecord candidate = record(scenario, key);
            assertThat(candidate.status()).isEqualTo(DefinitionStatus.CANDIDATE);
            assertThat(candidate.statusChangedBy()).isEqualTo(attributedTo(new WorkflowActor.User("alice")));
            assertThat(candidate.statusChangedAt()).isAfterOrEqualTo(before);

            // When a service principal publishes it
            as(vertx, scenario, service("deployer"), () -> scenario.service().publish(key));

            // Then the row names the publisher in place of the registrant, and its status change time moved on
            WorkflowDefinitionRecord published = record(scenario, key);
            assertThat(published.status()).isEqualTo(DefinitionStatus.AVAILABLE);
            assertThat(published.statusChangedBy()).isEqualTo(attributedTo(new WorkflowActor.Service("deployer")));
            assertThat(published.statusChangedAt()).isAfterOrEqualTo(candidate.statusChangedAt());

            // When a user activates it in the default scope
            as(vertx, scenario, user("bob"), () -> scenario.service().activate(DEFAULT, doc, DEFAULT, 1));

            // Then the activation record names who wrote it and when
            DefinitionActivation activation = scenario.activations(DEFAULT, doc).getFirst();
            assertThat(activation.activatedBy()).isEqualTo(attributedTo(new WorkflowActor.User("bob")));
            assertThat(activation.activatedAt()).isAfterOrEqualTo(before);

            // And a later activation of the same record replaces who wrote it
            as(vertx, scenario, user("bella"), () -> scenario.service().activate(DEFAULT, doc, DEFAULT, 1));
            assertThat(scenario.activations(DEFAULT, doc).getFirst().activatedBy())
                    .isEqualTo(attributedTo(new WorkflowActor.User("bella")));

            // When it is taken off the default, deprecated and reinstated by users, and deprecated again with no
            // identity
            await(scenario.service().deactivateDefault(doc));
            as(vertx, scenario, user("dave"), () -> scenario.service().deprecate(key));
            assertThat(record(scenario, key).statusChangedBy()).isEqualTo(attributedTo(new WorkflowActor.User("dave")));
            as(vertx, scenario, user("erin"), () -> scenario.service().reinstate(key));
            assertThat(record(scenario, key).statusChangedBy()).isEqualTo(attributedTo(new WorkflowActor.User("erin")));
            await(scenario.service().deprecate(key));

            // Then the last change replaces the earlier ones: with no identity the row records none at all
            WorkflowDefinitionRecord unattributed = record(scenario, key);
            assertThat(unattributed.status()).isEqualTo(DefinitionStatus.DEPRECATED);
            assertThat(unattributed.statusChangedBy()).isEqualTo(WorkflowAttribution.absent());

            // When a service principal retires it
            as(vertx, scenario, service("janitor"), () -> scenario.service().retire(key));

            // Then the retirement is the last change
            WorkflowDefinitionRecord retired = record(scenario, key);
            assertThat(retired.status()).isEqualTo(DefinitionStatus.RETIRED);
            assertThat(retired.statusChangedBy()).isEqualTo(attributedTo(new WorkflowActor.Service("janitor")));
        }
    }

    @Test
    @DisplayName("a refused change leaves the actor and the time of the last change as they were")
    void aRefusedChangeLeavesTheLastChangeAlone(Vertx vertx) {
        String id = "last-actor-refused";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);
            Scenario scenario = scenario(database, boot(database, code(id, 1)));
            DefinitionKey key = key(id, 1);
            WorkflowDefinitionRecord before = record(scenario, key);

            // Given version 1, recorded by startup and the default's current version
            // When another caller tries to deprecate it, which the registry refuses
            // Then the call fails and the row is exactly as startup left it
            assertThatThrownBy(() -> as(vertx, scenario, user("mallory"), () -> scenario.service()
                            .deprecate(key)))
                    .isNotNull();
            WorkflowDefinitionRecord after = record(scenario, key);
            assertThat(after.statusChangedBy()).isEqualTo(before.statusChangedBy());
            assertThat(after.statusChangedAt()).isEqualTo(before.statusChangedAt());
        }
    }

    @Test
    @DisplayName("startup attributes the versions and the default it records to the system with the reason bootstrap")
    void startupIsAttributedToTheSystem(Vertx vertx) {
        String id = "last-actor-bootstrap";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            // Given a start that records version 1 of a code definition and makes it the default
            own(database, id);
            Scenario scenario = scenario(database, boot(database, code(id, 1)));

            // Then the version and the activation record both name the system with its reason
            assertThat(record(scenario, key(id, 1)).statusChangedBy()).isEqualTo(BOOTSTRAP);
            assertThat(scenario.activations(DEFAULT, id).getFirst().activatedBy())
                    .isEqualTo(BOOTSTRAP);
        }
    }

    @Test
    @DisplayName("a publication that nobody completed is reset by the next start, which the system makes")
    void aStalePublicationIsResetBySystem(Vertx vertx) {
        String doc = "last-actor-stale";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, doc);
            Scenario scenario = scenario(database, boot(database));
            DefinitionKey key = key(doc, 1);

            // Given a registered document whose publication was claimed and abandoned long ago
            as(vertx, scenario, user("alice"), () -> scenario.service()
                    .load(
                            DEFAULT,
                            ActivationFixtures.document(doc, 1, ActorContract.class),
                            DocumentFormat.YAML,
                            DefinitionScenario.source("last-actor-stale", Instant.now())));
            database.claimWithoutCompleting(key);
            database.ageStatus(key, 100_000);

            // When the component starts again
            boot(database);

            // Then the document is a candidate again, and the system reset it
            WorkflowDefinitionRecord reset = record(scenario, key);
            assertThat(reset.status()).isEqualTo(DefinitionStatus.CANDIDATE);
            assertThat(reset.statusChangedBy())
                    .isEqualTo(new WorkflowAttribution(new WorkflowActor.System("stale publication reset"), null));
        }
    }

    @Test
    @DisplayName("an actor that acted for a subject is recorded with the subject")
    void theSubjectAndTheAttributionAreRecorded(Vertx vertx) {
        String doc = "last-actor-subject";

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, doc);
            Scenario scenario = scenario(database, boot(database));

            // A service and a delegated user share an id of the longest storable length that holds a control character,
            // but are different principals.
            String sharedId = "relay\t" + "x".repeat(249);
            SecurityIdentity relayed = new SecurityIdentity(
                    new PrincipalRef(PrincipalType.SERVICE, sharedId, Map.of()),
                    Optional.of(new PrincipalRef(PrincipalType.USER, sharedId, Map.of("issuer", "local"))),
                    Optional.empty(),
                    Optional.empty());
            as(vertx, scenario, relayed, () -> scenario.service()
                    .load(
                            DEFAULT,
                            ActivationFixtures.document(doc, 1, ActorContract.class),
                            DocumentFormat.YAML,
                            DefinitionScenario.source("last-actor-subject", Instant.now())));

            // Then the row names the service and the user it acted for
            WorkflowAttribution actor = record(scenario, key(doc, 1)).statusChangedBy();
            assertThat(actor.actor()).isEqualTo(new WorkflowActor.Service(sharedId));
            assertThat(actor.onBehalfOf()).isEqualTo(new PrincipalRef(PrincipalType.USER, sharedId, Map.of()));
        }
    }

    private static WorkflowAttribution attributedTo(WorkflowActor actor) {
        return new WorkflowAttribution(actor, null);
    }

    private static WorkflowDefinitionRecord record(Scenario scenario, DefinitionKey key) {
        List<WorkflowDefinitionRecord> rows = await(scenario.service()
                .list(new DefinitionFilter(
                        key.scopeKey(),
                        key.definitionId(),
                        Set.of(
                                DefinitionStatus.CANDIDATE,
                                DefinitionStatus.PUBLISHING,
                                DefinitionStatus.AVAILABLE,
                                DefinitionStatus.DEPRECATED,
                                DefinitionStatus.RETIRED))));
        return rows.stream()
                .filter(row -> row.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + key));
    }

    /** Runs the call on a context of its own with the identity bound as the security context, and waits for it. */
    private static <T> T as(Vertx vertx, Scenario scenario, SecurityIdentity caller, Supplier<Future<T>> call) {
        return await(onDuplicatedContext(vertx, () -> {
            ContextHolder holder = scenario.app().contextHolder();
            ContextHolder.Scope bound = holder.bind(SecurityContext.class, SecurityContexts.unauthenticated(caller));
            return call.get().onComplete(ignored -> bound.close());
        }));
    }

    private static <T> Future<T> onDuplicatedContext(Vertx vertx, Supplier<Future<T>> work) {
        Promise<T> result = Promise.promise();
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            try {
                work.get().onComplete(result);
            } catch (Throwable e) {
                result.tryFail(e);
            }
        });
        return result.future();
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static SecurityIdentity service(String id) {
        return SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, id, Map.of()));
    }

    private static DefinitionKey key(String id, long version) {
        return new DefinitionKey(DEFAULT, id, version);
    }

    private static Boot boot(DefinitionScenario database, WorkflowDefinition<?, ?>... code) {
        WorkflowDefinitionSource none = List::of;
        return database.node(none, new BootObservation("last-actor-unused", 1, "unused", false), List.of(code));
    }

    private static Scenario scenario(DefinitionScenario database, Boot node) {
        return new Scenario(database, node, ActorContract.class);
    }

    /** Clears the ids' rows and records now and again when the scenario closes, before any start records them. */
    private static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }

    private static WorkflowDefinition<?, ?> code(String definitionId, long version) {
        return new CodeWorkflow(definitionId, version);
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
            return (Class<Object>) (Class<?>) ActorContract.class;
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
