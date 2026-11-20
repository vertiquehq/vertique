// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.examples.workflow.order.signal.ShipmentCreated;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub implementation of {@link ShippingService} for integration tests.
 *
 * <p>Records all {@link #createShipment(CreateShipment)} calls and returns the shipment result.
 * The returned shipment resumes the saga.
 *
 * <p>Calls are recorded in a thread-safe list for test assertions.
 */
@Singleton
public final class StubShippingService implements ShippingService {

    private static final Logger log = LoggerFactory.getLogger(StubShippingService.class);

    private final StubScenario scenario;
    private final List<CreateShipment> createCalls = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new stub shipping service.
     */
    @Inject
    public StubShippingService(StubScenario scenario) {
        this.scenario = scenario;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the call and returns the shipment result.
     *
     * @param cmd the create-shipment command
     * @return a succeeded future
     */
    @Override
    public Future<ShipmentCreated> createShipment(CreateShipment cmd) {
        createCalls.add(cmd);
        log.info("StubShippingService.createShipment orderId={}", cmd.orderId());
        if (scenario.shippingShouldFail()) return Future.failedFuture(new IllegalStateException("Shipment failed"));
        return Future.succeededFuture(new ShipmentCreated(cmd.orderId(), "trk-" + cmd.orderId()));
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #createShipment(CreateShipment)} calls.
     *
     * @return list of create-shipment calls in call order
     */
    public List<CreateShipment> createCalls() {
        return List.copyOf(createCalls);
    }

    /** Clears all recorded calls. */
    public void reset() {
        createCalls.clear();
    }
}
