// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.plan.BranchResult;
import java.util.Map;

/**
 * Workflow state record for the document-defined fan-out order-fulfillment integration test.
 *
 * <p>Accumulates branch-level outputs as each parallel branch completes its signal step. All
 * optional fields are nullable so the record serialises cleanly to JSON throughout the workflow
 * lifecycle.
 *
 * <p>The static {@code apply*} reducer methods are contributed as named callbacks to the
 * {@link dev.vertique.workflow.definition.callbacks.StateReducerRegistry} and
 * {@link dev.vertique.workflow.definition.callbacks.BranchResultReducerRegistry} by the IT's
 * {@code FanOutCallbackModule}.
 *
 * @param orderId the order identifier populated from {@link PlaceFanOutOrder}
 * @param inventoryReservationId the reservation identifier set when the {@code inventory.reserved.doc}
 *     branch signal arrives; {@code null} until then
 * @param paymentChargeId the charge identifier set when the {@code payment.authorized.doc}
 *     branch signal arrives; {@code null} until then
 * @param fraudCleared set to {@code true} when the {@code fraud.screened.doc} branch signal
 *     arrives and the fraud check passed; {@code null} until then
 */
public record OrderFanOutState(
        String orderId, String inventoryReservationId, String paymentChargeId, Boolean fraudCleared) {

    /**
     * Maps a {@link PlaceFanOutOrder} start payload to the initial {@link OrderFanOutState}.
     *
     * @param payload the start payload; must not be {@code null}
     * @return initial state with {@code orderId} populated and all other fields {@code null}
     */
    public static OrderFanOutState fromPayload(PlaceFanOutOrder payload) {
        return new OrderFanOutState(payload.orderId(), null, null, null);
    }

    /**
     * Applies the {@link InventoryReservedDoc} branch signal, recording the reservation id.
     *
     * @param state  the current state; must not be {@code null}
     * @param signal the signal payload; must not be {@code null}
     * @return updated state with {@code inventoryReservationId} populated
     */
    public static OrderFanOutState applyInventoryReserved(OrderFanOutState state, InventoryReservedDoc signal) {
        return new OrderFanOutState(
                state.orderId(), signal.reservationId(), state.paymentChargeId(), state.fraudCleared());
    }

    /**
     * Applies the {@link PaymentAuthorizedDoc} branch signal, recording the charge id.
     *
     * @param state  the current state; must not be {@code null}
     * @param signal the signal payload; must not be {@code null}
     * @return updated state with {@code paymentChargeId} populated
     */
    public static OrderFanOutState applyPaymentAuthorized(OrderFanOutState state, PaymentAuthorizedDoc signal) {
        return new OrderFanOutState(
                state.orderId(), state.inventoryReservationId(), signal.paymentChargeId(), state.fraudCleared());
    }

    /**
     * Applies the {@link FraudScreenedDoc} branch signal, recording the fraud-screen result.
     *
     * @param state  the current state; must not be {@code null}
     * @param signal the signal payload; must not be {@code null}
     * @return updated state with {@code fraudCleared} populated
     */
    public static OrderFanOutState applyFraudScreened(OrderFanOutState state, FraudScreenedDoc signal) {
        return new OrderFanOutState(
                state.orderId(), state.inventoryReservationId(), state.paymentChargeId(), signal.fraudCleared());
    }

    /**
     * Join reducer: merges all three branch results into the final state after the ALL_REQUIRED
     * join succeeds. Because each branch's state updater writes to a distinct field on the parent
     * instance state, the accumulated state already contains all three outputs by the time the join
     * fires. This reducer is a no-op identity over the current state.
     *
     * @param state   the current workflow state after all three branch signals have been applied
     * @param results the branch result map (keyed by branch id); not used directly because each
     *     branch's signal already updated the shared parent state
     * @return the current state, unchanged
     */
    public static OrderFanOutState mergeBranchResults(OrderFanOutState state, Map<String, BranchResult> results) {
        return state;
    }
}
