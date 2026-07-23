// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

/**
 * Shared diagnostic message templates for {@code @Context} parameter violations on JAX-RS resource
 * methods.
 *
 * <p>These templates are used by both the runtime route validator
 * ({@code RouteValidator.addContextParamViolations} in {@code vertique-rest-jaxrs}) and the
 * compile-time context-param validator ({@code ContextParamValidator} in
 * {@code vertique-codegen-jaxrs}) to guarantee identical error wording across the two channels
 * (FR-REST-187/188/189 parity).
 *
 * <p>All methods are static; this class is not intended to be instantiated.
 */
public final class RestContextMessages {

    private RestContextMessages() {}

    // --- Context-param diagnostic templates (FR-REST-187/188/189) ---

    /**
     * Returns the error message when a parameter carries both {@code @Context} and a JAX-RS
     * value-binding annotation ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     * {@code @CookieParam}, {@code @FormParam}, {@code @BeanParam}).
     *
     * <p>Used by the runtime {@code RouteValidator} (CONTEXT_PARAM_CONFLICT violation) and the
     * compile-time {@code ContextParamValidator} (FR-REST-187).
     *
     * @param resourceClassName   the simple name of the declaring resource class
     *                            (e.g. {@code "HelloResource"})
     * @param methodName          the simple name of the resource method (e.g. {@code "get"})
     * @param paramTypeSimpleName the simple name of the offending parameter type
     *                            (e.g. {@code "String"})
     * @return the formatted error message
     */
    public static String contextParamConflict(String resourceClassName, String methodName, String paramTypeSimpleName) {
        return String.format(
                "@Context parameter %s on %s.%s() also carries a value-binding annotation;"
                        + " @Context parameters cannot be bound from path/query/header/cookie/form/bean",
                paramTypeSimpleName, resourceClassName, methodName);
    }

    /**
     * Returns the error message when a {@code @Context} parameter's declared type is a reserved
     * JAX-RS context type not supported in this framework version.
     *
     * <p>Used by the runtime {@code RouteValidator} (UNSUPPORTED_JAXRS_CONTEXT_TYPE violation) and
     * the compile-time {@code ContextParamValidator} (FR-REST-188).
     *
     * @param resourceClassName   the simple name of the declaring resource class
     * @param methodName          the simple name of the resource method
     * @param paramTypeSimpleName the simple name of the offending parameter type
     *                            (e.g. {@code "UriInfo"})
     * @return the formatted error message
     */
    public static String unsupportedJaxRsContextType(
            String resourceClassName, String methodName, String paramTypeSimpleName) {
        return String.format(
                "@Context parameter %s on %s.%s() is a reserved JAX-RS context type" + " not supported in this version",
                paramTypeSimpleName, resourceClassName, methodName);
    }

    /**
     * Returns the error message when a {@code @Context} parameter's declared type is not injectable
     * by the framework (not {@code RoutingContext}, JAX-RS {@code SecurityContext}, or a
     * {@code ContextValue} subtype).
     *
     * <p>Used by the runtime {@code RouteValidator} (NON_INJECTABLE_CONTEXT_TYPE violation) and the
     * compile-time {@code ContextParamValidator} (FR-REST-189).
     *
     * @param resourceClassName   the simple name of the declaring resource class
     * @param methodName          the simple name of the resource method
     * @param paramTypeSimpleName the simple name of the offending parameter type
     *                            (e.g. {@code "PaymentService"})
     * @return the formatted error message
     */
    public static String nonInjectableContextType(
            String resourceClassName, String methodName, String paramTypeSimpleName) {
        return String.format(
                "Unsupported @Context parameter %s on %s.%s()."
                        + " @Context injects RoutingContext, JAX-RS SecurityContext,"
                        + " and types marked @ContextValue (bound in ContextHolder).",
                paramTypeSimpleName, resourceClassName, methodName);
    }
}
