// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.client.WorkflowClientBinding;
import dev.vertique.workflow.ops.WorkflowOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the {@code vertique-codegen-workflow} annotation processor has generated static
 * proxies for this example's {@link OrderFulfillmentWorkflow} and
 * {@link OrderFulfillmentFanOutWorkflow} contracts, and that no aggregate Dagger module of typed
 * clients was emitted.
 *
 * <p>These are plain unit tests — no database, no Vert.x runtime, no TestContainers required.
 * The generated classes are compiled into the same package during APT and are therefore available
 * on the test classpath.
 */
class WorkflowCodegenTest {

    @Test
    @DisplayName("OrderFulfillmentWorkflow_WorkflowClientProxy implements OrderFulfillmentWorkflow")
    void orderFulfillmentProxyImplementsContract() {
        OrderFulfillmentWorkflow proxy = new OrderFulfillmentWorkflow_WorkflowClientProxy(
                mock(WorkflowOperations.class),
                new WorkflowClientBinding("order-fulfillment", "order-fulfillment", null, null, null));

        assertInstanceOf(
                OrderFulfillmentWorkflow.class, proxy, "generated proxy must implement the contract interface");
        assertTrue(
                proxy.getClass().getName().endsWith("_WorkflowClientProxy"),
                "generated class name must end with _WorkflowClientProxy, got: "
                        + proxy.getClass().getName());
    }

    @Test
    @DisplayName("OrderFulfillmentFanOutWorkflow_WorkflowClientProxy implements OrderFulfillmentFanOutWorkflow")
    void orderFulfillmentFanOutProxyImplementsContract() {
        OrderFulfillmentFanOutWorkflow proxy = new OrderFulfillmentFanOutWorkflow_WorkflowClientProxy(
                mock(WorkflowOperations.class),
                new WorkflowClientBinding("order-fulfillment-fanout", "order-fulfillment-fanout", null, null, null));

        assertInstanceOf(
                OrderFulfillmentFanOutWorkflow.class,
                proxy,
                "generated fan-out proxy must implement the contract interface");
        assertTrue(
                proxy.getClass().getName().endsWith("_WorkflowClientProxy"),
                "generated fan-out class name must end with _WorkflowClientProxy, got: "
                        + proxy.getClass().getName());
    }

    @Test
    @DisplayName("no Dagger module of typed clients is generated")
    void generatedWorkflowClientsModuleIsAbsent() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("dev.vertique.examples.workflow.order.GeneratedWorkflowClientsModule"),
                "typed clients are created through WorkflowClientFactory, not provided by a generated module");
    }
}
