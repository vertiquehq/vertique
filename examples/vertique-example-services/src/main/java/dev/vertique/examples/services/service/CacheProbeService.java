// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/** Service contract used to exercise cache behavior through a generated typed client. */
@ServiceContract(namespace = "cache", value = "probe")
public interface CacheProbeService {

    /**
     * Returns the caller identity and the number of concrete handler invocations.
     *
     * @return a future containing the probe result
     */
    @ServiceOperation("probe")
    Future<CacheProbeResult> probe();
}
