// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/** Service contract used to characterize composition of all framework annotation aspects. */
@ServiceContract(namespace = "composition", value = "probe")
public interface CompositionProbeService {

    /**
     * Runs the deterministic composition probe.
     *
     * @param key probe key
     * @return a future containing the successful probe result
     */
    @ServiceOperation("probe")
    Future<CompositionProbeResult> probe(String key);
}
