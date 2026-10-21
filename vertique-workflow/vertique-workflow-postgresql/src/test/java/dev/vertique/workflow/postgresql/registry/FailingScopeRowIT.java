// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.ActivationFixtures.DEFAULT;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.failureOf;
import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.source;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.InventoryReservedDoc;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.it.fixture.PlaceOrderDoc;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionFailingException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Pin;
import dev.vertique.workflow.postgresql.registry.ActivationFixtures.Scenario;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.failing.v1.FinanceDecision;
import dev.vertique.workflow.registry.store.DefinitionFailure.Rule;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a stored document row of a scope other than the default, which code shipped later puts in conflict,
 * does not fail startup: the row is marked failing, new starts on it are refused with the rule named, its
 * definition listing shows the rule, other scopes are unaffected and its live instances keep resuming while its plan
 * still compiles.
 *
 * <p>The conflict is made in one JVM by two deployments on one database. The first ships the default scope's purchase
 * state with one property; the second ships a state class of the same logical name with a second property, which the
 * document state of the scope, carrying only the first, no longer extends. A deployment is one graph with its own
 * hand-written type catalog, so the two same-named classes never meet in one index.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class FailingScopeRowIT {

    /** The contract of the default scope's code and of the scope's documents; it declares the signal they wait for. */
    @WorkflowContract("failing-c")
    public interface ContractC extends OrderDocumentSignals {}

    /** Another contract, whose clients only the scope's own definition serves. */
    @WorkflowContract("failing-d")
    public interface ContractD extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_failing_scope_row_it")
            .withMigration("classpath:db/migration/workflow");

    static final String ACME = "acme";
    private static final String GLOBEX = "globex";
    static final String PURCHASE = "purchase-approval";
    private static final Instant LOADED_AT = Instant.parse("2026-10-22T12:00:00Z");

    /** The property names of both purchase states and of the document state; no refusal message may repeat them. */
    private static final String[] STATE_DETAILS = {
        "orderId", "costCenter", "reservationId", "authId", "trackingNumber", "PurchaseState", "OrderDocState"
    };

    @Test
    @DisplayName("a scope row whose state no longer extends shipped code is marked failing and the node starts")
    void scopeRowWhoseStateNoLongerExtendsShippedCodeIsMarkedFailing(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, PURCHASE);

            // Given the first deployment, where acme publishes and activates its document and one acme instance waits
            // for the approval signal
            Boot first = boot(database, vertx, Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, PURCHASE, false);
            WorkflowInstanceId waiting = startInAcme(first, "waiting-order", "start-" + UUID.randomUUID());
            assertThat(database.instanceStatus(waiting.value())).isEqualTo("WAITING");
            first.close();

            // When the second deployment, whose state class acme's state no longer extends, starts on the database
            Boot second = boot(database, vertx, Deployment.laterWith(PURCHASE));

            // Then startup succeeded and the listing shows the row failing with the state rule
            WorkflowDefinitionRecord listed = listed(second, key);
            assertThat(listed.failure()).isNotNull();
            assertThat(listed.failure().rule()).isEqualTo(Rule.STATE_TYPE_NOT_EXTENDED);
            assertThat(failureReason(database, key)).isEqualTo("STATE_TYPE_NOT_EXTENDED");

            // And a start in acme is refused naming acme, the key and the rule, and no state detail
            Throwable refused = startFailure(second, ContractC.class, ACME, PURCHASE);
            assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionFailingException.class, failing -> {
                assertThat(failing.scopeKey()).isEqualTo(ACME);
                assertThat(failing.definitionKey()).isEqualTo(key);
                assertThat(failing.rule()).isEqualTo(Rule.STATE_TYPE_NOT_EXTENDED);
                assertThat(failing.getMessage()).contains(ACME, PURCHASE, "STATE_TYPE_NOT_EXTENDED");
                assertThat(failing.getMessage()).doesNotContain(STATE_DETAILS);
            });

            // And a start in globex runs the default's version 1
            assertThat(startPin(database, second, ContractC.class, GLOBEX, PURCHASE))
                    .isEqualTo(new Pin(GLOBEX, DEFAULT, PURCHASE, 1));

            // And the waiting acme instance accepts the signal and completes
            await(second.app()
                    .workflowOperations()
                    .signal(waiting, "inventory.reserved", new InventoryReservedDoc("reservation-1"), "approved-1"));
            assertThat(database.instanceStatus(waiting.value())).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a scope row whose name binding no longer resolves is marked failing and the node starts")
    void scopeRowWithUnresolvableNameBindingIsMarkedFailing(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, PURCHASE);

            // Given the first deployment, whose catalog lists the decision class acme's document names by binary name,
            // with the document published and activated and one acme instance waiting for the approval signal
            Boot first = boot(database, vertx, Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, PURCHASE, true);
            WorkflowDefinitionRecord published = listed(first, key);
            assertThat(published.nameBinding()).containsKey(FinanceDecision.class.getName());
            WorkflowInstanceId waiting = startInAcme(first, "waiting-order", "start-" + UUID.randomUUID());
            String statusBefore = database.instanceStatus(waiting.value());
            first.close();

            // When the second deployment, whose catalog omits the decision class although the class stays on the class
            // path, starts on the database
            Boot second = boot(database, vertx, Deployment.sameStateWithoutDecision(PURCHASE));

            // Then startup succeeded and the listing shows the row failing with the binding rule
            WorkflowDefinitionRecord listed = listed(second, key);
            assertThat(listed.failure()).isNotNull();
            assertThat(listed.failure().rule()).isEqualTo(Rule.NAME_BINDING_UNRESOLVED);
            assertThat(failureReason(database, key)).isEqualTo("NAME_BINDING_UNRESOLVED");

            // And the name was not resolved by loading the class, so no plan of the row is registered
            assertThat(second.app().workflowRegistry().find(key)).isEmpty();

            // And a start in acme is refused naming acme and the rule
            Throwable refused = startFailure(second, ContractC.class, ACME, PURCHASE);
            assertThat(refused).isInstanceOfSatisfying(WorkflowDefinitionFailingException.class, failing -> {
                assertThat(failing.scopeKey()).isEqualTo(ACME);
                assertThat(failing.definitionKey()).isEqualTo(key);
                assertThat(failing.rule()).isEqualTo(Rule.NAME_BINDING_UNRESOLVED);
                assertThat(failing.getMessage()).contains(ACME, PURCHASE, "NAME_BINDING_UNRESOLVED");
            });

            // And a start in globex runs the default's version 1
            assertThat(startPin(database, second, ContractC.class, GLOBEX, PURCHASE))
                    .isEqualTo(new Pin(GLOBEX, DEFAULT, PURCHASE, 1));

            // And the waiting acme instance, whose plan is not registered, was not failed by the startup
            assertThat(database.instanceStatus(waiting.value())).isEqualTo(statusBefore);
        }
    }

    @Test
    @DisplayName("an explicit version start on a failing row is refused and a replay of an earlier start is not")
    void explicitVersionStartOnFailingRowIsRefusedAndReplayIsNot(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, PURCHASE);

            // Given the first deployment with acme's row active and one acme instance started with a key
            Boot first = boot(database, vertx, Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, PURCHASE, false);
            String earlierKey = "start-" + UUID.randomUUID();
            WorkflowInstanceId earlier = startInAcme(first, "earlier-order", earlierKey);
            first.close();

            // When the second deployment marks the row failing, a start names acme's version 1 explicitly and the
            // earlier start is repeated with its key
            Boot second = boot(database, vertx, Deployment.laterWith(PURCHASE));
            Throwable explicit = failureOf(second.app()
                    .workflowOperations()
                    .start(command(ACME, PURCHASE, "explicit-order", "start-" + UUID.randomUUID(), 1L, ACME)));
            WorkflowInstanceId replayed = await(second.app()
                    .workflowOperations()
                    .start(command(ACME, PURCHASE, "earlier-order", earlierKey, null, null)));

            // Then the explicit start is refused the same way
            assertThat(explicit).isInstanceOfSatisfying(WorkflowDefinitionFailingException.class, failing -> {
                assertThat(failing.definitionKey()).isEqualTo(key);
                assertThat(failing.rule()).isEqualTo(Rule.STATE_TYPE_NOT_EXTENDED);
            });

            // And the replay returns the earlier instance and creates no other
            assertThat(replayed).isEqualTo(earlier);
            assertThat(database.instanceCount(PURCHASE)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a row whose conflict is gone is no longer failing after the next startup")
    void rowIsClearedWhenTheConflictIsGone(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, PURCHASE);

            // Given a row marked failing by the second deployment
            Boot first = boot(database, vertx, Deployment.firstWith(PURCHASE, true));
            DefinitionKey key = publishAndActivateAcme(first, PURCHASE, false);
            first.close();
            Boot second = boot(database, vertx, Deployment.laterWith(PURCHASE));
            assertThat(listed(second, key).failure()).isNotNull();
            second.close();

            // When a node that ships the first deployment's state again starts
            Boot third = boot(database, vertx, Deployment.firstWith(PURCHASE, true));

            // Then the listing and the stored column show no failure and a start in acme runs acme's own row
            assertThat(listed(third, key).failure()).isNull();
            assertThat(failureReason(database, key)).isNull();
            assertThat(startPin(database, third, ContractC.class, ACME, PURCHASE))
                    .isEqualTo(new Pin(ACME, ACME, PURCHASE, 1));
        }
    }

    @Test
    @DisplayName("default scope conflicts still fail startup and a contract overlap marks nothing")
    void defaultScopeConflictFailsStartupAndContractOverlapIsNotFailing(Vertx vertx) {
        // Given three separate scenarios, each started with the later deployment over rows of the first
        defaultScopeStateConflictFailsStartup(vertx);
        contractOverlapMarksNothing(vertx);
        retiredRowWithUnresolvableBindingIsNeitherRegisteredNorFailing(vertx);
    }

    /** (a) A default scope document whose state stops matching the later code fails startup, naming its key. */
    private void defaultScopeStateConflictFailsStartup(Vertx vertx) {
        String id = "failing-default-state";
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a default scope document published by a deployment that ships no code of the id
            Boot first = boot(database, vertx, Deployment.firstWith(null, true));
            publishDefaultDocument(first, id, false);
            first.close();

            // When a node ships code of the id whose state class differs from the document's
            Boot refused = attempt(database, vertx, Deployment.laterWith(id));

            // Then startup fails with the state refusal naming the default scope key, and not with a failing mark
            assertThat(refused.failure()).isNotNull();
            WorkflowDefinitionException refusal = causeOf(refused.failure(), WorkflowDefinitionException.class);
            assertThat(refusal.getMessage()).contains(DEFAULT, id);
            assertThat(refused.failure()).isNotInstanceOf(WorkflowDefinitionFailingException.class);
            assertThat(database.stored(DEFAULT, id, 1).status()).isEqualTo("AVAILABLE");
        }
    }

    /** (b) A scope row under one contract beside default code of the same id under another is not in conflict. */
    private void contractOverlapMarksNothing(Vertx vertx) {
        String id = "failing-overlap";
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given acme's row of the id under contract D published and activated before any default code of the id
            Boot first = boot(database, vertx, Deployment.firstWith(null, true));
            new Scenario(database, first, ContractD.class).publish(ACME, id, 1);
            await(first.app().definitionService().activate(ACME, id, ACME, 1));
            first.close();
            DefinitionKey key = new DefinitionKey(ACME, id, 1);

            // When the later deployment ships the default's code of the id under contract C with a state acme's does
            // not extend
            Boot second = boot(database, vertx, Deployment.laterWith(id));

            // Then startup succeeded, acme's row is not failing and a D client in acme starts acme's own row
            assertThat(listed(second, key).failure()).isNull();
            assertThat(failureReason(database, key)).isNull();
            assertThat(startPin(database, second, ContractD.class, ACME, id)).isEqualTo(new Pin(ACME, ACME, id, 1));
        }
    }

    /** (c) A retired row whose binding no longer resolves is exempt: neither registered, failing nor fatal. */
    private void retiredRowWithUnresolvableBindingIsNeitherRegisteredNorFailing(Vertx vertx) {
        String id = "failing-retired";
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given acme's row naming the decision class, published, deprecated and retired
            Boot first = boot(database, vertx, Deployment.firstWith(null, true));
            DefinitionKey key = publishAndActivateAcme(first, id, true);
            await(first.app().definitionService().deprecate(key));
            database.ageStatus(key, 60);
            await(first.app().definitionService().retire(key));
            assertThat(database.stored(ACME, id, 1).status()).isEqualTo("RETIRED");
            first.close();

            // When a node whose catalog omits the decision class starts
            Boot second = boot(database, vertx, Deployment.sameStateWithoutDecision(null));

            // Then startup succeeded, the retired row is not failing and its plan is not registered
            WorkflowDefinitionRecord listed = listed(second, key);
            assertThat(listed.failure()).isNull();
            assertThat(failureReason(database, key)).isNull();
            assertThat(second.app().workflowRegistry().find(key)).isEmpty();
        }
    }

    @Test
    @DisplayName("a default scope row with an unresolvable name binding still fails startup")
    void defaultScopeRowWithUnresolvableNameBindingStillFailsStartup(Vertx vertx) {
        String id = "failing-default-binding";
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, id);

            // Given a default scope document that names the decision class, published by the first deployment
            Boot first = boot(database, vertx, Deployment.firstWith(null, true));
            publishDefaultDocument(first, id, true);
            first.close();

            // When a node whose catalog omits the decision class starts
            Boot refused = attempt(database, vertx, Deployment.sameStateWithoutDecision(null));

            // Then startup fails with the unknown type name naming the default scope and the id
            assertThat(refused.failure()).isNotNull();
            WorkflowUnknownTypeNameException unknown =
                    causeOf(refused.failure(), WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage()).contains(DEFAULT, id);
            assertThat(database.stored(DEFAULT, id, 1).status()).isEqualTo("AVAILABLE");
        }
    }

    @Test
    @DisplayName("a candidate scope row with an unresolvable name binding is not failing and is refused at publish")
    void candidateScopeRowWithUnresolvableNameBindingIsCheckedAtPublish(Vertx vertx) {
        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            own(database, PURCHASE);

            // Given acme's document naming the decision class, loaded as a candidate by the first deployment
            Boot first = boot(database, vertx, Deployment.firstWith(PURCHASE, true));
            await(first.app()
                    .definitionService()
                    .load(ACME, document(PURCHASE, true), DocumentFormat.YAML, source("acme-candidate", LOADED_AT)));
            DefinitionKey key = new DefinitionKey(ACME, PURCHASE, 1);
            assertThat(database.stored(ACME, PURCHASE, 1).status()).isEqualTo("CANDIDATE");
            first.close();

            // When a node whose catalog omits the decision class starts, and the candidate is published
            Boot second = boot(database, vertx, Deployment.sameStateWithoutDecision(PURCHASE));
            Throwable publishing = failureOf(second.app().definitionService().publish(key));

            // Then startup succeeded and the candidate is not marked failing
            assertThat(listed(second, key).failure()).isNull();
            assertThat(failureReason(database, key)).isNull();

            // And publishing is refused with the unknown type name naming acme and the id
            WorkflowUnknownTypeNameException unknown = causeOf(publishing, WorkflowUnknownTypeNameException.class);
            assertThat(unknown.getMessage()).contains(ACME, PURCHASE);

            // And the row stays a candidate without a failure mark
            assertThat(database.stored(ACME, PURCHASE, 1).status()).isEqualTo("CANDIDATE");
            assertThat(failureReason(database, key)).isNull();
        }
    }

    // --- Deployments ---

    /**
     * What one deployment ships: its code definitions and its type catalog. The first deployment's purchase state has
     * one property, the later one's has two under the same logical name.
     */
    record Deployment(List<WorkflowDefinition<?, ?>> code, List<WorkflowTypeCatalogEntry> catalog) {

        /** The first deployment: default code for the id when given, and a catalog that lists the decision class. */
        static Deployment firstWith(String id, boolean decisionListed) {
            return new Deployment(
                    codeOf(id, dev.vertique.workflow.postgresql.registry.failing.v1.PurchaseState.class, s -> {
                        return new dev.vertique.workflow.postgresql.registry.failing.v1.PurchaseState(s);
                    }),
                    catalogOf(
                            dev.vertique.workflow.postgresql.registry.failing.v1.PurchaseState.class, decisionListed));
        }

        /** The later deployment: default code with the larger state, and a catalog without the decision class. */
        static Deployment laterWith(String id) {
            return new Deployment(
                    codeOf(id, dev.vertique.workflow.postgresql.registry.failing.v2.PurchaseState.class, s -> {
                        return new dev.vertique.workflow.postgresql.registry.failing.v2.PurchaseState(s, "cc-1");
                    }),
                    catalogOf(dev.vertique.workflow.postgresql.registry.failing.v2.PurchaseState.class, false));
        }

        /** A later deployment that keeps the first one's state and only drops the decision class from its catalog. */
        static Deployment sameStateWithoutDecision(String id) {
            return new Deployment(
                    firstWith(id, false).code(),
                    catalogOf(dev.vertique.workflow.postgresql.registry.failing.v1.PurchaseState.class, false));
        }

        private static <S> List<WorkflowDefinition<?, ?>> codeOf(
                String id, Class<S> state, Function<String, S> initial) {
            return id == null ? List.of() : List.of(new PurchaseCode<>(id, 1L, state, initial));
        }

        private static List<WorkflowTypeCatalogEntry> catalogOf(Class<?> purchaseState, boolean decisionListed) {
            List<WorkflowTypeCatalogEntry> entries = new java.util.ArrayList<>();
            entries.add(new WorkflowTypeCatalogEntry(Kind.TYPE, "purchase-state", purchaseState));
            if (decisionListed)
                entries.add(new WorkflowTypeCatalogEntry(
                        Kind.TYPE, FinanceDecision.class.getName(), FinanceDecision.class));
            return entries;
        }
    }

    /** A default scope code definition that completes at once, with the given state class. */
    private static final class PurchaseCode<S> implements WorkflowDefinition<S, Object> {

        private final String definitionId;
        private final long version;
        private final Class<S> stateType;
        private final Function<String, S> initial;

        PurchaseCode(String definitionId, long version, Class<S> stateType, Function<String, S> initial) {
            this.definitionId = definitionId;
            this.version = version;
            this.stateType = stateType;
            this.initial = initial;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<Object> contract() {
            return (Class<Object>) (Class<?>) ContractC.class;
        }

        @Override
        public Class<S> stateType() {
            return stateType;
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
        public void define(WorkflowBuilder<S> workflow) {
            workflow.init(PlaceOrderDoc.class, start -> initial.apply(start.orderId()))
                    .initialStep("done")
                    .complete("done");
        }
    }

    // --- Documents, starts and readers ---

    static Boot boot(DefinitionScenario database, Vertx vertx, Deployment deployment) {
        return database.nodeOf(pool -> SchemaDefinitionGraph.build(
                vertx, pool, FailingScopeRowIT.class, deployment.code(), deployment.catalog()));
    }

    private static Boot attempt(DefinitionScenario database, Vertx vertx, Deployment deployment) {
        return database.attemptOf(pool -> SchemaDefinitionGraph.build(
                vertx, pool, FailingScopeRowIT.class, deployment.code(), deployment.catalog()));
    }

    /** Loads, publishes and activates acme's document of the id and returns its key. */
    private static DefinitionKey publishAndActivateAcme(Boot node, String id, boolean financeReview) {
        await(node.app()
                .definitionService()
                .load(ACME, document(id, financeReview), DocumentFormat.YAML, source("acme-" + id, LOADED_AT)));
        DefinitionKey key = new DefinitionKey(ACME, id, 1);
        await(node.app().definitionService().publish(key));
        await(node.app().definitionService().activate(ACME, id, ACME, 1));
        return key;
    }

    /** Loads and publishes a default scope document of the id. */
    private static void publishDefaultDocument(Boot node, String id, boolean financeReview) {
        await(node.app()
                .definitionService()
                .load(DEFAULT, document(id, financeReview), DocumentFormat.YAML, source("default-" + id, LOADED_AT)));
        await(node.app().definitionService().publish(new DefinitionKey(DEFAULT, id, 1)));
    }

    private static WorkflowInstanceId startInAcme(Boot node, String orderId, String idempotencyKey) {
        return await(
                node.app().workflowOperations().start(command(ACME, PURCHASE, orderId, idempotencyKey, null, null)));
    }

    static StartCommand command(
            String scope, String id, String orderId, String idempotencyKey, Long version, String targetScope) {
        return command(ContractC.class, scope, id, orderId, idempotencyKey, version, targetScope);
    }

    static StartCommand command(
            Class<?> contract,
            String scope,
            String id,
            String orderId,
            String idempotencyKey,
            Long version,
            String targetScope) {
        return new StartCommand(
                id,
                contract.getAnnotation(WorkflowContract.class).value(),
                new PlaceOrderDoc(orderId),
                idempotencyKey,
                null,
                null,
                version,
                scope,
                targetScope);
    }

    /** Starts as a client of the contract and returns where the instance is pinned. */
    private static Pin startPin(DefinitionScenario database, Boot node, Class<?> contract, String scope, String id) {
        String key = "start-" + UUID.randomUUID();
        WorkflowInstanceId instance = await(
                node.app().workflowOperations().start(command(contract, scope, id, "order-" + key, key, null, null)));
        Row row = await(database.admin().preparedQuery("""
                                SELECT scope_key, definition_scope_key, definition_id, definition_version
                                FROM workflow_instances WHERE id = $1
                                """).execute(Tuple.of(instance.value())))
                .iterator()
                .next();
        return new Pin(
                row.getString("scope_key"),
                row.getString("definition_scope_key"),
                row.getString("definition_id"),
                row.getLong("definition_version"));
    }

    /** Starts as a client of the contract and returns the failure, whether thrown or failed. */
    private static Throwable startFailure(Boot node, Class<?> contract, String scope, String id) {
        String key = "start-" + UUID.randomUUID();
        StartCommand command = command(contract, scope, id, "order-" + key, key, null, null);
        return ActivationFixtures.failureOf(
                () -> node.app().workflowOperations().start(command));
    }

    /**
     * A document that waits for the approval signal and then completes, or, with a finance review, goes on to a human
     * task whose decision payload is the decision class named by its binary name.
     */
    private static byte[] document(String id, boolean financeReview) {
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
                """.formatted(id, ContractC.class.getName(), afterApproval, review)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The record the definition listing returns for the key. */
    static WorkflowDefinitionRecord listed(Boot node, DefinitionKey key) {
        return await(node.app()
                        .definitionService()
                        .list(new DefinitionFilter(key.scopeKey(), key.definitionId(), null)))
                .stream()
                .filter(record -> record.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The listing holds no row " + key));
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

    private static <T extends Throwable> T causeOf(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return type.cast(cause);
        }
        throw new AssertionError("Expected a " + type.getSimpleName() + " in the cause chain", failure);
    }

    /** Clears the ids' rows and records now and again when the scenario closes. */
    static void own(DefinitionScenario database, String... ids) {
        ActivationFixtures.clearActivations(database.admin(), ids);
        database.ownRows(ids);
        database.onClose(() -> ActivationFixtures.clearActivations(database.admin(), ids));
    }
}
