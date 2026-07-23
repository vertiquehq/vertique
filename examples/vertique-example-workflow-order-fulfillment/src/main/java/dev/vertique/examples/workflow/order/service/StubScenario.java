// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared scenario toggle for the stub service implementations.
 *
 * <p>When {@link #paymentShouldFail()} returns {@code true}, {@link StubPaymentService} will
 * fail its {@code authorize} call, triggering the compensation flow. This allows integration
 * tests to switch between happy-path and failure scenarios without rebuilding the Dagger graph.
 *
 * <p>This class holds a thread-safe flag that tests can flip between scenario runs.
 */
public final class StubScenario {

    private final AtomicBoolean paymentFails = new AtomicBoolean(false);
    private final AtomicBoolean inventoryFails = new AtomicBoolean(false);

    /**
     * Creates a new {@code StubScenario} with all services in success mode.
     */
    public StubScenario() {}

    /**
     * Returns whether the payment service should fail its {@code authorize} call.
     *
     * @return {@code true} if payment should fail
     */
    public boolean paymentShouldFail() {
        return paymentFails.get();
    }

    /**
     * Sets the payment failure mode.
     *
     * @param fail {@code true} to make payment fail; {@code false} for success
     */
    public void setPaymentFails(boolean fail) {
        paymentFails.set(fail);
    }

    /**
     * Returns whether the inventory service should fail its {@code reserve} call.
     *
     * @return {@code true} if inventory reservation should fail
     */
    public boolean inventoryShouldFail() {
        return inventoryFails.get();
    }

    /**
     * Sets the inventory failure mode.
     *
     * @param fail {@code true} to make inventory reservation fail; {@code false} for success
     */
    public void setInventoryFails(boolean fail) {
        inventoryFails.set(fail);
    }
}
