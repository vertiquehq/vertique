// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.client.convert.ClientConversionContexts;
import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientAttemptTarget;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.client.meta.ClientParamMeta;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import io.vertx.core.AsyncResult;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of {@link RestClientDispatcher} that runs the full 14-step HTTP
 * dispatch pipeline.
 *
 * <p>This class consolidates the pipeline that was previously duplicated in
 * {@link RestClientProxy#executeRequest} and {@link RestClientProxy} private helpers. Both
 * the reflective JDK proxy and the generated static proxies (Pass B) delegate to this
 * dispatcher so that the pipeline is implemented exactly once.
 *
 * <p>Pipeline steps (same as the prior {@link RestClientProxy} path):
 * <ol>
 *   <li>Assemble the full URI from the {@link RestRequestBuilder} contents and meta.</li>
 *   <li>Build the case-insensitive header map (default headers + param headers + cookies).</li>
 *   <li>Create {@link RestClientRequestContext}.</li>
 *   <li>Fire sync {@code onRequest} observers.</li>
 *   <li>Run async {@code beforeRequest} interceptors (may mutate headers/URI).</li>
 *   <li>Build Vert.x {@link HttpRequest} from final URI.</li>
 *   <li>Apply per-method timeout.</li>
 *   <li>Send inside circuit breaker if configured.</li>
 *   <li>Apply {@link Expectation} on the response.</li>
 *   <li>Capture response context; fire sync {@code onResponse} observers.</li>
 *   <li>Run async {@code afterResponse} interceptors.</li>
 *   <li>Handle {@code Optional<T>}: 404 → {@code Optional.empty()}.</li>
 *   <li>Deserialize body using per-client ObjectMapper + {@link ClientMethodMeta#responseType()}.</li>
 *   <li>Validate with bean validator if configured.</li>
 * </ol>
 */
@Slf4j
final class DefaultRestClientDispatcher implements RestClientDispatcher {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ACCEPT = "Accept";

    // --- Pipeline state ---

    private final WebClient webClient;
    private final String baseUrl;
    private final io.vertx.core.MultiMap cachedDefaultHeaders;
    private final RestClientExceptionMapper exceptionMapper;
    private final ObjectMapper objectMapper;

    @Nullable
    private final BeanValidator beanValidator;

    private final String clientName;
    private final RestClientInterceptorChain interceptorChain;
    private final RestClientResilienceResolver resilienceResolver;

    /** System-owned context capturers, pre-sorted in {@code OrderedExtension} order. */
    private final List<RestClientContextCapturer<?>> contextCapturers;

    /**
     * Resolver for outbound parameter serialization; used by the generated-proxy
     * {@code applyPathParam}/{@code applyQueryParam}/{@code applyHeaderParam}/{@code applyCookieParam}
     * apply methods to mirror the JDK-proxy path's typed conversion.
     */
    private final ParamConversionResolver resolver;

    /**
     * Creates a new dispatcher with all pipeline dependencies resolved.
     *
     * @param webClient the Vert.x web client used to send requests
     * @param baseUrl the base URL prepended to all path templates
     * @param cachedDefaultHeaders pre-built case-insensitive MultiMap of default headers;
     *     copied into each request's header map at dispatch time
     * @param interceptorChain encapsulates all interceptor pipeline execution
     * @param exceptionMapper translates transport and HTTP exceptions
     * @param objectMapper Jackson mapper for serialization and deserialization
     * @param resilienceResolver resolves timeout, expectation, circuit breaker, and retry policy
     * @param beanValidator optional response validator; {@code null} to skip validation
     * @param clientName the logical name of the REST client (used in interceptor context)
     * @param contextCapturers system-owned context capturers, pre-sorted in {@code OrderedExtension} order
     * @param resolver the parameter conversion resolver for outbound typed-parameter serialization;
     *     used by generated proxies to serialize each param through the same converter chain as
     *     the JDK reflective proxy path
     */
    DefaultRestClientDispatcher(
            WebClient webClient,
            String baseUrl,
            io.vertx.core.MultiMap cachedDefaultHeaders,
            RestClientInterceptorChain interceptorChain,
            RestClientExceptionMapper exceptionMapper,
            ObjectMapper objectMapper,
            RestClientResilienceResolver resilienceResolver,
            @Nullable BeanValidator beanValidator,
            String clientName,
            List<RestClientContextCapturer<?>> contextCapturers,
            ParamConversionResolver resolver) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.cachedDefaultHeaders = cachedDefaultHeaders;
        this.interceptorChain = interceptorChain;
        this.exceptionMapper = exceptionMapper;
        this.objectMapper = objectMapper;
        this.resilienceResolver = resilienceResolver;
        this.beanValidator = beanValidator;
        this.clientName = clientName;
        this.contextCapturers = contextCapturers;
        this.resolver = resolver;
    }

    /**
     * Parses a URI string for safe-by-type target extraction (scheme/host/port only). Null-tolerant: a
     * blank or syntactically invalid value yields {@code null} (the target then carries null scheme/host
     * and port {@code -1}); never throws on the dispatch path.
     *
     * @param uri the URI string, possibly blank or relative
     * @return the parsed URI, or {@code null} when it cannot be parsed
     */
    @Nullable
    private static URI parseUriSafely(@Nullable String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return URI.create(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Builds the safe-by-type {@link RestClientAttemptTarget} for an attempt from the <em>actual sent</em>
     * request URI — correct for both base-URL and {@code @Url}-argument methods (the recorded host always
     * matches the host the request was sent to). Only scheme/host/port are extracted (never the expanded
     * path, query, or userinfo). The route template is omitted for {@code @Url} methods, which have no
     * meaningful template.
     *
     * @param reqCtx the request context for this attempt (its {@code requestUri()} is the sent URI)
     * @param meta   the method metadata
     * @return the safe target identity
     */
    private static RestClientAttemptTarget buildTarget(RestClientRequestContext reqCtx, ClientMethodMeta meta) {
        URI sent = parseUriSafely(reqCtx.requestUri());
        String scheme = sent != null ? sent.getScheme() : null;
        String host = sent != null ? sent.getHost() : null;
        int port = sent != null ? sent.getPort() : -1;
        String pathTemplate = meta.hasUrlParam() ? null : meta.pathTemplate();
        return new RestClientAttemptTarget(scheme, host, port, pathTemplate);
    }

    /**
     * Pairs a {@link RestClientContextCapturer} with the value it captured at request entry, so the
     * dispatcher can hand each capturer back its own value per attempt without exposing it on any
     * application-visible surface.
     */
    private record CapturerCapture(
            RestClientContextCapturer<?> capturer, @Nullable Object value) {
        /**
         * Invokes the capturer's four-arg {@code onAttemptCompleted} with its own captured value and
         * the dispatcher-owned {@code operation} metadata.
         *
         * @param req         the request context for this attempt
         * @param completion  the attempt's completion facts
         * @param operation   the dispatcher-owned method metadata for the invoked client-interface
         *                    operation
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void fire(RestClientRequestContext req, RestClientAttemptCompletion completion, MethodMetadata operation) {
            ((RestClientContextCapturer) capturer).onAttemptCompleted(value, req, completion, operation);
        }
    }

    /**
     * Invokes every {@link RestClientContextCapturer} once, at request entry on the caller's context, and
     * pairs each with its captured value. Exceptions are swallowed (capture is fire-and-forget); a failing
     * capturer yields a {@code null} value.
     *
     * @return the per-call capture list (empty when no capturers are registered)
     */
    private List<CapturerCapture> captureContexts() {
        List<CapturerCapture> captures = new ArrayList<>(contextCapturers.size());
        for (RestClientContextCapturer<?> capturer : contextCapturers) {
            Object value;
            try {
                value = capturer.captureRequestContext();
            } catch (Exception e) {
                log.debug("RestClientContextCapturer.captureRequestContext threw (ignored)", e);
                value = null;
            }
            captures.add(new CapturerCapture(capturer, value));
        }
        return captures;
    }

    /**
     * Creates a new empty {@link RestRequestBuilder} for the given method invocation.
     *
     * @param meta the method metadata; unused in the builder creation itself but part of the
     *     interface contract
     * @return a new mutable request builder
     */
    @Override
    public RestRequestBuilder newRequest(ClientMethodMeta meta) {
        return new RestRequestBuilder();
    }

    /**
     * Sends the request described by the builder and returns a deserialized response future.
     *
     * @param <T> the expected return type
     * @param request the pre-populated request builder
     * @param meta the method metadata driving URI assembly, deserialization, and resilience
     * @return a {@link Future} completing with the deserialized value
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Future<T> send(RestRequestBuilder request, ClientMethodMeta meta) {
        return (Future<T>) executeRequest(request, meta);
    }

    // --- Generated-proxy param serialization helpers ---

    /**
     * Resolves the {@link ClientParamMeta} for the given wire param name and {@link
     * ClientParamMeta.ParamSource} from a method's param list, searching both top-level params and
     * bean-field sub-params recursively.
     *
     * <p>Filtering by source (in addition to name) is required because the same wire name can be
     * shared across sources on one method — e.g. {@code @PathParam("id")} and
     * {@code @QueryParam("id")} — and a name-only lookup would silently resolve to whichever param
     * happens to appear first, applying the wrong converter/componentType/annotations to the other.
     *
     * @param meta the method metadata whose params are searched
     * @param paramName the JAX-RS wire name to look up
     * @param source the param source the caller is resolving for; only candidates with a matching
     *     {@link ClientParamMeta#source()} are considered
     * @return the matching {@link ClientParamMeta}, or {@code null} if not found
     */
    @Nullable
    private static ClientParamMeta findParamByName(
            ClientMethodMeta meta, String paramName, ClientParamMeta.ParamSource source) {
        for (ClientParamMeta pm : meta.params()) {
            if (pm.source() == source && paramName.equals(pm.name())) {
                return pm;
            }
            // Search inside bean-field sub-params
            for (ClientParamMeta bf : pm.beanFields()) {
                if (bf.source() == source && paramName.equals(bf.name())) {
                    return bf;
                }
            }
        }
        return null;
    }

    /**
     * Serializes and applies a value to the request builder, shared by the {@code applyPathParam},
     * {@code applyHeaderParam}, and {@code applyCookieParam} dispatcher methods (whose bodies are
     * otherwise identical apart from the final builder call) and by {@code applyQueryParam}'s
     * scalar (non-collection) fall-through.
     *
     * <p>Looks up the {@link ClientParamMeta} for {@code paramName} from {@code meta}, then:
     * <ul>
     *   <li>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     *       string is substituted via {@code setter}.</li>
     *   <li>If {@code value} is {@code null} and no default, nothing is applied.</li>
     *   <li>Otherwise the value is serialized via the {@link ParamConversionResolver} and placed on
     *       the builder via {@code setter}.</li>
     * </ul>
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to look up param conversion context
     * @param paramName the JAX-RS wire name of the parameter
     * @param source the param source this call is resolving for (disambiguates a wire name shared
     *     across sources, e.g. a {@code @PathParam} and {@code @QueryParam} with the same name)
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback, or {@code null}
     * @param setter applies the serialized value to {@code req} for the caller's builder method
     *     (e.g. a lambda wrapping {@link RestRequestBuilder#path}, {@link RestRequestBuilder#header})
     * @return the (potentially same) request builder after applying the param
     */
    private RestRequestBuilder applySimpleParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            ClientParamMeta.ParamSource source,
            @Nullable Object value,
            @Nullable String defaultValue,
            java.util.function.BiFunction<RestRequestBuilder, String, RestRequestBuilder> setter) {
        if (value == null) {
            if (defaultValue != null) {
                req = setter.apply(req, defaultValue);
            }
            return req;
        }
        ClientParamMeta pm = findParamByName(meta, paramName, source);
        String serialized =
                pm != null ? resolver.toString(value, ClientConversionContexts.forParam(pm)) : value.toString();
        return setter.apply(req, serialized);
    }

    /**
     * Serializes and applies a path parameter to the request builder.
     *
     * <p>Looks up the {@link ClientParamMeta} for {@code paramName} from {@code meta}, then:
     * <ul>
     *   <li>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     *       string is substituted.</li>
     *   <li>If {@code value} is {@code null} and no default, nothing is applied (caller handles the
     *       null guard).</li>
     *   <li>Otherwise the value is serialized via the {@link ParamConversionResolver} and placed on
     *       the builder.</li>
     * </ul>
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to look up param conversion context
     * @param paramName the JAX-RS wire name of the path parameter
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback, or {@code null}
     * @return the (potentially same) request builder after applying the param
     */
    @Override
    public RestRequestBuilder applyPathParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue) {
        return applySimpleParam(
                req,
                meta,
                paramName,
                ClientParamMeta.ParamSource.PATH,
                value,
                defaultValue,
                (b, v) -> b.path(paramName, v));
    }

    /**
     * Serializes and applies a query parameter to the request builder.
     *
     * <p>Collection- and array-valued params (when the resolved {@link ClientParamMeta#componentType()}
     * is non-null) are expanded element-by-element via {@link ClientConversionContexts#elementsOf},
     * matching the JDK-proxy path's multi-value query-string behaviour.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to look up param conversion context
     * @param paramName the JAX-RS wire name of the query parameter
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback, or {@code null}
     * @return the (potentially same) request builder after applying the param
     */
    @Override
    public RestRequestBuilder applyQueryParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue) {
        if (value == null) {
            if (defaultValue != null) {
                req = req.query(paramName, defaultValue);
            }
            return req;
        }
        ClientParamMeta pm = findParamByName(meta, paramName, ClientParamMeta.ParamSource.QUERY);
        List<Object> elements =
                pm != null && pm.componentType() != null ? ClientConversionContexts.elementsOf(value) : null;
        if (elements != null) {
            for (Object element : elements) {
                if (element != null) {
                    req = req.query(
                            paramName,
                            resolver.toString(element, ClientConversionContexts.forComponent(pm, pm.componentType())));
                }
            }
            return req;
        }
        return applySimpleParam(
                req,
                meta,
                paramName,
                ClientParamMeta.ParamSource.QUERY,
                value,
                defaultValue,
                (b, v) -> b.query(paramName, v));
    }

    /**
     * Serializes and applies a header parameter to the request builder.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to look up param conversion context
     * @param paramName the HTTP header name
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback, or {@code null}
     * @return the (potentially same) request builder after applying the param
     */
    @Override
    public RestRequestBuilder applyHeaderParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue) {
        return applySimpleParam(
                req,
                meta,
                paramName,
                ClientParamMeta.ParamSource.HEADER,
                value,
                defaultValue,
                (b, v) -> b.header(paramName, v));
    }

    /**
     * Serializes and applies a cookie parameter to the request builder.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to look up param conversion context
     * @param paramName the cookie name
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback, or {@code null}
     * @return the (potentially same) request builder after applying the param
     */
    @Override
    public RestRequestBuilder applyCookieParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue) {
        return applySimpleParam(
                req,
                meta,
                paramName,
                ClientParamMeta.ParamSource.COOKIE,
                value,
                defaultValue,
                (b, v) -> b.cookie(paramName, v));
    }

    /**
     * Serializes and applies the request body to the request builder, sharing the exact
     * media-type branching used by the JDK reflective proxy path via
     * {@link RestClientRequestFactory#serializeBody}.
     *
     * @param req the request builder to populate
     * @param meta the method metadata carrying the {@code @Consumes} media type
     * @param value the raw body value; {@code null} means no body is applied
     * @return the updated request builder
     */
    @Override
    public RestRequestBuilder applyBody(RestRequestBuilder req, ClientMethodMeta meta, @Nullable Object value) {
        if (value == null) {
            return req;
        }
        try {
            Buffer buffer = RestClientRequestFactory.serializeBody(objectMapper, meta.consumesMediaType(), value);
            return req.body(buffer);
        } catch (Exception e) {
            throw new RestClientException(
                    "Failed to serialize request body for "
                            + meta.methodMetadata().name(),
                    e);
        }
    }

    /**
     * Validates and applies a {@code @Url} parameter to the request builder, sharing the exact
     * validation rules used by the JDK reflective proxy path via
     * {@link RestClientRequestFactory#validateUrl}.
     *
     * <p>A {@code null} value is passed through unchanged so {@link #assembleUri} can raise the
     * established {@link RestClientException} message when the {@code @Url} argument was
     * {@code null} — this method never throws on a {@code null} value.
     *
     * @param req the request builder to populate
     * @param meta the method metadata for the invocation
     * @param value the raw {@code @Url} argument value; may be {@code null}
     * @return the updated request builder
     */
    @Override
    public RestRequestBuilder applyUrlParam(RestRequestBuilder req, ClientMethodMeta meta, @Nullable URI value) {
        if (value == null) {
            return req.absoluteUri(null);
        }
        URI validated = RestClientRequestFactory.validateUrl(value);
        return req.absoluteUri(validated.toString());
    }

    // --- Request Execution ---

    /**
     * Builds and dispatches the HTTP request for the given method invocation.
     *
     * @param request the pre-populated request builder
     * @param meta the method metadata
     * @return a {@link Future} that completes with the deserialized response
     */
    private Future<Object> executeRequest(RestRequestBuilder request, ClientMethodMeta meta) {
        try {
            // System context capture — the VERY FIRST thing, on the caller's context, before any
            // app-controlled request construction (param toString, body serialization) and before any
            // interceptor runs, so the snapshot cannot be perturbed by application code. If request
            // construction below throws, no physical attempt fires, so capturing first is harmless.
            List<CapturerCapture> captures = captureContexts();

            // Step 1: Assemble full URI
            String fullUri = assembleUri(request, meta);

            // Step 2: Assemble headers from default headers + builder-level explicit headers
            io.vertx.core.MultiMap headers =
                    io.vertx.core.MultiMap.caseInsensitiveMultiMap().addAll(cachedDefaultHeaders);
            headers.set(ACCEPT, meta.producesMediaType());

            // Apply explicit headers from the builder
            for (Map.Entry<String, String> entry : request.headers()) {
                headers.set(entry.getKey(), entry.getValue());
            }

            // Apply cookies as a single Cookie header
            List<Map.Entry<String, String>> cookies = request.cookies();
            if (!cookies.isEmpty()) {
                StringBuilder cookieHeader = new StringBuilder();
                for (Map.Entry<String, String> cookie : cookies) {
                    if (cookieHeader.length() > 0) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader
                            .append(cookie.getKey())
                            .append("=")
                            .append(RestClientRequestFactory.urlEncode(cookie.getValue()));
                }
                headers.set("Cookie", cookieHeader.toString());
            }

            // Resolve the request body: prefer pre-serialized Buffer (JDK proxy path);
            // fall back to raw object serialization (generated static proxy path).
            Buffer bodyBuffer = request.body();
            if (bodyBuffer == null && request.bodyObject() != null) {
                try {
                    bodyBuffer = Buffer.buffer(objectMapper.writeValueAsBytes(request.bodyObject()));
                } catch (Exception e) {
                    throw new RestClientException(
                            "Failed to serialize request body for "
                                    + meta.methodMetadata().name(),
                            e);
                }
            }
            if (bodyBuffer != null) {
                headers.set(CONTENT_TYPE, meta.consumesMediaType());
            }

            // Step 3: Create request context
            RestClientRequestContext reqCtx = new RestClientRequestContext(
                    meta.httpMethod(),
                    fullUri,
                    headers,
                    bodyBuffer,
                    clientName,
                    meta.methodMetadata().name(),
                    Map.of());

            // Step 4: Sync onRequest observers
            interceptorChain.fireOnRequest(reqCtx);

            // Steps 5–14: async pipeline
            String callId = UUID.randomUUID().toString();
            AtomicInteger attemptOrdinal = new AtomicInteger(0);
            AtomicReference<RestClientRequestContext> dispatchedReqCtx = new AtomicReference<>(reqCtx);
            AtomicReference<RestClientResponseContext> capturedResCtx = new AtomicReference<>();

            return interceptorChain
                    .runBeforeInterceptors(reqCtx)
                    .compose(finalCtx -> {
                        dispatchedReqCtx.set(finalCtx);
                        return sendWithCircuitBreaker(
                                finalCtx,
                                meta,
                                capturedResCtx,
                                false,
                                dispatchedReqCtx,
                                callId,
                                attemptOrdinal,
                                captures);
                    })
                    .compose(response ->
                            handleSuccessResponse(response, meta, dispatchedReqCtx.get(), capturedResCtx.get()))
                    .recover(err -> handleFailure(err, dispatchedReqCtx.get(), capturedResCtx.get()));

        } catch (Exception e) {
            return Future.failedFuture(new RestClientException(
                    "Failed to build request for " + meta.methodMetadata().name(), e));
        }
    }

    /**
     * Assembles the full URI string from the request builder and method metadata.
     *
     * <p>If the builder contains a pre-resolved absolute URI (set by the JDK proxy or the
     * generated static proxy for {@code @Url} methods), any query parameters accumulated in the
     * builder are merged into that URI's existing query string. Otherwise, the URI is assembled
     * from the base URL, path template, path parameters from the builder, and query parameters.
     *
     * <p>When the {@code @Url} argument was {@code null}, the builder's {@link
     * RestRequestBuilder#absoluteUri()} is also {@code null}; this method throws
     * {@link RestClientException} in that case to match the reflective path's behaviour.
     *
     * @param request the populated request builder
     * @param meta the method metadata
     * @return the full URI string
     * @throws RestClientException if the method has a {@code @Url} parameter but its value is
     *     {@code null}
     */
    private String assembleUri(RestRequestBuilder request, ClientMethodMeta meta) {
        // @Url path: pre-resolved absolute URI; query params from builder are merged
        if (meta.hasUrlParam()) {
            String base = request.absoluteUri();
            if (base == null) {
                throw new RestClientException("@Url argument for "
                        + meta.methodMetadata().name() + " was null; an absolute http/https URI is required");
            }
            return mergeQueryParams(base, request.queryParams());
        }

        // Normal path: base URL + path template with path param substitution + query string
        String path = meta.pathTemplate();
        for (Map.Entry<String, Object> entry : request.pathParams()) {
            String encoded = RestClientRequestFactory.urlEncode(entry.getValue().toString());
            path = path.replace("{" + entry.getKey() + "}", encoded);
        }

        StringBuilder queryBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : request.queryParams()) {
            if (queryBuilder.length() == 0) {
                queryBuilder.append('?');
            } else {
                queryBuilder.append('&');
            }
            queryBuilder
                    .append(RestClientRequestFactory.urlEncode(entry.getKey()))
                    .append('=')
                    .append(RestClientRequestFactory.urlEncode(entry.getValue().toString()));
        }

        return baseUrl + path + queryBuilder;
    }

    /**
     * Merges accumulated query parameters into an existing absolute URI string.
     *
     * <p>The URI is decomposed via {@link java.net.URI} to avoid string-level heuristics that break
     * on edge cases:
     * <ul>
     *   <li>A trailing bare {@code ?} (empty raw query) is treated as no existing query — the
     *       separator is not doubled into {@code ?&}.</li>
     *   <li>An authority-only URI with no path (e.g. {@code https://host}) has {@code /} inserted
     *       before the {@code ?} to satisfy strict HTTP servers.</li>
     * </ul>
     *
     * <p>Mirrors the decomposition strategy in
     * {@link RestClientRequestFactory#buildUrlWithQueryParams}. Both names and values are
     * percent-encoded via {@link RestClientRequestFactory#urlEncode}.
     *
     * @param absoluteUri the pre-resolved absolute URI (from {@code @Url} argument)
     * @param queryParams the accumulated query parameters; may be empty
     * @return the URI with all query parameters appended
     */
    private static String mergeQueryParams(String absoluteUri, List<Map.Entry<String, Object>> queryParams) {
        // Decompose via URI to avoid string-level ambiguity (trailing ?, authority-only paths)
        URI uri = URI.create(absoluteUri);
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getRawAuthority());

        String rawPath = uri.getRawPath();
        boolean hasPath = rawPath != null && !rawPath.isEmpty();
        if (hasPath) {
            sb.append(rawPath);
        }

        // Collect query parts: existing (already encoded) then new params
        List<String> queryParts = new ArrayList<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            queryParts.add(rawQuery);
        }
        for (Map.Entry<String, Object> entry : queryParams) {
            queryParts.add(RestClientRequestFactory.urlEncode(entry.getKey())
                    + '='
                    + RestClientRequestFactory.urlEncode(entry.getValue().toString()));
        }

        if (!queryParts.isEmpty()) {
            // Insert '/' before '?' for authority-only URIs (https://host?q=1 → https://host/?q=1)
            if (!hasPath) {
                sb.append('/');
            }
            sb.append('?').append(String.join("&", queryParts));
        }

        return sb.toString();
    }

    /**
     * Sends the request applying the expectation, capturing response context, and running
     * after-response interceptors. The circuit breaker (if configured) wraps the send.
     *
     * <p>Fires {@link RestClientInterceptor#onAttemptCompleted} once per physical HTTP attempt,
     * before the retry/recovery decision. The {@code callId} and {@code attemptOrdinal} are shared
     * across retries and the one recovery re-dispatch so all attempts of a single logical call
     * carry the same {@code callId} with monotonically increasing ordinals.
     *
     * <p>On failure, if {@code recoveryAttempted} is {@code false}, the
     * {@link RestClientInterceptor#recoverRequest} chain is tried exactly once.
     *
     * @param reqCtx            the (possibly interceptor-mutated) request context
     * @param meta              the method metadata
     * @param capturedResCtx    holder that receives the response context after HTTP response arrives
     * @param recoveryAttempted {@code true} if a recovery attempt has already been made
     * @param dispatchedReqCtx  holder tracking the latest dispatched request context
     * @param callId            id unique to this logical client call, shared across all attempts
     * @param attemptOrdinal    1-based attempt counter, incremented on every physical send
     * @return a {@link Future} with the raw HTTP response after expectations and interceptors
     */
    private Future<HttpResponse<Buffer>> sendWithCircuitBreaker(
            RestClientRequestContext reqCtx,
            ClientMethodMeta meta,
            AtomicReference<RestClientResponseContext> capturedResCtx,
            boolean recoveryAttempted,
            AtomicReference<RestClientRequestContext> dispatchedReqCtx,
            String callId,
            AtomicInteger attemptOrdinal,
            List<CapturerCapture> captures) {
        String uri = reqCtx.requestUri();
        long effectiveTimeout = resilienceResolver.resolveTimeout(meta);

        HttpRequest<Buffer> httpRequest = buildHttpRequest(uri, meta.httpMethod());
        httpRequest.timeout(effectiveTimeout);

        // Apply interceptor-mutated headers
        reqCtx.headers().forEach(e -> httpRequest.putHeader(e.getKey(), e.getValue()));

        Buffer bodyToSend = reqCtx.body();

        // Resolve effective expectation (method-level wins over builder default)
        io.vertx.core.Expectation<io.vertx.core.http.HttpResponseHead> expectation =
                resilienceResolver.resolveExpectation(meta);

        // Send inside circuit breaker if configured; fire onAttemptCompleted per physical attempt
        io.vertx.circuitbreaker.CircuitBreaker cb = resilienceResolver.resolveCircuitBreaker(meta);

        Future<HttpResponse<Buffer>> sendFuture;
        if (cb != null) {
            sendFuture = cb.execute(promise -> {
                int ordinal = attemptOrdinal.incrementAndGet();
                long startNanos = System.nanoTime();
                Future<HttpResponse<Buffer>> f =
                        bodyToSend != null ? httpRequest.sendBuffer(bodyToSend) : httpRequest.send();
                f.onComplete(ar -> {
                    fireAttemptCompleted(reqCtx, ar, callId, ordinal, startNanos, meta, captures);
                    promise.handle(ar);
                });
            });
        } else {
            int ordinal = attemptOrdinal.incrementAndGet();
            long startNanos = System.nanoTime();
            Future<HttpResponse<Buffer>> raw =
                    bodyToSend != null ? httpRequest.sendBuffer(bodyToSend) : httpRequest.send();
            sendFuture =
                    raw.andThen(ar -> fireAttemptCompleted(reqCtx, ar, callId, ordinal, startNanos, meta, captures));
        }

        // Capture response context immediately upon receiving HTTP response, before expecting()
        sendFuture = sendFuture.map(response -> {
            RestClientResponseContext resCtx = RestClientResponseContext.from(response);
            capturedResCtx.set(resCtx);
            return response;
        });

        // Apply expectation if configured
        if (expectation != null) {
            if (meta.returnsOptional()) {
                sendFuture = sendFuture.compose(response -> {
                    if (response.statusCode() == 404) {
                        return Future.succeededFuture(response);
                    }
                    return Future.succeededFuture(response).expecting(expectation);
                });
            } else {
                sendFuture = sendFuture.expecting(expectation);
            }
        }

        // onResponse observers and afterResponse handlers
        Future<HttpResponse<Buffer>> resultFuture = sendFuture.compose(response -> {
            RestClientResponseContext resCtx = capturedResCtx.get();
            interceptorChain.fireOnResponse(reqCtx, resCtx);
            return interceptorChain.runAfterInterceptors(reqCtx, resCtx).map(v -> response);
        });

        // Recovery: try recoverRequest interceptors exactly once on failure
        if (!recoveryAttempted) {
            resultFuture = resultFuture.recover(err -> {
                RestClientResponseContext capturedRes = capturedResCtx.get();
                return interceptorChain
                        .runRecoverInterceptors(reqCtx, capturedRes, err)
                        .compose(newCtx -> {
                            // Recovery hands back a caller-authored newCtx; user attributes follow newCtx
                            // (recover-interceptor owns them). System context capture lives in the
                            // dispatcher-held captures (RestClientContextCapturer), not in the request context,
                            // so it survives recovery untouched.
                            dispatchedReqCtx.set(newCtx);
                            capturedResCtx.set(null);
                            return sendWithCircuitBreaker(
                                    newCtx,
                                    meta,
                                    capturedResCtx,
                                    true,
                                    dispatchedReqCtx,
                                    callId,
                                    attemptOrdinal,
                                    captures);
                        })
                        .recover(recoveryErr -> {
                            // Recovery was accepted, so the recovery re-dispatch is the terminal path: propagate
                            // ITS failure (paired with the recovered request/response context already set above),
                            // not the original pre-recovery error.
                            log.debug(
                                    "Recovery re-dispatch failed for {}.{}(), propagating the recovery failure",
                                    clientName,
                                    meta.methodMetadata().name(),
                                    recoveryErr);
                            return Future.failedFuture(recoveryErr);
                        });
            });
        }

        return resultFuture;
    }

    /**
     * Fires {@link RestClientInterceptorChain#fireOnAttemptCompleted} from an async result, building
     * the {@link RestClientAttemptCompletion} value object. Duration is monotonic
     * ({@link System#nanoTime()}); {@code completedAt} is wall-clock for {@code occurredAt}.
     *
     * @param reqCtx     the request context for this attempt
     * @param ar         the async result of the physical send
     * @param callId     id unique to this logical client call
     * @param ordinal    the 1-based attempt ordinal
     * @param startNanos the {@link System#nanoTime()} reading when the send was initiated
     * @param meta       the method metadata (for the safe-by-type path template); its
     *                   {@link ClientMethodMeta#methodMetadata()} is also passed to every system
     *                   capturer's four-arg {@code onAttemptCompleted} overload
     * @param captures   the per-call system context captures to observe this attempt
     */
    private void fireAttemptCompleted(
            RestClientRequestContext reqCtx,
            AsyncResult<HttpResponse<Buffer>> ar,
            String callId,
            int ordinal,
            long startNanos,
            ClientMethodMeta meta,
            List<CapturerCapture> captures) {
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        Instant completedAt = Instant.now();
        RestClientResponseContext res = ar.succeeded() ? RestClientResponseContext.from(ar.result()) : null;
        Throwable err = ar.failed() ? ar.cause() : null;
        RestClientAttemptTarget target = buildTarget(reqCtx, meta);
        RestClientAttemptCompletion completion =
                new RestClientAttemptCompletion(res, err, callId, ordinal, durationMs, completedAt, target);
        // Application-facing per-attempt observers (no system capture).
        interceptorChain.fireOnAttemptCompleted(reqCtx, completion);
        // System capturers — each observes the attempt with its own captured value (never exposed to apps).
        for (CapturerCapture c : captures) {
            try {
                c.fire(reqCtx, completion, meta.methodMetadata());
            } catch (Exception e) {
                log.debug("RestClientContextCapturer.onAttemptCompleted threw (ignored)", e);
            }
        }
    }

    /**
     * Handles a successful HTTP response: checks for 404+Optional, deserializes, and validates.
     *
     * @param response the received HTTP response
     * @param meta the method metadata
     * @param reqCtx the request context
     * @param resCtx the response context already captured; may be {@code null} defensively
     * @return a {@link Future} with the deserialized result
     */
    private Future<Object> handleSuccessResponse(
            HttpResponse<Buffer> response,
            ClientMethodMeta meta,
            RestClientRequestContext reqCtx,
            @Nullable RestClientResponseContext resCtx) {
        int status = response.statusCode();
        RestClientResponseContext effectiveResCtx = resCtx != null ? resCtx : RestClientResponseContext.from(response);

        // Handle Optional<T> with 404 → Optional.empty()
        if (meta.returnsOptional() && status == 404) {
            return Future.succeededFuture(Optional.empty());
        }

        // Non-2xx without expectation become RestClientResponseException
        if (status < 200 || status >= 300) {
            return Future.failedFuture(new RestClientResponseException(reqCtx, effectiveResCtx));
        }

        // Deserialize and validate
        try {
            Object result = deserializeSuccess(response, meta);
            if (beanValidator != null && result != null && !meta.returnsVoid() && !meta.returnsRawResponse()) {
                Object toValidate =
                        meta.returnsOptional() && result instanceof Optional<?> opt ? opt.orElse(null) : result;
                if (toValidate != null) {
                    beanValidator.validate(toValidate);
                }
            }
            return Future.succeededFuture(result);
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Runs the error pipeline: sync {@code onError} observers, then async {@code transformError}
     * handlers, then {@link RestClientExceptionMapper}.
     *
     * @param error the original failure
     * @param reqCtx the request context
     * @param resCtx the response context if an HTTP response was received; {@code null} otherwise
     * @return always a failed {@link Future} carrying the final mapped error
     */
    private Future<Object> handleFailure(
            Throwable error, RestClientRequestContext reqCtx, @Nullable RestClientResponseContext resCtx) {
        interceptorChain.fireOnError(reqCtx, resCtx, error);
        return interceptorChain
                .runTransformError(reqCtx, resCtx, error)
                .compose(mapped -> Future.failedFuture(exceptionMapper.translate(mapped)));
    }

    /**
     * Deserializes a successful response body according to the method's return type.
     *
     * @param response the successful HTTP response
     * @param meta the method metadata
     * @return the deserialized value, or {@code null} for void methods
     */
    @Nullable
    private Object deserializeSuccess(HttpResponse<Buffer> response, ClientMethodMeta meta) {
        if (meta.returnsVoid()) {
            return null;
        }
        if (meta.returnsRawResponse()) {
            return new HttpClientResponse(response);
        }
        Buffer body = response.body();
        if (body == null || body.length() == 0) {
            return meta.returnsOptional() ? Optional.empty() : null;
        }
        try {
            Object value = objectMapper.readValue(
                    body.getBytes(), objectMapper.getTypeFactory().constructType(meta.responseType()));
            return meta.returnsOptional() ? Optional.ofNullable(value) : value;
        } catch (Exception e) {
            throw new RestClientException(
                    "Failed to deserialize response for "
                            + meta.methodMetadata().name(),
                    e);
        }
    }

    /**
     * Builds a Vert.x {@link HttpRequest} from the given absolute URI and HTTP method string.
     *
     * @param uri the absolute URI
     * @param httpMethod the HTTP verb string
     * @return the configured HttpRequest
     * @throws RestClientException if the HTTP method is not supported
     */
    private HttpRequest<Buffer> buildHttpRequest(String uri, String httpMethod) {
        return switch (httpMethod) {
            case "GET" -> webClient.getAbs(uri);
            case "POST" -> webClient.postAbs(uri);
            case "PUT" -> webClient.putAbs(uri);
            case "DELETE" -> webClient.deleteAbs(uri);
            case "PATCH" -> webClient.patchAbs(uri);
            case "HEAD" -> webClient.headAbs(uri);
            default -> throw new RestClientException("Unsupported HTTP method: " + httpMethod);
        };
    }
}
