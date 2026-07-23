// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a method or type requires an action authorization check.
 *
 * <p>When placed on a JAX-RS resource method or class, a WebSocket endpoint class, or a service
 * method/class, the framework's enforcement layer (PEP) will evaluate the declared action against
 * the request's {@link SecurityContext} before dispatching. The action must be a canonical,
 * three-segment {@link ActionRef} value ({@code <subsystem>.<resource>.<verb>}, e.g.
 * {@code "cms.content.read"}).
 *
 * <p><strong>And-composition with Jakarta security annotations:</strong>
 * {@code @RequiresAction} AND-composes with {@code @RolesAllowed} and {@code @Authorized}: both
 * the role/scope gate <em>and</em> the action gate must pass. Combining {@code @RequiresAction}
 * with {@code @PermitAll} or {@code @DenyAll} on the same element is a conflict and will be
 * rejected at compile time (by the code-generation annotation processor) and at startup.
 *
 * <p><strong>Transport neutrality:</strong> this annotation is defined in
 * {@code vertique-core} and carries no dependency on any transport module. Each transport surface
 * (REST, WebSocket, services) provides its own PEP adapter that reads and enforces it.
 *
 * <p><strong>Fail-closed invariant:</strong> any surface that does <em>not</em> enforce
 * {@code @RequiresAction} must reject its presence at startup. An unenforceable annotation is a
 * startup error, never silently ignored.
 *
 * @see ActionRef
 * @see Authorizer
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAction {

    /**
     * The canonical action string in {@code <subsystem>.<resource>.<verb>} format.
     *
     * <p>The value must be parseable by {@link ActionRef#parse(String)}: three dot-separated
     * segments, each matching {@code ^[a-z][a-z0-9]*$}.
     *
     * @return the action value; never empty
     */
    String value();
}
