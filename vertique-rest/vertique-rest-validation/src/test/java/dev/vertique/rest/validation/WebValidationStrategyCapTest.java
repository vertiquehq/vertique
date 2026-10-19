// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proves that the schema gate lists at most 100 failures and one truncation entry across parameters,
 * the body and file parts, and that it stops converting engine results once it holds 101.
 *
 * <p>The gate is the real {@link WebValidationStrategy} compiling real schemas; only the routing
 * context it reads a request from is a stand-in. A parameter violation is a query value that is not an
 * integer; a body violation is an array element that is not an integer; a file-part violation is an
 * upload over a size limit.
 */
class WebValidationStrategyCapTest {

    /** The entry that closes a list the cap cut short: its detail and type, and no other member. */
    private static final ValidationErrorDetail TRUNCATED =
            new ValidationErrorDetail(null, null, "further violations omitted", null, "truncated", null);

    private final WebValidationStrategy strategy =
            new WebValidationStrategy(JaxRsConfig.builder().build());

    // --- Tests ---

    @Test
    @DisplayName("the failure list is capped at 100 entries and one truncation entry across locations")
    void capsTheFailureListAcrossLocations() {
        // Given a body of 250 violations, then 60 invalid query values with 60 body violations, then exactly
        // 100 failures, and the boundary bodies of 99, 100 and 101 violations
        List<String> bodyOf250 = run(0, 250, 0);
        List<String> queryAndBody = run(60, 60, 0);
        List<String> exactlyOneHundred = run(40, 60, 0);
        List<String> mixedOneHundredAndOne = run(41, 60, 0);
        List<String> body99 = run(0, 99, 0);
        List<String> body100 = run(0, 100, 0);
        List<String> body101 = run(0, 101, 0);
        List<String> queryAndFiles = run(60, 0, 60);

        assertAll(
                // Then 250 body violations list the first 100 in validation order and the marker
                () -> assertEquals(expected(0, 100, 0, true), bodyOf250, "250 body violations"),
                // And 60 query and 60 body violations list the 60 query ones, then the first 40 body ones, then the
                // marker
                () -> assertEquals(expected(60, 40, 0, true), queryAndBody, "60 query and 60 body violations"),
                // And exactly 100 failures list all of them with no marker
                () -> assertEquals(expected(40, 60, 0, false), exactlyOneHundred, "exactly 100 failures"),
                // And 101 failures across locations are cut at the 100th
                () -> assertEquals(expected(41, 59, 0, true), mixedOneHundredAndOne, "101 failures"),
                // And the boundary bodies: 99 and 100 carry no marker, 101 is cut at 100
                () -> assertEquals(expected(0, 99, 0, false), body99, "99 body violations"),
                () -> assertEquals(expected(0, 100, 0, false), body100, "100 body violations"),
                () -> assertEquals(expected(0, 100, 0, true), body101, "101 body violations"),
                // And file-part failures count toward the same cap
                () -> assertEquals(expected(60, 0, 40, true), queryAndFiles, "60 query and 60 file-part failures"));
    }

    @Test
    @DisplayName("the marker is the only entry without a location and carries no other member")
    void theMarkerCarriesOnlyItsDetailAndType() {
        // Given a body of 250 violations
        List<ValidationErrorDetail> errors = failures(0, 250, 0);

        // When the marker is inspected
        ValidationErrorDetail marker = errors.get(errors.size() - 1);

        // Then it equals the shared shape member for member
        assertEquals(TRUNCATED, marker);
        assertEquals(
                1, errors.stream().filter(e -> "truncated".equals(e.type())).count());
    }

    @Test
    @DisplayName("the walk of the engine's result stops at the 101st concrete violation")
    void stopsConvertingAtTheOneHundredAndFirstViolation() {
        // Given a body schema that counts every read of its root. Converting a violation reads the schema's root
        // (to resolve its constraint and its declared location), so the reads count the violations the conversion
        // touched. The validator itself is compiled from a copy and never reads the counted root
        int reads101 = rootReadsWhileValidating(notNumbers(101), true);
        int reads250 = rootReadsWhileValidating(notNumbers(250), true);
        int reads20000 = rootReadsWhileValidating(notNumbers(20_000), true);
        // And, as a control, bodies that conform: nothing is converted, however long they are
        int validReads250 = rootReadsWhileValidating(integers(250), false);
        int validReads20000 = rootReadsWhileValidating(integers(20_000), false);

        // Then the probe is live, and a result of 20,000 violations is converted no further than one of 250
        assertTrue(reads250 > 0, "the probe must observe the conversion");
        assertEquals(reads250, reads20000, "reads of the schema root for 250 and 20,000 violations");
        assertEquals(reads101, reads250, "reads of the schema root for 101 and 250 violations");
        // And none of those reads scales with the length of the body for any other reason
        assertEquals(validReads250, validReads20000, "reads of the schema root for conforming bodies");
    }

    @Test
    @DisplayName("a pattern-input rejection after the list is full is not listed, and one within the cap is")
    void aLaterPatternInputRejectionIsListedOnlyWithinTheCap() {
        // Given earlier invalid query parameters, then a last parameter whose value is longer than the pattern
        // input bound, so that validating it would raise a pattern-input rejection
        List<String> withinTheCap = patternParamRun(99);
        List<String> exactlyFull = patternParamRun(100);
        List<String> alreadyPastTheCap = patternParamRun(101);

        // Then the rejection is listed when the list holds 99 and takes it to 100
        List<String> expectedWithin = new ArrayList<>(expected(99, 0, 0, false));
        expectedWithin.add("query:p");
        // And it is not listed when the list already holds 100 or more: the first 100 and the marker remain
        assertAll(
                () -> assertEquals(expectedWithin, withinTheCap, "99 earlier failures"),
                () -> assertEquals(expected(100, 0, 0, true), exactlyFull, "100 earlier failures"),
                () -> assertEquals(expected(100, 0, 0, true), alreadyPastTheCap, "101 earlier failures"));
    }

    // --- Rows ---

    /** The entries of the failure a request with the given numbers of violations draws, described. */
    private List<String> run(int queryViolations, int bodyViolations, int fileViolations) {
        return failures(queryViolations, bodyViolations, fileViolations).stream()
                .map(WebValidationStrategyCapTest::describe)
                .toList();
    }

    /** What the gate must list: the query entries, then the body ones, then the file ones, then the marker. */
    private static List<String> expected(int query, int body, int file, boolean marker) {
        List<String> entries = new ArrayList<>();
        IntStream.range(0, query).forEach(i -> entries.add("query:q" + i));
        IntStream.range(0, body).forEach(i -> entries.add("body:/" + i));
        IntStream.range(0, file).forEach(i -> entries.add("file:-"));
        if (marker) {
            entries.add("truncated");
        }
        return entries;
    }

    private static String describe(ValidationErrorDetail entry) {
        if (TRUNCATED.equals(entry)) {
            return "truncated";
        }
        String where =
                entry.pointer() != null ? entry.pointer() : (entry.parameter() != null ? entry.parameter() : "-");
        return entry.location() + ":" + where;
    }

    private List<ValidationErrorDetail> failures(int queryViolations, int bodyViolations, int fileViolations) {
        OperationSchemas.Builder schemas = OperationSchemas.builder();
        List<ParamDescriptor> params = new ArrayList<>();
        MultiMap query = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < queryViolations; i++) {
            String name = "q" + i;
            params.add(new ParamDescriptor(name, ParamLocation.QUERY, Integer.class, null, null, null, List.of()));
            schemas.parameterSchema(ParamLocation.QUERY, name, new JsonObject().put("type", "integer"));
            query.add(name, "not-a-number");
        }
        StubDescriptors.Builder descriptor = StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .parameters(params);
        RequestBody body = null;
        if (bodyViolations > 0) {
            schemas.bodySchema(arrayOfIntegers());
            descriptor.body(new BodyDescriptor(Object.class, null, List.of()));
            body = jsonArrayBody(notNumbers(bodyViolations));
        }
        if (fileViolations > 0) {
            descriptor
                    .consumes(List.of("multipart/form-data"))
                    .fileParts(List.of(new FilePartDescriptor(null, List.of(), 10)));
        }
        Handler<RoutingContext> gate =
                strategy.gateFor(descriptor.build(), schemas.build()).orElseThrow();

        RoutingContext ctx = context(query, body, uploads(fileViolations));
        gate.handle(ctx);
        return captureFailure(ctx).errors();
    }

    /** The entries of a request with the given earlier invalid query parameters and a last over-length one. */
    private List<String> patternParamRun(int earlierViolations) {
        OperationSchemas.Builder schemas = OperationSchemas.builder();
        List<ParamDescriptor> params = new ArrayList<>();
        MultiMap query = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < earlierViolations; i++) {
            String name = "q" + i;
            params.add(new ParamDescriptor(name, ParamLocation.QUERY, Integer.class, null, null, null, List.of()));
            schemas.parameterSchema(ParamLocation.QUERY, name, new JsonObject().put("type", "integer"));
            query.add(name, "not-a-number");
        }
        params.add(new ParamDescriptor("p", ParamLocation.QUERY, String.class, null, null, null, List.of()));
        schemas.parameterSchema(
                ParamLocation.QUERY, "p", new JsonObject().put("type", "string").put("pattern", "^[a-z]+$"));
        // longer than the default bound of 4096 characters
        query.add("p", "a".repeat(5_000));
        JaxRsOperationDescriptor descriptor = StubDescriptors.builder()
                .httpMethod("GET")
                .routeTemplate("/things")
                .parameters(params)
                .build();
        Handler<RoutingContext> gate =
                strategy.gateFor(descriptor, schemas.build()).orElseThrow();

        RoutingContext ctx = context(query, null, List.of());
        gate.handle(ctx);
        return captureFailure(ctx).errors().stream()
                .map(WebValidationStrategyCapTest::describe)
                .toList();
    }

    private int rootReadsWhileValidating(JsonArray body, boolean expectFailure) {
        CountingSchema schema = new CountingSchema(arrayOfIntegers().getMap());
        OperationSchemas schemas = OperationSchemas.builder().bodySchema(schema).build();
        JaxRsOperationDescriptor descriptor = StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .body(new BodyDescriptor(Object.class, null, List.of()))
                .build();
        Handler<RoutingContext> gate = strategy.gateFor(descriptor, schemas).orElseThrow();
        RoutingContext ctx = context(MultiMap.caseInsensitiveMultiMap(), jsonArrayBody(body), List.of());

        schema.reads = 0;
        gate.handle(ctx);

        if (expectFailure) {
            captureFailure(ctx);
        } else {
            verify(ctx).next();
        }
        return schema.reads;
    }

    /** A body schema that counts how often its root is read. */
    private static final class CountingSchema extends JsonObject {
        private int reads;

        CountingSchema(Map<String, Object> map) {
            super(new LinkedHashMap<>(map));
        }

        @Override
        public Object getValue(String key) {
            reads++;
            return super.getValue(key);
        }
    }

    // --- Fixtures ---

    private static JsonObject arrayOfIntegers() {
        return new JsonObject().put("type", "array").put("items", new JsonObject().put("type", "integer"));
    }

    private static JsonArray integers(int count) {
        JsonArray values = new JsonArray();
        IntStream.range(0, count).forEach(values::add);
        return values;
    }

    private static JsonArray notNumbers(int count) {
        JsonArray values = new JsonArray();
        IntStream.range(0, count).forEach(i -> values.add("not-a-number-" + i));
        return values;
    }

    private static RequestBody jsonArrayBody(JsonArray json) {
        Buffer raw = json.toBuffer();
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(raw);
        when(body.asJsonObject()).thenAnswer(inv -> {
            Object decoded = Json.decodeValue(raw);
            return decoded instanceof JsonObject object ? object : null;
        });
        when(body.asJsonArray()).thenAnswer(inv -> {
            Object decoded = Json.decodeValue(raw);
            return decoded instanceof JsonArray array ? array : null;
        });
        return body;
    }

    private static List<FileUpload> uploads(int count) {
        List<FileUpload> uploads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            FileUpload upload = mock(FileUpload.class);
            when(upload.name()).thenReturn("f" + i);
            when(upload.fileName()).thenReturn("f" + i + ".bin");
            when(upload.uploadedFileName()).thenReturn("target/f" + i + ".upload");
            when(upload.contentType()).thenReturn("application/octet-stream");
            when(upload.size()).thenReturn(100L);
            uploads.add(upload);
        }
        return uploads;
    }

    private static RoutingContext context(MultiMap query, RequestBody body, List<FileUpload> uploads) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(query);
        when(ctx.body()).thenReturn(body);
        when(ctx.fileUploads()).thenReturn(uploads);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.<Cookie>of());
        when(request.formAttributes()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        return assertInstanceOf(RestValidationException.class, captor.getValue());
    }
}
