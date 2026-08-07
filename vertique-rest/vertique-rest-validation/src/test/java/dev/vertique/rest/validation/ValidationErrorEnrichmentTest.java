// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies enrichment of {@link ValidationErrorDetail} with JSON Pointer, failed keyword, and
 * expected constraint values produced by {@link WebValidationStrategy} (Slice 11, FR-010/FR-011).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>{@code minLength} violation carries the JSON pointer as {@code path}, the keyword
 *       {@code "minLength"} in {@code type}, and {@code {minLength: 3}} in {@code args}</li>
 *   <li>No submitted/actual value leaks into the serialized error response</li>
 *   <li>Aggregate mode (default) collects all three violations before failing</li>
 *   <li>Fail-fast mode stops at the first violation</li>
 *   <li>Golden structure: status 400, enriched error entries have required fields typed correctly</li>
 * </ul>
 */
class ValidationErrorEnrichmentTest {

    // --- Descriptor stubs ---

    private static JaxRsOperationDescriptor op(List<ParamDescriptor> params, Optional<BodyDescriptor> body) {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .parameters(params);
        body.ifPresent(builder::body);
        return builder.build();
    }

    private static Optional<BodyDescriptor> bodyOf() {
        return Optional.of(new BodyDescriptor(Object.class, null, List.of()));
    }

    // --- Context mock ---

    private static RoutingContext mockContext(RequestBody body) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.<Cookie>of());
        when(ctx.body()).thenReturn(body);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
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

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        return assertInstanceOf(RestValidationException.class, captor.getValue());
    }

    private static WebValidationStrategy strategyWith(String validationMode) {
        JaxRsConfig config =
                JaxRsConfig.builder().validationMode(validationMode).build();
        return new WebValidationStrategy(config);
    }

    private static WebValidationStrategy defaultStrategy() {
        return strategyWith("aggregate");
    }

    // --- Tests ---

    @Test
    @DisplayName(
            "MinLengthViolationCarriesPointerAndKeyword: minLength:3 violation has path=/code, type=minLength, args={minLength:3}")
    void minLengthViolationCarriesPointerAndKeyword() {
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
        Handler<RoutingContext> gate =
                defaultStrategy().gateFor(op(List.of(), bodyOf()), schemas).orElseThrow();

        // "AB" violates minLength:3
        RoutingContext ctx = mockContext(jsonBody(new JsonObject().put("code", "AB")));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty(), "should have at least one error");

        ValidationErrorDetail error = ex.errors().get(0);
        // path must be a JSON Pointer identifying the failing field
        assertNotNull(error.path(), "path must not be null");
        assertTrue(error.path().contains("code"), "path must reference the 'code' field; was: " + error.path());

        // type must be the failed keyword
        assertEquals("minLength", error.type(), "type must be the failed keyword 'minLength'");

        // args must carry the constraint value
        assertNotNull(error.args(), "args must not be null for a minLength violation");
        assertTrue(error.args().containsKey("minLength"), "args must carry the 'minLength' key");
        assertEquals(3, ((Number) error.args().get("minLength")).intValue(), "args.minLength must be 3");

        verify(ctx, never()).next();
    }

    @Test
    @DisplayName(
            "NoSubmittedValueInErrorResponse: serialized error response must not contain value/actual/submittedValue")
    void noSubmittedValueInErrorResponse() throws Exception {
        // Build a violation on a sensitive field
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "password",
                                        new JsonObject().put("type", "string").put("minLength", 8)));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate =
                defaultStrategy().gateFor(op(List.of(), bodyOf()), schemas).orElseThrow();

        // Submit a short password — should NOT appear in the error response
        RoutingContext ctx = mockContext(jsonBody(new JsonObject().put("password", "short")));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        // Build the problem detail as the error pipeline would
        ValidationProblemDetail pd = ValidationProblemDetail.of(ex.getMessage(), ex.errors());
        String json = new ObjectMapper().writeValueAsString(pd);

        // The submitted value "short" must NOT appear anywhere in the response
        assertFalse(json.contains("\"short\""), "submitted value must not appear in the response; got: " + json);
        // Standard security check: no "value", "actual", or "submittedValue" keys
        assertFalse(json.contains("\"submittedValue\""), "response must not contain 'submittedValue'");
        assertFalse(json.contains("\"actual\""), "response must not contain 'actual'");
        // The response must still be a 400 problem detail
        assertTrue(json.contains("\"status\":400"), "must still be a 400 status");
    }

    @Test
    @DisplayName("AggregateModeReturnsAllThreeViolations: three distinct violations with mode=aggregate -> 3 errors")
    void aggregateModeReturnsAllThreeViolations() {
        // Schema with three independent violations for a request missing all required fields
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("a").add("b").add("c"));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategyWith("aggregate")
                .gateFor(op(List.of(), bodyOf()), schemas)
                .orElseThrow();

        // Empty body triggers all three required violations
        RoutingContext ctx = mockContext(jsonBody(new JsonObject()));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertEquals(3, ex.errors().size(), "aggregate mode must collect all 3 required-field violations");
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("FailFastModeReturnsExactlyOneViolation: three distinct violations with mode=failFast -> 1 error")
    void failFastModeReturnsExactlyOneViolation() {
        // Same schema: three required fields missing
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("a").add("b").add("c"));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate = strategyWith("failFast")
                .gateFor(op(List.of(), bodyOf()), schemas)
                .orElseThrow();

        // Empty body triggers all three required violations, but failFast should stop at first
        RoutingContext ctx = mockContext(jsonBody(new JsonObject()));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertEquals(1, ex.errors().size(), "failFast mode must return exactly 1 error (first violation only)");
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("GoldenStructureTest: status 400, errors array with path/detail/location/type fields")
    void goldenStructureTest() throws Exception {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "name",
                                        new JsonObject().put("type", "string").put("minLength", 2)));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate =
                defaultStrategy().gateFor(op(List.of(), bodyOf()), schemas).orElseThrow();

        RoutingContext ctx = mockContext(jsonBody(new JsonObject().put("name", "X")));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty(), "must have at least one error");

        ValidationErrorDetail error = ex.errors().get(0);
        // path is present
        assertNotNull(error.path(), "path must be non-null");
        // detail is present
        assertNotNull(error.detail(), "detail must be non-null");
        // location is present (body)
        assertEquals("body", error.location(), "location must be 'body'");
        // type is present (enriched keyword)
        assertNotNull(error.type(), "type must be non-null (enriched keyword)");

        // Serialize and check structure
        ValidationProblemDetail pd = ValidationProblemDetail.of(ex.getMessage(), ex.errors());
        String json = new ObjectMapper().writeValueAsString(pd);

        assertTrue(json.contains("\"status\":400"), "must have status 400");
        assertTrue(json.contains("\"errors\""), "must have errors array");
        assertTrue(json.contains("\"path\""), "error entry must have 'path'");
        assertTrue(json.contains("\"detail\""), "error entry must have 'detail'");
        assertTrue(json.contains("\"location\""), "error entry must have 'location'");
        assertTrue(json.contains("\"type\""), "error entry must have 'type' (enriched keyword)");
        // No submitted value in the response
        assertFalse(json.contains("\"X\""), "submitted value must not appear in the error response");
    }

    @Test
    @DisplayName("JaxRsConfig defaults validationMode to aggregate")
    void jaxRsConfigDefaultsValidationModeToAggregate() {
        JaxRsConfig config = JaxRsConfig.builder().build();
        assertEquals("aggregate", config.validationMode(), "default validationMode must be 'aggregate'");
    }

    @Test
    @DisplayName("RequiredViolation carries type=required and no submitted value")
    void requiredViolationCarriesKeyword() {
        JsonObject bodySchema = new JsonObject()
                .put("type", "object")
                .put("required", new JsonArray().add("email"))
                .put("properties", new JsonObject().put("email", new JsonObject().put("type", "string")));
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        Handler<RoutingContext> gate =
                defaultStrategy().gateFor(op(List.of(), bodyOf()), schemas).orElseThrow();

        // Missing the required "email" field
        RoutingContext ctx = mockContext(jsonBody(new JsonObject().put("name", "Alice")));
        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty());

        // At least one error must have type=required
        boolean hasRequired = ex.errors().stream().anyMatch(e -> "required".equals(e.type()));
        assertTrue(hasRequired, "at least one error must carry type='required'");
        // The error must not echo the submitted value "Alice"
        boolean leaksValue = ex.errors().stream()
                .anyMatch(e -> (e.detail() != null && e.detail().contains("Alice"))
                        || (e.path() != null && e.path().contains("Alice")));
        assertFalse(leaksValue, "submitted value 'Alice' must not appear in any error detail or path");
    }

    @Test
    @DisplayName("ParamViolation with maximum: type=maximum and args={maximum: N}")
    void paramMaximumViolationCarriesKeyword() {
        ParamDescriptor limit =
                new ParamDescriptor("limit", ParamLocation.QUERY, Integer.class, null, null, null, List.of());
        JsonObject paramSchema = new JsonObject().put("type", "integer").put("maximum", 100);
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "limit", paramSchema)
                .build();
        Handler<RoutingContext> gate = defaultStrategy()
                .gateFor(op(List.of(limit), Optional.empty()), schemas)
                .orElseThrow();

        // Build a context with query param "limit=500" (violates maximum:100)
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        MultiMap query = MultiMap.caseInsensitiveMultiMap();
        query.add("limit", "500");
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(query);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.<Cookie>of());
        when(ctx.body()).thenReturn(null);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });

        gate.handle(ctx);

        RestValidationException ex = captureFailure(ctx);
        assertFalse(ex.errors().isEmpty());

        ValidationErrorDetail error = ex.errors().get(0);
        assertEquals("maximum", error.type(), "type must be 'maximum' for a maximum violation");
        assertNotNull(error.args(), "args must not be null");
        assertTrue(error.args().containsKey("maximum"), "args must carry 'maximum' key");
        assertEquals(100, ((Number) error.args().get("maximum")).intValue(), "args.maximum must be 100");
        // submitted value "500" must not appear
        boolean leaksValue = ex.errors().stream()
                .anyMatch(e -> (e.detail() != null && e.detail().contains("500"))
                        || (e.path() != null && e.path().contains("500")));
        assertFalse(leaksValue, "submitted value '500' must not appear in any error detail or path");
    }
}
