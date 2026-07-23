// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;

/**
 * Context passed to each {@link InboundContextInitializer#initialize(InboundContextInitializationContext)}
 * call at first ingress.
 *
 * <p>The boundary string (e.g. {@code "kafka"}, {@code "workflow-branch"}, {@code "http"})
 * identifies the entry point at which default context values are being seeded. Initializers may
 * use the boundary to decide whether to install a value or return {@link ContextScopes#noop()}.
 *
 * @param boundary the entry-point identifier; never {@code null} or blank
 */
public record InboundContextInitializationContext(String boundary) {

    /**
     * Compact constructor that validates the boundary.
     *
     * @param boundary the entry-point identifier; must not be {@code null} or blank
     * @throws NullPointerException     if {@code boundary} is {@code null}
     * @throws IllegalArgumentException if {@code boundary} is blank
     */
    public InboundContextInitializationContext {
        Objects.requireNonNull(boundary, "boundary must not be null");
        if (boundary.isBlank()) {
            throw new IllegalArgumentException("boundary must not be blank");
        }
    }
}
