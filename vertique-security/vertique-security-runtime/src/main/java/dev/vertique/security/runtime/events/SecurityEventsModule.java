// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.security.events.SecurityEventObserver;
import java.util.Set;

/**
 * Dagger {@link Module} that owns the canonical security-event fan-out wiring for the framework.
 *
 * <p>This module declares the <strong>single</strong> {@code Set<SecurityEventObserver>}
 * multibinding for the whole framework and makes the transport-neutral {@link SecurityEventEmitter}
 * available to any enforcement layer. Per ADR-0114 the emitter and this multibinding declaration
 * live in {@code vertique-core} so REST, WebSocket, services, and jobs can all emit security events
 * through one shared emitter rather than each surface owning its own.
 *
 * <p>The {@link SecurityEventEmitter} is a {@code @Singleton} with an {@code @Inject} constructor,
 * so any Dagger component that includes this module can inject it directly; this module only needs
 * to declare the empty-by-default observer set it fans out to.
 *
 * <p>Observer contributions come from surface and integration modules via
 * {@code @Provides @IntoSet SecurityEventObserver} (e.g. audit, OpenTelemetry, Micrometer). Each
 * surface contributes only its {@code @IntoSet} observers; the multibinding set itself is declared
 * exactly once, here, so the declaration is never duplicated across surfaces.
 *
 * <p>Include this module in any Dagger {@code @Component} that needs to emit or observe security
 * lifecycle events. Surface modules that previously declared their own
 * {@code @Multibinds Set<SecurityEventObserver>} (the REST {@code AuthModule} and the WebSocket
 * {@code WebSocketModule}) now include this module instead.
 *
 * @see SecurityEventEmitter
 * @see SecurityEventObserver
 */
@Module
public abstract class SecurityEventsModule {

    // --- multibinding declaration ---

    /**
     * Declares the empty-by-default multibinding set of {@link SecurityEventObserver}s — the single
     * canonical declaration for the framework.
     *
     * <p>Modules contribute entries via {@code @Provides @IntoSet SecurityEventObserver}. The
     * framework consumes this set through {@link SecurityEventEmitter}, which fans out each security
     * lifecycle event to every registered observer with per-observer failure isolation.
     *
     * @return the set of security event observers (may be empty when no observer is contributed)
     */
    @Multibinds
    abstract Set<SecurityEventObserver> securityEventObservers();
}
