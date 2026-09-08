// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

/**
 * INTERNAL framework seam — HTTP-runtime collaborator consumed by sibling framework modules; not
 * an application contract and outside the maturity promise. An application uses the extension
 * points and configuration this module documents and never names this type.
 *
 * <p>Typed, non-instantiable marker signalling that the authentication/authorization enforcement
 * runtime is installed.
 *
 * <p>The route registrar uses the presence of this binding (via an {@code Optional}) to decide
 * whether restrictive security annotations have a supporting runtime. When the binding is absent and
 * an operation declares a restrictive {@link SecurityPolicy}, route registration fails fast at
 * startup (see {@link dev.vertique.rest.core.router.OperationHandlerContributor} consumers and the
 * JAX-RS route validator).
 *
 * <p>This type is deliberately non-instantiable from application code: its constructor is
 * {@code private} and the single framework-owned value is exposed as {@link #INSTANCE}. Only the
 * security module's Dagger module binds it, so an application cannot accidentally {@code new} a
 * value and spoof the "auth installed" signal. Replacing the former {@code @Named}-qualified
 * {@code Boolean} presence signal with this typed marker is the change recorded in ADR-0105.
 */
public final class AuthEnforcementCapability {

    /** The single framework-owned instance of this marker, bound only by the security module. */
    public static final AuthEnforcementCapability INSTANCE = new AuthEnforcementCapability();

    private AuthEnforcementCapability() {}
}
