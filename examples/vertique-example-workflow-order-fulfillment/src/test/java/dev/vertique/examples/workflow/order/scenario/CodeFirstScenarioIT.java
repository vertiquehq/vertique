// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.finish;
import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.order;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.OrderFulfillmentDefinition;
import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.scenario.ScenarioNode.Pin;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A code-first workflow gains a second version: a client made from the definition class starts the version that has
 * become current, and an instance that started before the restart keeps running on its own version.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CodeFirstScenarioIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_code_first_it");

    private static final String ORDER_FULFILLMENT = "order-fulfillment";

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
    }

    @Test
    @DisplayName("a client made from the definition class starts the current version and pins survive a restart")
    void definitionClassClientStartsCurrentVersionAndPinsSurviveRestart(Vertx vertx) {
        // Given a component with version 1 and a version 1 instance waiting on its first signal
        WorkflowInstanceId versionOneInstance;
        try (ScenarioNode first = restartWith(vertx, new OrderFulfillmentDefinition())) {
            OrderFulfillmentWorkflow client =
                    first.graph().workflowClientFactory().create(OrderFulfillmentDefinition.class);
            versionOneInstance = await(client.start(order("order-1")));
            assertEquals(defaultPin(1), first.pin(versionOneInstance));
            assertEquals("WAITING", first.status(versionOneInstance));
        }

        // When the component restarts with version 1 and version 2 and a client made from the definition class starts
        try (ScenarioNode second =
                restartWith(vertx, new OrderFulfillmentDefinition(), new OrderFulfillmentDefinitionV2())) {
            OrderFulfillmentWorkflow client =
                    second.graph().workflowClientFactory().create(OrderFulfillmentDefinition.class);
            WorkflowInstanceId versionTwoInstance = await(client.start(order("order-2")));

            // Then the new instance is pinned to version 2, the current version
            assertInstanceOf(OrderFulfillmentWorkflow.class, client);
            assertEquals(defaultPin(2), second.pin(versionTwoInstance));

            // And the version 1 instance resumes on version 1 and completes
            finish(client, versionOneInstance, "order-1");
            assertEquals("COMPLETED", second.status(versionOneInstance));
            assertEquals(defaultPin(1), second.pin(versionOneInstance));
            assertEquals("WAITING", second.status(versionTwoInstance));
        }
    }

    private static ScenarioNode restartWith(Vertx vertx, WorkflowDefinition<?, ?>... definitions) {
        return ScenarioNode.on(vertx, db).code(definitions).start();
    }

    private static Pin defaultPin(long version) {
        return new Pin("default", "default", ORDER_FULFILLMENT, version);
    }
}
