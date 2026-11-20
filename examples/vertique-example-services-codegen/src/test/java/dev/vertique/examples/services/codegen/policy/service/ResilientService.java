// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.annotation.Timeout;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract whose resilience anchor and retry declaration sit on the interface method; the
 * implementation declares neither.
 */
@ServiceContract(namespace = "policy", value = "resilient")
public interface ResilientService {

    /**
     * Always fails, so every attempt of the declared retry is visible as one counted body run.
     *
     * @param resourceId the resource the caller acts on
     * @return a failed future
     */
    @ServiceOperation("alwaysFails")
    @Resilient
    @Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
    @Timeout(2000)
    Future<String> alwaysFails(String resourceId);
}
