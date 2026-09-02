// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract used to exercise the {@code @RateLimited} annotation through a generated typed
 * client and the AOP proxy (T013, TP-002 — transport-neutrality proof).
 */
@ServiceContract(namespace = "rate-limit", value = "probe")
public interface RateLimitProbeService {

    /**
     * The policy shared by this contract's annotated implementation and {@link
     * dev.vertique.examples.services.resource.RateLimitProbeResource}'s programmatic path, so both
     * entry points compete for the exact same token bucket — proving they resolve through the same
     * {@code RateLimiters} handle rather than two independent counters.
     */
    String POLICY_NAME = "rate-limit-probe-shared";

    /**
     * Admits the call against {@value #POLICY_NAME}.
     *
     * @return a future completing when the call is admitted; fails with {@code
     *     RateLimitExceededException} once the shared bucket is exhausted
     */
    @ServiceOperation("probe")
    Future<RateLimitProbeResult> probe();
}
