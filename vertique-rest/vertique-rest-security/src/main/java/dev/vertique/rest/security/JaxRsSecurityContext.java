// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorityKind;
import java.security.Principal;

/**
 * Bridges the framework's {@link SecurityContext} to the standard
 * {@link jakarta.ws.rs.core.SecurityContext} interface.
 *
 * <p>This enables portable JAX-RS code that uses standard security context injection:
 * <pre>{@code
 * @GET
 * public Response getResource(jakarta.ws.rs.core.SecurityContext sc) {
 *     Principal p = sc.getUserPrincipal();
 *     boolean isAdmin = sc.isUserInRole("admin");
 *     // ...
 * }
 * }</pre>
 *
 * <p>The four JAX-RS bridge methods read from the new typed identity model:
 * <ul>
 *   <li>{@link #getUserPrincipal()} — wraps {@code identity().actor()} as a
 *       {@link java.security.Principal} whose {@code getName()} returns {@code actor.id()}</li>
 *   <li>{@link #isUserInRole(String)} — filters {@code authorization().claims()} for
 *       {@link AuthorityKind#ROLE} entries</li>
 *   <li>{@link #isSecure()} — derived from {@code origin().map(o -> "https".equals(o.scheme()))},
 *       or the constructor-supplied {@code secure} flag as a fallback</li>
 *   <li>{@link #getAuthenticationScheme()} — mapped from
 *       {@code authentication().primaryMethod().normalizedKind()}</li>
 * </ul>
 */
public class JaxRsSecurityContext implements jakarta.ws.rs.core.SecurityContext {

    private final SecurityContext frameworkContext;
    private final boolean secure;

    /**
     * Creates a new JAX-RS security context bridge.
     *
     * @param frameworkContext the framework's security context
     * @param secure           {@code true} if the request was made over HTTPS; used as a
     *                         fallback when {@code origin()} is not yet populated
     */
    public JaxRsSecurityContext(SecurityContext frameworkContext, boolean secure) {
        this.frameworkContext = frameworkContext;
        this.secure = secure;
    }

    /**
     * Returns a {@link Principal} wrapping {@code identity().actor()} whose {@code getName()}
     * returns the actor's {@code id}.
     *
     * <p>Per JAX-RS semantics, returns {@code null} when there is no authenticated user: either
     * because the framework context is absent, or because the actor type is
     * {@link PrincipalType#ANONYMOUS}.
     *
     * @return the principal, or {@code null} when unauthenticated or context is absent
     */
    @Override
    public Principal getUserPrincipal() {
        if (frameworkContext == null) {
            return null;
        }
        if (frameworkContext.identity().actor().type() == PrincipalType.ANONYMOUS) {
            return null;
        }
        String actorId = frameworkContext.identity().actor().id();
        return () -> actorId;
    }

    /**
     * Checks whether the authenticated principal holds the given role by scanning
     * {@code authorization().claims()} for {@link AuthorityKind#ROLE} entries.
     *
     * @param role the role name to check
     * @return {@code true} if a ROLE claim with the given value is present; {@code false}
     *         when the framework context is {@code null} or no matching claim is found
     */
    @Override
    public boolean isUserInRole(String role) {
        if (frameworkContext == null || role == null) {
            return false;
        }
        // Enforce the JAX-RS contract at the bridge: an anonymous principal is never in any role,
        // regardless of whether the context somehow carries role claims.
        if (frameworkContext.identity().actor().type() == PrincipalType.ANONYMOUS) {
            return false;
        }
        return frameworkContext.authorization().claims().stream()
                .anyMatch(c -> c.kind() == AuthorityKind.ROLE && role.equals(c.value()));
    }

    /**
     * Returns {@code true} if the effective request scheme is {@code "https"}.
     *
     * <p>Prefers {@code origin().scheme()} when the {@link dev.vertique.security.origin.RequestOrigin}
     * is present (populated by {@link OriginCaptureMiddleware}); falls back to the
     * constructor-supplied {@code secure} flag which reflects the TLS state of the raw
     * {@link io.vertx.ext.web.RoutingContext#request()}.
     *
     * @return {@code true} when the request is over HTTPS
     */
    @Override
    public boolean isSecure() {
        if (frameworkContext != null) {
            return frameworkContext
                    .origin()
                    .map(o -> "https".equals(o.scheme()))
                    .orElse(secure);
        }
        return secure;
    }

    /**
     * Maps {@code authentication().primaryMethod().normalizedKind()} to a JAX-RS
     * authentication scheme string.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@link AuthMethodKind#JWT} → {@code "BEARER"}</li>
     *   <li>{@link AuthMethodKind#BASIC} → {@link jakarta.ws.rs.core.SecurityContext#BASIC_AUTH}</li>
     *   <li>{@link AuthMethodKind#API_KEY} → {@code "API_KEY"}</li>
     *   <li>{@link AuthMethodKind#MTLS} → {@link jakarta.ws.rs.core.SecurityContext#CLIENT_CERT_AUTH}</li>
     *   <li>{@link AuthMethodKind#HMAC} → {@code "HMAC"}</li>
     *   <li>{@link AuthMethodKind#CUSTOM} or {@link AuthMethodKind#UNKNOWN} → the method id
     *       upper-cased</li>
     *   <li>{@link AuthMethodKind#NONE} → {@code null}</li>
     * </ul>
     *
     * @return the authentication scheme string, or {@code null} when the framework context
     *         is absent or the method is {@link AuthMethodKind#NONE}
     */
    @Override
    public String getAuthenticationScheme() {
        if (frameworkContext == null) {
            return null;
        }
        return switch (frameworkContext.authentication().primaryMethod().normalizedKind()) {
            case JWT -> "BEARER";
            case BASIC -> jakarta.ws.rs.core.SecurityContext.BASIC_AUTH;
            case API_KEY -> "API_KEY";
            case MTLS -> jakarta.ws.rs.core.SecurityContext.CLIENT_CERT_AUTH;
            case HMAC -> "HMAC";
            case CUSTOM, UNKNOWN ->
                frameworkContext.authentication().primaryMethod().id().toUpperCase();
            case NONE -> null;
        };
    }
}
