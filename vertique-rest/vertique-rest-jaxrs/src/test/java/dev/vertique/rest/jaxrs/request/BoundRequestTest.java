// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.routing.StubOperationDescriptor;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultBoundRequest}, the binding facade over a Vert.x
 * {@link RoutingContext}.
 *
 * <p>Verifies the FR-024 binding model: case-insensitive header/cookie lookup, path-param coercion
 * to the declared scalar type, scalar-first vs collection-all multiplicity driven by the matching
 * {@link ParamDescriptor}, and the never-null body contract.
 */
class BoundRequestTest {

    /**
     * Builds a {@link JaxRsOperationDescriptor} stub with the supplied declared parameters and no
     * body.
     */
    private static JaxRsOperationDescriptor opWithParams(ParamDescriptor... params) {
        return StubOperationDescriptor.builder()
                .operationId("op")
                .httpMethod("GET")
                .routeTemplate("/test")
                .parameters(List.of(params))
                .build();
    }

    /**
     * Builds a mocked {@link RoutingContext} with the supplied path params, query MultiMap, header
     * MultiMap, cookies, and request body. Any argument may be {@code null} for "not configured".
     */
    private static RoutingContext mockContext(
            Map<String, String> pathParams, MultiMap query, MultiMap headers, Set<Cookie> cookies, RequestBody body) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(pathParams != null ? pathParams : Map.of());
        when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(headers != null ? headers : MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(cookies != null ? cookies : Set.of());
        when(ctx.body()).thenReturn(body);
        return ctx;
    }

    /**
     * Builds a mocked {@link RoutingContext} carrying a request body buffer and an explicit
     * {@code Content-Type} header (which may be {@code null} for the no-content-type path). The
     * {@link RequestBody} mock parses the supplied raw bytes through the real Vert.x
     * {@code asJsonObject()}/{@code asJsonArray()} semantics so the binder sees production behavior.
     */
    private static RoutingContext bodyContext(String contentType, Buffer rawBody) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(request.getHeader("Content-Type")).thenReturn(contentType);
        RequestBody body = realRequestBody(rawBody);
        when(ctx.body()).thenReturn(body);
        return ctx;
    }

    /**
     * Builds a {@link RequestBody} mock over the given raw buffer whose {@code asJsonObject()},
     * {@code asJsonArray()}, and {@code buffer()} accessors reflect <em>real</em> Vert.x behavior:
     * each accessor parses the buffered bytes via {@link io.vertx.core.json.Json#decodeValue(Buffer,
     * Class)} and <em>throws</em> a {@code DecodeException} on a mismatch (a JSON array passed to
     * {@code asJsonObject()}, a JSON object passed to {@code asJsonArray()}, a scalar, or non-JSON
     * binary), exactly as production does — rather than returning {@code null}. Returning {@code null}
     * on mismatch (the previous mock behavior) masked a binding defect where the unguarded throw
     * escaped as a 500; modeling the throw lets these tests guard {@link DefaultBoundRequest}'s
     * exception handling. An empty buffer yields a body whose accessors return {@code null}.
     */
    private static RequestBody realRequestBody(Buffer rawBody) {
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(rawBody);
        when(body.asJsonObject()).thenAnswer(inv -> parseJsonObject(rawBody));
        when(body.asJsonArray()).thenAnswer(inv -> parseJsonArray(rawBody));
        return body;
    }

    /**
     * Parses the buffer as a {@link JsonObject}, returning {@code null} for an empty buffer and
     * <em>throwing</em> {@code DecodeException} on a mismatch — mirroring {@code RequestBody.asJsonObject()}.
     */
    private static JsonObject parseJsonObject(Buffer rawBody) {
        if (rawBody == null || rawBody.length() == 0) {
            return null;
        }
        return io.vertx.core.json.Json.decodeValue(rawBody, JsonObject.class);
    }

    /**
     * Parses the buffer as a {@link JsonArray}, returning {@code null} for an empty buffer and
     * <em>throwing</em> {@code DecodeException} on a mismatch — mirroring {@code RequestBody.asJsonArray()}.
     */
    private static JsonArray parseJsonArray(Buffer rawBody) {
        if (rawBody == null || rawBody.length() == 0) {
            return null;
        }
        return io.vertx.core.json.Json.decodeValue(rawBody, JsonArray.class);
    }

    /** Builds a no-param POST operation descriptor (body-bearing) for body-shape tests. */
    private static JaxRsOperationDescriptor bodyOp() {
        return opWithParams();
    }

    @Test
    @DisplayName("Header map lookup is case-insensitive for undeclared headers")
    void boundRequestQueryMapCaseInsensitiveHeader() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Content-Type", "application/json");
        RoutingContext ctx = mockContext(null, null, headers, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams());

        assertEquals("application/json", bound.headers().get("content-type").getString());
    }

    @Test
    @DisplayName("Path param is bound from the RoutingContext and coerced to the declared Integer type")
    void boundRequestPathParamBoundFromRoutingContext() {
        ParamDescriptor idParam =
                new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        RoutingContext ctx = mockContext(Map.of("id", "42"), null, null, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(idParam));

        assertEquals(42, bound.pathParameters().get("id").getInteger());
    }

    @Test
    @DisplayName("Scalar query param binds the first value only")
    void boundRequestQueryParamScalarFirstValue() {
        ParamDescriptor tagParam =
                new ParamDescriptor("tag", ParamLocation.QUERY, String.class, null, null, null, List.of());
        MultiMap query = MultiMap.caseInsensitiveMultiMap();
        query.add("tag", "a");
        query.add("tag", "b");
        RoutingContext ctx = mockContext(null, query, null, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(tagParam));

        assertEquals("a", bound.query().get("tag").getString());
    }

    @Test
    @DisplayName("Collection query param binds all values as a JsonArray")
    void boundRequestQueryParamCollectionAllValues() {
        ParamDescriptor tagParam =
                new ParamDescriptor("tag", ParamLocation.QUERY, List.class, String.class, null, null, List.of());
        MultiMap query = MultiMap.caseInsensitiveMultiMap();
        query.add("tag", "a");
        query.add("tag", "b");
        RoutingContext ctx = mockContext(null, query, null, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(tagParam));

        var array = bound.query().get("tag").getJsonArray();
        assertNotNull(array);
        assertTrue(array.contains("a"));
        assertTrue(array.contains("b"));
    }

    @Test
    @DisplayName("Body is never null even when the request has no body")
    void boundRequestBodyIsNeverNull() {
        RequestBody emptyBody = mock(RequestBody.class);
        when(emptyBody.asJsonObject()).thenReturn(null);
        when(emptyBody.buffer()).thenReturn(null);
        RoutingContext ctx = mockContext(null, null, null, null, emptyBody);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams());

        assertNotNull(bound.body());
        assertTrue(bound.body().isNull());
    }

    @Test
    @DisplayName("A JSON array body with application/json content type binds as a JsonArray")
    void boundRequestBindsJsonArrayBody() {
        Buffer raw = Buffer.buffer("[{\"a\":1},{\"a\":2}]");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        JsonArray array = bound.body().getJsonArray();
        assertNotNull(array, "array body must bind as a JsonArray, not null/Buffer");
        assertEquals(2, array.size());
        assertEquals(1, array.getJsonObject(0).getInteger("a"));
        assertNull(bound.body().getBuffer(), "array body must not bind as a raw Buffer");
    }

    @Test
    @DisplayName("A JSON object body with application/json content type binds as a JsonObject")
    void boundRequestBindsJsonObjectBody() {
        Buffer raw = Buffer.buffer("{\"name\":\"Alice\"}");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        JsonObject object = bound.body().getJsonObject();
        assertNotNull(object, "object body must bind as a JsonObject");
        assertEquals("Alice", object.getString("name"));
    }

    @Test
    @DisplayName("A scalar JSON string body binds as a String via Json.decodeValue")
    void boundRequestBindsScalarJsonStringBody() {
        Buffer raw = Buffer.buffer("\"x\"");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        assertEquals("x", bound.body().getString(), "scalar JSON string body must bind as a String");
    }

    @Test
    @DisplayName("A scalar JSON number body binds as a Number")
    void boundRequestBindsScalarJsonNumberBody() {
        Buffer raw = Buffer.buffer("42");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        assertEquals(42, bound.body().getInteger(), "scalar JSON number body must bind as a Number");
    }

    @Test
    @DisplayName("A JSON content type with parameters (charset) is recognized case-insensitively")
    void boundRequestBindsJsonBodyWithContentTypeParameters() {
        Buffer raw = Buffer.buffer("[1,2,3]");
        RoutingContext ctx = bodyContext("Application/JSON; charset=utf-8", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        JsonArray array = bound.body().getJsonArray();
        assertNotNull(array, "a JSON content type with parameters must still parse as JSON");
        assertEquals(3, array.size());
    }

    @Test
    @DisplayName("An application/*+json content type is recognized as JSON")
    void boundRequestBindsVendorJsonBody() {
        Buffer raw = Buffer.buffer("[1,2]");
        RoutingContext ctx = bodyContext("application/vnd.api+json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        assertNotNull(bound.body().getJsonArray(), "application/*+json must parse as JSON");
    }

    @Test
    @DisplayName("A malformed JSON body falls back to the raw Buffer instead of throwing")
    void boundRequestFallsBackToBufferOnMalformedJson() {
        Buffer raw = Buffer.buffer("{not valid json");
        RoutingContext ctx = bodyContext("application/json", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        assertNotNull(bound.body().getBuffer(), "malformed JSON must bind as the raw Buffer");
        assertNull(bound.body().getJsonObject());
        assertNull(bound.body().getJsonArray());
    }

    @Test
    @DisplayName("A text/plain body binds as a UTF-8 String")
    void boundRequestBindsTextPlainBody() {
        Buffer raw = Buffer.buffer("hello world");
        RoutingContext ctx = bodyContext("text/plain", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        assertEquals("hello world", bound.body().getString(), "text body must bind as a String");
        assertNull(bound.body().getBuffer(), "text body must not bind as a raw Buffer");
    }

    @Test
    @DisplayName("A binary octet-stream body binds as a raw Buffer")
    void boundRequestBindsBinaryBody() {
        Buffer raw = Buffer.buffer(new byte[] {1, 2, 3, 4});
        RoutingContext ctx = bodyContext("application/octet-stream", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        Buffer buffer = bound.body().getBuffer();
        assertNotNull(buffer, "binary body must bind as a raw Buffer");
        assertEquals(4, buffer.length());
        assertNull(bound.body().getString(), "binary body must not bind as a String");
    }

    @Test
    @DisplayName("An octet-stream body whose bytes are valid JSON binds as a Buffer, not a JsonObject (W3)")
    void boundRequestExplicitOctetStreamJsonShapedBytesBindAsBuffer() {
        // The bytes "{}" parse as a JSON object, but the EXPLICIT application/octet-stream content type
        // means the client declared the body as binary. Before the W3 fix the binder probed the JSON
        // shape first and bound a JsonObject, leaving a byte[]/Buffer body param bound to null
        // (BinaryRequestBodyDecoder only adapts Buffer). The body must bind as the raw Buffer.
        Buffer raw = Buffer.buffer("{}");
        RoutingContext ctx = bodyContext("application/octet-stream", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        Buffer buffer = bound.body().getBuffer();
        assertNotNull(buffer, "explicit octet-stream JSON-shaped bytes must bind as a raw Buffer");
        assertEquals("{}", buffer.toString(), "the buffer must carry the original bytes");
        assertNull(bound.body().getJsonObject(), "explicit octet-stream must not bind as a JsonObject");
    }

    @Test
    @DisplayName("An octet-stream body whose bytes are a JSON array binds as a Buffer, not a JsonArray (W3)")
    void boundRequestExplicitOctetStreamJsonArrayBytesBindAsBuffer() {
        Buffer raw = Buffer.buffer("[1,2]");
        RoutingContext ctx = bodyContext("application/octet-stream", raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        Buffer buffer = bound.body().getBuffer();
        assertNotNull(buffer, "explicit octet-stream JSON-array bytes must bind as a raw Buffer");
        assertEquals("[1,2]", buffer.toString());
        assertNull(bound.body().getJsonArray(), "explicit octet-stream must not bind as a JsonArray");
    }

    @Test
    @DisplayName("A JSON object body with no content type still binds as a JsonObject (no crash)")
    void boundRequestBindsJsonObjectBodyWithNoContentType() {
        Buffer raw = Buffer.buffer("{\"name\":\"Bob\"}");
        RoutingContext ctx = bodyContext(null, raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        JsonObject object = bound.body().getJsonObject();
        assertNotNull(object, "no-content-type JSON object must still bind as a JsonObject");
        assertEquals("Bob", object.getString("name"));
    }

    @Test
    @DisplayName("A JSON array body with no content type still binds as a JsonArray (no crash)")
    void boundRequestBindsJsonArrayBodyWithNoContentType() {
        Buffer raw = Buffer.buffer("[{\"a\":1}]");
        RoutingContext ctx = bodyContext(null, raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        JsonArray array = bound.body().getJsonArray();
        assertNotNull(array, "no-content-type JSON array must still bind as a JsonArray");
        assertEquals(1, array.size());
    }

    @Test
    @DisplayName("A non-JSON body with no content type falls back to the raw Buffer")
    void boundRequestBindsBufferBodyWithNoContentType() {
        Buffer raw = Buffer.buffer(new byte[] {9, 8, 7});
        RoutingContext ctx = bodyContext(null, raw);

        BoundRequest bound = new DefaultBoundRequest(ctx, bodyOp());

        Buffer buffer = bound.body().getBuffer();
        assertNotNull(buffer, "a non-JSON no-content-type body must bind as the raw Buffer");
        assertEquals(3, buffer.length());
    }

    @Test
    @DisplayName("Non-coercible scalar retains the raw string instead of throwing during binding")
    void boundRequestRetainsRawStringOnNonCoercibleScalar() {
        ParamDescriptor idParam =
                new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        RoutingContext ctx = mockContext(Map.of("id", "abc"), null, null, null, null);

        BoundRequest bound = assertDoesNotThrow(() -> new DefaultBoundRequest(ctx, opWithParams(idParam)));

        assertEquals("abc", bound.pathParameters().get("id").getString(), "raw string must be retained");
        assertNull(bound.pathParameters().get("id").getInteger(), "non-coercible value must not parse as Integer");
    }

    @Test
    @DisplayName("Coercible scalar still parses to the declared Integer type after the lenient change")
    void boundRequestCoercibleScalarStillCoerces() {
        ParamDescriptor idParam =
                new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        RoutingContext ctx = mockContext(Map.of("id", "42"), null, null, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(idParam));

        assertEquals(42, bound.pathParameters().get("id").getInteger());
    }

    @Test
    @DisplayName("Collection header param binds all repeated values as a JsonArray (case-insensitive)")
    void boundRequestHeaderParamCollectionAllValues() {
        ParamDescriptor tagsParam =
                new ParamDescriptor("X-Tag", ParamLocation.HEADER, List.class, String.class, null, null, List.of());
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("X-Tag", "a");
        headers.add("X-Tag", "b");
        RoutingContext ctx = mockContext(null, null, headers, null, null);

        BoundRequest bound = new DefaultBoundRequest(ctx, opWithParams(tagsParam));

        var array = bound.headers().get("x-tag").getJsonArray();
        assertNotNull(array);
        assertTrue(array.contains("a"));
        assertTrue(array.contains("b"));
    }

    // --- Profile-mapper scalar body tests (FR-JSON-024A / review finding W-B) ---

    /**
     * Builds a profile {@link ObjectMapper} with Vert.x JSON support and
     * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} enabled.
     *
     * <p>The standard Vert.x {@code Json.decodeValue} uses the default Jackson mapper which returns
     * {@link Double} for JSON floating-point scalars. This profile mapper returns {@link BigDecimal}
     * instead — an observable, non-throwing type difference that proves the scalar was parsed by the
     * profile mapper, not by the Vert.x default path.
     *
     * @return the profile mapper with big-decimal float parsing
     */
    private static ObjectMapper bigDecimalProfileMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .addModule(VertxJsonSupport.module())
                .build();
    }

    /**
     * Builds a profile {@link ObjectMapper} with Vert.x JSON support and
     * {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} enabled.
     *
     * <p>Used for the rejection test where the profile mapper rejects a scalar body with trailing
     * content (e.g. {@code "true false"} — a second boolean after the first).
     *
     * @return the strict profile mapper
     */
    private static ObjectMapper trailingTokenProfileMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .addModule(VertxJsonSupport.module())
                .build();
    }

    /**
     * Builds a {@link RoutingContext} mock carrying {@code rawBody} as an
     * {@code application/json} body <em>and</em> stashing {@code profileMapper} under
     * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER}, so {@link DefaultBoundRequest} routes the
     * first parse through the supplied profile mapper rather than the default Vert.x path.
     *
     * @param rawBody       the raw request body bytes
     * @param profileMapper the resolved profile mapper to stash on the context
     * @return the mocked routing context with the profile mapper present
     */
    private static RoutingContext profiledBodyContext(Buffer rawBody, ObjectMapper profileMapper) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(profileMapper);
        RequestBody body = realRequestBody(rawBody);
        when(ctx.body()).thenReturn(body);
        return ctx;
    }

    @Test
    @DisplayName("A scalar JSON float body is parsed by the profile mapper, returning BigDecimal (not Double)"
            + " — proves scalar routes through profile mapper (W-B fix)")
    void profiledScalarBody_floatReturnsBigDecimal_provingProfileMapperUsed() {
        // given: a profile mapper with USE_BIG_DECIMAL_FOR_FLOATS enabled (returns BigDecimal for
        // floats), and a scalar body "1.5".
        // when: DefaultBoundRequest.bindBody routes the scalar through the profile mapper (after fix).
        // then: the raw bound value is a BigDecimal (not Double) — proving the profile mapper, not
        // Json.decodeValue (which returns Double for "1.5"), performed the parse.
        // Before the fix, Json.decodeValue("1.5") returns a Double; the raw get() value is a
        // Double — the BigDecimal assertion is RED before the fix.
        ObjectMapper profileMapper = bigDecimalProfileMapper();
        Buffer rawBody = Buffer.buffer("1.5");
        RoutingContext ctx = profiledBodyContext(rawBody, profileMapper);

        BoundRequest bound = assertDoesNotThrow(
                () -> new DefaultBoundRequest(ctx, bodyOp()),
                "a clean scalar float body must bind successfully through the profile mapper");

        // The load-bearing assertion: get() returns the raw wrapped value without coercion.
        // When the profile mapper is used (USE_BIG_DECIMAL_FOR_FLOATS), it is BigDecimal.
        // When Json.decodeValue is used (the old scalar fallback), it is Double.
        Object raw = bound.body().get();
        assertNotNull(raw, "the scalar float body must bind as a non-null value through the profile mapper");
        assertInstanceOf(
                BigDecimal.class,
                raw,
                "USE_BIG_DECIMAL_FOR_FLOATS profile must produce BigDecimal (not Double) for a JSON float scalar");
    }

    @Test
    @DisplayName("A scalar JSON float body on the vertx (default) path still binds as Double (non-regression)")
    void vertxPathScalarBody_floatStillReturnsDouble() {
        // given: no profile mapper on the context (the vertx default path), scalar body "1.5".
        // when: DefaultBoundRequest.bindBody uses the vertx path (Json.decodeValue).
        // then: the raw bound value is a Double — proving the vertx path is byte-for-byte unchanged.
        // This guards the non-regression: the fix must NOT affect the vertx default scalar path.
        Buffer rawBody = Buffer.buffer("1.5");
        RoutingContext ctx = bodyContext("application/json", rawBody);

        BoundRequest bound = assertDoesNotThrow(
                () -> new DefaultBoundRequest(ctx, bodyOp()),
                "a clean scalar float on the vertx path must bind without exception");

        Object raw = bound.body().get();
        assertNotNull(raw, "the scalar float body must bind as a non-null value on the vertx path");
        assertInstanceOf(Double.class, raw, "the vertx default path must return Double for a JSON float scalar");
    }

    @Test
    @DisplayName("A scalar JSON body with trailing content is rejected (400) by the profile mapper (W-B)")
    void profiledScalarBody_trailingTokens_rejectedAs400() {
        // given: a profile mapper with FAIL_ON_TRAILING_TOKENS (the profile-mapper strict feature),
        // and a scalar body "true false" — a boolean followed by a trailing token.
        // when: DefaultBoundRequest.bindBody routes the scalar through the profile mapper (after fix).
        // then: a ValidationException (HTTP 400) is thrown — the profile's strict feature applies
        // to scalars uniformly. This proves the strict parse is not limited to object/array bodies.
        ObjectMapper profileMapper = trailingTokenProfileMapper();
        Buffer rawBody = Buffer.buffer("true false");
        RoutingContext ctx = profiledBodyContext(rawBody, profileMapper);

        assertThrows(
                ValidationException.class,
                () -> new DefaultBoundRequest(ctx, bodyOp()),
                "trailing tokens on a profiled scalar body must be rejected (400) by the profile mapper");
    }

    @Test
    @DisplayName("A clean scalar number body binds successfully through the profile mapper")
    void profiledScalarBody_cleanNumber_bindsSuccessfully() {
        // given: a profile mapper with FAIL_ON_TRAILING_TOKENS and a clean scalar body "42".
        // when: DefaultBoundRequest.bindBody routes the scalar through the profile mapper (after fix).
        // then: the body binds as Integer 42 — normal scalars still work through the profile mapper.
        ObjectMapper profileMapper = trailingTokenProfileMapper();
        Buffer rawBody = Buffer.buffer("42");
        RoutingContext ctx = profiledBodyContext(rawBody, profileMapper);

        BoundRequest bound = assertDoesNotThrow(
                () -> new DefaultBoundRequest(ctx, bodyOp()),
                "a clean scalar body must bind successfully through the profile mapper");

        assertEquals(42, bound.body().getInteger(), "a clean scalar number must bind as Integer 42");
    }

    @Test
    @DisplayName("A clean scalar string body binds successfully through the profile mapper")
    void profiledScalarBody_cleanString_bindsSuccessfully() {
        // given: a profile mapper with FAIL_ON_TRAILING_TOKENS and a clean scalar body '"hello"'.
        // when: DefaultBoundRequest.bindBody routes the scalar through the profile mapper (after fix).
        // then: the body binds as String "hello" — normal string scalars still work.
        ObjectMapper profileMapper = trailingTokenProfileMapper();
        Buffer rawBody = Buffer.buffer("\"hello\"");
        RoutingContext ctx = profiledBodyContext(rawBody, profileMapper);

        BoundRequest bound = assertDoesNotThrow(
                () -> new DefaultBoundRequest(ctx, bodyOp()),
                "a clean scalar string body must bind successfully through the profile mapper");

        assertEquals("hello", bound.body().getString(), "a clean scalar string must bind as String \"hello\"");
    }
}
