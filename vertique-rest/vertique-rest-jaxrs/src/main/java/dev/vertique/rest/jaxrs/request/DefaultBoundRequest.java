// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.convert.ConversionContext;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.ProfileBodyMaterialization;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link BoundRequest} implementation that binds the values of a Vert.x
 * {@link RoutingContext} request against an operation's declared parameter model.
 *
 * <p>Binding rules (FR-024):
 *
 * <ul>
 *   <li><b>Multiplicity is type-driven</b> by the matching {@link ParamDescriptor}: a parameter
 *       whose declared type is a collection ({@code componentType != null}) binds <em>all</em>
 *       values as a {@link JsonArray}; a scalar parameter binds only the <em>first</em> value. The rule
 *       applies uniformly to query parameters, headers, and cookies; because a cookie is single-valued,
 *       a collection-declared {@code @CookieParam} binds a single-entry {@link JsonArray}.
 *   <li><b>Declared scalar parameters are coerced</b> to their declared type via the
 *       {@link ParamConversionResolver} before being wrapped, because {@link RequestValue} does not
 *       parse strings.
 *   <li><b>Undeclared keys</b> (no matching descriptor) bind as their raw first-value
 *       {@link String}.
 *   <li><b>Headers and cookies are case-insensitive</b>: their maps are keyed by lower-cased name,
 *       so {@code get("content-type")} finds a {@code Content-Type} header, and the declared-parameter
 *       match that decides multiplicity is equally case-insensitive for those two locations (see
 *       {@link #findDescriptor}). Path and query names stay case-sensitive.
 *   <li><b>The body is bound in its actual wire shape, content-type-aware</b>: a JSON content type
 *       yields a {@link JsonObject}/{@link JsonArray}/scalar; a {@code text/*} content type yields a
 *       {@link String}; otherwise the raw {@code Buffer} (see {@link #bindBody}).
 *   <li>{@link #body()} never returns {@code null}: an absent body yields
 *       {@code RequestValue.of(null)}.
 * </ul>
 */
public final class DefaultBoundRequest implements BoundRequest {

    /**
     * Route-scoped cache of the scalar {@link ConversionContext} per declared {@link ParamDescriptor}.
     * {@code DefaultBoundRequest} is allocated per request, so caching on the instance saves nothing;
     * this static, JVM-wide cache (mirroring {@code ParameterExtractor.BEAN_PARAM_CACHE}) is keyed by
     * the {@code ParamDescriptor} instances of an operation — which are built once at route
     * registration and reused across requests — so the per-descriptor {@code ConversionContext} (and
     * its {@code Annotation[]} closure) is allocated at most once instead of on every {@link #wrapScalar}
     * call. The {@code Supplier<Annotation[]>} closes over a per-descriptor array and is safe to share
     * across requests.
     */
    private static final Map<ParamDescriptor, ConversionContext> SCALAR_CONTEXT_CACHE = new ConcurrentHashMap<>();

    private final HttpServerRequest raw;
    private final Map<String, RequestValue> pathParameters;
    private final Map<String, RequestValue> query;
    private final Map<String, RequestValue> headers;
    private final Map<String, RequestValue> cookies;
    private final RequestValue body;

    /**
     * Binds the request carried by {@code ctx} against the declared parameter model of {@code op},
     * coercing declared scalars through the framework built-ins-only resolver
     * ({@link ConversionContexts#defaultResolver()}).
     *
     * <p>This overload is visible for testing: it preserves the legacy two-arg construction used by test
     * fixtures and any caller that does not have a Dagger-managed resolver. Production dispatch uses the
     * three-arg overload so application converter bindings and JAX-RS providers participate in binding.
     * (The project has no {@code @VisibleForTesting} annotation; this note records the intent.)
     *
     * @param ctx the current routing context; must not be {@code null}
     * @param op  the operation descriptor whose declared parameters drive multiplicity and coercion;
     *            must not be {@code null}
     */
    public DefaultBoundRequest(RoutingContext ctx, JaxRsOperationDescriptor op) {
        this(ctx, op, ConversionContexts.defaultResolver());
    }

    /**
     * Binds the request carried by {@code ctx} against the declared parameter model of {@code op},
     * coercing declared scalars through the supplied {@link ParamConversionResolver}.
     *
     * @param ctx      the current routing context; must not be {@code null}
     * @param op       the operation descriptor whose declared parameters drive multiplicity and
     *                 coercion; must not be {@code null}
     * @param resolver the conversion resolver used to coerce declared scalar parameters to their
     *                 declared types; must not be {@code null}
     */
    public DefaultBoundRequest(RoutingContext ctx, JaxRsOperationDescriptor op, ParamConversionResolver resolver) {
        this.raw = ctx.request();
        List<ParamDescriptor> params = op.parameters();

        this.pathParameters = bindPath(ctx.pathParams(), params, resolver);
        this.query = bindMultiMap(ctx.queryParams(), params, ParamLocation.QUERY, false, resolver);
        this.headers = bindMultiMap(raw.headers(), params, ParamLocation.HEADER, true, resolver);
        this.cookies = bindCookies(raw.cookies(), params, resolver);

        // FR-JSON-024/024A: a non-vertx JSON profile resolved for this method (slice 2.1) is stashed
        // on the routing context under KEY_RESOLVED_BODY_MAPPER. When present, that profile mapper
        // owns the FIRST PARSE of a JSON body (applying its strict parser features); when absent
        // (the vertx default), the body path below is byte-for-byte identical to today.
        ObjectMapper profileMapper = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER);
        this.body = bindBody(ctx.body(), raw.getHeader("Content-Type"), profileMapper);
    }

    // --- Binding helpers ---

    /**
     * Binds path parameters from the {@code RoutingContext.pathParams()} map. Path values are always
     * single-valued; a declared scalar descriptor coerces the value to its type.
     *
     * @param pathParams the raw path parameter map (name to single value)
     * @param params     the operation's declared parameters
     * @param resolver   the conversion resolver used to coerce declared scalars
     * @return an immutable map of bound path parameter values
     */
    private static Map<String, RequestValue> bindPath(
            Map<String, String> pathParams, List<ParamDescriptor> params, ParamConversionResolver resolver) {
        Map<String, RequestValue> result = new LinkedHashMap<>();
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                ParamDescriptor descriptor = findDescriptor(params, entry.getKey(), ParamLocation.PATH);
                result.put(entry.getKey(), wrapScalar(entry.getValue(), descriptor, resolver));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Binds a multi-valued source ({@link MultiMap}) — query parameters or headers — applying the
     * type-driven multiplicity rule per declared parameter. When {@code caseInsensitive} is
     * {@code true}, keys are normalized to lower-case so case-insensitive lookup works on the
     * returned map.
     *
     * @param source          the multi-map source (query or headers)
     * @param params          the operation's declared parameters
     * @param location        the parameter source the keys belong to
     * @param caseInsensitive whether to lower-case keys (headers) for case-insensitive lookup
     * @param resolver        the conversion resolver used to coerce declared scalars
     * @return an immutable map of bound values
     */
    private static Map<String, RequestValue> bindMultiMap(
            MultiMap source,
            List<ParamDescriptor> params,
            ParamLocation location,
            boolean caseInsensitive,
            ParamConversionResolver resolver) {
        Map<String, RequestValue> result = new LinkedHashMap<>();
        if (source != null) {
            for (String name : source.names()) {
                List<String> values = source.getAll(name);
                ParamDescriptor descriptor = findDescriptor(params, name, location);
                String key = caseInsensitive ? name.toLowerCase(Locale.ROOT) : name;
                result.put(key, wrapValues(values, descriptor, resolver));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Binds request cookies, keyed case-insensitively by cookie name, applying the same type-driven
     * multiplicity rule as query parameters and headers via {@link #wrapValues}.
     *
     * <p>A cookie is <em>single-valued</em> per name, so its value is presented to
     * {@link #wrapValues} as a one-element list:
     *
     * <ul>
     *   <li>a <b>collection-declared</b> {@code @CookieParam} ({@code componentType != null}) binds as a
     *       single-entry {@link JsonArray}, exactly as QUERY and HEADER already do, so the downstream
     *       collection state machine materializes a single-entry collection instead of receiving a bare
     *       {@link String} it has no converter for (which failed the request);
     *   <li>a <b>scalar</b> descriptor takes {@link #wrapValues}' first-value fallback to
     *       {@link #wrapScalar}, so scalar cookie binding is unchanged.
     * </ul>
     *
     * <p>A {@code null} cookie value is bound as {@code RequestValue.of(null)} directly rather than
     * routed through {@link #wrapValues}, which keeps the previous {@link #wrapScalar} outcome for the
     * scalar shape byte-for-byte and lets a collection-declared parameter apply its absence contract
     * (empty collection, or the single-entry {@code @DefaultValue}) instead of binding a single-entry
     * array holding {@code null}.
     *
     * @param cookieSet the request cookies
     * @param params    the operation's declared parameters
     * @param resolver  the conversion resolver used to coerce declared scalars
     * @return an immutable map of bound cookie values
     */
    private static Map<String, RequestValue> bindCookies(
            Set<Cookie> cookieSet, List<ParamDescriptor> params, ParamConversionResolver resolver) {
        Map<String, RequestValue> result = new LinkedHashMap<>();
        if (cookieSet != null) {
            for (Cookie cookie : cookieSet) {
                ParamDescriptor descriptor = findDescriptor(params, cookie.getName(), ParamLocation.COOKIE);
                String key = cookie.getName().toLowerCase(Locale.ROOT);
                String value = cookie.getValue();
                result.put(
                        key, value == null ? RequestValue.of(null) : wrapValues(List.of(value), descriptor, resolver));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Binds the request body in its <em>actual wire shape</em>, content-type-aware, so the decoders
     * and validation gate downstream receive the {@link JsonObject}/{@link JsonArray}/{@link String}/
     * scalar/{@link Buffer} value they expect rather than a raw buffer (FR-024). The body buffer is
     * read once off the already-buffered {@code ctx.body()}; no stream is re-read.
     *
     * <p>Binding rules:
     *
     * <ul>
     *   <li><b>No body / empty buffer</b> &rarr; {@code RequestValue.of(null)} (preserves the
     *       never-null body contract via a {@code null}-wrapping value).
     *   <li><b>JSON content type</b> ({@code application/json} or {@code application/*+json},
     *       case-insensitive, parameters stripped) &rarr; the parsed JSON value: a
     *       {@link JsonObject}, a {@link JsonArray}, or a scalar ({@link String}, {@link Number},
     *       {@link Boolean}, or {@code null}) via {@link Json#decodeValue(Buffer)}. Malformed JSON
     *       falls back to the raw {@link Buffer} so the gate/decoder surfaces a clean failure rather
     *       than the binder throwing.
     *   <li><b>{@code text/*} content type</b> &rarr; the UTF-8 decoded {@link String}.
     *   <li><b>Any other explicit content type</b> (e.g. {@code application/octet-stream}) &rarr; the
     *       raw {@link Buffer}. The bytes are <em>not</em> probed for a JSON shape: the client
     *       declared the body as binary, so octet-stream bytes that happen to parse as JSON (e.g.
     *       {@code {}}) must still bind as a {@link Buffer}, not a {@link JsonObject} — otherwise a
     *       {@code byte[]}/{@link Buffer} body parameter would silently bind to {@code null}.
     *   <li><b>No usable content type</b> (header missing or blank) &rarr; a JSON object or array is
     *       preferred when the body parses as one (a missing content type must not crash or lose a
     *       JSON shape), falling back to the raw {@link Buffer}.
     * </ul>
     *
     * <p>Type-conversion of the bound wire shape to the method-argument type remains the decoders'
     * job; this method only produces the correct wire shape.
     *
     * <p>When a non-{@code vertx} JSON profile applies to the dispatched method (FR-JSON-024/024A),
     * {@code profileMapper} is non-{@code null} and performs the first parse of a JSON-content-type
     * body (see {@link #bindJsonBody}); a body it rejects surfaces as an HTTP 400. When
     * {@code profileMapper} is {@code null} (the {@code vertx} default) every branch below is
     * byte-for-byte identical to the pre-profile behavior.
     *
     * @param requestBody the routing-context request body, possibly {@code null}
     * @param contentType the request {@code Content-Type} header value, possibly {@code null}
     * @param profileMapper the resolved non-{@code vertx} profile mapper for a JSON first parse, or
     *     {@code null} to use today's default Vert.x JSON path
     * @return the bound body value, never {@code null}
     */
    private static RequestValue bindBody(
            RequestBody requestBody, String contentType, @Nullable ObjectMapper profileMapper) {
        if (requestBody == null) {
            return RequestValue.of(null);
        }
        Buffer buffer = requestBody.buffer();
        if (buffer == null || buffer.length() == 0) {
            return RequestValue.of(null);
        }

        String mediaType = baseMediaType(contentType);
        if (isJsonMediaType(mediaType)) {
            return bindJsonBody(requestBody, buffer, profileMapper);
        }
        if (mediaType != null && mediaType.startsWith("text/")) {
            return RequestValue.of(buffer.toString(StandardCharsets.UTF_8));
        }

        // An EXPLICIT non-JSON, non-text Content-Type (e.g. application/octet-stream) binds the raw
        // Buffer directly: the client declared the body as binary, so a JSON-shape probe must not
        // reinterpret octet-stream bytes that happen to parse as JSON (e.g. "{}") as a JsonObject —
        // that would leave a byte[]/Buffer body param bound to null (BinaryRequestBodyDecoder only
        // adapts Buffer). The JSON-shape fallback below is reserved for the MISSING-content-type case.
        if (mediaType != null) {
            return RequestValue.of(buffer);
        }

        // No usable content type (null/blank). When a non-vertx profile applies (FR-JSON-024A), a
        // JSON-shaped body ('{'/'[' as the first non-whitespace, BOM-skipped, byte) must still be
        // FIRST-PARSED by the profile mapper — JsonRequestBodyDecoder.canDecode returns true for a null
        // content type, so this body would otherwise dispatch but with the strict first parse skipped.
        // Other leading bytes (a scalar / non-JSON body) fall through to the unchanged Vert.x shape
        // probe below.
        if (profileMapper != null) {
            char shape = firstNonWhitespace(buffer.getBytes());
            if (shape == '{' || shape == '[') {
                return bindProfiledJsonBody(buffer, profileMapper);
            }
        }

        // Prefer a JSON object/array when the body parses as one (a missing content type must not crash
        // or lose a JSON shape), else the raw buffer.
        JsonObject json = asJsonObjectOrNull(requestBody);
        if (json != null) {
            return RequestValue.of(json);
        }
        JsonArray array = asJsonArrayOrNull(requestBody);
        if (array != null) {
            return RequestValue.of(array);
        }
        return RequestValue.of(buffer);
    }

    /**
     * Binds a JSON-content-type body to its parsed wire shape.
     *
     * <p>When {@code profileMapper} is {@code null} (the {@code vertx} default) this is the
     * unchanged Vert.x path: prefer a {@link JsonObject} then a {@link JsonArray}, then any scalar
     * JSON value via {@link Json#decodeValue(Buffer)}; malformed JSON falls back to the raw
     * {@link Buffer} so the failure is surfaced downstream, not in the binder.
     *
     * <p>When {@code profileMapper} is non-{@code null} (a non-{@code vertx} profile, FR-JSON-024A)
     * the profile mapper performs the FIRST PARSE of the raw {@code buffer} for ALL body shapes,
     * applying the profile's strict parser features (e.g. {@code STRICT_DUPLICATE_DETECTION},
     * {@code FAIL_ON_TRAILING_TOKENS}) uniformly: the body shape is detected from the first
     * non-whitespace byte — {@code '{'} parses to a {@link JsonObject}, {@code '['} to a
     * {@link JsonArray}; any other leading byte is a scalar parsed by the profile mapper as
     * {@link Object} (not by the {@code vertx} scalar path). A parse the profile mapper rejects is
     * translated to a {@link dev.vertique.core.exception.ValidationException} (HTTP 400) rather than
     * binding the raw buffer.
     *
     * @param requestBody   the routing-context request body
     * @param buffer        the already-buffered request body bytes (non-empty)
     * @param profileMapper the resolved non-{@code vertx} profile mapper for the first parse, or
     *     {@code null} to use today's default Vert.x JSON path
     * @return the bound body value, never {@code null}
     * @throws dev.vertique.core.exception.ValidationException when {@code profileMapper} rejects the
     *     body (HTTP 400)
     */
    private static RequestValue bindJsonBody(
            RequestBody requestBody, Buffer buffer, @Nullable ObjectMapper profileMapper) {
        if (profileMapper != null) {
            return bindProfiledJsonBody(buffer, profileMapper);
        }
        JsonObject json = asJsonObjectOrNull(requestBody);
        if (json != null) {
            return RequestValue.of(json);
        }
        JsonArray array = asJsonArrayOrNull(requestBody);
        if (array != null) {
            return RequestValue.of(array);
        }
        try {
            // Scalar JSON body (e.g. 42, "x", true, null) — decodeValue yields a String/Number/Boolean/null.
            return RequestValue.of(Json.decodeValue(buffer));
        } catch (RuntimeException malformedJson) {
            // Malformed JSON: bind the raw buffer so the validation gate / decoder produces the 4xx.
            return RequestValue.of(buffer);
        }
    }

    /**
     * Performs the profile-aware first parse of a JSON-content-type body (FR-JSON-024A): the
     * resolved profile mapper deserializes the raw {@code buffer} to a {@link JsonObject},
     * {@link JsonArray}, or scalar {@link Object} (the profile mapper carries Vert.x JSON support
     * via the startup probe), so the profile's strict parser features run on the first parse for
     * ALL body shapes — object, array, and scalar. The body shape is detected from the first
     * non-whitespace byte ({@code '{'} &rarr; object, {@code '['} &rarr; array); any other leading
     * byte is a scalar parsed by the profile mapper as {@link Object}, so the profile's strict
     * features (e.g. {@link com.fasterxml.jackson.databind.DeserializationFeature#FAIL_ON_TRAILING_TOKENS})
     * apply uniformly across all body shapes. A parse failure is translated to a
     * {@link dev.vertique.core.exception.ValidationException} (HTTP 400) with a secret-free message.
     *
     * @param buffer        the already-buffered request body bytes (non-empty)
     * @param profileMapper the resolved non-{@code vertx} profile mapper, never {@code null}
     * @return the bound body value, never {@code null}
     * @throws dev.vertique.core.exception.ValidationException when the profile mapper rejects the
     *     body (HTTP 400)
     */
    private static RequestValue bindProfiledJsonBody(Buffer buffer, ObjectMapper profileMapper) {
        byte[] bytes = buffer.getBytes();
        char shape = firstNonWhitespace(bytes);
        try {
            if (shape == '{') {
                return RequestValue.of(profileMapper.readValue(bytes, JsonObject.class));
            }
            if (shape == '[') {
                return RequestValue.of(profileMapper.readValue(bytes, JsonArray.class));
            }
            // Scalar JSON body (rare under a profile, which targets object/array DTOs): parse through
            // the profile mapper so its strict parser features (e.g. FAIL_ON_TRAILING_TOKENS) apply
            // uniformly to scalars as well as objects and arrays. The vertx-default scalar path
            // (Json.decodeValue) is NOT used here — that path lives in the non-profiled bindJsonBody
            // and is byte-for-byte unchanged.
            return RequestValue.of(profileMapper.readValue(bytes, Object.class));
        } catch (RuntimeException | java.io.IOException rejected) {
            // The profile mapper rejected the body (duplicate key, trailing token, malformed). Surface
            // a 400 via the standard error pipeline (ValidationException -> DefaultExceptionMapper).
            throw ProfileBodyMaterialization.rejection(rejected);
        }
    }

    /**
     * Returns the first significant byte of {@code bytes} as a {@code char}, or {@code '\0'} when the
     * buffer is empty or contains only a leading UTF-8 BOM and whitespace. Used (only on profile body
     * paths) to detect the JSON body shape (object vs array vs scalar) before invoking the profile
     * mapper.
     *
     * <p>A leading UTF-8 byte-order mark ({@code EF BB BF}) is skipped first, then the JSON
     * insignificant-whitespace set (space, tab, CR, LF) per RFC 8259, so a BOM-prefixed object/array
     * body is correctly classified as {@code '{'}/{@code '['} rather than misread as a scalar. The
     * profile mapper carries Vert.x JSON support and tolerates a leading BOM, so routing such a body to
     * the profile parse is safe; the byte skip here only affects the shape decision.
     *
     * @param bytes the raw request body bytes (non-empty)
     * @return the first significant byte as a {@code char}, or {@code '\0'} when none
     */
    private static char firstNonWhitespace(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        for (int i = start; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return (char) b;
            }
        }
        return '\0';
    }

    /**
     * Returns the body parsed as a {@link JsonObject}, or {@code null} when it is not a JSON object.
     *
     * <p>Vert.x's {@link RequestBody#asJsonObject()} <em>throws</em> a {@code DecodeException} when the
     * body is a JSON array, a JSON scalar, or non-JSON bytes (e.g. an {@code application/octet-stream}
     * body) rather than returning {@code null}. Binding must never propagate that out of the binder —
     * an unguarded throw escapes as an unmapped 500 on a perfectly valid array/binary body. This
     * helper normalizes the contract to {@code null}-on-mismatch so {@link #bindBody}/{@link
     * #bindJsonBody} fall through to the array, scalar, or raw-buffer branches.
     *
     * @param requestBody the routing-context request body
     * @return the parsed {@link JsonObject}, or {@code null} when the body is not a JSON object
     */
    private static JsonObject asJsonObjectOrNull(RequestBody requestBody) {
        try {
            return requestBody.asJsonObject();
        } catch (RuntimeException notJsonObject) {
            return null;
        }
    }

    /**
     * Returns the body parsed as a {@link JsonArray}, or {@code null} when it is not a JSON array.
     *
     * <p>Mirrors {@link #asJsonObjectOrNull(RequestBody)}: {@link RequestBody#asJsonArray()} throws a
     * {@code DecodeException} for a JSON object, a JSON scalar, or non-JSON bytes, so this helper
     * normalizes the contract to {@code null}-on-mismatch so binding falls through to the raw-buffer
     * branch instead of escaping as a 500.
     *
     * @param requestBody the routing-context request body
     * @return the parsed {@link JsonArray}, or {@code null} when the body is not a JSON array
     */
    private static JsonArray asJsonArrayOrNull(RequestBody requestBody) {
        try {
            return requestBody.asJsonArray();
        } catch (RuntimeException notJsonArray) {
            return null;
        }
    }

    /**
     * Extracts the lower-cased base media type from a {@code Content-Type} header value, stripping
     * any parameters (e.g. {@code "; charset=utf-8"}) and surrounding whitespace.
     *
     * <p>A {@code null} or blank header (including a header that is only parameters, e.g.
     * {@code "; charset=utf-8"}, which leaves a blank base) is treated as <em>missing</em> and yields
     * {@code null}, so {@link #bindBody} routes it through the no-content-type JSON-shape fallback
     * rather than the explicit-content-type raw-{@link Buffer} branch.
     *
     * @param contentType the raw {@code Content-Type} header value, possibly {@code null}
     * @return the lower-cased base media type, or {@code null} when {@code contentType} is {@code null}
     *     or blank
     */
    private static String baseMediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        String trimmed = base.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns whether the given base media type is JSON, i.e. {@code application/json} or a structured
     * JSON suffix type {@code application/*+json}.
     *
     * @param mediaType the lower-cased base media type, possibly {@code null}
     * @return {@code true} when the media type is a JSON media type
     */
    private static boolean isJsonMediaType(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        return mediaType.equals("application/json")
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /**
     * Wraps a list of raw string values according to the matching descriptor's multiplicity: a
     * collection descriptor ({@code componentType != null}) wraps all values as a {@link JsonArray};
     * otherwise the first value is wrapped as a coerced scalar.
     *
     * @param values     all raw values for this key (never {@code null}, may be empty)
     * @param descriptor the matching declared parameter, or {@code null} when undeclared
     * @param resolver   the conversion resolver used to coerce a declared scalar
     * @return the wrapped {@link RequestValue}
     */
    private static RequestValue wrapValues(
            List<String> values, ParamDescriptor descriptor, ParamConversionResolver resolver) {
        if (descriptor != null && descriptor.componentType() != null) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return RequestValue.of(array);
        }
        String first = values.isEmpty() ? null : values.get(0);
        return wrapScalar(first, descriptor, resolver);
    }

    /**
     * Wraps a single raw string value, coercing it to the descriptor's declared scalar type when a
     * descriptor is present, or wrapping the raw string when the key is undeclared.
     *
     * <p>Coercion is <em>lenient</em> at this binding facade: when the
     * {@link ParamConversionResolver} cannot convert the value to the declared type (e.g. {@code "abc"}
     * for a declared {@code Integer}, or {@code "not-a-uuid"} for a {@code UUID}), the raw
     * {@link String} is retained instead of propagating the failure. Binding therefore never throws on
     * a non-coercible scalar; the downstream {@code ParameterExtractor} re-converts through the same
     * resolver and fails closed there (a clean 400 {@code ParamConversionException}) so the failure
     * carries the parameter diagnostics rather than surfacing as an opaque {@code Method.invoke} 500.
     *
     * @param value      the raw string value, possibly {@code null}
     * @param descriptor the matching declared parameter, or {@code null} when undeclared
     * @param resolver   the conversion resolver used to coerce the declared scalar
     * @return the wrapped {@link RequestValue}; the raw string when coercion fails or no converter applies
     */
    private static RequestValue wrapScalar(String value, ParamDescriptor descriptor, ParamConversionResolver resolver) {
        if (value == null) {
            return RequestValue.of(null);
        }
        if (descriptor != null) {
            try {
                ConversionContext context =
                        SCALAR_CONTEXT_CACHE.computeIfAbsent(descriptor, ConversionContexts::forDescriptor);
                return RequestValue.of(resolver.fromString(value, context));
            } catch (RuntimeException coercionFailure) {
                // Non-coercible value, or no converter for the declared type: retain the raw string so
                // binding never throws; ParameterExtractor re-converts through the resolver and fails
                // closed there with parameter diagnostics (a 400), or Method.invoke surfaces the type
                // mismatch.
                return RequestValue.of(value);
            }
        }
        return RequestValue.of(value);
    }

    /**
     * Finds the declared parameter matching the given name and location, or {@code null} when none
     * is declared (an undeclared key).
     *
     * <p>Name matching is <b>per location</b>, and deliberately agrees with the extraction-side lookup
     * ({@code ParameterExtractor.lookup}) so the two halves of the same name resolution cannot disagree:
     *
     * <ul>
     *   <li><b>{@link ParamLocation#HEADER} and {@link ParamLocation#COOKIE}</b> match
     *       <em>case-insensitively</em>. Both maps are keyed by lower-cased name and extraction
     *       lower-cases the declared name before its lookup, so the descriptor half must be equally
     *       tolerant. It is load-bearing rather than cosmetic: RFC 9113 §8.2.1 requires HTTP/2 to
     *       transmit header field names in lower case, so with ALPN enabled the wire name of a
     *       {@code @HeaderParam("X-Tags")} declaration is {@code x-tags} for <em>every</em> HTTP/2
     *       client. A case-sensitive match there would leave a collection-declared parameter
     *       scalar-wrapped — dropping every value past the first — while passing HTTP/1.1 tests.</li>
     *   <li><b>{@link ParamLocation#PATH} and {@link ParamLocation#QUERY}</b> match
     *       <em>case-sensitively</em>: their maps are keyed verbatim and extraction looks the declared
     *       name up unchanged, so both halves already agree.</li>
     * </ul>
     *
     * <p>{@link String#equalsIgnoreCase(String)} keeps the case-insensitive branch allocation-free — no
     * per-comparison lower-casing on this per-request hot path.
     *
     * @param params   the operation's declared parameters
     * @param name     the parameter name to match, as reported by the request
     * @param location the parameter source to match
     * @return the matching descriptor, or {@code null}
     */
    private static ParamDescriptor findDescriptor(List<ParamDescriptor> params, String name, ParamLocation location) {
        boolean caseInsensitive = location == ParamLocation.HEADER || location == ParamLocation.COOKIE;
        for (ParamDescriptor descriptor : params) {
            if (descriptor.location() != location) {
                continue;
            }
            boolean matches = caseInsensitive
                    ? descriptor.name().equalsIgnoreCase(name)
                    : descriptor.name().equals(name);
            if (matches) {
                return descriptor;
            }
        }
        return null;
    }

    // --- Accessors ---

    @Override
    public Map<String, RequestValue> pathParameters() {
        return pathParameters;
    }

    @Override
    public Map<String, RequestValue> query() {
        return query;
    }

    @Override
    public Map<String, RequestValue> headers() {
        return headers;
    }

    @Override
    public Map<String, RequestValue> cookies() {
        return cookies;
    }

    @Override
    public RequestValue body() {
        return body;
    }

    @Override
    public HttpServerRequest raw() {
        return raw;
    }
}
