// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Internal Dagger-injected accessor for the framework's inbound dispatch path.
 *
 * <p>Wraps two operations that the inbound service dispatcher
 * ({@code ServiceMethodInvoker}) needs at the start and during arg extraction of every dispatch:
 *
 * <ol>
 *   <li>{@link #install(Map)} — install a sanitized FQCN-keyed dispatch-context map for the
 *       lifetime of one dispatch, returning a {@link ContextHolder.Scope} that restores prior
 *       per-entry values on close. Uses per-entry {@code Map.compute} so two dispatches that
 *       share a Vert.x context do not wipe each other's view.
 *   <li>{@link #currentRawValueByKey(String)} — by-FQCN-string raw read used by reflective arg
 *       extraction when the handler parameter declares an SC subtype (the value is stored
 *       under the canonical {@code SecurityContext} FQCN, not the subtype's).
 * </ol>
 *
 * <p><strong>Internal framework API.</strong> Consumers should depend on
 * {@link ContextHolder} via Dagger and bind values through it; this class is exposed only so
 * the inbound dispatcher can avoid coupling to {@link DefaultContextHolder} static helpers.
 */
@Singleton
public final class InboundDispatchScope {

    /**
     * Constructs the accessor. The underlying {@link io.vertx.core.spi.context.storage.ContextLocal}
     * slot is registered by {@link ContextLocalServiceProvider} at Vert.x bootstrap and reached
     * via {@link DefaultContextHolder}'s package-internal static helpers.
     */
    @Inject
    public InboundDispatchScope() {}

    /**
     * Installs an FQCN-keyed map of dispatch-context values for the lifetime of one dispatch.
     * Returns a {@link ContextHolder.Scope}; close restores prior per-entry values (or removes
     * keys that were absent before).
     *
     * @param values FQCN-keyed values to install; null or empty returns a no-op scope
     * @return scope that unwinds the install on close
     */
    public ContextHolder.Scope install(Map<String, Object> values) {
        return DefaultContextHolder.installScoped(values);
    }

    /**
     * Returns the raw value stored under the given FQCN key in the current Vert.x context's
     * value map, without any type check.
     *
     * @param key the FQCN key to look up
     * @return the raw value, or {@code null} if absent or outside a Vert.x context
     */
    public Object currentRawValueByKey(String key) {
        return DefaultContextHolder.currentRawValueByKey(key);
    }
}
