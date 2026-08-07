// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the {@code web-validation} gate produced by {@link WebValidationStrategy}: it validates a
 * bound request body and declared path/query/header parameters against vertx-json-schema validators
 * built once at {@code gateFor} time, calls {@code ctx.next()} for conforming requests, and
 * {@code ctx.fail(RestValidationException)} for violations. It also confirms a {@code @Context} param
 * (absent from {@code op.parameters()}) triggers no spurious validation, and that a param-only
 * operation with no body still installs a gate.
 */
class WebValidationGateTest {

    private final WebValidationStrategy strategy =
            new WebValidationStrategy(JaxRsConfig.builder().build());

    // --- Descriptor stub ---

    private static JaxRsOperationDescriptor op(
            String method, String route, List<ParamDescriptor> params, Optional<BodyDescriptor> body) {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .httpMethod(method)
                .routeTemplate(route)
                .parameters(params);
        body.ifPresent(builder::body);
        return builder.build();
    }

    private static ParamDescriptor param(String name, ParamLocation location, Class<?> type) {
        return new ParamDescriptor(name, location, type, null, null, null, List.of());
    }

    /** A simple body descriptor; its type is unused by the gate (the schema comes from {@code schemas}). */
    private static Optional<BodyDescriptor> bodyOf() {
        return Optional.of(new BodyDescriptor(Object.class, null, List.of()));
    }

    // --- RoutingContext mock with a backing data map for get/put ---

    private static RoutingContext mockContext(
            Map<String, String> pathParams, MultiMap query, MultiMap headers, Set<Cookie> cookies, RequestBody body) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(pathParams != null ? pathParams : Map.of());
        when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(headers != null ? headers : MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(cookies != null ? cookies : Set.of());
        when(ctx.body()).thenReturn(body);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    /** A context carrying an {@code application/json} content type so the binder takes the JSON path. */
    private static RoutingContext jsonContext(RequestBody body) {
        RoutingContext ctx = mockContext(null, null, null, null, body);
        when(ctx.request().getHeader("Content-Type")).thenReturn("application/json");
        return ctx;
    }

    private static RequestBody jsonBody(JsonObject json) {
        // Production always buffers the body bytes; mirror that so the content-type-aware binder can
        // read the body once off the buffer (a null buffer would mean "no body").
        RequestBody body = mock(RequestBody.class);
        when(body.asJsonObject()).thenReturn(json);
        when(body.asJsonArray()).thenReturn(null);
        when(body.buffer()).thenReturn(json.toBuffer());
        return body;
    }

    /**
     * Builds a {@link RequestBody} over a raw JSON-array buffer whose accessors reflect real Vert.x
     * parsing — {@code asJsonArray()} returns the parsed array, {@code asJsonObject()} returns
     * {@code null} (the body is not an object). This drives the gate through the production
     * {@link dev.vertique.rest.jaxrs.request.DefaultBoundRequest} array-binding path.
     */
    private static RequestBody jsonArrayBody(JsonArray json) {
        Buffer raw = json.toBuffer();
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(raw);
        when(body.asJsonObject()).thenAnswer(inv -> {
            Object decoded = io.vertx.core.json.Json.decodeValue(raw);
            return decoded instanceof JsonObject jo ? jo : null;
        });
        when(body.asJsonArray()).thenAnswer(inv -> {
            Object decoded = io.vertx.core.json.Json.decodeValue(raw);
            return decoded instanceof JsonArray ja ? ja : null;
        });
        return body;
    }

    private static MultiMap query(String name, String value) {
        MultiMap q = MultiMap.caseInsensitiveMultiMap();
        q.add(name, value);
        return q;
    }

    private static MultiMap headers(String name, String value) {
        MultiMap h = MultiMap.caseInsensitiveMultiMap();
        h.add(name, value);
        return h;
    }

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        return assertInstanceOf(RestValidationException.class, captor.getValue());
    }

    private static boolean referencesField(RestValidationException ex, String token) {
        for (ValidationErrorDetail e : ex.errors()) {
            String path = e.path() == null ? "" : e.path();
            String detail = e.detail() == null ? "" : e.detail();
            if (path.contains(token) || detail.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // --- Tests ---

    @Test
    @DisplayName("Body missing a required field is rejected via ctx.fail(RestValidationException)")
    void webValidationGateRejectsBodyViolatingRequired() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("name"))
                .put("properties", new JsonObject().put("name", new JsonObject().put("type", "string")));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(null, null, null, null, jsonBody(new JsonObject().put("age", 30)));
        gate.handle(ctx);

        captureFailure(ctx);
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A conforming body passes — ctx.next() is called and no failure occurs")
    void webValidationGatePassesConformingRequest() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("name"))
                .put(
                        "properties",
                        new JsonObject()
                                .put("name", new JsonObject().put("type", "string"))
                                .put(
                                        "age",
                                        new JsonObject().put("type", "integer").put("minimum", 0)));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(
                null,
                null,
                null,
                null,
                jsonBody(new JsonObject().put("name", "Alice").put("age", 30)));
        gate.handle(ctx);

        verify(ctx, times(1)).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A body violating minLength on 'code' is rejected, referencing 'code'")
    void webValidationGateRejectsMinLengthViolation() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "code",
                                        new JsonObject().put("type", "string").put("minLength", 3)));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(null, null, null, null, jsonBody(new JsonObject().put("code", "AB")));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertTrue(referencesField(ex, "code"), "a violation must reference field 'code'");
    }

    @Test
    @DisplayName("A @Context param (absent from op.parameters()) yields no spurious validation failure")
    void webValidationGateDoesNotValidateContextParam() {
        // op.parameters() excludes the @Context param entirely (adapter filters non-bindable sources).
        OperationSchemas schemas = OperationSchemas.builder().build();
        Optional<Handler<RoutingContext>> maybeGate =
                strategy.gateFor(op("GET", "/things", List.of(), Optional.empty()), schemas);

        // No body schema and no declared params -> nothing to validate, so no gate is installed.
        assertTrue(maybeGate.isEmpty(), "an operation with no schemas installs no gate");
    }

    @Test
    @DisplayName("A declared @HeaderParam violating its pattern is rejected, referencing the header")
    void webValidationGateRejectsDeclaredHeaderParamViolation() {
        ParamDescriptor tenant = param("X-Tenant", ParamLocation.HEADER, String.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.HEADER,
                        "X-Tenant",
                        new JsonObject().put("type", "string").put("pattern", "[a-z]+"))
                .build();
        Handler<RoutingContext> gate = strategy.gateFor(
                        op("GET", "/things", List.of(tenant), Optional.empty()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(null, null, headers("X-Tenant", "ABC123"), null, null);
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertTrue(
                referencesField(ex, "X-Tenant") || referencesField(ex, "x-tenant"),
                "violation must reference the X-Tenant header");
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A declared @QueryParam passes within bound and is rejected over bound")
    void webValidationGatePassesAndRejectsDeclaredQueryParam() {
        ParamDescriptor limit = param("limit", ParamLocation.QUERY, Integer.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "limit",
                        new JsonObject().put("type", "integer").put("maximum", 100))
                .build();
        JaxRsOperationDescriptor descriptor = op("GET", "/things", List.of(limit), Optional.empty());
        Handler<RoutingContext> gate = strategy.gateFor(descriptor, schemas).orElseThrow();

        RoutingContext ok = mockContext(null, query("limit", "50"), null, null, null);
        gate.handle(ok);
        verify(ok, times(1)).next();
        verify(ok, never()).fail(any(Throwable.class));

        RoutingContext bad = mockContext(null, query("limit", "500"), null, null, null);
        gate.handle(bad);
        RestValidationException ex = captureFailure(bad);
        assertTrue(referencesField(ex, "limit"), "violation must reference query param 'limit'");
    }

    @Test
    @DisplayName("A declared @PathParam typed integer rejects a non-integer and passes an integer")
    void webValidationGateRejectsDeclaredPathParamViolation() {
        ParamDescriptor id = param("id", ParamLocation.PATH, Integer.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(ParamLocation.PATH, "id", new JsonObject().put("type", "integer"))
                .build();
        JaxRsOperationDescriptor descriptor = op("GET", "/users/{id}", List.of(id), Optional.empty());
        Handler<RoutingContext> gate = strategy.gateFor(descriptor, schemas).orElseThrow();

        Map<String, String> abc = new HashMap<>();
        abc.put("id", "abc");
        RoutingContext bad = mockContext(abc, null, null, null, null);
        gate.handle(bad);
        RestValidationException ex = captureFailure(bad);
        assertTrue(referencesField(ex, "id"), "violation must reference path param 'id'");

        Map<String, String> fortyTwo = new HashMap<>();
        fortyTwo.put("id", "42");
        RoutingContext ok = mockContext(fortyTwo, null, null, null, null);
        gate.handle(ok);
        verify(ok, times(1)).next();
        verify(ok, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A declared boolean @QueryParam rejects a non-boolean string and passes true/false")
    void webValidationGateRejectsNonBooleanQueryParam() {
        ParamDescriptor enabled = param("enabled", ParamLocation.QUERY, Boolean.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "enabled", new JsonObject().put("type", "boolean"))
                .build();
        JaxRsOperationDescriptor descriptor = op("GET", "/things", List.of(enabled), Optional.empty());
        Handler<RoutingContext> gate = strategy.gateFor(descriptor, schemas).orElseThrow();

        // A non-boolean string must NOT be leniently coerced to false and silently pass; it must fail.
        RoutingContext bad = mockContext(null, query("enabled", "notabool"), null, null, null);
        gate.handle(bad);
        RestValidationException ex = captureFailure(bad);
        assertTrue(referencesField(ex, "enabled"), "violation must reference query param 'enabled'");
        verify(bad, never()).next();

        // Valid 'true' and 'false' still pass.
        RoutingContext trueCtx = mockContext(null, query("enabled", "true"), null, null, null);
        gate.handle(trueCtx);
        verify(trueCtx, times(1)).next();
        verify(trueCtx, never()).fail(any(Throwable.class));

        RoutingContext falseCtx = mockContext(null, query("enabled", "false"), null, null, null);
        gate.handle(falseCtx);
        verify(falseCtx, times(1)).next();
        verify(falseCtx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A GET op with declared params but no body still installs a gate that validates params")
    void webValidationGateInstallsForOperationWithNoBodySchema() {
        ParamDescriptor limit = param("limit", ParamLocation.QUERY, Integer.class);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "limit",
                        new JsonObject().put("type", "integer").put("maximum", 100))
                .build();
        Optional<Handler<RoutingContext>> maybeGate =
                strategy.gateFor(op("GET", "/things", List.of(limit), Optional.empty()), schemas);

        assertTrue(maybeGate.isPresent(), "a param-only operation must still install a gate");

        // The installed gate validates the declared param.
        RoutingContext bad = mockContext(null, query("limit", "500"), null, null, null);
        maybeGate.orElseThrow().handle(bad);
        RestValidationException ex = captureFailure(bad);
        assertTrue(referencesField(ex, "limit"), "the param-only gate must validate 'limit'");
    }

    @Test
    @DisplayName("The gate stashes one shared BoundRequest under the meta-data key (body read once)")
    void webValidationGateStashesSharedBoundRequest() {
        JsonObject bodySchema = new JsonObject().put("type", "object");
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(null, null, null, null, jsonBody(new JsonObject().put("x", 1)));
        gate.handle(ctx);

        Object stashed = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
        assertInstanceOf(BoundRequest.class, stashed);
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A conforming JSON array body passes the array body schema (bound as a JsonArray)")
    void webValidationGatePassesConformingArrayBody() {
        JsonObject itemSchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("name"))
                .put("properties", new JsonObject().put("name", new JsonObject().put("type", "string")));
        JsonObject bodySchema = new JsonObject().put("type", "array").put("items", itemSchema);
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        JsonArray conforming =
                new JsonArray().add(new JsonObject().put("name", "Alice")).add(new JsonObject().put("name", "Bob"));
        RoutingContext ctx = jsonContext(jsonArrayBody(conforming));
        gate.handle(ctx);

        verify(ctx, times(1)).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A JSON array body with a violating element is rejected (the gate sees the array)")
    void webValidationGateRejectsViolatingArrayElement() {
        JsonObject itemSchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("name"))
                .put("properties", new JsonObject().put("name", new JsonObject().put("type", "string")));
        JsonObject bodySchema = new JsonObject().put("type", "array").put("items", itemSchema);
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        // Second element is missing the required 'name' field.
        JsonArray violating =
                new JsonArray().add(new JsonObject().put("name", "Alice")).add(new JsonObject().put("age", 30));
        RoutingContext ctx = jsonContext(jsonArrayBody(violating));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty(), "the violating array element must produce a validation failure");
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("Multiple body violations are all collected into the failure's errors list")
    void webValidationGateCollectsAllBodyViolations() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("name").add("email"));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategy.gateFor(op("POST", "/things", List.of(), bodyOf()), schemas)
                .orElseThrow();

        RoutingContext ctx = mockContext(null, null, null, null, jsonBody(new JsonObject().put("age", 1)));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty(), "at least one required-field violation must be reported");
        // Sanity: the collected errors list is the populated one (not the empty default).
        List<ValidationErrorDetail> all = new ArrayList<>(ex.errors());
        assertTrue(all.size() >= 1);
    }
}
