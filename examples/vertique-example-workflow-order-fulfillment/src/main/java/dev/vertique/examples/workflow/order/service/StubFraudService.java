// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.ScreenFraud;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub fraud-screening service for integration tests.
 *
 * <p>Records all {@link #screen(ScreenFraud)} calls in a thread-safe list. The IT drives the
 * fan-out saga by checking this list and posting the corresponding {@code fraud.screened} branch
 * signal directly via
 * {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations}.
 */
@Singleton
public final class StubFraudService {

    private static final Logger log = LoggerFactory.getLogger(StubFraudService.class);

    private final List<ScreenFraud> screenCalls = Collections.synchronizedList(new ArrayList<>());

    /**
     * Creates a new stub fraud service.
     */
    @Inject
    public StubFraudService() {}

    /**
     * Records the fraud-screening call and returns success immediately.
     *
     * <p>The IT test is responsible for posting the {@code fraud.screened} branch signal to
     * advance the branch after this method returns.
     *
     * @param cmd the fraud-screening command
     * @return a succeeded future
     */
    public Future<Void> screen(ScreenFraud cmd) {
        screenCalls.add(cmd);
        log.info("StubFraudService.screen orderId={}", cmd.orderId());
        return Future.succeededFuture();
    }

    /**
     * Returns an unmodifiable snapshot of all {@link #screen(ScreenFraud)} calls received.
     *
     * @return list of screen calls in call order
     */
    public List<ScreenFraud> screenCalls() {
        return List.copyOf(screenCalls);
    }

    /** Clears all recorded calls. */
    public void reset() {
        screenCalls.clear();
    }
}
