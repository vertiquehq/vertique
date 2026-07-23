// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.core.context.ContextValue;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Set;

/**
 * Shared constants and runtime predicate for {@code @Context}-injectable types in the Vertique
 * REST framework.
 *
 * <p>This class is the single source of truth for two concerns:
 *
 * <ol>
 *   <li><b>FQN string constants</b> — fully-qualified type names consumed by annotation processors
 *       and code-generation paths (e.g., the OpenAPI route registrar) that operate on type mirrors
 *       rather than live {@link Class} objects.
 *   <li><b>Runtime injectability predicate</b> ({@link #isInjectable(Class)}) — used by the
 *       handler dispatch layer to decide whether a JAX-RS resource method parameter should be
 *       resolved from the framework context rather than from a path/query/body binding.
 * </ol>
 *
 * <p><b>Internal use only.</b> This class is not an application extension point. It is not part
 * of the public SPI and may change between framework versions without notice.
 *
 * @see #isInjectable(Class)
 * @see #RESERVED_UNSUPPORTED_JAXRS_FQNS
 */
public final class RestContextTypes {

    // --- FQN constants ---

    /**
     * Fully-qualified name of the {@link ContextValue} marker interface.
     *
     * <p>Used by annotation-processor and code-generation paths that work with type mirrors and
     * cannot reference the live {@link Class} object.
     */
    public static final String CONTEXT_VALUE_FQN = "dev.vertique.core.context.ContextValue";

    /**
     * Fully-qualified name of {@code io.vertx.ext.web.RoutingContext}.
     *
     * <p>A parameter of this type is resolved to the current Vert.x {@link RoutingContext} for
     * the request.
     */
    public static final String ROUTING_CONTEXT_FQN = "io.vertx.ext.web.RoutingContext";

    /**
     * Fully-qualified name of {@code jakarta.ws.rs.core.SecurityContext}.
     *
     * <p>A parameter of this type is resolved to the framework's JAX-RS {@link SecurityContext}
     * adapter for the current request.
     */
    public static final String JAXRS_SECURITY_CONTEXT_FQN = "jakarta.ws.rs.core.SecurityContext";

    // --- Reserved / unsupported set ---

    /**
     * JAX-RS {@code @Context} types that are syntactically valid JAX-RS but are <em>not</em>
     * supported in Vertique V1 (FR-REST-188).
     *
     * <p>The framework detects parameters whose declared type appears in this set and rejects them
     * at startup with a clear error, rather than silently ignoring them or producing a runtime
     * {@code NullPointerException}.
     */
    public static final Set<String> RESERVED_UNSUPPORTED_JAXRS_FQNS = Set.of(
            "jakarta.ws.rs.core.UriInfo",
            "jakarta.ws.rs.core.HttpHeaders",
            "jakarta.ws.rs.core.Request",
            "jakarta.ws.rs.core.Configuration",
            "jakarta.ws.rs.core.Application",
            "jakarta.ws.rs.ext.Providers",
            "jakarta.ws.rs.container.ResourceContext");

    // --- Runtime predicate ---

    /**
     * Returns {@code true} if a JAX-RS resource method parameter of the given {@code type} should
     * be resolved from the framework context rather than from a JAX-RS binding (FR-REST-168).
     *
     * <p>A type is considered injectable when it satisfies any of the following:
     * <ul>
     *   <li>{@code type} is or extends {@link RoutingContext} — resolved to the current Vert.x
     *       routing context. Subtypes are accepted because the resolver uses
     *       {@code Class.isInstance}.</li>
     *   <li>{@code type} is <em>exactly</em> {@link SecurityContext} — resolved to the JAX-RS
     *       security context adapter for the request. Subtypes are <em>not</em> accepted: the
     *       framework resolver ({@code JaxRsSecurityContextResolver}) only handles the exact JAX-RS
     *       interface, so a custom subtype would pass validation here but fail at request time.
     *       Application-defined security context types should implement
     *       {@link ContextValue} instead and be bound via {@code ContextHolder}.</li>
     *   <li>{@code type} is or extends {@link ContextValue} — resolved from the framework
     *       {@code ContextHolder} for the current request scope. Subtypes are the whole point of
     *       this arm (e.g., the framework {@code dev.vertique.security.SecurityContext} is a
     *       {@link ContextValue}).</li>
     * </ul>
     *
     * <p>Note: {@code RequestPreconditions} is <em>not</em> a context type and returns
     * {@code false}. It is resolved via a separate, dedicated parameter resolver.
     *
     * @param type the parameter type to test; may be {@code null}
     * @return {@code true} if {@code type} is context-injectable; {@code false} for {@code null}
     *         or any type that does not meet the criteria above
     */
    public static boolean isInjectable(Class<?> type) {
        if (type == null) {
            return false;
        }
        return RoutingContext.class.isAssignableFrom(type)
                || type == SecurityContext.class
                || ContextValue.class.isAssignableFrom(type);
    }

    /** Private constructor — this class is a non-instantiable constants holder. */
    private RestContextTypes() {}
}
