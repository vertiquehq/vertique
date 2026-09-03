// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/** Service contract used to exercise a named {@code @Resilient} policy through a generated proxy. */
@ServiceContract(namespace = "resilience", value = "probe")
public interface ResilienceProbeService {

    /**
     * Executes the transient-failure probe.
     *
     * @param key probe key
     * @return the successful probe result
     */
    @ServiceOperation("probe")
    Future<String> probe(String key);
}
