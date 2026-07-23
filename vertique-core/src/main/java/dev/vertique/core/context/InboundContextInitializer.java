// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for installing default holder-bound values at first ingress when no upstream-decoded value
 * is present.
 *
 * <p>Used by {@link InboundExecutionContextScope} after the inbound install/bind step. Each
 * registered initializer is invoked in iteration order; the returned scopes are composed and
 * closed LIFO by the containing {@link InboundExecutionContextScope} composite scope.
 *
 * <p>Registered via Dagger multibindings as {@code Set<InboundContextInitializer>}. Initializers
 * MUST be idempotent: returning {@link ContextScopes#noop()} when their concern is already bound
 * is the normal path.
 *
 * <p>Implementations MUST NOT block the Vert.x event loop.
 */
public interface InboundContextInitializer {

    /**
     * Inspects the holder for the initializer's concern; if absent, installs a default value and
     * returns a scope; if already present, returns {@link ContextScopes#noop()}.
     *
     * @param context the initialization context carrying the boundary identifier; never {@code null}
     * @return a scope that undoes any bindings installed by this call; never {@code null}
     */
    ContextHolder.Scope initialize(InboundContextInitializationContext context);
}
