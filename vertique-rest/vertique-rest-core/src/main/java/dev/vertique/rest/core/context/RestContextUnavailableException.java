// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import dev.vertique.rest.core.RestConfigurationException;

/**
 * Thrown at request dispatch time when a required REST context parameter cannot be resolved
 * (FR-REST-174).
 *
 * <p>This exception is raised by {@link RestContextResolution#require} when the resolver chain
 * returns no value for the requested type. It signals a configuration or wiring error: the
 * resource method declared a context parameter of a type that no registered
 * {@link RestContextResolver} can supply for the current request.
 *
 * <p>The exception message follows the exact FR-REST-174 format:
 *
 * <pre>
 * Missing REST context parameter &lt;SimpleType&gt; for &lt;resourceClass&gt;#&lt;methodName&gt;.
 * Ensure &lt;SimpleType&gt; is bound before REST dispatch.
 * </pre>
 *
 * <p>where {@code <SimpleType>} is {@link Class#getSimpleName()} of the requested type.
 *
 * @see RestContextResolution#require(Class, io.vertx.ext.web.RoutingContext, String, String)
 * @see RestContextResolver
 */
public class RestContextUnavailableException extends RestConfigurationException {

    /** The type of the context value that could not be resolved. */
    private final Class<?> type;

    /** The simple name of the JAX-RS resource class whose method declared the parameter. */
    private final String resourceClass;

    /** The name of the resource method that declared the unresolvable context parameter. */
    private final String methodName;

    /**
     * Constructs the exception with the FR-REST-174 message derived from the given arguments.
     *
     * @param type          the context value type that could not be resolved; must not be
     *                      {@code null}
     * @param resourceClass the simple or qualified name of the resource class declaring the
     *                      unresolvable parameter; must not be {@code null}
     * @param methodName    the name of the resource method declaring the unresolvable parameter;
     *                      must not be {@code null}
     */
    public RestContextUnavailableException(Class<?> type, String resourceClass, String methodName) {
        super(buildMessage(type, resourceClass, methodName));
        this.type = type;
        this.resourceClass = resourceClass;
        this.methodName = methodName;
    }

    /**
     * Returns the type of the context value that could not be resolved.
     *
     * @return the unresolvable context value type; never {@code null}
     */
    public Class<?> type() {
        return type;
    }

    /**
     * Returns the name of the JAX-RS resource class whose method declared the unresolvable
     * context parameter.
     *
     * @return the resource class name; never {@code null}
     */
    public String resourceClass() {
        return resourceClass;
    }

    /**
     * Returns the name of the resource method that declared the unresolvable context parameter.
     *
     * @return the method name; never {@code null}
     */
    public String methodName() {
        return methodName;
    }

    /**
     * Builds the FR-REST-174 error message for the given type, resource class, and method name.
     *
     * @param type          the unresolvable context type
     * @param resourceClass the resource class name
     * @param methodName    the method name
     * @return the formatted message string
     */
    private static String buildMessage(Class<?> type, String resourceClass, String methodName) {
        String simpleName = type.getSimpleName();
        return "Missing REST context parameter "
                + simpleName
                + " for "
                + resourceClass
                + "#"
                + methodName
                + ".\n"
                + "Ensure "
                + simpleName
                + " is bound before REST dispatch.";
    }
}
