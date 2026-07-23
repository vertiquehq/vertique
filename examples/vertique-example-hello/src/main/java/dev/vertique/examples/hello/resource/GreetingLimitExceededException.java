// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello.resource;

/**
 * Thrown when a greeting name exceeds the configured character limit.
 * This is a pure domain exception with no HTTP or JAX-RS dependency.
 * Mapped to a 429 response with a custom {@link GreetingLimitProblemDetail}
 * via {@link GreetingLimitExceptionMapper}.
 */
public class GreetingLimitExceededException extends RuntimeException {

    private final int limit;

    public GreetingLimitExceededException(int limit) {
        super("Greeting limit of " + limit + " exceeded");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
