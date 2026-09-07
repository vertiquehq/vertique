// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.validation.ValidateWith;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.ReflectiveInvocationPolicies;
import dev.vertique.rest.core.context.RestContextTypes;
import dev.vertique.rest.core.request.RequestParams;
import dev.vertique.rest.core.request.RequestPreconditions;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.ext.web.FileUpload;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans JAX-RS annotated resource classes and extracts {@link ResourceMethodMeta} entries.
 *
 * <p>Uses a {@link SecurityPolicyBuilder} to resolve and validate security annotations on each
 * discovered method. Methods with conflicting security annotations are collected as violations
 * rather than thrown immediately, allowing all resources to be scanned before failing fast.
 *
 * <p>All annotation lookups use {@link AnnotationResolver} to merge annotations from the
 * concrete class, its superclass chain, and all transitively reachable interfaces. This ensures
 * that interface-declared JAX-RS annotations ({@code @Path}, HTTP verbs, {@code @Operation},
 * {@code @Consumes}/{@code @Produces}, parameter annotations, {@code @DefaultValue}) are honoured
 * for resource implementations that carry no direct annotations.
 *
 * <p><strong>Descriptor fast-path / reflective fallback dispatch:</strong> for each resource
 * instance, {@link #scanResource(Object, List)} first consults
 * {@link GeneratedJaxRsDescriptorRegistry#shared()} for a generated companion class. On a hit,
 * the companion's {@link GeneratedJaxRsResourceDescriptor#describe(Object,
 * GeneratedJaxRsDescriptorSupport, List)} is called and its result returned directly, bypassing
 * the reflective method walk entirely. On a clean miss ({@link java.util.Optional#empty()}) the
 * existing reflective path runs unchanged. A broken companion (registry throws) is not masked —
 * the exception propagates so build defects surface immediately rather than silently degrading to
 * the slower reflective path.
 */
@Slf4j
class ResourceScanner {

    private final SecurityPolicyBuilder securityPolicyBuilder;

    /**
     * Creates a new {@code ResourceScanner} backed by the given security policy builder.
     *
     * @param securityPolicyBuilder builder used to resolve and validate security annotations
     */
    ResourceScanner(SecurityPolicyBuilder securityPolicyBuilder) {
        this.securityPolicyBuilder = securityPolicyBuilder;
    }

    // --- Public API ---

    /**
     * Scans a single JAX-RS resource instance and returns metadata for all discovered methods.
     * Methods without an HTTP verb annotation are ignored.
     *
     * <p>If any methods have conflicting security annotations, a {@link
     * SecurityPolicyViolationException} is thrown immediately.
     *
     * @param resource the JAX-RS annotated resource instance
     * @return list of method metadata, one entry per discoverable endpoint
     * @throws SecurityPolicyViolationException if any method has conflicting security annotations
     */
    public List<ResourceMethodMeta> scanResource(Object resource) {
        List<SecurityPolicyViolation> violations = new ArrayList<>();
        List<ResourceMethodMeta> result = scanResource(resource, violations);
        if (!violations.isEmpty()) {
            throw new SecurityPolicyViolationException(violations);
        }
        return result;
    }

    /**
     * Internal scan that accumulates {@link SecurityPolicyViolation} entries rather than throwing.
     * Used by {@link JaxRsRouteRegistrar#registerAll} to collect violations across all resources
     * before failing fast.
     *
     * @param resource   the JAX-RS annotated resource instance
     * @param violations mutable list to which any CONFLICTING_SECURITY_ANNOTATIONS violations are
     *                   appended; methods with conflicts are skipped (not added to the result)
     * @return list of method metadata for all non-conflicting, discoverable methods
     */
    List<ResourceMethodMeta> scanResource(Object resource, List<SecurityPolicyViolation> violations) {
        List<ResourceMethodMeta> result = new ArrayList<>();
        Class<?> clazz = resource.getClass();

        // --- Descriptor fast-path ---
        // Look up a generated companion class for this exact resource type. This call may throw
        // if the companion exists on the classpath but is broken (e.g., constructor throws). That
        // exception is intentionally NOT caught here: a broken companion is a build defect and
        // must not silently fall back to the reflective path.
        Optional<GeneratedJaxRsResourceDescriptor<?>> descriptorOpt =
                GeneratedJaxRsDescriptorRegistry.shared().lookup(clazz);
        if (descriptorOpt.isPresent()) {
            @SuppressWarnings("unchecked")
            GeneratedJaxRsResourceDescriptor<Object> descriptor =
                    (GeneratedJaxRsResourceDescriptor<Object>) descriptorOpt.get();
            return descriptor.describe(resource, new GeneratedJaxRsDescriptorSupport(), violations);
        }

        // Resolve class-level annotations once, including superclasses and interfaces.
        // A class with no @Path anywhere in its hierarchy is not a JAX-RS root resource.
        List<Annotation> classAnnotations = AnnotationResolver.resolveClassAnnotations(clazz);
        if (findAnnotation(classAnnotations, Path.class) == null) {
            log.debug("Skipping class {} - no @Path annotation in class hierarchy", clazz.getName());
            return result;
        }

        for (Method method : collectMethods(clazz)) {
            // Hoist merged annotation lists to the top of the loop so all subsequent helpers
            // use the same pre-resolved lists and avoid redundant AnnotationResolver traversal.
            List<Annotation> methodAnnotations = AnnotationResolver.resolveMethodAnnotations(method);

            String httpMethod = resolveHttpMethod(methodAnnotations);
            if (httpMethod == null) {
                continue;
            }

            String operationId = resolveOperationId(methodAnnotations, method);
            String path = resolvePath(classAnnotations, methodAnnotations);

            // Detect conflicting security annotations before building policy
            if (securityPolicyBuilder.hasConflictingSecurityAnnotations(classAnnotations, methodAnnotations)) {
                String conflict = securityPolicyBuilder.describeConflict(classAnnotations, methodAnnotations);
                log.error("Conflicting security annotations on operationId={}: {}", operationId, conflict);
                violations.add(new SecurityPolicyViolation(
                        operationId,
                        SecurityPolicyViolation.ViolationType.CONFLICTING_SECURITY_ANNOTATIONS,
                        "Conflicting security annotations at " + conflict));
                continue;
            }

            // Detect empty @RolesAllowed — use @DenyAll to deny all access instead
            if (securityPolicyBuilder.hasEmptyRolesAllowed(classAnnotations, methodAnnotations)) {
                log.error(
                        "Empty @RolesAllowed on operationId={}: use @DenyAll to deny access or specify at least one role",
                        operationId);
                violations.add(new SecurityPolicyViolation(
                        operationId,
                        SecurityPolicyViolation.ViolationType.EMPTY_ROLES_ALLOWED,
                        String.format(
                                "@RolesAllowed with empty value array on operationId='%s' — use @DenyAll to deny access or specify at least one role",
                                operationId)));
                continue;
            }

            List<ResourceMethodMeta.ParamMeta> params = resolveParams(method);
            Class<?> responseBodyType = resolveResponseBodyType(method);
            boolean returnsFuture = io.vertx.core.Future.class.isAssignableFrom(method.getReturnType());
            boolean returnsVoid = responseBodyType == Void.class || method.getReturnType() == void.class;
            List<String> consumes = resolveConsumes(classAnnotations, methodAnnotations);
            List<String> produces = resolveProduces(classAnnotations, methodAnnotations);

            SecurityPolicy securityPolicy =
                    securityPolicyBuilder.buildSecurityPolicy(classAnnotations, methodAnnotations);

            // Use the pre-resolved merged annotation list rather than method.getAnnotation() to
            // honour interface-declared @ValidateWith (MEDIUM-3 fix: direct lookup misses interfaces).
            ValidateWith validateWith = findAnnotation(methodAnnotations, ValidateWith.class);
            Class<?>[] validationGroups = (validateWith != null) ? validateWith.value() : null;

            // Resolve route-level canonicalization/sanitization chains through the shared resolver
            // (dev.vertique.input.processing). InvocationPolicyConflictException (an
            // IllegalStateException) propagates uncaught when the method or class declares both an
            // additive and a skip annotation on the same axis.
            EffectiveInputPolicies routePolicies = ReflectiveInvocationPolicies.resolveRoute(method, clazz);
            List<Class<? extends Canonicalizer>> routeCanonicalizerChain = routePolicies.canonicalizers();
            List<Class<? extends Sanitizer>> routeSanitizerChain = routePolicies.sanitizers();

            // Make the Method invokable from ResourceMethodInvoker even when it is non-public
            // (protected, package-private, or private). Discovery via getDeclaredMethods accepts
            // non-public methods, but Method.invoke without setAccessible(true) throws
            // IllegalAccessException at request time. Doing this once per discovered method here
            // means the per-request invoke path never has to.
            method.setAccessible(true);

            result.add(new ResourceMethodMeta(
                    resource,
                    method,
                    operationId,
                    httpMethod,
                    path,
                    params,
                    responseBodyType,
                    returnsFuture,
                    returnsVoid,
                    securityPolicy,
                    new ResourceMethodMeta.MediaTypes(consumes, produces),
                    validationGroups,
                    methodAnnotations,
                    classAnnotations,
                    routeCanonicalizerChain,
                    routeSanitizerChain));

            log.debug(
                    "Discovered: {} {} -> {}.{}() operationId={}",
                    httpMethod,
                    path,
                    clazz.getSimpleName(),
                    method.getName(),
                    operationId);
        }

        return result;
    }

    // --- Resolution helpers ---

    /**
     * Resolves the operationId for a method from pre-resolved method annotations. Uses the value
     * from {@link io.swagger.v3.oas.annotations.Operation#operationId()} if present and non-empty,
     * otherwise falls back to the method name.
     *
     * @param methodAnnotations merged annotations from the method and its overrides
     * @param method            the resource method (used only for name fallback)
     * @return the resolved operationId
     */
    String resolveOperationId(List<Annotation> methodAnnotations, Method method) {
        Operation opAnnotation = findAnnotation(methodAnnotations, Operation.class);
        if (opAnnotation != null && !opAnnotation.operationId().isEmpty()) {
            return opAnnotation.operationId();
        }
        return method.getName();
    }

    /**
     * Resolves the HTTP method string for a resource method from pre-resolved method annotations
     * by checking for JAX-RS HTTP verb annotations ({@link GET}, {@link POST}, {@link PUT},
     * {@link DELETE}, {@link PATCH}, {@link HEAD}, {@link OPTIONS}).
     *
     * <p>Precedence order is GET &gt; POST &gt; PUT &gt; DELETE &gt; PATCH &gt; HEAD &gt; OPTIONS,
     * matching the original direct-annotation behaviour.
     *
     * @param methodAnnotations merged annotations from the method and its overrides in the
     *                          superclass chain and interfaces
     * @return the HTTP method string (e.g. {@code "GET"}), or {@code null} if no verb annotation
     *     is present
     */
    String resolveHttpMethod(List<Annotation> methodAnnotations) {
        if (findAnnotation(methodAnnotations, GET.class) != null) return "GET";
        if (findAnnotation(methodAnnotations, POST.class) != null) return "POST";
        if (findAnnotation(methodAnnotations, PUT.class) != null) return "PUT";
        if (findAnnotation(methodAnnotations, DELETE.class) != null) return "DELETE";
        if (findAnnotation(methodAnnotations, PATCH.class) != null) return "PATCH";
        if (findAnnotation(methodAnnotations, HEAD.class) != null) return "HEAD";
        if (findAnnotation(methodAnnotations, OPTIONS.class) != null) return "OPTIONS";
        return null;
    }

    /**
     * Resolves the full path for a resource method from pre-resolved annotation lists by combining
     * the class-level {@link Path} value with the method-level {@link Path} value.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return the normalized full path (e.g. {@code "/items/{id}"})
     */
    String resolvePath(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        String basePath = "";
        Path classPathAnn = findAnnotation(classAnnotations, Path.class);
        if (classPathAnn != null) {
            basePath = classPathAnn.value();
        }

        String methodPath = "";
        Path methodPathAnn = findAnnotation(methodAnnotations, Path.class);
        if (methodPathAnn != null) {
            methodPath = methodPathAnn.value();
        }

        return normalizePath(basePath + "/" + methodPath);
    }

    /**
     * Resolves the parameter metadata for a resource method by inspecting each parameter's JAX-RS
     * annotations ({@link PathParam}, {@link QueryParam}, {@link HeaderParam}, {@link CookieParam},
     * {@link FormParam}, {@link BeanParam}, {@link Context}) and type (body, context,
     * {@code List<FileUpload>}, {@code List<EntityPart>}).
     *
     * <p>Merged parameter annotations from the method's superclass chain and interfaces are
     * retrieved via {@link AnnotationResolver#resolveParameterAnnotations(Method, int)}, ensuring
     * that interface-declared parameter annotations ({@code @PathParam}, {@code @DefaultValue},
     * etc.) are honoured for concrete implementations with no direct annotations.
     *
     * <p>Detection order:
     *
     * <ol>
     *   <li>Parameters annotated with {@link Context} or whose type satisfies
     *       {@link RestContextTypes#isInjectable(Class)} — single unified {@code CONTEXT} source.
     *       A {@code @Context}-annotated parameter is always {@code CONTEXT} even when its type is
     *       not natively injectable, so {@link RouteValidator} can reject it instead of letting it
     *       fall through to {@code BODY}. Annotations are preserved so the validator can detect
     *       {@code @Context} + binding-annotation conflicts (FR-REST-166/167/179).
     *   <li>{@link RequestPreconditions} — conditional request preconditions injected directly.
     *   <li>{@link PathParam}, {@link QueryParam}, {@link HeaderParam}, {@link CookieParam} —
     *       annotated scalar params (with optional {@link DefaultValue} fallback)
     *   <li>{@link FormParam} — form field or file upload by name (with optional {@link
     *       DefaultValue} fallback)
     *   <li>Type annotated with {@link RequestParams} — composite parameter object whose type
     *       carries JAX-RS parameter annotations; detected automatically without {@code @BeanParam}
     *       on the method parameter
     *   <li>{@link BeanParam} — composite parameter object whose fields carry the above annotations
     *       (explicit method-parameter annotation; retained for backward compatibility)
     *   <li>Unannotated {@code List<FileUpload>} — all uploaded files
     *   <li>Unannotated {@code List<EntityPart>} — all multipart parts as JAX-RS {@code EntityPart}
     *   <li>Unannotated other type — request body (JSON deserialization)
     * </ol>
     *
     * @param method the resource method
     * @return ordered list of parameter metadata
     */
    List<ResourceMethodMeta.ParamMeta> resolveParams(Method method) {
        List<ResourceMethodMeta.ParamMeta> params = new ArrayList<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            // Merge parameter annotations from the concrete method, superclasses, and interfaces.
            Annotation[] mergedParamAnnotations = AnnotationResolver.resolveParameterAnnotations(method, i);

            // FR-REST-166/167/179: single CONTEXT source. A @Context-annotated parameter is
            // ALWAYS CONTEXT (even if its type is non-injectable) so RouteValidator can reject it
            // instead of letting it fall through to BODY. Annotations are preserved so the
            // validator can detect @Context + binding-annotation conflicts.
            boolean hasContextAnnotation = findParamAnnotation(mergedParamAnnotations, Context.class) != null;
            if (hasContextAnnotation || RestContextTypes.isInjectable(param.getType())) {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.CONTEXT,
                        param.getType(),
                        null,
                        null,
                        null,
                        mergedParamAnnotations));
            } else if (RequestPreconditions.class.isAssignableFrom(param.getType())) {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null, ResourceMethodMeta.ParamSource.PRECONDITIONS, RequestPreconditions.class));
            } else if (param.getType().isAnnotationPresent(RequestParams.class)) {
                // Type-level check — annotation is on the parameter *type*, not the parameter itself
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.BEAN_PARAM,
                        param.getType(),
                        null,
                        null,
                        null,
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, BeanParam.class) != null) {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.BEAN_PARAM,
                        param.getType(),
                        null,
                        null,
                        null,
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, PathParam.class) != null) {
                PathParam pp = findParamAnnotation(mergedParamAnnotations, PathParam.class);
                params.add(new ResourceMethodMeta.ParamMeta(
                        pp.value(),
                        ResourceMethodMeta.ParamSource.PATH,
                        param.getType(),
                        null,
                        null,
                        resolveDefaultValue(mergedParamAnnotations),
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, QueryParam.class) != null) {
                QueryParam qp = findParamAnnotation(mergedParamAnnotations, QueryParam.class);
                Class<?> componentType = resolveComponentType(param);
                params.add(new ResourceMethodMeta.ParamMeta(
                        qp.value(),
                        ResourceMethodMeta.ParamSource.QUERY,
                        param.getType(),
                        componentType,
                        param.getParameterizedType(),
                        resolveDefaultValue(mergedParamAnnotations),
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, HeaderParam.class) != null) {
                HeaderParam hp = findParamAnnotation(mergedParamAnnotations, HeaderParam.class);
                Class<?> componentType = resolveComponentType(param);
                params.add(new ResourceMethodMeta.ParamMeta(
                        hp.value(),
                        ResourceMethodMeta.ParamSource.HEADER,
                        param.getType(),
                        componentType,
                        param.getParameterizedType(),
                        resolveDefaultValue(mergedParamAnnotations),
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, CookieParam.class) != null) {
                CookieParam cp = findParamAnnotation(mergedParamAnnotations, CookieParam.class);
                Class<?> componentType = resolveComponentType(param);
                params.add(new ResourceMethodMeta.ParamMeta(
                        cp.value(),
                        ResourceMethodMeta.ParamSource.COOKIE,
                        param.getType(),
                        componentType,
                        param.getParameterizedType(),
                        resolveDefaultValue(mergedParamAnnotations),
                        mergedParamAnnotations));
            } else if (findParamAnnotation(mergedParamAnnotations, FormParam.class) != null) {
                FormParam fp = findParamAnnotation(mergedParamAnnotations, FormParam.class);
                Class<?> componentType = resolveComponentType(param);
                params.add(new ResourceMethodMeta.ParamMeta(
                        fp.value(),
                        ResourceMethodMeta.ParamSource.FORM,
                        param.getType(),
                        componentType,
                        // genericType stays null for FORM: ResourceMethodMeta.ParamMeta documents it as
                        // the full generic type for BODY parameters only (ADR-0191), and the FORM
                        // collection path needs only type() + componentType() to materialize.
                        null,
                        resolveDefaultValue(mergedParamAnnotations),
                        mergedParamAnnotations));
            } else if (isFileUploadList(param)) {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.FILE_UPLOADS,
                        List.class,
                        FileUpload.class,
                        null,
                        null,
                        mergedParamAnnotations));
            } else if (isEntityPartList(param)) {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.ENTITY_PARTS,
                        List.class,
                        EntityPart.class,
                        null,
                        null,
                        mergedParamAnnotations));
            } else {
                params.add(new ResourceMethodMeta.ParamMeta(
                        null,
                        ResourceMethodMeta.ParamSource.BODY,
                        param.getType(),
                        null,
                        param.getParameterizedType(),
                        null,
                        mergedParamAnnotations));
            }
        }
        return params;
    }

    /**
     * Resolves consumed media types for a resource method from pre-resolved annotation lists.
     * Method-level {@link Consumes} overrides class-level.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return list of consumed media type strings, empty if unconstrained
     */
    List<String> resolveConsumes(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolveMediaTypes(classAnnotations, methodAnnotations, Consumes.class, Consumes::value);
    }

    /**
     * Resolves produced media types for a resource method from pre-resolved annotation lists.
     * Method-level {@link Produces} overrides class-level.
     *
     * @param classAnnotations  merged annotations from the class and its hierarchy
     * @param methodAnnotations merged annotations from the method and its overrides
     * @return list of produced media type strings, empty if unconstrained
     */
    List<String> resolveProduces(List<Annotation> classAnnotations, List<Annotation> methodAnnotations) {
        return resolveMediaTypes(classAnnotations, methodAnnotations, Produces.class, Produces::value);
    }

    /**
     * Resolves the response body type for a resource method. Unwraps {@code Future<T>} to {@code
     * T}. Returns {@link Void} for void and {@code Future<Void>} methods.
     *
     * @param method the resource method
     * @return the resolved response body type
     */
    Class<?> resolveResponseBodyType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType pt) {
            if (io.vertx.core.Future.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                Type typeArg = pt.getActualTypeArguments()[0];
                if (typeArg == Void.class) {
                    return Void.class;
                }
                if (typeArg instanceof ParameterizedType nestedPt) {
                    return (Class<?>) nestedPt.getRawType();
                }
                return (Class<?>) typeArg;
            }
        }
        if (method.getReturnType() == void.class) {
            return Void.class;
        }
        return method.getReturnType();
    }

    // --- Private helpers ---

    /**
     * Finds the first annotation of the given type in a pre-resolved annotation list.
     *
     * @param annotations    the annotation list to search
     * @param annotationType the annotation class to find
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if absent
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findAnnotation(List<Annotation> annotations, Class<A> annotationType) {
        for (Annotation ann : annotations) {
            if (annotationType.isInstance(ann)) {
                return (A) ann;
            }
        }
        return null;
    }

    /**
     * Finds the first annotation of the given type in a merged parameter annotation array.
     *
     * @param annotations    the merged parameter annotation array to search
     * @param annotationType the annotation class to find
     * @param <A>            the annotation type
     * @return the first matching annotation, or {@code null} if absent
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <A extends Annotation> A findParamAnnotation(Annotation[] annotations, Class<A> annotationType) {
        for (Annotation ann : annotations) {
            if (annotationType.isInstance(ann)) {
                return (A) ann;
            }
        }
        return null;
    }

    /**
     * Resolves the {@link DefaultValue} string from a merged parameter annotation array, if
     * present.
     *
     * @param mergedParamAnnotations the merged annotation array for the method parameter
     * @return the default value string, or {@code null} if {@code @DefaultValue} is absent
     */
    @Nullable
    private static String resolveDefaultValue(Annotation[] mergedParamAnnotations) {
        DefaultValue dv = findParamAnnotation(mergedParamAnnotations, DefaultValue.class);
        return dv != null ? dv.value() : null;
    }

    /**
     * Resolves the element ("component") type for a multi-value scalar parameter shape, used to gate
     * the collection binding/validation path (a non-null component type makes the whole pipeline bind
     * and validate <em>all</em> request values rather than only the first). The recognized shapes are:
     *
     * <ul>
     *   <li>{@code List<T>}, {@code Set<T>}, {@code SortedSet<T>}, {@code NavigableSet<T>}, and
     *       {@code Collection<T>} where {@code T} is a concrete {@link Class} — returns {@code T}.
     *   <li>An array {@code T[]} whose element type is a sensible scalar (see
     *       {@link #isScalarArrayComponent(Class)}) — returns the element class.
     * </ul>
     *
     * <p>A {@code byte[]} / {@code char[]} (or any other primitive-array) is deliberately
     * <em>excluded</em>: those are binary/buffer body shapes, not multi-value scalar params, so they
     * must not be routed through the collection path. The {@code List<FileUpload>} and
     * {@code List<EntityPart>} multipart shapes are handled by earlier branches in
     * {@link #extractParams} (which classify them as FILE_UPLOADS / ENTITY_PARTS) and never reach a
     * call site that consults this method for a non-annotated parameter, so they are unaffected.
     *
     * @param param the method parameter to inspect
     * @return the element class for a recognized multi-value shape, or {@code null} otherwise
     */
    @Nullable
    private Class<?> resolveComponentType(Parameter param) {
        // Array shape: T[] — restrict to sensible scalar element types (excludes byte[]/char[]
        // and other primitive arrays, which are binary/buffer body shapes, not multi-value params).
        Class<?> rawType = param.getType();
        if (rawType.isArray()) {
            Class<?> componentType = rawType.getComponentType();
            return isScalarArrayComponent(componentType) ? componentType : null;
        }

        // Parameterized collection shape: List/Set/SortedSet/NavigableSet/Collection of a concrete type.
        Type genericType = param.getParameterizedType();
        if (genericType instanceof ParameterizedType pt && isSupportedCollectionRawType(pt.getRawType())) {
            Type typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> cls) {
                return cls;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if {@code rawType} is one of the multi-value collection interfaces this
     * scanner binds element-wise: {@link List}, {@link java.util.Set}, {@link java.util.SortedSet},
     * {@link java.util.NavigableSet}, or {@link java.util.Collection}.
     *
     * @param rawType the raw type of a parameterized parameter type
     * @return {@code true} when the raw type is a supported multi-value collection interface
     */
    private static boolean isSupportedCollectionRawType(Type rawType) {
        return rawType == List.class
                || rawType == java.util.Set.class
                || rawType == java.util.SortedSet.class
                || rawType == java.util.NavigableSet.class
                || rawType == java.util.Collection.class;
    }

    /**
     * Returns {@code true} if {@code componentType} is a sensible element type for a multi-value
     * scalar array parameter: {@link String}, a boxed numeric ({@link Integer}, {@link Long},
     * {@link Short}, {@link Byte}, {@link Double}, {@link Float}), {@link Boolean}, {@link Character},
     * or an {@code enum}. Primitive element types (e.g. {@code byte}, {@code char}) return
     * {@code false} so {@code byte[]} / {@code char[]} are not treated as multi-value collections.
     *
     * @param componentType the array element type
     * @return {@code true} when the element type is a sensible scalar array component
     */
    private static boolean isScalarArrayComponent(Class<?> componentType) {
        if (componentType.isPrimitive()) {
            return false;
        }
        return componentType == String.class
                || componentType == Integer.class
                || componentType == Long.class
                || componentType == Short.class
                || componentType == Byte.class
                || componentType == Double.class
                || componentType == Float.class
                || componentType == Boolean.class
                || componentType == Character.class
                || componentType.isEnum();
    }

    /**
     * Returns {@code true} if the parameter is an unannotated {@code List<FileUpload>}.
     *
     * @param param the method parameter to inspect
     * @return {@code true} if the parameter is {@code List<FileUpload>}
     */
    private boolean isFileUploadList(Parameter param) {
        return isListOf(param, FileUpload.class);
    }

    /**
     * Returns {@code true} if the parameter is an unannotated {@code List<EntityPart>}.
     *
     * @param param the method parameter to inspect
     * @return {@code true} if the parameter is {@code List<EntityPart>}
     */
    private boolean isEntityPartList(Parameter param) {
        return isListOf(param, EntityPart.class);
    }

    /**
     * Returns {@code true} if the parameter is a {@code List} with the given element type.
     *
     * @param param       the method parameter to inspect
     * @param elementType the expected generic element type
     * @return {@code true} if the parameter generic type is {@code List<elementType>}
     */
    private boolean isListOf(Parameter param, Class<?> elementType) {
        Type genericType = param.getParameterizedType();
        if (genericType instanceof ParameterizedType pt && pt.getRawType() == List.class) {
            Type typeArg = pt.getActualTypeArguments()[0];
            return typeArg == elementType;
        }
        return false;
    }

    /**
     * Resolves media type annotations from pre-resolved annotation lists with method-level
     * overriding class-level.
     *
     * @param classAnnotations   merged annotations from the class hierarchy
     * @param methodAnnotations  merged annotations from the method hierarchy
     * @param annotationClass    the annotation type ({@link Consumes} or {@link Produces})
     * @param valueExtractor     function to extract the {@code String[]} value from the annotation
     * @param <A>                the annotation type
     * @return list of media type strings, empty if unconstrained
     */
    private <A extends Annotation> List<String> resolveMediaTypes(
            List<Annotation> classAnnotations,
            List<Annotation> methodAnnotations,
            Class<A> annotationClass,
            java.util.function.Function<A, String[]> valueExtractor) {
        A methodAnn = findAnnotation(methodAnnotations, annotationClass);
        if (methodAnn != null) {
            return List.of(valueExtractor.apply(methodAnn));
        }
        A classAnn = findAnnotation(classAnnotations, annotationClass);
        if (classAnn != null) {
            return List.of(valueExtractor.apply(classAnn));
        }
        return List.of();
    }

    /**
     * Collects methods from the class hierarchy, starting with the most specific class and walking
     * up through superclasses (stopping at {@link Object}).
     *
     * <p>Deduplicates by method name + parameter types: subclass overrides win. Skips bridge
     * methods, synthetic methods, and default interface methods.
     *
     * @param clazz the class to scan
     * @return collected methods with subclass overrides taking precedence
     */
    private List<Method> collectMethods(Class<?> clazz) {
        Map<String, Method> seen = new LinkedHashMap<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                if (method.isDefault()) {
                    continue;
                }
                String key = methodKey(method);
                seen.putIfAbsent(key, method);
            }
            current = current.getSuperclass();
        }
        return List.copyOf(seen.values());
    }

    /**
     * Creates a deduplication key for a method based on its name and parameter types.
     *
     * @param method the method
     * @return a string key combining the method name and parameter type names
     */
    private String methodKey(Method method) {
        StringBuilder sb = new StringBuilder(method.getName());
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append(':').append(paramType.getName());
        }
        return sb.toString();
    }

    /**
     * Normalizes a path by collapsing duplicate slashes, ensuring a leading slash, and removing
     * any trailing slash (except for root {@code "/"}).
     *
     * @param path the raw path string
     * @return the normalized path
     */
    private String normalizePath(String path) {
        path = path.replaceAll("/+", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
