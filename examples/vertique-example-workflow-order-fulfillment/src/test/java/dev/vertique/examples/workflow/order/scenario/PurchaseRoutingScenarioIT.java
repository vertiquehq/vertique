// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.ACME;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.DEFAULT;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.approveFinance;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.source;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** The code and document forms of the purchase routing take the same steps. */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PurchaseRoutingScenarioIT {

    private static final String PURCHASE_ROUTING = "purchase-routing";

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_purchase_routing_it");

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
    }

    @Test
    @DisplayName("the code and YAML forms route, approve, hold and audit a purchase alike")
    void codeAndYamlFormsTakeTheSameSteps(Vertx vertx) throws Exception {
        try (ScenarioNode node = ScenarioNode.on(vertx, db)
                .code(new PurchaseRoutingDefinition())
                .types(ScenarioCatalogs.purchase())
                .start()) {
            // Given the code form in the default scope and the document form in Acme
            await(node.graph().definitionService().load(ACME, document(), DocumentFormat.YAML, source("routing")));
            await(node.graph().definitionService().publish(new DefinitionKey(ACME, PURCHASE_ROUTING, 1)));
            await(node.graph().definitionService().activate(ACME, PURCHASE_ROUTING, ACME, 1));

            for (String scope : new String[] {DEFAULT, ACME}) {
                // When an ordinary purchase starts, then it waits at the finance task until it is approved
                WorkflowInstanceId ordinary = start(node, scope, "p-" + scope);
                assertEquals("TASK", node.waitType(ordinary));
                assertEquals("finance-approval", node.step(ordinary));
                approveFinance(node, ordinary);
                assertEquals("hold-shipment", node.step(ordinary));
                finish(node, scope, ordinary);

                // When a rush purchase starts, then it skips the task and goes straight to the timer
                WorkflowInstanceId rush = start(node, scope, "rush-" + scope);
                assertEquals("TIMER", node.waitType(rush));
                assertEquals("hold-shipment", node.step(rush));
                finish(node, scope, rush);
            }
        }
    }

    private static PurchaseApprovalWorkflow client(ScenarioNode node, String scope) {
        return node.graph()
                .workflowClientFactory()
                .client(PurchaseApprovalWorkflow.class, PURCHASE_ROUTING)
                .scope(scope)
                .create();
    }

    private static WorkflowInstanceId start(ScenarioNode node, String scope, String purchaseId) {
        WorkflowInstanceId instance =
                await(client(node, scope).start(new StartPurchase(purchaseId, "PO-" + purchaseId)));
        node.awaitIdle(instance);
        return instance;
    }

    /** Waits for the timer to reach the audit wait, then delivers the audit and expects the instance to complete. */
    private static void finish(ScenarioNode node, String scope, WorkflowInstanceId instance)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!"await-audit".equals(node.step(instance)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertEquals("SIGNAL", node.waitType(instance));
        await(client(node, scope).audited(instance, new PoAudited("dana"), "audit-" + instance.value()));
        node.awaitIdle(instance);
        assertEquals("COMPLETED", node.status(instance));
    }

    private static byte[] document() throws IOException {
        try (InputStream input =
                PurchaseRoutingScenarioIT.class.getResourceAsStream("/scenario/purchase-routing-acme.workflow.yaml")) {
            return input.readAllBytes();
        }
    }
}
