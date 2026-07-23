// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;

/**
 * DI-managed service for accessing and managing the current request's {@link SecurityContext}.
 *
 * <p>The implementation is provided by the security package (backed by {@code ContextHolder}
 * storage) and consumed by the REST module (parameter injection) and auth module (context
 * lifecycle).
 *
 * <p>Injected as {@code @Nullable} into {@code JaxRsRouterMount.Factory}. Returns {@code null}
 * from {@link #current()} when no security context has been bound for the current request.
 * When the security module is not configured, this interface is not bound and the
 * {@code @Nullable} injection provides {@code null}.
 *
 * <p>The caller that binds a {@link SecurityContext} receives a {@link ContextHolder.Scope} and is
 * responsible for handing it to the per-request lifecycle (e.g.
 * {@code RequestContextLifecycle.Handle.onClose(scope)}) so the binding is released at
 * request end.
 */
public interface SecurityRuntime {

    /**
     * Returns the {@link SecurityContext} for the current request, or {@code null} if no context
     * has been bound on the current Vert.x context.
     *
     * @return the current security context, or {@code null} if no context is bound
     */
    SecurityContext current();

    /**
     * Binds the given {@link SecurityContext} into the per-request {@link ContextHolder} and
     * returns a {@link ContextHolder.Scope} that removes the binding on close.
     *
     * <p>The caller must hand the returned scope to the per-request lifecycle so the binding is
     * released at request end. Typically:
     * <pre>{@code
     * RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
     * lifecycle.onClose(securityRuntime.bindCurrent(sc));
     * }</pre>
     *
     * @param context the security context to bind; must not be {@code null}
     * @return a scope that removes the binding when closed; never {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    ContextHolder.Scope bindCurrent(SecurityContext context);

    /**
     * Creates a JAX-RS {@link jakarta.ws.rs.core.SecurityContext} bridge
     * from the framework's {@link SecurityContext}.
     *
     * @param context the framework security context (may be {@code null} for anonymous requests)
     * @param secure  whether the request was made over a secure channel (HTTPS)
     * @return a JAX-RS SecurityContext instance, or {@code null} if no bridge factory is available
     */
    jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure);
}
