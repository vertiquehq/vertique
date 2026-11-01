// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.kafka.test.KafkaTestContainers;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Resumes an order that waits for its payment from a Kafka event that names the order and nothing else.
 *
 * <p>The {@code wait-payment} step of the order-fulfillment saga keys on the order id, and the application consumes the
 * {@code payments.captured} topic through an annotated Kafka source. A payment service that publishes
 * {@code PaymentCaptured} with the order id and a charge id therefore needs to know no workflow instance id, and the
 * application keeps no registry from orders to instances. This class shows that end to end against a real broker and
 * a real database:
 * <ol>
 *   <li>Start an order and let the relay drive it to the payment wait.</li>
 *   <li>Produce a JSON {@code PaymentCaptured} record to {@code payments.captured}.</li>
 *   <li>Wait for the order to leave {@code wait-payment}, and read its state and history.</li>
 * </ol>
 *
 * <p>The other saga tests post the same signal by instance id through the relay; that path is unchanged and they do
 * not start a Kafka consumer.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PaymentEventSourceIT extends SagaTestBase {

    private static final String PAYMENTS_TOPIC = "payments.captured";

    private static final long ARRIVAL_DEADLINE_MS = 30_000;

    private static String kafkaBootstrap;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        kafkaBootstrap = KafkaTestContainers.shared().getBootstrapServers();
        createTopic(kafkaBootstrap, PAYMENTS_TOPIC);
        setUpComponent(vertx, kafkaBootstrap)
                .compose(ready -> component.kafkaConsumerDeploymentManager().deployAll())
                .onComplete(ctx.succeedingThenComplete());
    }

    @BeforeEach
    void reset(VertxTestContext ctx) {
        component.stubScenario().setPaymentFails(false);
        component.stubScenario().setInventoryFails(false);
        component.stubInventoryService().reset();
        component.stubPaymentService().reset();
        component.stubShippingService().reset();
        truncateTables().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        component.kafkaConsumerDeploymentManager().undeployAll().onComplete(undeployed -> {
            closeComponent();
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("a payment event that holds the order id resumes the order with no application-held registry")
    void paymentEventResumesTheOrderWithNoApplicationHeldRegistry(VertxTestContext ctx) {
        String orderId = "order-paid-by-event";
        String chargeId = "charge-" + orderId;

        // Given an order that waits for its payment. The test keeps the instance id only to read the order at the end.
        startOrder(orderId)
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .compose(waiting -> {
                    assertEquals("wait-payment", waiting.instance().currentStepId(), "the order waits for payment");

                    // When the payment service publishes the event: the order id and the charge id, no instance id.
                    JsonObject paymentCaptured =
                            new JsonObject().put("orderId", orderId).put("chargeId", chargeId);
                    return produceJson(kafkaBootstrap, PAYMENTS_TOPIC, orderId, paymentCaptured)
                            .map(produced -> waiting.instance().id());
                })
                .compose(id -> awaitStep(id, "wait-shipment", ARRIVAL_DEADLINE_MS))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ctx.succeeding(resumed -> ctx.verify(() -> {
                    // Then the order has left wait-payment with the charge recorded, and now waits for its shipment.
                    assertEquals(WorkflowStatus.WAITING, resumed.instance().status());
                    assertEquals("wait-shipment", resumed.instance().currentStepId());
                    assertEquals(
                            chargeId,
                            new JsonObject(resumed.instance().stateJson()).getString("authorizationId"),
                            "the charge id is the order's authorization id");
                    assertTrue(
                            resumed.recentHistory().stream()
                                    .anyMatch(entry -> entry.entryType() == WorkflowEntryType.SIGNAL_RECEIVED),
                            "the history records the received signal");
                    ctx.completeNow();
                })));
    }
}
