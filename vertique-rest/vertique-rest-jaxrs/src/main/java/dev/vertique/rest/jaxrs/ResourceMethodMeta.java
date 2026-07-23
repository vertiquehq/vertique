// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Immutable metadata for a single JAX-RS resource method discovered at startup.
 *
 * <p>This is the full internal descriptor used within the {@code rest-jaxrs} module for parameter
 * extraction, invocation, and handler registration. The {@link #operationId()} and
 * {@link #securityPolicy()} accessors are used directly at extension-point call sites.
 *
 * @param resourceInstance         the resource object instance
 * @param method                   the reflected method
 * @param operationId              the OpenAPI operationId
 * @param httpMethod               the HTTP verb (e.g. {@code "GET"})
 * @param path                     the JAX-RS path
 * @param params                   ordered list of parameter descriptors
 * @param responseBodyType         the unwrapped response body class
 * @param returnsFuture            whether the method returns a {@code Future}
 * @param returnsVoid              whether the method returns void / {@code Void}
 * @param securityPolicy           the resolved security policy for this operation
 * @param mediaTypes               consumed and produced media types declared by {@code @Consumes}
 *                                 and {@code @Produces}; empty lists mean unconstrained
 * @param validationGroups         Bean Validation groups to apply when validating this method's
 *                                 parameters; {@code null} means the default validation group;
 *                                 populated from {@link dev.vertique.core.validation.ValidateWith}
 * @param methodAnnotations        all annotations resolved from the method and its overrides in the
 *                                 superclass chain and interfaces; resolved at startup by
 *                                 {@link dev.vertique.core.util.AnnotationResolver}
 * @param classAnnotations         all annotations resolved from the declaring class and its superclass
 *                                 chain and interfaces; resolved at startup by
 *                                 {@link dev.vertique.core.util.AnnotationResolver}
 * @param routeCanonicalizerChain  route-level canonicalizer chain resolved from
 *                                 {@code @Canonicalize} on the resource class or method;
 *                                 method-level overrides class-level; empty if none declared
 * @param routeSanitizerChain      route-level sanitizer chain resolved from {@code @Sanitize} on
 *                                 the resource class or method; method-level overrides class-level;
 *                                 empty if none declared
 * @param executionPlan            optional CG-010 generated execution plan that replaces the
 *                                 reflective {@code ParameterExtractor + Method.invoke(...)} path
 *                                 on the request hot path; {@code null} means the reflective path
 *                                 runs unchanged
 */
public record ResourceMethodMeta(
        Object resourceInstance,
        Method method,
        String operationId,
        String httpMethod,
        String path,
        List<ParamMeta> params,
        Class<?> responseBodyType,
        boolean returnsFuture,
        boolean returnsVoid,
        SecurityPolicy securityPolicy,
        MediaTypes mediaTypes,
        @Nullable Class<?>[] validationGroups,
        List<Annotation> methodAnnotations,
        List<Annotation> classAnnotations,
        List<Class<? extends Canonicalizer>> routeCanonicalizerChain,
        List<Class<? extends Sanitizer>> routeSanitizerChain,
        @Nullable ResourceExecutionPlan executionPlan) {

    /**
     * Compact constructor that defensively copies the mutable validation groups array,
     * annotation lists, and route-level canonicalization/sanitization chains, ensuring all
     * components are immutable.
     */
    public ResourceMethodMeta {
        validationGroups = validationGroups != null ? validationGroups.clone() : null;
        methodAnnotations = methodAnnotations != null ? List.copyOf(methodAnnotations) : List.of();
        classAnnotations = classAnnotations != null ? List.copyOf(classAnnotations) : List.of();
        routeCanonicalizerChain = routeCanonicalizerChain != null ? List.copyOf(routeCanonicalizerChain) : List.of();
        routeSanitizerChain = routeSanitizerChain != null ? List.copyOf(routeSanitizerChain) : List.of();
    }

    /**
     * Convenience constructor that omits the optional {@link #executionPlan} (defaulting to
     * {@code null}) so existing callers keep compiling. The reflective path runs.
     */
    public ResourceMethodMeta(
            Object resourceInstance,
            Method method,
            String operationId,
            String httpMethod,
            String path,
            List<ParamMeta> params,
            Class<?> responseBodyType,
            boolean returnsFuture,
            boolean returnsVoid,
            SecurityPolicy securityPolicy,
            MediaTypes mediaTypes,
            @Nullable Class<?>[] validationGroups,
            List<Annotation> methodAnnotations,
            List<Annotation> classAnnotations,
            List<Class<? extends Canonicalizer>> routeCanonicalizerChain,
            List<Class<? extends Sanitizer>> routeSanitizerChain) {
        this(
                resourceInstance,
                method,
                operationId,
                httpMethod,
                path,
                params,
                responseBodyType,
                returnsFuture,
                returnsVoid,
                securityPolicy,
                mediaTypes,
                validationGroups,
                methodAnnotations,
                classAnnotations,
                routeCanonicalizerChain,
                routeSanitizerChain,
                null);
    }

    /**
     * Metadata for a single method parameter.
     *
     * <p>This record <em>composes</em> a neutral {@link ParameterMetadata} view rather than carrying an
     * eager {@code Annotation[]} field: the parameter's annotations are reached through
     * {@link #parameterMetadata()} (or the delegating {@link #findAnnotation(Class)} /
     * {@link #hasAnnotation(Class)} / {@link #annotationsLazy()} accessors). On the runtime scan path the
     * composed view is a {@link ReflectiveParameterMetadata} backed by the reflectively-captured
     * annotation array; on the codegen path it is the generated, literal-backed implementation.
     *
     * @param name              the parameter name (from annotation or reflection)
     * @param source            where the parameter value comes from
     * @param type              the declared parameter type (raw class)
     * @param componentType     the element type for collection/multi-value parameters (nullable)
     * @param genericType       the full generic type for body parameters, e.g. {@code List<MyPojo>}
     *                          (nullable)
     * @param defaultValue      the value from {@code @DefaultValue} annotation (nullable); used when
     *                          the request supplies no value for this parameter
     * @param parameterMetadata the composed neutral parameter-metadata view supplying the parameter's
     *                          annotations (via {@link ParameterMetadata#findAnnotation(Class)} and
     *                          {@link ParameterMetadata#annotationsLazy()}); must not be {@code null}.
     *                          Its annotations are passed to
     *                          {@link jakarta.ws.rs.ext.ParamConverterProvider#getConverter} during type
     *                          coercion
     */
    public record ParamMeta(
            String name,
            ParamSource source,
            Class<?> type,
            @Nullable Class<?> componentType,
            @Nullable Type genericType,
            @Nullable String defaultValue,
            ParameterMetadata parameterMetadata) {

        /**
         * Convenience constructor that accepts the raw parameter {@code Annotation[]} and wraps it in a
         * {@link ReflectiveParameterMetadata} (with index {@code -1}, since this form carries no method
         * position), via a small {@link AnnotatedElement} adapter ({@link #annotationArrayElement})
         * over the fixed array. Eases call-site migration for the reflective scan path and direct test
         * construction.
         *
         * @param name          the parameter name (nullable)
         * @param source        where the parameter value comes from
         * @param type          the declared parameter type
         * @param componentType the element type for collection parameters (nullable)
         * @param genericType   the full generic type (nullable)
         * @param defaultValue  the {@code @DefaultValue} string (nullable)
         * @param annotations   the raw annotations on the method parameter (nullable)
         */
        public ParamMeta(
                String name,
                ParamSource source,
                Class<?> type,
                @Nullable Class<?> componentType,
                @Nullable Type genericType,
                @Nullable String defaultValue,
                @Nullable Annotation[] annotations) {
            this(
                    name,
                    source,
                    type,
                    componentType,
                    genericType,
                    defaultValue,
                    new ReflectiveParameterMetadata(-1, name, type, genericType, annotationArrayElement(annotations)));
        }

        /**
         * Wraps a fixed {@code Annotation[]} (possibly {@code null}) as an {@link AnnotatedElement}, so
         * it can back a {@link ReflectiveParameterMetadata} the same way a real {@code Parameter}/
         * {@code Field}/{@code Method} does. A {@code null} array yields an element reporting no
         * annotations present, matching the prior jaxrs-local {@code ReflectiveParameterMetadata}'s
         * null-array handling.
         *
         * @param annotations the annotations to expose; may be {@code null} (treated as empty)
         * @return an {@link AnnotatedElement} view over {@code annotations}
         */
        @Nullable
        private static AnnotatedElement annotationArrayElement(@Nullable Annotation[] annotations) {
            if (annotations == null) {
                return null;
            }
            Annotation[] copy = annotations.clone();
            return new AnnotatedElement() {
                @Override
                @SuppressWarnings("unchecked")
                public <A extends Annotation> A getAnnotation(Class<A> annotationClass) {
                    for (Annotation a : copy) {
                        if (annotationClass.isInstance(a)) {
                            return (A) a;
                        }
                    }
                    return null;
                }

                @Override
                public Annotation[] getAnnotations() {
                    return copy.clone();
                }

                @Override
                public Annotation[] getDeclaredAnnotations() {
                    return copy.clone();
                }
            };
        }

        /**
         * Convenience constructor for parameters without a component type, generic type, default
         * value, or annotations.
         *
         * @param name   the parameter name
         * @param source where the parameter value comes from
         * @param type   the declared parameter type
         */
        public ParamMeta(String name, ParamSource source, Class<?> type) {
            this(name, source, type, null, null, null, (Annotation[]) null);
        }

        /**
         * Convenience constructor for parameters with a component type but no generic type,
         * default value, or annotations.
         *
         * @param name          the parameter name
         * @param source        where the parameter value comes from
         * @param type          the declared parameter type
         * @param componentType the element type for collection parameters
         */
        public ParamMeta(String name, ParamSource source, Class<?> type, Class<?> componentType) {
            this(name, source, type, componentType, null, null, (Annotation[]) null);
        }

        /**
         * Convenience constructor for parameters with a component type and generic type but no
         * default value or annotations.
         *
         * @param name          the parameter name
         * @param source        where the parameter value comes from
         * @param type          the declared parameter type
         * @param componentType the element type for collection parameters (nullable)
         * @param genericType   the full generic type (nullable)
         */
        public ParamMeta(String name, ParamSource source, Class<?> type, Class<?> componentType, Type genericType) {
            this(name, source, type, componentType, genericType, null, (Annotation[]) null);
        }

        /**
         * Looks up an annotation of the given type on the parameter, delegating to the composed
         * {@link ParameterMetadata} view.
         *
         * @param annotationType the annotation type to look up
         * @param <A>            the annotation type
         * @return the annotation if present, otherwise {@link Optional#empty()}
         */
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return parameterMetadata.findAnnotation(annotationType);
        }

        /**
         * Reports whether an annotation of the given type is declared on the parameter, delegating to
         * the composed {@link ParameterMetadata} view.
         *
         * @param annotationType the annotation type to test for
         * @return {@code true} if the annotation is present, {@code false} otherwise
         */
        public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
            return parameterMetadata.hasAnnotation(annotationType);
        }

        /**
         * Returns a supplier of the parameter's full declared annotation array, delegating to the
         * composed {@link ParameterMetadata} view's {@link ParameterMetadata#annotationsLazy()} bridge.
         *
         * @return a supplier of the parameter's annotations (never {@code null}; supplies an empty
         *         array when none were captured)
         */
        public Supplier<Annotation[]> annotationsLazy() {
            return parameterMetadata.annotationsLazy();
        }
    }

    /** Source of a JAX-RS method parameter value. */
    public enum ParamSource {
        /** Value extracted from a URI path segment (e.g. {@code /items/{id}}). */
        PATH,
        /** Value extracted from a URI query parameter. */
        QUERY,
        /** Value extracted from an HTTP request header. */
        HEADER,
        /** Value extracted from a request cookie. */
        COOKIE,
        /** Value deserialized from the HTTP request body. */
        BODY,
        /**
         * Any {@code @Context}-injected or auto-injectable type resolved through the
         * {@link dev.vertique.rest.core.context.RestContextResolver} chain. This includes
         * {@link io.vertx.ext.web.RoutingContext}, {@link jakarta.ws.rs.core.SecurityContext},
         * the framework {@link dev.vertique.security.SecurityContext}, and any
         * {@link dev.vertique.core.context.ContextValue} subtype.
         */
        CONTEXT,
        /**
         * Conditional request preconditions ({@code If-None-Match}, {@code If-Match}, etc.)
         * injected as {@link dev.vertique.rest.core.request.RequestPreconditions}.
         */
        PRECONDITIONS,
        /** Form field from a {@code multipart/form-data} or {@code application/x-www-form-urlencoded} request. */
        FORM,
        /** All file upload parts from a {@code multipart/form-data} request as a list of {@link io.vertx.ext.web.FileUpload}. */
        FILE_UPLOADS,
        /** All entity parts from a {@code multipart/form-data} request as a typed list. */
        ENTITY_PARTS,
        /**
         * Composite parameter object populated from multiple request parameters annotated with
         * {@code @QueryParam}, {@code @PathParam}, {@code @HeaderParam}, {@code @CookieParam}, or
         * {@code @FormParam} on the bean's fields. Declared with {@link jakarta.ws.rs.BeanParam}
         * on the method parameter.
         */
        BEAN_PARAM
    }

    /**
     * Consumed and produced media types declared by {@code @Consumes} and {@code @Produces} on
     * the JAX-RS resource method or its class.
     *
     * @param consumes media types declared by {@code @Consumes}, empty means unconstrained
     * @param produces media types declared by {@code @Produces}, empty means unconstrained
     */
    public record MediaTypes(List<String> consumes, List<String> produces) {
        /** Empty media types — both consumes and produces are unconstrained. */
        public static final MediaTypes EMPTY = new MediaTypes(List.of(), List.of());
    }
}
