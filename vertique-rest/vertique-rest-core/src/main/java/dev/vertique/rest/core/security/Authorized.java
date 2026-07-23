// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a JAX-RS resource method or class requires scope-based authorization.
 * Extends standard JAX-RS security with fine-grained permission checks.
 *
 * <p>Scopes are mapped to Vert.x {@code PermissionBasedAuthorization} instances
 * and checked via the Vert.x {@code AuthorizationHandler} before method invocation.
 *
 * <p>Can be combined with standard {@code @RolesAllowed} for role + scope checks:
 * the user must satisfy BOTH the role requirement AND the scope requirement.
 *
 * <p>When placed on a class, applies to all methods unless overridden at method level.
 * Method-level {@code @Authorized} overrides class-level.
 *
 * <p>Usage:
 * <pre>{@code
 * @GET @Path("/items")
 * @Authorized(scopes = "items:read")
 * public Future<List<Item>> listItems() { ... }
 *
 * @DELETE @Path("/items/{id}")
 * @Authorized(scopes = {"items:write", "items:delete"}, matchAll = false)
 * public Future<Void> deleteItem(@PathParam("id") String id) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Authorized {

    /**
     * Required scopes/permissions. Each scope maps to a
     * {@code PermissionBasedAuthorization} in Vert.x.
     * Empty array means authentication-only (any authenticated user).
     */
    String[] scopes() default {};

    /**
     * If true, the principal must have ALL specified scopes.
     * If false, having ANY one scope is sufficient.
     * Default: true.
     */
    boolean matchAll() default true;
}
