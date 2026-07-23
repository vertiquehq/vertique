// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.meta;

import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.core.resilience.Retry;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.rest.client.ExpectedStatus;
import dev.vertique.rest.client.HttpClientResponse;
import dev.vertique.rest.client.Url;
import dev.vertique.rest.client.meta.ClientMethodMeta.ResilienceConfig;
import dev.vertique.rest.client.meta.ClientMethodMeta.RetryConfig;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.core.http.HttpResponseHead;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans a JAX-RS-annotated interface and produces {@link ClientMethodMeta} descriptors for all
 * discoverable methods.
 *
 * <p>All methods in the client interface must return {@code Future<T>}. Methods without an HTTP
 * verb annotation ({@code @GET}, {@code @POST}, etc.) are skipped. Default and bridge methods are
 * also skipped.
 *
 * <p>This class is stateless and all public methods are static. Results are cached by the
 * {@link dev.vertique.rest.client.RestClientFactory}.
 */
@Slf4j
public final class ClientInterfaceScanner {

    private static final String DEFAULT_MEDIA_TYPE = "application/json";

    private ClientInterfaceScanner() {}

    /**
     * Scans the given interface and returns a map of {@link Method} to {@link ClientMethodMeta}.
     *
     * <p>Only methods with an HTTP verb annotation are included. Methods that do not return
     * {@code Future<T>} cause an {@link IllegalArgumentException}.
     *
     * @param iface the JAX-RS-annotated client interface to scan; must be an interface
     * @return unmodifiable map from method to its metadata; methods without HTTP verbs are omitted
     * @throws IllegalArgumentException if {@code iface} is not an interface, or if a method does
     *     not return {@code Future<T>}
     */
    public static Map<Method, ClientMethodMeta> scan(Class<?> iface) {
        if (!iface.isInterface()) {
            throw new IllegalArgumentException("REST client must be an interface: " + iface.getName());
        }

        Map<Method, ClientMethodMeta> result = new LinkedHashMap<>();

        for (Method method : collectMethods(iface)) {
            String httpMethod = resolveHttpMethod(method);
            if (httpMethod == null) {
                log.debug("Skipping method {}.{}() — no HTTP verb annotation", iface.getSimpleName(), method.getName());
                continue;
            }

            String pathTemplate = resolvePath(iface, method);
            List<ClientParamMeta> params = resolveParams(method);
            validateUrlParam(iface, method, params);
            ReturnTypeMeta returnType = resolveReturnType(method);
            String consumesMediaType = resolveConsumes(iface, method);
            String producesMediaType = resolveProduces(iface, method);
            Expectation<HttpResponseHead> expectation = resolveExpectedStatus(method);
            ResilienceConfig resilience = resolveResilienceConfig(method);

            boolean hasUrlParam = params.stream().anyMatch(p -> p.source() == ClientParamMeta.ParamSource.URL);

            List<ClientParamMeta> immutableParams = Collections.unmodifiableList(params);
            List<ParameterMetadata> parameterMetadata = immutableParams.stream()
                    .map(ClientParamMeta::parameterMetadata)
                    .collect(Collectors.toUnmodifiableList());
            ClientMethodMeta meta = new ClientMethodMeta(
                    new ReflectiveMethodMetadata(method, parameterMetadata),
                    httpMethod,
                    pathTemplate,
                    immutableParams,
                    returnType.genericType(),
                    returnType.rawType(),
                    returnType.returnsVoid(),
                    returnType.returnsRawResponse(),
                    returnType.returnsOptional(),
                    consumesMediaType,
                    producesMediaType,
                    expectation,
                    resilience,
                    hasUrlParam);

            result.put(method, meta);

            log.debug(
                    "Discovered client method: {} {} -> {}.{}()",
                    httpMethod,
                    pathTemplate,
                    iface.getSimpleName(),
                    method.getName());
        }

        return Collections.unmodifiableMap(result);
    }

    // --- Return Type Resolution ---

    /**
     * Resolves the return type metadata for a method. The method must return {@code Future<T>}.
     *
     * <p>If the type argument is {@code Optional<X>}, {@code returnsOptional} is set to
     * {@code true} and {@code X} is used as the actual response type.
     *
     * @param method the interface method
     * @return the resolved return type metadata
     * @throws IllegalArgumentException if the method does not return {@code Future<T>}
     */
    private static ReturnTypeMeta resolveReturnType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (!(returnType instanceof ParameterizedType pt)
                || !Future.class.isAssignableFrom((Class<?>) pt.getRawType())) {
            throw new IllegalArgumentException(String.format(
                    "REST client method %s.%s() must return Future<T>, found: %s",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    method.getReturnType().getName()));
        }

        Type typeArg = pt.getActualTypeArguments()[0];

        if (typeArg == Void.class) {
            return new ReturnTypeMeta(Void.class, Void.class, true, false, false);
        }

        if (typeArg instanceof ParameterizedType nestedPt) {
            Class<?> rawClass = (Class<?>) nestedPt.getRawType();

            // Detect Future<Optional<X>>
            if (Optional.class.isAssignableFrom(rawClass)) {
                Type optionalTypeArg = nestedPt.getActualTypeArguments()[0];
                Class<?> optionalRawClass = optionalTypeArg instanceof ParameterizedType optPt
                        ? (Class<?>) optPt.getRawType()
                        : optionalTypeArg instanceof Class<?> rawOpt ? rawOpt : Object.class;
                return new ReturnTypeMeta(optionalTypeArg, optionalRawClass, false, false, true);
            }

            return new ReturnTypeMeta(typeArg, rawClass, false, false, false);
        }

        if (typeArg instanceof Class<?> rawClass) {
            if (HttpClientResponse.class.isAssignableFrom(rawClass)) {
                return new ReturnTypeMeta(typeArg, HttpClientResponse.class, false, true, false);
            }
            return new ReturnTypeMeta(typeArg, rawClass, false, false, false);
        }

        // Fallback for wildcards and type variables — use Object
        return new ReturnTypeMeta(typeArg, Object.class, false, false, false);
    }

    /**
     * Internal holder for resolved return type information.
     *
     * @param genericType the full generic type {@code T} from {@code Future<T>} (or {@code X} from
     *     {@code Future<Optional<X>>})
     * @param rawType the raw erased class of the type argument
     * @param returnsVoid whether the type argument is {@link Void}
     * @param returnsRawResponse whether the type argument is {@link HttpClientResponse}
     * @param returnsOptional whether the type argument is {@code Optional<X>}
     */
    private record ReturnTypeMeta(
            Type genericType,
            Class<?> rawType,
            boolean returnsVoid,
            boolean returnsRawResponse,
            boolean returnsOptional) {}

    // --- HTTP Method Resolution ---

    /**
     * Resolves the HTTP verb string from a method's JAX-RS annotations. Supports
     * {@code GET}, {@code POST}, {@code PUT}, {@code DELETE}, {@code PATCH}, and {@code HEAD}.
     * Methods annotated with unsupported verbs (e.g. {@code OPTIONS}) are treated as having no
     * verb and are skipped during scanning.
     *
     * @param method the interface method
     * @return the HTTP verb (e.g. {@code "GET"}), or {@code null} if no verb annotation is present
     */
    private static String resolveHttpMethod(Method method) {
        if (method.isAnnotationPresent(GET.class)) return "GET";
        if (method.isAnnotationPresent(POST.class)) return "POST";
        if (method.isAnnotationPresent(PUT.class)) return "PUT";
        if (method.isAnnotationPresent(DELETE.class)) return "DELETE";
        if (method.isAnnotationPresent(PATCH.class)) return "PATCH";
        if (method.isAnnotationPresent(HEAD.class)) return "HEAD";
        return null;
    }

    // --- Path Resolution ---

    /**
     * Validates that methods with {@code @Url} parameters do not conflict with path-related
     * annotations. Called from {@link #scan} after parameter resolution so that the full parameter
     * list and the declaring interface/method are available.
     *
     * @param iface the client interface
     * @param method the method being scanned
     * @param params the resolved parameter metadata
     * @throws IllegalArgumentException if validation fails
     */
    private static void validateUrlParam(Class<?> iface, Method method, List<ClientParamMeta> params) {
        long urlCount = params.stream()
                .filter(p -> p.source() == ClientParamMeta.ParamSource.URL)
                .count();
        if (urlCount == 0) {
            return;
        }
        if (urlCount > 1) {
            throw new IllegalArgumentException(
                    "At most one @Url parameter is allowed per method: " + method.getName() + " has " + urlCount);
        }
        // @Url present — check for conflicting @Path / @PathParam
        if (iface.isAnnotationPresent(Path.class)) {
            throw new IllegalArgumentException("@Url method " + method.getName()
                    + " must not have interface-level @Path on " + iface.getSimpleName());
        }
        if (method.isAnnotationPresent(Path.class)) {
            throw new IllegalArgumentException("@Url method " + method.getName() + " must not have method-level @Path");
        }
        for (ClientParamMeta p : params) {
            if (p.source() == ClientParamMeta.ParamSource.PATH) {
                throw new IllegalArgumentException(
                        "@Url method " + method.getName() + " must not have @PathParam parameters");
            }
            if (p.source() == ClientParamMeta.ParamSource.BEAN_PARAM) {
                for (ClientParamMeta field : p.beanFields()) {
                    if (field.source() == ClientParamMeta.ParamSource.PATH) {
                        throw new IllegalArgumentException(
                                "@Url method " + method.getName() + " must not have @BeanParam with @PathParam fields");
                    }
                }
            }
        }
    }

    /**
     * Resolves the full path template for a method by combining the interface-level and
     * method-level {@link Path} annotations.
     *
     * @param iface the client interface
     * @param method the interface method
     * @return the normalized path template (e.g. {@code "/users/{id}"})
     */
    private static String resolvePath(Class<?> iface, Method method) {
        String basePath = "";
        Path classPath = iface.getAnnotation(Path.class);
        if (classPath != null) {
            basePath = classPath.value();
        }

        String methodPath = "";
        Path methodPathAnn = method.getAnnotation(Path.class);
        if (methodPathAnn != null) {
            methodPath = methodPathAnn.value();
        }

        return normalizePath(basePath + "/" + methodPath);
    }

    /**
     * Normalizes a path by collapsing duplicate slashes and removing trailing slashes, while
     * ensuring the path starts with {@code /}.
     *
     * @param path the raw combined path string
     * @return the normalized path
     */
    private static String normalizePath(String path) {
        String normalized = path.replaceAll("/+", "/");
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    // --- Parameter Resolution ---

    /**
     * Resolves the parameter metadata for all parameters of the given method.
     *
     * <p>Detection order matches JAX-RS conventions:
     * <ol>
     *   <li>{@link PathParam} — path template variable</li>
     *   <li>{@link QueryParam} — URI query string parameter</li>
     *   <li>{@link HeaderParam} — HTTP request header</li>
     *   <li>{@link CookieParam} — HTTP cookie</li>
     *   <li>{@link BeanParam} — composite object; expanded into sub-parameters</li>
     *   <li>Unannotated — request body (serialized as JSON)</li>
     * </ol>
     *
     * @param method the interface method
     * @return ordered list of parameter metadata
     */
    private static List<ClientParamMeta> resolveParams(Method method) {
        List<ClientParamMeta> params = new ArrayList<>();
        Parameter[] parameters = method.getParameters();

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            ClientParamMeta meta = resolveParam(param, i);
            params.add(meta);
        }

        return params;
    }

    /**
     * Resolves the metadata for a single method parameter.
     *
     * @param param the method parameter
     * @param index the zero-based index of the parameter in the method signature
     * @return the resolved parameter metadata
     */
    private static ClientParamMeta resolveParam(Parameter param, int index) {
        String defaultValue = resolveDefaultValue(param);
        Type genericType = param.getParameterizedType();
        Class<?> componentType = resolveComponentType(param.getType(), genericType);

        if (param.isAnnotationPresent(Url.class)) {
            if (param.getType() != URI.class) {
                throw new IllegalArgumentException("@Url parameter must be java.net.URI, found: "
                        + param.getType().getName());
            }
            // Reject @DefaultValue before the generic jakarta.ws.rs loop for a clearer message
            if (param.isAnnotationPresent(DefaultValue.class)) {
                throw new IllegalArgumentException("@DefaultValue is not allowed on @Url parameters");
            }
            // Reject if any jakarta.ws.rs parameter-binding annotation is also present
            for (Annotation ann : param.getAnnotations()) {
                if (ann.annotationType().getPackageName().startsWith("jakarta.ws.rs")) {
                    throw new IllegalArgumentException("@Url is mutually exclusive with "
                            + ann.annotationType().getSimpleName() + " on the same parameter");
                }
            }
            return new ClientParamMeta(
                    null,
                    ClientParamMeta.ParamSource.URL,
                    param.getType(),
                    genericType,
                    componentType,
                    null,
                    index,
                    param);
        }

        if (param.isAnnotationPresent(PathParam.class)) {
            return new ClientParamMeta(
                    param.getAnnotation(PathParam.class).value(),
                    ClientParamMeta.ParamSource.PATH,
                    param.getType(),
                    genericType,
                    componentType,
                    defaultValue,
                    index,
                    param);
        }

        if (param.isAnnotationPresent(QueryParam.class)) {
            return new ClientParamMeta(
                    param.getAnnotation(QueryParam.class).value(),
                    ClientParamMeta.ParamSource.QUERY,
                    param.getType(),
                    genericType,
                    componentType,
                    defaultValue,
                    index,
                    param);
        }

        if (param.isAnnotationPresent(HeaderParam.class)) {
            return new ClientParamMeta(
                    param.getAnnotation(HeaderParam.class).value(),
                    ClientParamMeta.ParamSource.HEADER,
                    param.getType(),
                    genericType,
                    componentType,
                    defaultValue,
                    index,
                    param);
        }

        if (param.isAnnotationPresent(CookieParam.class)) {
            return new ClientParamMeta(
                    param.getAnnotation(CookieParam.class).value(),
                    ClientParamMeta.ParamSource.COOKIE,
                    param.getType(),
                    genericType,
                    componentType,
                    defaultValue,
                    index,
                    param);
        }

        if (param.isAnnotationPresent(BeanParam.class) || isRequestParams(param.getType())) {
            List<ClientParamMeta> beanFields = expandBeanParam(param.getType(), index);
            return new ClientParamMeta(
                    new ReflectiveParameterMetadata(index, null, param.getType(), genericType, param),
                    null,
                    ClientParamMeta.ParamSource.BEAN_PARAM,
                    null,
                    null,
                    beanFields);
        }

        // Unannotated: treat as request body
        return new ClientParamMeta(
                null,
                ClientParamMeta.ParamSource.BODY,
                param.getType(),
                genericType,
                componentType,
                null,
                index,
                param);
    }

    /**
     * Resolves the element/component type for a collection or array parameter.
     *
     * <p>For a single-type-argument {@link java.util.Collection} (e.g. {@code List<UUID>}) the sole
     * type argument is returned; for an array type (e.g. {@code UUID[]}) the array component type is
     * returned. All other shapes yield {@code null}.
     *
     * @param rawType     the erased parameter type
     * @param genericType the generic parameter type
     * @return the element type, or {@code null} when the parameter is not a single-element collection
     *     or array
     */
    private static Class<?> resolveComponentType(Class<?> rawType, Type genericType) {
        if (rawType.isArray()) {
            return rawType.getComponentType();
        }
        if (java.util.Collection.class.isAssignableFrom(rawType)
                && genericType instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> elementType) {
            return elementType;
        }
        return null;
    }

    /**
     * Expands a {@code @BeanParam} or {@code @RequestParams} type into its constituent
     * sub-parameters by inspecting record components (for records) or fields (for classes).
     *
     * <p>For records, annotations on record components are checked directly. For classes, fields
     * are inspected walking the class hierarchy. JAX-RS annotations
     * ({@code @QueryParam}, {@code @PathParam}, {@code @HeaderParam}, {@code @CookieParam})
     * on each element determine the parameter source. The {@code @DefaultValue} annotation is
     * also read from each field or record component.
     *
     * @param beanType the bean or record class to expand
     * @param beanIndex the zero-based index of the bean parameter in the parent method
     * @return the expanded list of sub-parameter descriptors
     */
    private static List<ClientParamMeta> expandBeanParam(Class<?> beanType, int beanIndex) {
        List<ClientParamMeta> fields = new ArrayList<>();

        if (beanType.isRecord()) {
            for (RecordComponent component : beanType.getRecordComponents()) {
                // For records, JAX-RS annotations land on the accessor method (due to @Target not
                // including RECORD_COMPONENT), so check both the component and its accessor.
                ClientParamMeta fieldMeta =
                        resolveAnnotatedElement(component.getName(), component.getType(), component, beanIndex);
                if (fieldMeta == null) {
                    fieldMeta = resolveAnnotatedElement(
                            component.getName(), component.getType(), component.getAccessor(), beanIndex);
                }
                if (fieldMeta == null) {
                    // Also check the backing field for records (annotations may be on field)
                    fieldMeta = resolveFieldInClass(component.getName(), component.getType(), beanType, beanIndex);
                }
                if (fieldMeta != null) {
                    fields.add(fieldMeta);
                }
            }
        } else {
            for (Field field : collectFields(beanType)) {
                ClientParamMeta fieldMeta = resolveAnnotatedElement(field.getName(), field.getType(), field, beanIndex);
                if (fieldMeta != null) {
                    fields.add(fieldMeta);
                }
            }
        }

        return Collections.unmodifiableList(fields);
    }

    /**
     * Checks an {@link AnnotatedElement} (field, record component, or accessor method) for JAX-RS
     * parameter annotations and creates a {@link ClientParamMeta} if one is found.
     *
     * <p>The {@code javaName} is stored as the {@link ClientParamMeta#accessorName()} so the proxy
     * can locate the correct field or record component via reflection, even when the JAX-RS wire
     * name differs from the Java identifier (e.g. field {@code pageSize} annotated with
     * {@code @QueryParam("size")}).
     *
     * <p>A {@link DefaultValue} annotation on the element is also captured.
     *
     * @param javaName the Java field or record-component name used for reflective access
     * @param type the raw type of the field
     * @param element the annotated element to inspect
     * @param beanIndex the index of the parent bean parameter in the method signature
     * @return a {@link ClientParamMeta} if a JAX-RS annotation is found, {@code null} otherwise
     */
    private static ClientParamMeta resolveAnnotatedElement(
            String javaName, Class<?> type, AnnotatedElement element, int beanIndex) {
        String defaultValue = resolveElementDefaultValue(element);
        Class<?> componentType = resolveComponentType(type, elementGenericType(element, type));

        PathParam pathParam = element.getAnnotation(PathParam.class);
        if (pathParam != null) {
            return new ClientParamMeta(
                    pathParam.value(),
                    javaName,
                    ClientParamMeta.ParamSource.PATH,
                    type,
                    componentType,
                    defaultValue,
                    element);
        }
        QueryParam queryParam = element.getAnnotation(QueryParam.class);
        if (queryParam != null) {
            return new ClientParamMeta(
                    queryParam.value(),
                    javaName,
                    ClientParamMeta.ParamSource.QUERY,
                    type,
                    componentType,
                    defaultValue,
                    element);
        }
        HeaderParam headerParam = element.getAnnotation(HeaderParam.class);
        if (headerParam != null) {
            return new ClientParamMeta(
                    headerParam.value(),
                    javaName,
                    ClientParamMeta.ParamSource.HEADER,
                    type,
                    componentType,
                    defaultValue,
                    element);
        }
        CookieParam cookieParam = element.getAnnotation(CookieParam.class);
        if (cookieParam != null) {
            return new ClientParamMeta(
                    cookieParam.value(),
                    javaName,
                    ClientParamMeta.ParamSource.COOKIE,
                    type,
                    componentType,
                    defaultValue,
                    element);
        }
        return null;
    }

    /**
     * Resolves the generic type of a bean-field annotation source (a {@link Field},
     * {@link RecordComponent}, or accessor {@link Method}).
     *
     * @param element  the annotation source element
     * @param fallback the raw type to fall back to when no richer generic type is available
     * @return the generic type of the element, or {@code fallback} when it cannot be determined
     */
    private static Type elementGenericType(AnnotatedElement element, Class<?> fallback) {
        if (element instanceof Field f) {
            return f.getGenericType();
        }
        if (element instanceof RecordComponent rc) {
            return rc.getGenericType();
        }
        if (element instanceof Method m) {
            return m.getGenericReturnType();
        }
        return fallback;
    }

    /**
     * Searches for a named field in the given class (walking the hierarchy) and checks it for
     * JAX-RS parameter annotations.
     *
     * @param fieldName the field name to look up
     * @param fieldType the expected field type
     * @param beanClass the class to search in
     * @param beanIndex the index of the parent bean parameter
     * @return a {@link ClientParamMeta} if found, {@code null} otherwise
     */
    private static ClientParamMeta resolveFieldInClass(
            String fieldName, Class<?> fieldType, Class<?> beanClass, int beanIndex) {
        Class<?> current = beanClass;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                ClientParamMeta meta = resolveAnnotatedElement(fieldName, fieldType, field, beanIndex);
                if (meta != null) {
                    return meta;
                }
            } catch (NoSuchFieldException ignored) {
                // continue up hierarchy
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Checks whether the given type is annotated with
     * {@code dev.vertique.rest.core.request.RequestParams}. This check is done by annotation
     * simple name and package prefix to avoid a compile-time dependency on the {@code rest-core}
     * module while still preventing false positives from third-party annotations with the same
     * simple name.
     *
     * @param type the type to check
     * @return {@code true} if annotated with a {@code @RequestParams} annotation from the
     *     {@code dev.vertique} package namespace
     */
    private static boolean isRequestParams(Class<?> type) {
        for (Annotation ann : type.getAnnotations()) {
            if ("RequestParams".equals(ann.annotationType().getSimpleName())
                    && ann.annotationType().getPackageName().startsWith("dev.vertique")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects all fields from the class hierarchy (stopping at {@link Object}).
     *
     * @param clazz the class to collect fields from
     * @return all declared fields across the hierarchy
     */
    private static List<Field> collectFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * Resolves the {@link DefaultValue} annotation from a method parameter, if present.
     *
     * @param param the method parameter to inspect
     * @return the default value string, or {@code null} if absent
     */
    private static String resolveDefaultValue(Parameter param) {
        DefaultValue dv = param.getAnnotation(DefaultValue.class);
        return dv != null ? dv.value() : null;
    }

    /**
     * Resolves the {@link DefaultValue} annotation from an arbitrary annotated element (field,
     * record component, accessor method), if present.
     *
     * @param element the element to inspect
     * @return the default value string, or {@code null} if absent
     */
    private static String resolveElementDefaultValue(AnnotatedElement element) {
        DefaultValue dv = element.getAnnotation(DefaultValue.class);
        return dv != null ? dv.value() : null;
    }

    // --- Media Type Resolution ---

    /**
     * Resolves the {@code Content-Type} media type to send, from {@code @Consumes} on the method
     * or the interface. Defaults to {@code application/json}.
     *
     * @param iface the client interface
     * @param method the interface method
     * @return the content type string
     */
    private static String resolveConsumes(Class<?> iface, Method method) {
        Consumes methodConsumes = method.getAnnotation(Consumes.class);
        if (methodConsumes != null && methodConsumes.value().length > 0) {
            return methodConsumes.value()[0];
        }
        Consumes classConsumes = iface.getAnnotation(Consumes.class);
        if (classConsumes != null && classConsumes.value().length > 0) {
            return classConsumes.value()[0];
        }
        return DEFAULT_MEDIA_TYPE;
    }

    /**
     * Resolves the {@code Accept} media type to request, from {@code @Produces} on the method or
     * the interface. Defaults to {@code application/json}.
     *
     * @param iface the client interface
     * @param method the interface method
     * @return the accept media type string
     */
    private static String resolveProduces(Class<?> iface, Method method) {
        Produces methodProduces = method.getAnnotation(Produces.class);
        if (methodProduces != null && methodProduces.value().length > 0) {
            return methodProduces.value()[0];
        }
        Produces classProduces = iface.getAnnotation(Produces.class);
        if (classProduces != null && classProduces.value().length > 0) {
            return classProduces.value()[0];
        }
        return DEFAULT_MEDIA_TYPE;
    }

    // --- Resilience Resolution ---

    /**
     * Resolves the {@link ExpectedStatus} annotation on a method into a Vert.x
     * {@link Expectation}. Returns {@code null} if no annotation is present or the annotation
     * specifies neither exact codes nor a range.
     *
     * @param method the interface method
     * @return the resolved expectation, or {@code null}
     */
    private static Expectation<HttpResponseHead> resolveExpectedStatus(Method method) {
        ExpectedStatus ann = method.getAnnotation(ExpectedStatus.class);
        if (ann == null) {
            return null;
        }
        if (ann.value().length > 0) {
            Expectation<HttpResponseHead> result = HttpResponseExpectation.status(ann.value()[0]);
            for (int i = 1; i < ann.value().length; i++) {
                result = result.or(HttpResponseExpectation.status(ann.value()[i]));
            }
            return result;
        }
        if (ann.min() >= 0 && ann.max() >= 0) {
            return HttpResponseExpectation.status(ann.min(), ann.max());
        }
        return null;
    }

    /**
     * Resolves the per-method {@link ResilienceConfig} by combining {@link Timeout},
     * {@link CircuitBreaker}, and {@link Retry} annotations on the method and its declaring
     * interface. Returns {@code null} if none of these annotations are present.
     *
     * <p>Method-level annotations take precedence over interface-level annotations for both
     * {@link Timeout} and {@link Retry}. A method-level annotation completely replaces the
     * corresponding interface-level one — individual fields are not merged.
     *
     * <p>{@link CircuitBreaker} is method-level only: an interface-level {@code @CircuitBreaker}
     * is handled separately by {@link dev.vertique.rest.client.RestClientBuilder}, which creates
     * a shared circuit breaker for the whole client interface. Inheriting it here would create
     * redundant per-method circuit breakers.
     *
     * @param method the interface method
     * @return the resilience configuration, or {@code null} if no resilience annotations are present
     */
    private static ResilienceConfig resolveResilienceConfig(Method method) {
        // @Timeout: method-level overrides interface-level (broadened to support type-level)
        Timeout timeoutAnn = method.getAnnotation(Timeout.class);
        if (timeoutAnn == null) {
            timeoutAnn = method.getDeclaringClass().getAnnotation(Timeout.class);
        }

        // @CircuitBreaker: method-level only (interface-level handled by RestClientBuilder)
        CircuitBreaker cbAnn = method.getAnnotation(CircuitBreaker.class);

        // @Retry: method-level overrides interface-level
        Retry retryAnn = method.getAnnotation(Retry.class);
        if (retryAnn == null) {
            retryAnn = method.getDeclaringClass().getAnnotation(Retry.class);
        }

        if (timeoutAnn == null && cbAnn == null && retryAnn == null) {
            return null;
        }

        long timeoutMs = timeoutAnn != null ? timeoutAnn.unit().toMillis(timeoutAnn.value()) : -1L;
        CircuitBreakerOptions cbOptions = cbAnn != null ? toCircuitBreakerOptions(cbAnn) : null;
        RetryConfig retryConfig = retryAnn != null ? toRetryConfig(retryAnn) : null;

        return new ResilienceConfig(cbOptions, timeoutMs, retryConfig);
    }

    /**
     * Converts a {@link CircuitBreaker} annotation into a Vert.x {@link CircuitBreakerOptions}.
     *
     * @param ann the annotation to convert
     * @return the populated options
     */
    private static CircuitBreakerOptions toCircuitBreakerOptions(CircuitBreaker ann) {
        return new CircuitBreakerOptions()
                .setTimeout(ann.timeoutMs())
                .setMaxFailures(ann.maxFailures())
                .setResetTimeout(ann.resetTimeoutMs());
    }

    /**
     * Converts a {@link Retry} annotation into a {@link RetryConfig} record.
     *
     * @param ann the annotation to convert
     * @return the populated retry configuration
     */
    private static RetryConfig toRetryConfig(Retry ann) {
        Set<Class<? extends Throwable>> retryOn = Arrays.stream(ann.retryOn()).collect(Collectors.toUnmodifiableSet());
        Set<Class<? extends Throwable>> abortOn = Arrays.stream(ann.abortOn()).collect(Collectors.toUnmodifiableSet());
        return new RetryConfig(ann.maxRetries(), ann.backoff(), retryOn, abortOn);
    }

    // --- Method Collection ---

    /**
     * Collects all interface methods to scan, skipping bridge, synthetic, and default methods.
     *
     * @param iface the interface to collect methods from
     * @return the list of candidate methods
     */
    private static List<Method> collectMethods(Class<?> iface) {
        List<Method> methods = new ArrayList<>();
        for (Method method : iface.getMethods()) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (method.isDefault()) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            methods.add(method);
        }
        return methods;
    }
}
