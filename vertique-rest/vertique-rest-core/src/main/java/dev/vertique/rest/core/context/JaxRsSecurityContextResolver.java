// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Built-in {@link RestContextResolver} that resolves the JAX-RS
 * {@link jakarta.ws.rs.core.SecurityContext} interface for the current request.
 *
 * <p>This resolver handles injection of {@code @Context jakarta.ws.rs.core.SecurityContext}
 * parameters. It delegates to {@link SecurityRuntime#toJaxRs(dev.vertique.security.SecurityContext, boolean)}
 * to produce the bridge instance. When the security module is absent (i.e., the
 * {@code Optional<SecurityRuntime>} is empty), the resolver returns empty for all types.
 *
 * <p>The match is exact: this resolver only responds to requests for
 * {@link jakarta.ws.rs.core.SecurityContext}{@code .class} precisely — it does not match subtypes
 * or supertypes. The framework's own {@code dev.vertique.security.SecurityContext} is handled
 * by {@link ContextHolderResolver}.
 *
 * <p>Priority is {@code 110} — after application resolvers ({@code 0}) and
 * {@link RoutingContextResolver} ({@code 100}), but before {@link ContextHolderResolver}
 * ({@code 120}).
 *
 * <p><b>Internal framework built-in — not an application SPI.</b> Applications must not extend or
 * contribute this resolver. To customise JAX-RS security-context bridging, implement
 * {@link SecurityRuntime} (backed by the security module).
 */
final class JaxRsSecurityContextResolver implements RestContextResolver {

    /** The security runtime, present only when the security module is wired. */
    private final Optional<SecurityRuntime> securityRuntime;

    /**
     * Constructs the resolver.
     *
     * <p>Intended for framework-internal use via Dagger. The {@code Optional<SecurityRuntime>} is
     * satisfied by a {@code @BindsOptionalOf} declaration in {@link RestContextModule}; it is empty
     * when the security module has not been included in the application component.
     *
     * @param securityRuntime the optional security runtime; never {@code null} — may be empty
     */
    @Inject
    JaxRsSecurityContextResolver(Optional<SecurityRuntime> securityRuntime) {
        this.securityRuntime = securityRuntime;
    }

    /**
     * Returns {@link RestContextResolver#PRIORITY_JAXRS_SECURITY_CONTEXT} ({@code 110}), placing
     * this built-in after application resolvers ({@code 0}) and
     * {@link RoutingContextResolver} ({@code 100}), and before {@link ContextHolderResolver}
     * ({@code 120}).
     *
     * @return {@link RestContextResolver#PRIORITY_JAXRS_SECURITY_CONTEXT}
     */
    @Override
    public int priority() {
        return RestContextResolver.PRIORITY_JAXRS_SECURITY_CONTEXT;
    }

    /**
     * Resolves the JAX-RS {@link jakarta.ws.rs.core.SecurityContext} for the current request.
     *
     * <p>Returns empty when:
     * <ul>
     *   <li>{@code type} is not exactly {@link jakarta.ws.rs.core.SecurityContext}{@code .class}
     *       (subtypes and other types are not handled by this resolver), or</li>
     *   <li>the security module is absent ({@link #securityRuntime} is empty), or</li>
     *   <li>{@link SecurityRuntime#toJaxRs(dev.vertique.security.SecurityContext, boolean)}
     *       returns {@code null} (no JAX-RS bridge factory is configured).</li>
     * </ul>
     *
     * @param <T>  the requested context type
     * @param type the class of the requested context value; never {@code null}
     * @param ctx  the current Vert.x routing context; never {@code null}
     * @return the JAX-RS security context bridge wrapped in an {@link Optional}, or empty
     */
    @Override
    public <T> Optional<T> resolve(Class<T> type, RoutingContext ctx) {
        if (type != jakarta.ws.rs.core.SecurityContext.class) {
            return Optional.empty();
        }
        if (securityRuntime.isEmpty()) {
            return Optional.empty();
        }
        SecurityRuntime rt = securityRuntime.get();
        jakarta.ws.rs.core.SecurityContext jaxRs =
                rt.toJaxRs(rt.current(), ctx.request().isSSL());
        return Optional.ofNullable(type.cast(jaxRs));
    }
}
