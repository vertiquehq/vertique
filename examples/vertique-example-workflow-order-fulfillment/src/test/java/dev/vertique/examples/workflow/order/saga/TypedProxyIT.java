// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.order.OrderFulfillmentWorkflow;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
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
 * Typed proxy integration test (§9.4 test 21).
 *
 * <p>Drives the same happy-path scenario as {@link OrderFulfillmentSagaIT} but invokes the saga
 * through the {@link OrderFulfillmentWorkflow} typed proxy created by
 * {@link dev.vertique.workflow.client.WorkflowClientFactory}. Outcomes must be identical:
 * status COMPLETED after all relay cycles.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class TypedProxyIT extends SagaTestBase {

    static OrderFulfillmentWorkflow proxy;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        setUpComponent(vertx)
                .map(v -> {
                    proxy = component.workflowClientFactory().create(OrderFulfillmentWorkflow.class);
                    return v;
                })
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        ctx.completeNow();
                    } else {
                        ctx.failNow(ar.cause());
                    }
                });
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
    static void tearDown() {
        if (component != null && component.pgInboxOutboxRepository() != null) {
            component.pgInboxOutboxRepository().pool().close();
        }
    }

    @Test
    @DisplayName("typed proxy: happy path reaches COMPLETED same as direct operations")
    void typedProxyHappyPath(VertxTestContext ctx) {
        var cmd = new dev.vertique.examples.workflow.order.command.PlaceOrder(
                "proxy-happy-1",
                "cust-proxy-1",
                java.util.List.of(
                        new dev.vertique.examples.workflow.order.command.PlaceOrder.OrderItem("SKU-P", 1, 100L)),
                100L);

        proxy.start(cmd)
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> proxy.status(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "saga via proxy must succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "proxy saga must reach COMPLETED");

                    assertEquals(
                            1, component.stubInventoryService().reserveCalls().size());
                    assertEquals(
                            1, component.stubPaymentService().authorizeCalls().size());
                    assertEquals(
                            1, component.stubShippingService().createCalls().size());

                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("proxy toString returns WorkflowProxy[...] for contract interface")
    void proxyToString(VertxTestContext ctx) {
        String str = proxy.toString();
        assertTrue(str.startsWith("WorkflowProxy["), "proxy toString must start with WorkflowProxy[");
        assertTrue(str.contains("OrderFulfillmentWorkflow"), "proxy toString must contain contract name");
        ctx.completeNow();
    }

    @Test
    @DisplayName("factory returns the APT-generated proxy, not a JDK dynamic proxy")
    void usesGeneratedProxy() {
        assertTrue(
                proxy.getClass().getName().endsWith("_WorkflowClientProxy"),
                "expected APT-generated proxy, got " + proxy.getClass().getName());
        assertFalse(java.lang.reflect.Proxy.isProxyClass(proxy.getClass()), "proxy must not be a JDK dynamic proxy");
    }

    @Test
    @DisplayName(
            "Dagger-injected contract (via GeneratedWorkflowClientsModule) is the generated proxy obtained through the factory")
    void daggerBindingProvidesGeneratedProxy() {
        // GeneratedWorkflowClientsModule's @Provides delegates to WorkflowClientFactory.create(...), which
        // validates the contract against the registered definition and then selects the generated proxy.
        // Getting a *_WorkflowClientProxy back from the Dagger accessor proves that binding path end-to-end
        // (module -> factory.create -> generated proxy), not a raw proxy return that would bypass validation.
        OrderFulfillmentWorkflow injected = component.orderFulfillmentWorkflow();
        assertTrue(
                injected.getClass().getName().endsWith("_WorkflowClientProxy"),
                "Dagger-injected contract must be the APT-generated proxy, got "
                        + injected.getClass().getName());
        assertFalse(
                java.lang.reflect.Proxy.isProxyClass(injected.getClass()),
                "injected contract must not be a JDK dynamic proxy");
    }

    @Test
    @DisplayName("proxy start is idempotent: duplicate call returns same instance id")
    void proxyStartIdempotent(VertxTestContext ctx) {
        var cmd = new dev.vertique.examples.workflow.order.command.PlaceOrder(
                "proxy-idem-1",
                "cust-idem",
                java.util.List.of(
                        new dev.vertique.examples.workflow.order.command.PlaceOrder.OrderItem("SKU-I", 1, 50L)),
                50L);

        proxy.start(cmd)
                .compose(id1 -> proxy.start(cmd).map(id2 -> new WorkflowInstanceId[] {id1, id2}))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "idempotent start must succeed: " + ar.cause());
                    var ids = ar.result();
                    assertEquals(ids[0], ids[1], "duplicate start must return the same instance id");
                    ctx.completeNow();
                }));
    }
}
