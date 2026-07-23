// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.dsl;

/**
 * Contract for authoring a workflow definition using the fluent {@link WorkflowBuilder} DSL.
 *
 * <p>Implementations declare the workflow's structure by implementing {@link #define(WorkflowBuilder)}.
 * The method receives a fresh builder and MUST call {@code wf.init(...)} exactly once before any
 * step DSL methods. Definitions that never call {@code .init(...)} (or call it more than once) are
 * rejected at registration time with a {@code WorkflowDefinitionException}.
 *
 * <p>Example:
 * <pre>{@code
 * public class OrderFulfillmentDefinition implements WorkflowDefinition<OrderState, OrderFulfillmentContract> {
 *
 *     public Class<OrderFulfillmentContract> contract() { return OrderFulfillmentContract.class; }
 *     public Class<OrderState> stateType() { return OrderState.class; }
 *     public String definitionId() { return "order-fulfillment"; }
 *     public long definitionVersion() { return 1L; }
 *
 *     public void define(WorkflowBuilder<OrderState> wf) {
 *         wf.init(PlaceOrderCmd.class, cmd -> new OrderState(cmd.orderId()))
 *           .initialStep("charge-payment")
 *           .dispatch("charge-payment", "payment-service", s -> new ChargeRequest(s.orderId()), "ship-order")
 *           .dispatch("ship-order", "fulfillment-service", s -> new ShipRequest(s.orderId()), "wait-shipped")
 *           .waitFor("wait-shipped", "order.shipped", ShippedEvent.class,
 *               (s, e) -> s.withTrackingCode(e.trackingCode()), "complete")
 *           .complete("complete");
 *     }
 * }
 * }</pre>
 *
 * @param <S> the workflow state type
 * @param <C> the workflow contract interface type; must be annotated with
 *     {@link dev.vertique.workflow.contract.WorkflowContract}
 */
public interface WorkflowDefinition<S, C> {

    /**
     * Returns the contract interface type for this workflow definition.
     *
     * @return the contract interface class; must be annotated with
     *     {@link dev.vertique.workflow.contract.WorkflowContract}
     */
    Class<C> contract();

    /**
     * Returns the workflow state type.
     *
     * @return the state class; must be JSON-serializable
     */
    Class<S> stateType();

    /**
     * Returns the unique identifier for this workflow definition.
     *
     * @return the definition id; must match the {@code definitionId} on the contract annotation
     */
    String definitionId();

    /**
     * Returns the version of this workflow definition.
     *
     * @return the definition version; must match the {@code definitionVersion} on the contract
     *     annotation
     */
    long definitionVersion();

    /**
     * Defines the workflow structure using the fluent DSL.
     *
     * <p>MUST call {@code wf.init(startPayloadType, initialState)} exactly once before any step
     * DSL methods. The start payload type and the initializer function are the single source of
     * truth; they are extracted from the builder's output and stored on the {@code RuntimeWorkflow}.
     *
     * @param wf the workflow builder DSL to use; must not be null
     */
    void define(WorkflowBuilder<S> wf);
}
