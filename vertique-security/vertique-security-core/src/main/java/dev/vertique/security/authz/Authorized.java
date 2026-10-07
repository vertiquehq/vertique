// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a supported framework type or method requires scope-based authorization, extending
 * role-based checks with fine-grained scope requirements where the consumer supports it.
 *
 * <p>Scopes are evaluated by the consuming framework integration. Empty scopes require
 * authentication only. When scopes are present, {@linkplain #matchAll() matchAll} selects whether
 * the caller must have every scope or any one scope.
 *
 * <p>Can be combined with standard {@code @RolesAllowed} for role and scope checks: the caller must
 * satisfy both requirements.
 *
 * <p>When placed on a class, applies to all methods unless overridden at method level. Method-level
 * {@code @Authorized} overrides class-level on consumers that support this annotation.
 *
 * <p>Usage on a JAX-RS resource:
 *
 * <pre>{@code
 * import dev.vertique.security.authz.Authorized;
 *
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

    /** Required scopes/permissions; an empty array means authentication-only access. */
    String[] scopes() default {};

    /**
     * If true, the principal must have ALL specified scopes; if false, any one scope suffices.
     * Defaults to true.
     */
    boolean matchAll() default true;
}
