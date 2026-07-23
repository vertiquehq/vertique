// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.ReleaseInventory;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub implementation of {@link InventoryService} for integration tests.
 *
 * <p>Records all reserve and release calls in thread-safe lists. The IT tests drive the saga
 * by checking these lists and posting the corresponding signals directly via
 * {@link dev.vertique.workflow.ops.WorkflowOperations} (injected in the test, not here, to avoid a
 * Dagger dependency cycle through the service-contract multibinding).
 *
 * <p>When {@link StubScenario#inventoryShouldFail()} is {@code true}, {@link #reserve} returns a
 * failed future to test the no-compensation path.
 */
@Singleton
public final class StubInventoryService implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(StubInventoryService.class);

    private final StubScenario scenario;

    private final List<ReserveInventory> reserveCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<ReleaseInventory> releaseCalls = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new stub inventory service.
     *
     * @param scenario the scenario toggle controlling success/failure mode
     */
    @Inject
    public StubInventoryService(StubScenario scenario) {
        this.scenario = scenario;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the call. If {@link StubScenario#inventoryShouldFail()} is set, returns a failed
     * future. Otherwise returns success; the IT test is responsible for posting the
     * {@code inventory.reserved} signal to advance the saga.
     *
     * @param cmd the reservation command
     * @return a succeeded or failed future based on the current scenario
     */
    @Override
    public Future<Void> reserve(ReserveInventory cmd) {
        reserveCalls.add(cmd);
        log.info("StubInventoryService.reserve orderId={} fail={}", cmd.orderId(), scenario.inventoryShouldFail());

        if (scenario.inventoryShouldFail()) {
            return Future.failedFuture(new IllegalStateException("Stub: inventory reserve forced failure"));
        }

        return Future.succeededFuture();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the call and returns success immediately.
     *
     * @param cmd the release command
     * @return a succeeded future
     */
    @Override
    public Future<Void> release(ReleaseInventory cmd) {
        releaseCalls.add(cmd);
        log.info("StubInventoryService.release orderId={} reservationId={}", cmd.orderId(), cmd.reservationId());
        return Future.succeededFuture();
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #reserve(ReserveInventory)} calls received.
     *
     * @return list of reserve calls in call order
     */
    public List<ReserveInventory> reserveCalls() {
        return List.copyOf(reserveCalls);
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #release(ReleaseInventory)} calls received.
     *
     * @return list of release calls in call order
     */
    public List<ReleaseInventory> releaseCalls() {
        return List.copyOf(releaseCalls);
    }

    /** Clears all recorded calls. */
    public void reset() {
        reserveCalls.clear();
        releaseCalls.clear();
    }
}
