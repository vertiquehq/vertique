// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.finish;
import static dev.vertique.examples.workflow.order.scenario.OrderScenarios.order;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.ACME;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.DEFAULT;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.GLOBEX;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.approveFinance;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.loadPublishAndActivateAcme;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.pin;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.purchaseClient;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.startPurchase;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.OrderFulfillmentDefinition;
import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.examples.workflow.order.scenario.ScenarioNode.Pin;
import dev.vertique.workflow.exception.WorkflowBusinessKeyConflictException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
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
 * Business keys are unique within a scope and not across scopes, and a signal is checked against the version its
 * instance is pinned to. Instances of the other scenarios, started before a restart, resume on their pins.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScopedKeysScenarioIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_scoped_keys_it");

    private static final String QA = "qa";
    private static final String ORDER_FULFILLMENT = "order-fulfillment";
    private static final String PO_42 = "PO-42";

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
    }

    @Test
    @DisplayName("business keys are scoped and signals follow the pin")
    void businessKeysAreScopedAndSignalsFollowThePin(Vertx vertx) {
        WorkflowInstanceId orderInDefault;
        WorkflowInstanceId orderInQa;
        WorkflowInstanceId acmeEarly;
        WorkflowInstanceId globexEarly;

        // Given a component with the order saga in two versions, the purchase approval from code, and Acme's variant
        // activated for Acme, with an order in the default scope, an order in qa, which runs version 2, and a
        // purchase in Acme and one in globex
        try (ScenarioNode first = deploy(vertx)) {
            loadPublishAndActivateAcme(first);
            await(first.graph().definitionService().activate(QA, ORDER_FULFILLMENT, DEFAULT, 2));
            orderInDefault = await(orderClient(first, DEFAULT).start(order("order-default")));
            orderInQa = await(orderClient(first, QA).start(order("order-qa")));
            acmeEarly = startPurchase(first, ACME, "p-acme", "PO-1");
            globexEarly = startPurchase(first, GLOBEX, "p-globex", "PO-1");
            assertEquals(orderPin(DEFAULT, 1), first.pin(orderInDefault));
            assertEquals(orderPin(QA, 2), first.pin(orderInQa));
            assertEquals(pin(ACME, ACME), first.pin(acmeEarly));
            assertEquals(pin(GLOBEX, DEFAULT), first.pin(globexEarly));
        }

        // When the component restarts
        try (ScenarioNode second = deploy(vertx)) {
            // Then every instance started before the restart resumes on its own pin and completes
            finish(orderClient(second, DEFAULT), orderInDefault, "order-default");
            finish(orderClient(second, QA), orderInQa, "order-qa");
            approveFinance(second, acmeEarly);
            await(purchaseClient(second, ACME).audited(acmeEarly, new PoAudited("dave"), "audit-early"));
            await(purchaseClient(second, GLOBEX).approved(globexEarly, new PoApproved("erin"), "approve-early"));
            for (WorkflowInstanceId instance :
                    new WorkflowInstanceId[] {orderInDefault, orderInQa, acmeEarly, globexEarly}) {
                assertEquals("COMPLETED", second.status(instance));
            }
            assertEquals(orderPin(DEFAULT, 1), second.pin(orderInDefault));
            assertEquals(orderPin(QA, 2), second.pin(orderInQa));
            assertEquals(pin(ACME, ACME), second.pin(acmeEarly));
            assertEquals(pin(GLOBEX, DEFAULT), second.pin(globexEarly));

            // When the same business key starts in Acme and in globex, and then again in Acme
            WorkflowInstanceId acme = startPurchase(second, ACME, "p-42-acme", PO_42);
            WorkflowInstanceId globex = startPurchase(second, GLOBEX, "p-42-globex", PO_42);
            Throwable repeated = failureOf(purchaseClient(second, ACME).start(new StartPurchase("p-42-acme-2", PO_42)));

            // Then the first two starts succeed in their own scopes and the third conflicts
            assertEquals(pin(ACME, ACME), second.pin(acme));
            assertEquals(pin(GLOBEX, DEFAULT), second.pin(globex));
            assertNotNull(repeated, "the business key is already taken in Acme");
            assertInstanceOf(WorkflowBusinessKeyConflictException.class, repeated);

            // When Acme's instance, pinned to the variant that waits for the audit, is sent the approval
            approveFinance(second, acme);
            Throwable approvalToAcme =
                    failureOf(purchaseClient(second, ACME).approved(acme, new PoApproved("frank"), "a-1"));

            // Then the signal fails naming the pinned version and the instance is unchanged
            assertRejectedNamingVersionOne(approvalToAcme, "po.approved");
            assertEquals("WAITING", second.status(acme));

            // When the instance is sent the audit that its pin waits for
            await(purchaseClient(second, ACME).audited(acme, new PoAudited("gail"), "a-2"));

            // Then it is delivered
            assertEquals("COMPLETED", second.status(acme));

            // When globex's instance, pinned to the default scope's version, is sent the audit and then the approval
            Throwable auditToGlobex =
                    failureOf(purchaseClient(second, GLOBEX).audited(globex, new PoAudited("hugo"), "g-1"));
            assertRejectedNamingVersionOne(auditToGlobex, "po.audited");
            assertEquals("WAITING", second.status(globex));
            await(purchaseClient(second, GLOBEX).approved(globex, new PoApproved("ines"), "g-2"));

            // Then the approval is delivered
            assertEquals("COMPLETED", second.status(globex));
        }
    }

    private static void assertRejectedNamingVersionOne(Throwable failure, String signal) {
        assertNotNull(failure, "the signal must be refused");
        assertInstanceOf(WorkflowSignalRejectedException.class, failure);
        String message = failure.getMessage();
        assertTrue(message.contains("'purchase-approval' v1"), message);
        assertTrue(message.contains("does not wait for signal '" + signal + "'"), message);
    }

    private static ScenarioNode deploy(Vertx vertx) {
        return ScenarioNode.on(vertx, db)
                .code(
                        new OrderFulfillmentDefinition(),
                        new OrderFulfillmentDefinitionV2(false),
                        new PurchaseApprovalDefinition())
                .types(ScenarioCatalogs.purchase())
                .start();
    }

    private static OrderFulfillmentWorkflow orderClient(ScenarioNode node, String scope) {
        return node.graph()
                .workflowClientFactory()
                .client(OrderFulfillmentDefinition.class)
                .scope(scope)
                .create();
    }

    private static Pin orderPin(String startScope, long version) {
        return new Pin(startScope, DEFAULT, ORDER_FULFILLMENT, version);
    }
}
