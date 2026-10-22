// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Workflow state record for the document-defined order-fulfillment integration tests.
 *
 * <p>Tracks the core identifiers accumulated as the saga progresses through the three service
 * dispatch steps. All fields are nullable (using {@code null} sentinel) so that the state can be
 * serialised to/from JSON without requiring optional wrappers.
 *
 * @param orderId       the order identifier populated from the start payload
 * @param reservationId set when the {@code inventory.reserved} signal arrives
 * @param authId        set when the {@code payment.captured} signal arrives
 * @param trackingNumber set when the {@code shipment.created} signal arrives
 */
public record OrderDocState(String orderId, String reservationId, String authId, String trackingNumber) {

    /**
     * Maps a {@link PlaceOrderDoc} start payload to the initial {@link OrderDocState}.
     *
     * @param payload the start payload; must not be {@code null}
     * @return initial state with {@code orderId} populated and all other fields {@code null}
     */
    public static OrderDocState fromPayload(PlaceOrderDoc payload) {
        return new OrderDocState(payload.orderId(), null, null, null);
    }

    /**
     * Applies the {@link InventoryReservedDoc} signal, recording the reservation id.
     *
     * @param state   the current state; must not be {@code null}
     * @param signal  the signal payload; must not be {@code null}
     * @return updated state with {@code reservationId} populated
     */
    public static OrderDocState applyInventoryReserved(OrderDocState state, InventoryReservedDoc signal) {
        return new OrderDocState(state.orderId(), signal.reservationId(), state.authId(), state.trackingNumber());
    }

    /**
     * Applies the {@link PaymentCapturedDoc} signal, recording the authorization id.
     *
     * @param state   the current state; must not be {@code null}
     * @param signal  the signal payload; must not be {@code null}
     * @return updated state with {@code authId} populated
     */
    public static OrderDocState applyPaymentCaptured(OrderDocState state, PaymentCapturedDoc signal) {
        return new OrderDocState(state.orderId(), state.reservationId(), signal.authId(), state.trackingNumber());
    }

    /**
     * Applies the {@link ShipmentCreatedDoc} signal, recording the tracking number.
     *
     * @param state   the current state; must not be {@code null}
     * @param signal  the signal payload; must not be {@code null}
     * @return updated state with {@code trackingNumber} populated
     */
    public static OrderDocState applyShipmentCreated(OrderDocState state, ShipmentCreatedDoc signal) {
        return new OrderDocState(state.orderId(), state.reservationId(), state.authId(), signal.trackingNumber());
    }
}
