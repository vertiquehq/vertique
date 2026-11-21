// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Timeout;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * A class with its own {@code @Resilient} method, so the AOP processor still emits its module in
 * this package while {@link ResilientService}, a service contract, is left to the services pipeline.
 * Without it the proof would compile only when the processor wrongly weaves the contract.
 */
@Singleton
public class ResilientProbe {

    /** Creates the probe. */
    @Inject
    public ResilientProbe() {}

    /**
     * Succeeds immediately.
     *
     * @return a succeeded future
     */
    @Resilient
    @Timeout(2000)
    public Future<String> ping() {
        return Future.succeededFuture("pong");
    }
}
