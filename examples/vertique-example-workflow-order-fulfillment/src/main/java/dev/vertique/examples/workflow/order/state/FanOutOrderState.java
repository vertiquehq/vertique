// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.state;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.workflow.plan.BranchResult;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Durable state of the ALL_REQUIRED fan-out order-fulfillment workflow instance.
 *
 * <p>Serialized to/from JSONB by the workflow engine. Used by
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition}.
 * Branch signal callbacks in the PRD-WF-002 branch model decode from the parent instance's
 * {@code state_json} via the stateUpdater; this record accumulates the results of all three
 * parallel branches (inventory, payment, fraud) so each can contribute independently.
 *
 * @param orderId       the client-assigned order identifier
 * @param customerId    the customer who placed the order
 * @param items         line items in the order
 * @param totalCents    total order amount in cents
 * @param status        current application-level status of the order
 * @param reservationId inventory reservation id; set after the inventory branch signal arrives
 * @param chargeId      payment charge id; set after the payment branch signal arrives
 * @param screeningRef  fraud-screening reference id; set after the fraud branch signal arrives
 * @param trackingNumber shipment tracking number; set after shipment is created post-join
 */
public record FanOutOrderState(
        String orderId,
        String customerId,
        List<PlaceOrder.OrderItem> items,
        long totalCents,
        OrderStatus status,
        @Nullable String reservationId,
        @Nullable String chargeId,
        @Nullable String screeningRef,
        @Nullable String trackingNumber) {

    /**
     * Derives the initial {@link FanOutOrderState} from a {@link PlaceOrder} start command.
     *
     * @param cmd the start command
     * @return initial state with {@link OrderStatus#PENDING} and no reservation, payment,
     *     fraud, or shipment ids
     */
    public static FanOutOrderState fromCommand(PlaceOrder cmd) {
        return new FanOutOrderState(
                cmd.orderId(),
                cmd.customerId(),
                cmd.items(),
                cmd.totalCents(),
                OrderStatus.PENDING,
                null,
                null,
                null,
                null);
    }

    /**
     * Transitions the state after the inventory branch receives its reservation signal.
     *
     * <p>Called by the {@code screen-inventory} branch's stateUpdater when the
     * {@code inventory.reserved} signal arrives on the {@code inventory} branch.
     *
     * @param reservationId the reservation id returned by the inventory service
     * @return updated state with {@link OrderStatus#INVENTORY_RESERVED} and the reservation id set
     */
    public FanOutOrderState withReservation(String reservationId) {
        return new FanOutOrderState(
                orderId,
                customerId,
                items,
                totalCents,
                OrderStatus.INVENTORY_RESERVED,
                reservationId,
                chargeId,
                screeningRef,
                trackingNumber);
    }

    /**
     * Transitions the state after the payment branch receives its captured signal.
     *
     * <p>Called by the {@code authorize-payment} branch's stateUpdater when the
     * {@code payment.captured} signal arrives on the {@code payment} branch.
     *
     * @param chargeId the charge id returned by the payment service
     * @return updated state with the charge id set
     */
    public FanOutOrderState withPayment(String chargeId) {
        return new FanOutOrderState(
                orderId, customerId, items, totalCents, status, reservationId, chargeId, screeningRef, trackingNumber);
    }

    /**
     * Transitions the state after the fraud branch receives its screened signal.
     *
     * <p>Called by the {@code screen-fraud} branch's stateUpdater when the {@code fraud.screened}
     * signal arrives on the {@code fraud} branch.
     *
     * @param screeningRef the screening reference from the fraud service
     * @return updated state with the screening ref set
     */
    public FanOutOrderState withFraudScreening(String screeningRef) {
        return new FanOutOrderState(
                orderId, customerId, items, totalCents, status, reservationId, chargeId, screeningRef, trackingNumber);
    }

    /**
     * Reducer called by the fan-in join once all three branches have completed.
     *
     * <p>Advances the order status to {@link OrderStatus#PAYMENT_AUTHORIZED} to indicate all
     * pre-shipment checks have passed. The {@code branchResults} parameter is currently ignored
     * because the state is already populated by the individual branch stateUpdater callbacks.
     *
     * @param state         the current workflow state after all branch signals have been applied
     * @param branchResults per-branch result metadata, keyed by branch id
     * @return updated state with {@link OrderStatus#PAYMENT_AUTHORIZED}
     */
    public static FanOutOrderState mergeBranchResults(FanOutOrderState state, Map<String, BranchResult> branchResults) {
        return new FanOutOrderState(
                state.orderId(),
                state.customerId(),
                state.items(),
                state.totalCents(),
                OrderStatus.PAYMENT_AUTHORIZED,
                state.reservationId(),
                state.chargeId(),
                state.screeningRef(),
                state.trackingNumber());
    }

    /**
     * Transitions the state after shipment is created (post-join step).
     *
     * @param trackingNumber the tracking number returned by the shipping service
     * @return updated state with {@link OrderStatus#SHIPPED} and the tracking number set
     */
    public FanOutOrderState withShipment(String trackingNumber) {
        return new FanOutOrderState(
                orderId,
                customerId,
                items,
                totalCents,
                OrderStatus.SHIPPED,
                reservationId,
                chargeId,
                screeningRef,
                trackingNumber);
    }
}
