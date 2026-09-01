// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import java.util.Set;

/** Internal observer fan-out that isolates optional diagnostics from admission behavior. */
final class RateLimitObservationSupport {

    private RateLimitObservationSupport() {}

    static void emit(Set<RateLimitObserver> observers, RateLimitEvent event) {
        for (RateLimitObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable ignored) {
                // Observer failures must never alter admission or the enclosing operation.
            }
        }
    }
}
