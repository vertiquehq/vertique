// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.VoidAuthorization;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub implementation of {@link PaymentService} for integration tests.
 *
 * <p>When {@link StubScenario#paymentShouldFail()} is {@code true}, {@link #authorize} returns a
 * failed future, triggering the compensation flow for any completed compensable forward steps.
 * When {@code false}, {@link #authorize} succeeds immediately (the IT signals
 * {@code payment.captured} separately to advance the saga).
 *
 * <p>Calls are recorded in a thread-safe list for test assertions.
 */
@Singleton
public final class StubPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentService.class);

    // --- Dependencies ---

    private final StubScenario scenario;

    // --- State ---

    private final List<AuthorizePayment> authorizeCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<VoidAuthorization> voidCalls = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new stub payment service.
     *
     * @param scenario the scenario toggle controlling success/failure mode
     */
    @Inject
    public StubPaymentService(StubScenario scenario) {
        this.scenario = scenario;
    }

    /**
     * {@inheritDoc}
     *
     * <p>If {@link StubScenario#paymentShouldFail()} is set, returns a failed future to trigger
     * compensation. Otherwise succeeds; the IT is expected to post the {@code payment.captured}
     * signal to advance the saga.
     *
     * @param cmd the authorization command
     * @return a {@link Future} that completes (or fails, depending on scenario) immediately
     */
    @Override
    public Future<Void> authorize(AuthorizePayment cmd) {
        authorizeCalls.add(cmd);
        log.info("StubPaymentService.authorize orderId={} fail={}", cmd.orderId(), scenario.paymentShouldFail());

        if (scenario.paymentShouldFail()) {
            return Future.failedFuture(new IllegalStateException("Stub: payment authorize forced failure"));
        }

        return Future.succeededFuture();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the call and returns success immediately.
     *
     * @param cmd the void-authorization command
     * @return a succeeded future
     */
    @Override
    public Future<Void> voidAuthorization(VoidAuthorization cmd) {
        voidCalls.add(cmd);
        log.info("StubPaymentService.voidAuthorization orderId={} authId={}", cmd.orderId(), cmd.authorizationId());
        return Future.succeededFuture();
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #authorize(AuthorizePayment)} calls received.
     *
     * @return list of authorize calls in call order
     */
    public List<AuthorizePayment> authorizeCalls() {
        return List.copyOf(authorizeCalls);
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #voidAuthorization(VoidAuthorization)} calls
     * received.
     *
     * @return list of void-authorization calls in call order
     */
    public List<VoidAuthorization> voidCalls() {
        return List.copyOf(voidCalls);
    }

    /** Clears all recorded calls. */
    public void reset() {
        authorizeCalls.clear();
        voidCalls.clear();
    }
}
