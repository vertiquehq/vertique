// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.client.convert.ClientConversionContexts;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.client.meta.ClientParamMeta;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds HTTP request components (path, query string, headers, body) from method metadata and
 * invocation arguments.
 *
 * <p>This class handles all parameter resolution — path substitution, query string assembly, header
 * and cookie population, and body serialization — for a single REST client method invocation.
 *
 * <p>Bean-param field extraction is delegated to the {@link BeanParamAccessorRegistry}, which
 * resolves a generated accessor when available, falling back to the reflective path for types
 * without a generated class.
 */
@Slf4j
final class RestClientRequestFactory {

    static final String MEDIA_TEXT_PLAIN = "text/plain";
    static final String MEDIA_OCTET_STREAM = "application/octet-stream";

    /** Built-ins-only resolver used as a fallback when no application resolver is provided. */
    private static final ParamConversionResolver BUILTIN_RESOLVER = ParamConversionResolver.builtins();

    private final ObjectMapper objectMapper;
    private final BeanParamAccessorRegistry beanParamAccessorRegistry;
    private final ParamConversionResolver resolver;

    /**
     * Per-factory cache of the scalar {@link ConversionContext} for each parameter, keyed by the
     * {@link ClientParamMeta} instance. Populated lazily on the hot path so a fresh
     * {@code ConversionContext} (and its {@code Annotation[]} closure) is allocated at most once per
     * parameter for the lifetime of this factory instead of per call. {@link ClientParamMeta}
     * instances are scan-cached and stable per method (see {@link RestClientBuilder}'s
     * {@code META_CACHE}), so {@code computeIfAbsent} deduplicates by identity/value-equal keys.
     * Mirrors {@code dev.vertique.rest.jaxrs.ParameterExtractor}'s {@code scalarContextCache}.
     */
    private final Map<ClientParamMeta, ConversionContext> scalarContextCache = new ConcurrentHashMap<>();

    /**
     * Per-factory cache of the per-element {@link ConversionContext} for each collection-valued
     * parameter, keyed by the {@link ClientParamMeta} instance. Mirrors {@link #scalarContextCache}
     * for the collection-expansion path; since {@link ClientConversionContexts#forComponent} only
     * accepts a component type equal to {@code pm.componentType()}, the key is the {@code pm} alone.
     */
    private final Map<ClientParamMeta, ConversionContext> componentContextCache = new ConcurrentHashMap<>();

    /**
     * Creates a new request factory using the shared {@link BeanParamAccessorRegistry} and the
     * framework built-ins-only conversion resolver.
     *
     * @param objectMapper the Jackson ObjectMapper used to serialize request bodies
     */
    RestClientRequestFactory(ObjectMapper objectMapper) {
        this(objectMapper, BeanParamAccessorRegistry.shared(), BUILTIN_RESOLVER);
    }

    /**
     * Creates a new request factory with an explicit {@link BeanParamAccessorRegistry} and the
     * framework built-ins-only conversion resolver.
     *
     * @param objectMapper the Jackson ObjectMapper used to serialize request bodies
     * @param beanParamAccessorRegistry the registry used to resolve bean-param field accessors
     */
    RestClientRequestFactory(ObjectMapper objectMapper, BeanParamAccessorRegistry beanParamAccessorRegistry) {
        this(objectMapper, beanParamAccessorRegistry, BUILTIN_RESOLVER);
    }

    /**
     * Creates a new request factory with an explicit {@link BeanParamAccessorRegistry} and the
     * supplied {@link ParamConversionResolver} for outbound typed-parameter serialization.
     *
     * <p>This is the constructor used by {@link RestClientBuilder#build(Class)} after slice 4.2,
     * which threads the effective resolver (built from the builder's converter accumulator lists,
     * or the Dagger-provided app-wide resolver seeded by {@link RestClientFactory#builder()}).
     *
     * @param objectMapper the Jackson ObjectMapper used to serialize request bodies
     * @param resolver the parameter conversion resolver for outbound serialization
     */
    RestClientRequestFactory(ObjectMapper objectMapper, ParamConversionResolver resolver) {
        this(objectMapper, BeanParamAccessorRegistry.shared(), resolver);
    }

    /**
     * Full constructor with explicit registry and resolver.
     *
     * @param objectMapper the Jackson ObjectMapper used to serialize request bodies
     * @param beanParamAccessorRegistry the registry used to resolve bean-param field accessors
     * @param resolver the parameter conversion resolver for outbound serialization
     */
    RestClientRequestFactory(
            ObjectMapper objectMapper,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            ParamConversionResolver resolver) {
        this.objectMapper = objectMapper;
        this.beanParamAccessorRegistry = beanParamAccessorRegistry;
        this.resolver = resolver;
    }

    // --- Path and Query ---

    /**
     * Builds the path by substituting {@code {paramName}} placeholders with URL-encoded arg values.
     * Each value is serialized through the {@link ParamConversionResolver} before URL-encoding.
     *
     * @param meta the method metadata
     * @param args the method arguments
     * @return the path with path parameters substituted
     */
    String buildPath(ClientMethodMeta meta, Object[] args) {
        String path = meta.pathTemplate();
        for (ClientParamMeta param : meta.params()) {
            if (param.source() == ClientParamMeta.ParamSource.PATH) {
                Object rawArg = rawArg(args, param);
                String serialized = serializeScalar(rawArg, param.defaultValue(), scalarContext(param));
                if (serialized == null) {
                    throw new RestClientException(
                            "path param '" + param.name() + "' was null and has no @DefaultValue");
                }
                path = path.replace("{" + param.name() + "}", urlEncode(serialized));
            } else if (param.source() == ClientParamMeta.ParamSource.BEAN_PARAM) {
                Object bean = args[param.index()];
                if (bean != null) {
                    for (ClientParamMeta field : param.beanFields()) {
                        if (field.source() == ClientParamMeta.ParamSource.PATH) {
                            String accessorName = field.accessorName() != null ? field.accessorName() : field.name();
                            Object rawFieldValue = extractFieldValue(bean, accessorName);
                            String serialized =
                                    serializeScalar(rawFieldValue, field.defaultValue(), scalarContext(field));
                            if (serialized == null) {
                                throw new RestClientException(
                                        "path param '" + field.name() + "' was null and has no @DefaultValue");
                            }
                            path = path.replace("{" + field.name() + "}", urlEncode(serialized));
                        }
                    }
                }
            }
        }
        return path;
    }

    /**
     * Builds the query string suffix for URI construction.
     *
     * @param meta the method metadata
     * @param args the method arguments
     * @return the query string including leading {@code ?}, or empty string if no query params
     */
    String buildQueryString(ClientMethodMeta meta, Object[] args) {
        List<String> encoded = encodeQueryParams(meta, args);
        return encoded.isEmpty() ? "" : "?" + String.join("&", encoded);
    }

    // --- Headers ---

    /**
     * Populates a {@link io.vertx.core.MultiMap} with {@link ClientParamMeta.ParamSource#HEADER}
     * and {@link ClientParamMeta.ParamSource#COOKIE} parameters from method arguments.
     *
     * <p>Each value is serialized through the {@link ParamConversionResolver} via
     * {@link #collectParamsWithMeta} before being set on the header map.
     *
     * @param headers the target header map
     * @param meta the method metadata
     * @param args the method arguments
     */
    void applyParamHeadersToMap(io.vertx.core.MultiMap headers, ClientMethodMeta meta, Object[] args) {
        for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.HEADER)) {
            // collectParamsWithMeta already serialized to String via resolver; value is a String.
            headers.set(entry.getKey(), (String) entry.getValue());
        }
        // Apply cookie params as a single "Cookie" header
        List<String> cookieParts = new ArrayList<>();
        for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.COOKIE)) {
            cookieParts.add(entry.getKey() + "=" + urlEncode((String) entry.getValue()));
        }
        if (!cookieParts.isEmpty()) {
            headers.set("Cookie", String.join("; ", cookieParts));
        }
    }

    // --- Body ---

    /**
     * Serializes the request body parameter, if any. Respects the {@code @Consumes} media type:
     * {@code text/plain} → raw UTF-8 string; {@code application/octet-stream} → raw bytes if the
     * value is a {@link byte[]} or {@link Buffer}; otherwise JSON.
     *
     * @param meta the method metadata
     * @param args the method arguments
     * @return the serialized body buffer, or {@code null} if no body parameter is declared
     */
    @Nullable
    Buffer buildBody(ClientMethodMeta meta, Object[] args) {
        for (ClientParamMeta param : meta.params()) {
            if (param.source() == ClientParamMeta.ParamSource.BODY) {
                Object value = args[param.index()];
                if (value == null) {
                    return null;
                }
                try {
                    return serializeBody(objectMapper, meta.consumesMediaType(), value);
                } catch (RestClientException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RestClientException(
                            "Failed to serialize request body for "
                                    + meta.methodMetadata().name(),
                            e);
                }
            }
        }
        return null;
    }

    /**
     * Serializes a non-null request body value according to the {@code @Consumes} media type,
     * shared by both the JDK reflective proxy path ({@link #buildBody}) and the generated
     * static proxy path ({@code DefaultRestClientDispatcher.applyBody}) so both proxy shapes
     * apply the exact same media-type branching.
     *
     * <p>{@code text/plain} serializes {@code value.toString()} as raw UTF-8 bytes;
     * {@code application/octet-stream} passes {@code byte[]} or {@link Buffer} values through
     * unchanged (falling back to JSON for any other value type); every other media type
     * JSON-serializes via the given {@link ObjectMapper}.
     *
     * @param objectMapper the Jackson mapper used for the JSON fallback branch
     * @param consumesMediaType the {@code @Consumes} media type driving the branch selection
     * @param value the non-null body value to serialize
     * @return the serialized body buffer
     * @throws Exception if JSON serialization fails
     */
    static Buffer serializeBody(ObjectMapper objectMapper, String consumesMediaType, Object value) throws Exception {
        if (consumesMediaType.startsWith(MEDIA_TEXT_PLAIN)) {
            // text/plain: serialize as raw string
            return Buffer.buffer(value.toString().getBytes(StandardCharsets.UTF_8));
        } else if (consumesMediaType.startsWith(MEDIA_OCTET_STREAM)) {
            // application/octet-stream: pass through byte[] or Buffer
            if (value instanceof byte[] bytes) {
                return Buffer.buffer(bytes);
            } else if (value instanceof Buffer buf) {
                return buf;
            }
            // Fall through to JSON for other types
        }
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        return Buffer.buffer(bytes);
    }

    // --- Parameter Collection ---

    /**
     * Encodes all {@code @QueryParam} values from the method arguments as URL-encoded
     * {@code key=value} pairs. Each value is serialized through the {@link ParamConversionResolver}
     * before URL-encoding. Shared by {@link #buildQueryString} and
     * {@link #buildUrlWithQueryParams}.
     *
     * <p>Collection-valued query params are expanded element-by-element: each element is serialized
     * via the component-type {@link ConversionContext} and emitted as a separate {@code key=value}
     * pair (multi-value query string).
     *
     * @param meta the method metadata
     * @param args the method arguments
     * @return list of encoded {@code key=value} strings; empty if no query params
     */
    private List<String> encodeQueryParams(ClientMethodMeta meta, Object[] args) {
        List<String> parts = new ArrayList<>();
        for (ClientParamMeta param : meta.params()) {
            if (param.source() == ClientParamMeta.ParamSource.QUERY) {
                Object rawArg = rawArg(args, param);
                List<Object> elements =
                        param.componentType() != null ? ClientConversionContexts.elementsOf(rawArg) : null;
                if (elements != null) {
                    // Expand collection/array params element-by-element (only a real argument can be
                    // a collection/array — a @DefaultValue is always a scalar wire-form string).
                    ConversionContext elemCtx = componentContext(param);
                    for (Object element : elements) {
                        if (element != null) {
                            parts.add(urlEncode(param.name()) + "=" + urlEncode(resolver.toString(element, elemCtx)));
                        }
                    }
                } else {
                    String serialized = serializeScalar(rawArg, param.defaultValue(), scalarContext(param));
                    if (serialized != null) {
                        parts.add(urlEncode(param.name()) + "=" + urlEncode(serialized));
                    }
                }
            } else if (param.source() == ClientParamMeta.ParamSource.BEAN_PARAM) {
                Object bean = args[param.index()];
                if (bean != null) {
                    for (ClientParamMeta field : param.beanFields()) {
                        if (field.source() == ClientParamMeta.ParamSource.QUERY) {
                            String accessorName = field.accessorName() != null ? field.accessorName() : field.name();
                            Object rawFieldValue = extractFieldValue(bean, accessorName);
                            List<Object> fieldElements = field.componentType() != null
                                    ? ClientConversionContexts.elementsOf(rawFieldValue)
                                    : null;
                            if (fieldElements != null) {
                                ConversionContext elemCtx = componentContext(field);
                                for (Object element : fieldElements) {
                                    if (element != null) {
                                        parts.add(urlEncode(field.name()) + "="
                                                + urlEncode(resolver.toString(element, elemCtx)));
                                    }
                                }
                            } else {
                                String serialized =
                                        serializeScalar(rawFieldValue, field.defaultValue(), scalarContext(field));
                                if (serialized != null) {
                                    parts.add(urlEncode(field.name()) + "=" + urlEncode(serialized));
                                }
                            }
                        }
                    }
                }
            }
        }
        return parts;
    }

    // --- @Url support ---

    /**
     * Extracts the {@code @Url} URI from the method arguments, if the method declares one.
     *
     * @param meta the method metadata
     * @param args the method arguments
     * @return the absolute URI, or {@code null} if no {@code @Url} parameter is present
     * @throws RestClientException if the URI is null, relative, uses a non-HTTP scheme, contains a
     *     fragment, has no authority, or has no host
     */
    @Nullable
    URI extractUrlParam(ClientMethodMeta meta, Object[] args) {
        for (ClientParamMeta param : meta.params()) {
            if (param.source() == ClientParamMeta.ParamSource.URL) {
                Object value = args[param.index()];
                if (value == null) {
                    throw new RestClientException("@Url parameter must not be null");
                }
                return validateUrl((URI) value);
            }
        }
        return null;
    }

    /**
     * Validates a non-null {@code @Url} URI, shared by both the JDK reflective proxy path
     * ({@link #extractUrlParam}) and the generated static proxy path (
     * {@code DefaultRestClientDispatcher.applyUrlParam}) so both proxy shapes enforce the exact
     * same constraints with the exact same messages.
     *
     * @param uri the non-null URI to validate
     * @return {@code uri} unchanged, if valid
     * @throws RestClientException if the URI is relative, uses a non-HTTP(S) scheme, contains a
     *     fragment, has no authority, or has no host
     */
    static URI validateUrl(URI uri) {
        if (!uri.isAbsolute()) {
            throw new RestClientException("@Url URI must be absolute (have a scheme), got: " + uri);
        }
        if (uri.getAuthority() == null) {
            throw new RestClientException("@Url URI must be a hierarchical HTTP URI, got: " + uri);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RestClientException("@Url URI must have a valid host, got: " + uri);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new RestClientException("@Url URI scheme must be http or https, got: " + uri.getScheme());
        }
        if (uri.getFragment() != null) {
            throw new RestClientException("@Url URI must not contain a fragment, got: " + uri);
        }
        return uri;
    }

    /**
     * Builds the full request URI string from a {@code @Url} URI, merging any {@code @QueryParam}
     * values from the method arguments.
     *
     * <p>The URI's existing query parameters (if any) are preserved as-is (already encoded).
     * Additional {@code @QueryParam} values are URL-encoded and appended with {@code &}.
     *
     * @param uri the absolute URI from the {@code @Url} parameter
     * @param meta the method metadata
     * @param args the method arguments
     * @return the full URI string with merged query parameters
     */
    String buildUrlWithQueryParams(URI uri, ClientMethodMeta meta, Object[] args) {
        // Reconstruct base: scheme + authority + path
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty();
        if (hasPath) {
            sb.append(uri.getRawPath());
        }

        // Collect query parts
        List<String> queryParts = new ArrayList<>();

        // Preserve existing query params from the URI (already encoded)
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            queryParts.add(rawQuery);
        }

        // Append @QueryParam values
        List<String> paramParts = encodeQueryParams(meta, args);
        if (!paramParts.isEmpty()) {
            queryParts.add(String.join("&", paramParts));
        }

        if (!queryParts.isEmpty()) {
            // Ensure a leading '/' before '?' for authority-only URIs (e.g. https://host?q=1
            // becomes https://host/?q=1) — some strict HTTP servers expect this.
            if (!hasPath) {
                sb.append('/');
            }
            sb.append('?').append(String.join("&", queryParts));
        }

        return sb.toString();
    }

    // --- RestRequestBuilder population ---

    /**
     * Populates a {@link RestRequestBuilder} from method metadata and invocation arguments.
     *
     * <p>For {@code @Url} methods, the absolute URI is pre-resolved and stored via
     * {@link RestRequestBuilder#absoluteUri(String)}. For normal methods, path params and query
     * params are stored as named entries; the dispatcher assembles the final URI.
     *
     * <p>Headers (from {@code @HeaderParam}) and cookies (from {@code @CookieParam}) are stored
     * as named entries. The body is pre-serialized into a {@link io.vertx.core.buffer.Buffer}.
     *
     * @param meta the method metadata
     * @param args the method invocation arguments
     * @return a populated {@link RestRequestBuilder}
     */
    RestRequestBuilder buildRequestBuilder(ClientMethodMeta meta, Object[] args) {
        RestRequestBuilder builder = new RestRequestBuilder();

        if (meta.hasUrlParam()) {
            // @Url path: pre-resolve the absolute URI (including any @QueryParam merging)
            URI urlParam = extractUrlParam(meta, args);
            String fullUri = buildUrlWithQueryParams(urlParam, meta, args);
            builder.absoluteUri(fullUri);
        } else {
            // Normal path: accumulate path params, serialized through the resolver.
            // Values are stored as their resolver-serialized Strings so the dispatcher can call
            // entry.getValue().toString() safely for both the JDK reflective proxy and the
            // generated static proxy code paths.
            for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.PATH)) {
                builder.path(entry.getKey(), entry.getValue());
            }
            // Query params: serialize each scalar through the resolver; expand collections
            // element-by-element so multi-valued params produce one entry per element.
            for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.QUERY)) {
                builder.query(entry.getKey(), entry.getValue());
            }
        }

        // Accumulate header params — serialize each typed value via the resolver
        for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.HEADER)) {
            builder.header(entry.getKey(), entry.getValue().toString());
        }

        // Accumulate cookie params — serialize each typed value via the resolver
        for (Entry<String, Object> entry : collectParamsWithMeta(meta, args, ClientParamMeta.ParamSource.COOKIE)) {
            builder.cookie(entry.getKey(), entry.getValue().toString());
        }

        // Serialize body
        builder.body(buildBody(meta, args));

        return builder;
    }

    /**
     * Collects resolved parameter name-value pairs for the given source, serializing each value
     * via the {@link ParamConversionResolver} using a {@link ConversionContext} built from the
     * parameter metadata. Collection-valued parameters are expanded element-by-element, each
     * element serialized via its component-type context.
     *
     * <p>This method is used for HEADER and COOKIE sources where the resolved string must replace
     * the typed value before the entry reaches the wire. PATH and QUERY entries are serialized
     * later in the dispatcher's {@code assembleUri} path (also via the resolver).
     *
     * @param meta   the method metadata
     * @param args   the invocation arguments
     * @param source the parameter source to collect (HEADER or COOKIE)
     * @return name-value entries where each value is already the resolver-serialized string
     */
    private List<Entry<String, Object>> collectParamsWithMeta(
            ClientMethodMeta meta, Object[] args, ClientParamMeta.ParamSource source) {
        List<Entry<String, Object>> result = new ArrayList<>();
        for (ClientParamMeta param : meta.params()) {
            if (param.source() == source) {
                Object rawArg = rawArg(args, param);
                List<Object> elements =
                        param.componentType() != null ? ClientConversionContexts.elementsOf(rawArg) : null;
                if (elements != null) {
                    // Expand collection/array params element-by-element, each via the component-type
                    // context (only a real argument can be a collection/array — a @DefaultValue is
                    // always a scalar wire-form string).
                    ConversionContext elemCtx = componentContext(param);
                    for (Object element : elements) {
                        if (element != null) {
                            result.add(Map.entry(param.name(), resolver.toString(element, elemCtx)));
                        }
                    }
                } else {
                    String serialized = serializeScalar(rawArg, param.defaultValue(), scalarContext(param));
                    if (serialized == null && source == ClientParamMeta.ParamSource.PATH) {
                        // A null path param leaves the {placeholder} unresolved.
                        throw new RestClientException(
                                "path param '" + param.name() + "' was null and has no @DefaultValue");
                    }
                    if (serialized != null) {
                        result.add(Map.entry(param.name(), serialized));
                    }
                }
            } else if (param.source() == ClientParamMeta.ParamSource.BEAN_PARAM) {
                Object bean = args[param.index()];
                if (bean != null) {
                    for (ClientParamMeta field : param.beanFields()) {
                        if (field.source() == source) {
                            String accessorName = field.accessorName() != null ? field.accessorName() : field.name();
                            Object rawFieldValue = extractFieldValue(bean, accessorName);
                            List<Object> fieldElements = field.componentType() != null
                                    ? ClientConversionContexts.elementsOf(rawFieldValue)
                                    : null;
                            if (fieldElements != null) {
                                ConversionContext elemCtx = componentContext(field);
                                for (Object element : fieldElements) {
                                    if (element != null) {
                                        result.add(Map.entry(field.name(), resolver.toString(element, elemCtx)));
                                    }
                                }
                            } else {
                                String serialized =
                                        serializeScalar(rawFieldValue, field.defaultValue(), scalarContext(field));
                                if (serialized == null && source == ClientParamMeta.ParamSource.PATH) {
                                    throw new RestClientException(
                                            "path param '" + field.name() + "' was null and has no @DefaultValue");
                                }
                                if (serialized != null) {
                                    result.add(Map.entry(field.name(), serialized));
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    // --- Utilities ---

    /**
     * URL-encodes a value using UTF-8, replacing {@code +} with {@code %20}.
     *
     * @param value the string to encode
     * @return the percent-encoded string
     */
    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Returns the per-factory scalar {@link ConversionContext} for {@code param}, computing and
     * caching it on first use so the hot path allocates no fresh context or annotation array on
     * repeated calls. Mirrors {@code ParameterExtractor.scalarContext} on the inbound path.
     *
     * @param param the parameter metadata
     * @return the cached scalar conversion context for this parameter
     */
    private ConversionContext scalarContext(ClientParamMeta param) {
        return scalarContextCache.computeIfAbsent(param, ClientConversionContexts::forParam);
    }

    /**
     * Returns the per-factory per-element {@link ConversionContext} for the collection-valued
     * {@code param}, computing and caching it on first use. Mirrors {@link #scalarContext} for the
     * collection-expansion path.
     *
     * @param param the collection parameter metadata; {@link ClientParamMeta#componentType()} must
     *     be non-null
     * @return the cached per-element conversion context for this parameter
     */
    private ConversionContext componentContext(ClientParamMeta param) {
        return componentContextCache.computeIfAbsent(
                param, pm -> ClientConversionContexts.forComponent(pm, pm.componentType()));
    }

    /**
     * Returns the raw invocation argument for a top-level parameter, with no {@code @DefaultValue}
     * substitution applied.
     *
     * @param args the method arguments array
     * @param param the parameter metadata
     * @return the raw argument value, or {@code null} if absent or out of range
     */
    @Nullable
    private Object rawArg(Object[] args, ClientParamMeta param) {
        return param.index() < args.length ? args[param.index()] : null;
    }

    /**
     * Serializes a scalar (non-collection) parameter value to its wire-form string, applying the
     * {@code @DefaultValue} contract correctly: a {@code @DefaultValue} string is already wire-form
     * (the same contract the server-side {@code ParameterExtractor} relies on to parse it directly),
     * so when {@code rawArg} is {@code null} the raw {@code defaultValue} string is returned
     * unchanged — it is <em>not</em> passed through {@code resolver.toString(...)} a second time.
     * Only a real, non-null argument is serialized via the {@link ParamConversionResolver}.
     *
     * <p>Mirrors {@code DefaultRestClientDispatcher.applySimpleParam}'s generated-proxy behaviour,
     * which already applies the raw default directly via the builder setter.
     *
     * @param rawArg the raw invocation argument; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback string, or {@code null}
     * @param ctx the conversion context used only when {@code rawArg} is non-null
     * @return the wire-form string, or {@code null} if both {@code rawArg} and {@code defaultValue}
     *     are absent
     */
    @Nullable
    private String serializeScalar(@Nullable Object rawArg, @Nullable String defaultValue, ConversionContext ctx) {
        if (rawArg == null) {
            return defaultValue;
        }
        return resolver.toString(rawArg, ctx);
    }

    /**
     * Extracts a field value from a bean or record by field/component name, delegating to the
     * {@link BeanParamAccessorRegistry}.
     *
     * <p>The registry resolves a generated accessor when one is present on the classpath
     * ({@code {BeanType}_BeanParamAccessor}), falling back to the reflective path
     * ({@link ReflectiveBeanParamAccessor}) for types without a generated accessor.
     *
     * @param bean the bean or record instance
     * @param fieldName the Java field or record-component name to access
     * @return the field value, or {@code null} if not accessible or not found
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private Object extractFieldValue(Object bean, String fieldName) {
        BeanParamAccessor<Object> accessor =
                (BeanParamAccessor<Object>) beanParamAccessorRegistry.resolve(bean.getClass());
        return accessor.extract(bean, fieldName);
    }
}
