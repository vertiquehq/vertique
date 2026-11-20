// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy.service;

import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Direct implementation of {@link ResilientService}; it declares no resilience of its own. */
@Singleton
public class ResilientServiceImpl implements ResilientService {

    private final ResilientAttempts attempts;

    /**
     * Creates the implementation.
     *
     * @param attempts the counter of body runs
     */
    @Inject
    public ResilientServiceImpl(ResilientAttempts attempts) {
        this.attempts = attempts;
    }

    @Override
    public Future<String> alwaysFails(String resourceId) {
        int run = attempts.record();
        return Future.failedFuture(new IllegalStateException("failure " + run));
    }
}
