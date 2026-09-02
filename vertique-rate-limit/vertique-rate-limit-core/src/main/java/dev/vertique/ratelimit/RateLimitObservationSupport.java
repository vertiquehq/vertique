// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import java.util.Set;

/**
 * Internal observer fan-out that isolates optional diagnostics from admission behavior.
 *
 * <p>Adopts {@code dev.vertique.resilience.Resilience#emit}'s fatal guard: an observer failure
 * that is a {@link VirtualMachineError}, {@link ThreadDeath}, or {@link LinkageError} is rethrown
 * rather than swallowed — the JVM itself is in an unrecoverable or invalid-classloading state, and
 * masking that here would only hide it. Every other observer failure is isolated exactly as before.
 */
final class RateLimitObservationSupport {

    private RateLimitObservationSupport() {}

    static void emit(Set<RateLimitObserver> observers, RateLimitEvent event) {
        for (RateLimitObserver observer : observers) {
            try {
                observer.onEvent(event);
            } catch (Throwable failure) {
                if (isFatal(failure)) {
                    throw (Error) failure;
                }
                // Observer failures must never alter admission or the enclosing operation.
            }
        }
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError
                || failure instanceof ThreadDeath
                || failure instanceof LinkageError;
    }
}
