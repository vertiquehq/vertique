// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.order;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.OrderFulfillmentDefinition;
import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.scenario.ScenarioNode.Pin;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A second version that opts out of activation on deploy is available but not current: it runs only in a scope that
 * was told to use it, or where a start asks for it by number, and a restart keeps those choices.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class StagedVersionScenarioIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_staged_version_it");

    private static final String DEFAULT = WorkflowScopes.DEFAULT;
    private static final String QA = "qa";
    private static final String GLOBEX = "globex";
    private static final String ORDER_FULFILLMENT = "order-fulfillment";

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
    }

    @Test
    @DisplayName("an opted-out version runs only where it is activated or requested explicitly")
    void optedOutVersionRunsOnlyWhereActivatedOrRequestedExplicitly(Vertx vertx) {
        // Given version 1 as the current version and version 2 deployed with activation on deploy turned off
        try (ScenarioNode first = deploy(vertx)) {
            // When a start in the default scope, a start in qa after activating version 2 there, and a start in the
            // default scope that asks for version 2 by number
            Pin defaultStart = start(first, "order-default", DEFAULT);
            await(first.graph().definitionService().activate(QA, ORDER_FULFILLMENT, DEFAULT, 2));
            Pin qaStart = start(first, "order-qa", QA);
            Pin explicitStart = startVersion(first, "order-explicit", 2);

            // Then the pins are version 1, version 2 and version 2
            assertEquals(pin(DEFAULT, 1), defaultStart);
            assertEquals(pin(QA, 2), qaStart);
            assertEquals(pin(DEFAULT, 2), explicitStart);
        }

        // When the component restarts with the same code and starts in qa
        try (ScenarioNode second = deploy(vertx)) {
            // Then qa still runs version 2
            assertEquals(pin(QA, 2), start(second, "order-qa-again", QA));

            // When version 2 is activated in the default scope and a start comes from globex, which has no record
            await(second.graph().definitionService().activate(DEFAULT, ORDER_FULFILLMENT, DEFAULT, 2));
            Pin globexStart = start(second, "order-globex", GLOBEX);

            // Then globex follows the default scope to version 2
            assertEquals(pin(GLOBEX, 2), globexStart);

            // And qa's activation record is listed
            List<DefinitionActivation> qaRecords =
                    await(second.graph().definitionService().activations(QA));
            assertEquals(1, qaRecords.size());
            DefinitionActivation record = qaRecords.get(0);
            assertEquals(QA, record.scopeKey());
            assertEquals(ORDER_FULFILLMENT, record.definitionId());
            assertEquals(DEFAULT, record.targetScopeKey());
            assertEquals(2L, record.version());
        }
    }

    private static ScenarioNode deploy(Vertx vertx) {
        return ScenarioNode.on(vertx, db)
                .code(new OrderFulfillmentDefinition(), new OrderFulfillmentDefinitionV2(false))
                .start();
    }

    /** Starts an order in the scope without naming a version and reports where the instance is pinned. */
    private static Pin start(ScenarioNode node, String orderId, String scope) {
        OrderFulfillmentWorkflow client = node.graph()
                .workflowClientFactory()
                .client(OrderFulfillmentDefinition.class)
                .scope(scope)
                .create();
        WorkflowInstanceId instance = await(client.start(order(orderId)));
        node.awaitIdle(instance);
        return node.pin(instance);
    }

    /** Starts an order in the default scope on the version of the same scope that the start names. */
    private static Pin startVersion(ScenarioNode node, String orderId, long version) {
        OrderFulfillmentWorkflow client = node.graph()
                .workflowClientFactory()
                .client(OrderFulfillmentDefinition.class)
                .version(version)
                .create();
        WorkflowInstanceId instance = await(client.start(order(orderId)));
        node.awaitIdle(instance);
        return node.pin(instance);
    }

    /** The pin of an instance that started in the scope on the version of the default scope. */
    private static Pin pin(String startScope, long version) {
        return new Pin(startScope, DEFAULT, ORDER_FULFILLMENT, version);
    }
}
