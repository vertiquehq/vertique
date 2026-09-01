// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.ratelimit.spi.event.RateLimitEvent;

/**
 * Optional provider-neutral observation seam for the sealed rate-limit event vocabulary.
 *
 * <p>Events are emitted synchronously by the shared rate-limit runtime, once per completed
 * admission decision. Implementations must remain bounded and non-blocking; observer failures are
 * caught and swallowed by the emitter and never alter the admission decision.
 */
@FunctionalInterface
public interface RateLimitObserver {
    /**
     * Observes one redacted rate-limit event. Exceptions thrown by this callback are caught and
     * swallowed; they do not affect the enclosing operation.
     *
     * @param event the sealed, immutable event payload
     */
    void onEvent(RateLimitEvent event);
}
