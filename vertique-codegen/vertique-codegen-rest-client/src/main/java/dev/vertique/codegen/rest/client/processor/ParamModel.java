// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side model for a single method parameter in a {@code @RestClient} interface method, or a
 * single field/component in a {@code @BeanParam} bean type.
 *
 * <p>The {@code kind} field classifies the parameter by its JAX-RS annotation source:
 * <ul>
 *   <li>{@link Kind#PATH} &mdash; annotated with {@code @PathParam}</li>
 *   <li>{@link Kind#QUERY} &mdash; annotated with {@code @QueryParam}</li>
 *   <li>{@link Kind#HEADER} &mdash; annotated with {@code @HeaderParam}</li>
 *   <li>{@link Kind#COOKIE} &mdash; annotated with {@code @CookieParam}</li>
 *   <li>{@link Kind#BEAN} &mdash; annotated with {@code @BeanParam}</li>
 *   <li>{@link Kind#URL} &mdash; annotated with {@code @Url} (overrides base URL + path template)</li>
 *   <li>{@link Kind#BODY} &mdash; no JAX-RS annotation present (treated as request body)</li>
 * </ul>
 *
 * <p>For bean fields and record components, {@code name} is the JAX-RS wire name (the annotation
 * value, e.g. {@code "q"} from {@code @QueryParam("q")}), while {@code javaName} is the Java
 * source identifier (e.g. {@code "query"}). These two values are equal when the annotation value
 * matches the field name, but differ when the field is intentionally renamed. The
 * {@link dev.vertique.codegen.rest.client.processor.emit.BeanAccessorEmitter} uses
 * {@code javaName} as the {@code switch} case key (since
 * {@link dev.vertique.rest.client.BeanParamAccessor#extract(Object, String)} receives the Java
 * member name) and {@code name} as the wire name passed to the request builder.
 *
 * @param kind the parameter classification
 * @param name the JAX-RS annotation value (e.g. the {@code @PathParam} name), or the Java field
 *     name for {@code BODY}, {@code BEAN}, and {@code URL} parameters
 * @param javaName the Java source identifier for this parameter (record component simple name or
 *     class field name); equals {@code name} for non-bean parameters and for bean fields where
 *     the JAX-RS annotation value matches the field name
 * @param type the APT-side type mirror for the parameter
 * @param element the source element (method parameter or bean field/component); may be
 *     {@code null} for synthetic entries (e.g. record components use {@code null} here)
 * @param accessExpression the Java expression used to access this field on a bean instance named
 *     {@code bean} (e.g. {@code "bean.query"}, {@code "bean.getStatus()"}, {@code "bean.page()"}
 *     for a record component accessor); {@code null} for non-bean parameters
 * @param defaultValue the {@code @DefaultValue} string for this parameter; {@code null} when not
 *     present
 */
public record ParamModel(
        Kind kind,
        String name,
        String javaName,
        TypeMirror type,
        VariableElement element,
        String accessExpression,
        String defaultValue) {

    /**
     * Constructs a {@link ParamModel} without an access expression or default value (for method
     * parameters without {@code @DefaultValue}). Sets {@code javaName} equal to {@code name}.
     *
     * @param kind the parameter classification
     * @param name the JAX-RS annotation value or Java name
     * @param type the APT-side type mirror
     * @param element the source element; may be {@code null}
     */
    public ParamModel(Kind kind, String name, TypeMirror type, VariableElement element) {
        this(kind, name, name, type, element, null, null);
    }

    /**
     * Constructs a {@link ParamModel} with an access expression but no default value (for bean
     * field parameters). Sets {@code javaName} equal to {@code name}.
     *
     * @param kind the parameter classification
     * @param name the JAX-RS annotation value or Java name
     * @param type the APT-side type mirror
     * @param element the source element; may be {@code null}
     * @param accessExpression the Java expression for bean field access; {@code null} for
     *     non-bean parameters
     */
    public ParamModel(Kind kind, String name, TypeMirror type, VariableElement element, String accessExpression) {
        this(kind, name, name, type, element, accessExpression, null);
    }

    /**
     * Classification of a REST client method parameter or bean field.
     */
    public enum Kind {
        /** Mapped to a {@code {name}} placeholder in the path template via {@code @PathParam}. */
        PATH,
        /** Appended to the query string via {@code @QueryParam}. */
        QUERY,
        /** Sent as a request header via {@code @HeaderParam}. */
        HEADER,
        /** Sent as a cookie via {@code @CookieParam}. */
        COOKIE,
        /** Expanded via {@code @BeanParam} &mdash; the parameter is itself a bean. */
        BEAN,
        /**
         * Overrides the base URL and path template via {@code @Url}. The parameter must be a
         * {@link java.net.URI}; its {@code toString()} is passed to
         * {@link dev.vertique.rest.client.RestRequestBuilder#absoluteUri(String)}.
         */
        URL,
        /** The request body &mdash; no JAX-RS annotation present. */
        BODY
    }
}
