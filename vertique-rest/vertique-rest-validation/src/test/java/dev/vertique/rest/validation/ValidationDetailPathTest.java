// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the {@code path} a concrete validation detail names: the full instance location wherever the
 * schema declares every segment of it — through {@code allOf}/{@code anyOf}/{@code oneOf} branches,
 * {@code prefixItems} tuple positions and property names the validator reports RFC 6901-escaped and
 * percent-encoded — and the containing location, cut at the first segment the schema does not
 * declare, wherever the client chose the key (vertiquehq/vertique-dev#598). It also pins the
 * declared constraint value a concrete detail names in its {@code args} and message at those same
 * composed, referenced, tuple and escaped positions.
 *
 * <p>Each path asserted is the instance location vertx-json-schema 5.1.6 reports for the failing
 * keyword, spelled exactly as the validator spells it.
 */
class ValidationDetailPathTest {

    /** A distinctive client-chosen key: its absence from the response is provable. */
    private static final String CLIENT_KEY = "CLIENT-KEY-598-PATH-" + "q".repeat(300);

    /** An object declaring {@code name} (at most three characters) and typed string extras. */
    private static JsonObject inner() {
        return new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("name", maxLength(3)))
                .put("additionalProperties", new JsonObject().put("type", "string"));
    }

    private static JsonObject maxLength(int max) {
        return new JsonObject().put("type", "string").put("maxLength", max);
    }

    private static JsonObject object(JsonObject properties) {
        return new JsonObject().put("type", "object").put("properties", properties);
    }

    @Test
    @DisplayName("A declared plain nested location is named unchanged")
    void plainNestedPathIsUnchanged() throws Exception {
        JsonObject schema = object(new JsonObject().put("pet", inner()));

        Outcome outcome = validate(schema, new JsonObject().put("pet", new JsonObject().put("name", "toolong")));

        assertEquals(Set.of("#/pet/name"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("A violation inside a nullable (anyOf null | $ref) nested object names the field")
    void nullableNestedObjectKeepsTheFieldName() throws Exception {
        JsonObject schema = object(new JsonObject()
                        .put(
                                "pet",
                                new JsonObject()
                                        .put(
                                                "anyOf",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "null"))
                                                        .add(new JsonObject().put("$ref", "#/$defs/Inner")))))
                .put("$defs", new JsonObject().put("Inner", inner()));

        Outcome outcome = validate(schema, new JsonObject().put("pet", new JsonObject().put("name", "toolong")));

        assertEquals(Set.of("#/pet/name"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("A violation inside an explicit oneOf branch names the field")
    void oneOfBranchKeepsTheFieldName() throws Exception {
        JsonObject schema = object(new JsonObject()
                .put(
                        "pet",
                        new JsonObject()
                                .put(
                                        "oneOf",
                                        new JsonArray()
                                                .add(new JsonObject().put("type", "integer"))
                                                .add(new JsonObject().put("allOf", new JsonArray().add(inner()))))));

        Outcome outcome = validate(schema, new JsonObject().put("pet", new JsonObject().put("name", "toolong")));

        assertEquals(Set.of("#/pet/name"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("A violation at a prefixItems tuple position names the index")
    void prefixItemsIndexIsKept() throws Exception {
        JsonObject schema = object(new JsonObject()
                .put(
                        "t",
                        new JsonObject()
                                .put("type", "array")
                                .put(
                                        "prefixItems",
                                        new JsonArray()
                                                .add(maxLength(2))
                                                .add(new JsonObject().put("type", "integer")))));

        Outcome outcome = validate(
                schema, new JsonObject().put("t", new JsonArray().add("long").add(1)));

        assertEquals(Set.of("#/t/0"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("Declared names with a space, a non-ASCII letter, a slash and a tilde are named as reported")
    void escapedDeclaredNamesAreKept() throws Exception {
        JsonObject schema = object(new JsonObject()
                .put("a b", maxLength(1))
                .put("é", maxLength(1))
                .put("a/b", maxLength(1))
                .put("a~c", maxLength(1)));

        Outcome outcome = validate(
                schema,
                new JsonObject()
                        .put("a b", "xx")
                        .put("é", "xx")
                        .put("a/b", "xx")
                        .put("a~c", "xx"));

        assertEquals(Set.of("#/a%20b", "#/%C3%A9", "#/a~1b", "#/a~0c"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("An undeclared client key under a composed object is cut back and never echoed")
    void undeclaredKeyUnderCompositionIsCutAndNeverEchoed() throws Exception {
        // allOf of a single $ref: the extra's type failure is the only one, so the cut is what is named.
        JsonObject schema = object(new JsonObject()
                        .put(
                                "pet",
                                new JsonObject()
                                        .put(
                                                "allOf",
                                                new JsonArray().add(new JsonObject().put("$ref", "#/$defs/Inner")))))
                .put("$defs", new JsonObject().put("Inner", inner()));

        Outcome outcome = validate(
                schema,
                new JsonObject().put("pet", new JsonObject().put("name", "a").put(CLIENT_KEY, 5)));

        assertAll(
                () -> assertEquals(Set.of("#/pet"), outcome.pathsFor("type"), outcome.json),
                () -> assertFalse(
                        outcome.json.contains(CLIENT_KEY), "the client key must not be echoed: " + outcome.json));
    }

    @Test
    @DisplayName("A client key that decodes to a declared name, or is escaped like one, is still cut back")
    void clientKeysSpelledLikeDeclaredNamesAreCut() throws Exception {
        String percentSpelled = "%6Eame" + CLIENT_KEY;
        JsonObject schema = object(new JsonObject().put("pet", inner()));
        JsonObject pet = new JsonObject()
                .put("name", "a")
                .put("%6Eame", 5)
                .put("na%6De", 5)
                .put("name~0", 5)
                .put(percentSpelled, 5);

        Outcome outcome = validate(schema, new JsonObject().put("pet", pet));

        assertAll(
                () -> assertEquals(Set.of("#/pet"), outcome.pathsFor("type"), outcome.json),
                () -> assertFalse(
                        outcome.json.contains(CLIENT_KEY), "the client key must not be echoed: " + outcome.json),
                () -> assertFalse(outcome.json.contains("6Eame"), "the client key must not be echoed: " + outcome.json),
                () -> assertFalse(outcome.json.contains("na%"), "the client key must not be echoed: " + outcome.json));
    }

    @Test
    @DisplayName("An all-digit client key under a map-or-list composition is cut back; a real index is kept")
    void allDigitClientKeyUnderMapOrListCompositionIsCut() throws Exception {
        // An application-authored oneOf of a map and a list: the list branch offers `items`, so an
        // all-digit key the client chose for the map branch looks like an index.
        String digitKey = "9".repeat(400);
        JsonObject someMap = new JsonObject().put("type", "object").put("additionalProperties", maxLength(1));
        JsonObject someList = new JsonObject().put("type", "array").put("items", maxLength(1));
        JsonObject schema = object(new JsonObject()
                .put(
                        "m",
                        new JsonObject()
                                .put("oneOf", new JsonArray().add(someMap).add(someList))));

        Outcome mapOutcome = validate(
                schema,
                new JsonObject()
                        .put(
                                "m",
                                new JsonObject()
                                        .put(digitKey, "xx")
                                        .put("0123", "xx")
                                        .put("12345678901", "xx")));
        JsonArray list = new JsonArray();
        for (int index = 0; index < 12; index++) {
            list.add("x");
        }
        list.add("xx");
        Outcome listOutcome = validate(schema, new JsonObject().put("m", list));

        assertAll(
                () -> assertFalse(
                        mapOutcome.json.contains(digitKey), "the client key must not be echoed: " + mapOutcome.json),
                () -> assertFalse(mapOutcome.json.contains("0123"), "a zero-led key is no index: " + mapOutcome.json),
                () -> assertFalse(
                        mapOutcome.json.contains("12345678901"), "an 11-digit key is no index: " + mapOutcome.json),
                () -> assertEquals(Set.of("#/m"), mapOutcome.pathsFor("maxLength"), mapOutcome.json),
                () -> assertEquals(Set.of("#/m/12"), listOutcome.pathsFor("maxLength"), listOutcome.json));
    }

    @Test
    @DisplayName("An index past a closed prefixItems tuple is not named")
    void indexPastClosedTupleIsCut() throws Exception {
        JsonObject tuple = new JsonObject()
                .put("type", "array")
                .put("prefixItems", new JsonArray().add(maxLength(1)))
                .put("items", false);
        JsonObject schema = object(new JsonObject()
                .put(
                        "m",
                        new JsonObject()
                                .put(
                                        "anyOf",
                                        new JsonArray()
                                                .add(tuple)
                                                .add(new JsonObject()
                                                        .put("type", "object")
                                                        .put("additionalProperties", maxLength(1))))));

        Outcome outcome = validate(schema, new JsonObject().put("m", new JsonObject().put("7", "xx")));

        assertEquals(Set.of("#/m"), outcome.pathsFor("maxLength"), outcome.json);
    }

    @Test
    @DisplayName("A detail inside a composed, referenced, tuple or escaped position names the declared value")
    void constraintValueIsNamedInsideComposedAndTuplePositions() throws Exception {
        JsonObject schema = object(new JsonObject()
                        .put(
                                "p",
                                new JsonObject()
                                        .put(
                                                "oneOf",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "integer"))
                                                        .add(maxLength(3))))
                        .put(
                                "pet",
                                new JsonObject()
                                        .put(
                                                "anyOf",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "null"))
                                                        .add(new JsonObject().put("$ref", "#/$defs/Inner"))))
                        .put(
                                "t",
                                new JsonObject()
                                        .put("type", "array")
                                        .put(
                                                "prefixItems",
                                                new JsonArray()
                                                        .add(maxLength(2))
                                                        .add(new JsonObject()
                                                                .put("type", "integer")
                                                                .put("minimum", 5))))
                        .put(
                                "code",
                                new JsonObject()
                                        .put(
                                                "allOf",
                                                new JsonArray()
                                                        .add(new JsonObject()
                                                                .put("type", "string")
                                                                .put("pattern", "^[a-z]+$"))))
                        .put("a/b", maxLength(1)))
                .put("$defs", new JsonObject().put("Inner", inner()));

        Outcome outcome = validate(
                schema,
                new JsonObject()
                        .put("p", "toolong")
                        .put("pet", new JsonObject().put("name", "toolong"))
                        .put("t", new JsonArray().add("long").add(1))
                        .put("code", "ABC")
                        .put("a/b", "xx"));

        assertAll(
                () -> outcome.assertConstraint("#/p", "maxLength", 3, "must have a maximum length of 3"),
                () -> outcome.assertConstraint("#/pet/name", "maxLength", 3, "must have a maximum length of 3"),
                () -> outcome.assertConstraint("#/t/0", "maxLength", 2, "must have a maximum length of 2"),
                () -> outcome.assertConstraint("#/t/1", "minimum", 5, "must be at least 5"),
                () -> outcome.assertConstraint("#/code", "pattern", "^[a-z]+$", "must match pattern: ^[a-z]+$"),
                () -> outcome.assertConstraint("#/a~1b", "maxLength", 1, "must have a maximum length of 1"),
                () -> assertFalse(outcome.json.contains("of true"), outcome.json));
    }

    @Test
    @DisplayName("A type detail names the declared type, or the declared list of types, at every position")
    void typeDetailNamesTheDeclaredType() throws Exception {
        JsonObject pet = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("age", new JsonObject().put("type", "integer")));
        JsonObject schema = object(new JsonObject()
                        .put("plain", new JsonObject().put("type", "string"))
                        .put(
                                "either",
                                new JsonObject()
                                        .put(
                                                "type",
                                                new JsonArray().add("string").add("null")))
                        .put(
                                "pet",
                                new JsonObject()
                                        .put(
                                                "anyOf",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "null"))
                                                        .add(new JsonObject().put("$ref", "#/$defs/Pet"))))
                        .put(
                                "owner",
                                new JsonObject()
                                        .put(
                                                "type",
                                                new JsonArray().add("object").add("null"))
                                        .put(
                                                "properties",
                                                new JsonObject()
                                                        .put("active", new JsonObject().put("type", "boolean"))))
                        .put(
                                "p",
                                new JsonObject()
                                        .put(
                                                "oneOf",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "integer"))
                                                        .add(maxLength(3))))
                        .put(
                                "t",
                                new JsonObject()
                                        .put("type", "array")
                                        .put(
                                                "prefixItems",
                                                new JsonArray()
                                                        .add(new JsonObject().put("type", "string"))
                                                        .add(new JsonObject().put("type", "integer")))))
                .put("$defs", new JsonObject().put("Pet", pet));

        Outcome outcome = validate(
                schema,
                new JsonObject()
                        .put("plain", 7)
                        .put("either", 5)
                        .put("pet", new JsonObject().put("age", "old"))
                        .put("owner", new JsonObject().put("active", "yes"))
                        .put("p", true)
                        .put("t", new JsonArray().add(1).add("x")));

        assertAll(
                () -> outcome.assertAnyConstraint("#/plain", "type", "string", "must be of type: string"),
                () -> outcome.assertAnyConstraint(
                        "#/either", "type", List.of("string", "null"), "must be of type: string or null"),
                () -> outcome.assertAnyConstraint("#/pet/age", "type", "integer", "must be of type: integer"),
                () -> outcome.assertAnyConstraint("#/owner/active", "type", "boolean", "must be of type: boolean"),
                () -> outcome.assertAnyConstraint("#/p", "type", "integer", "must be of type: integer"),
                () -> outcome.assertAnyConstraint("#/p", "type", "string", "must be of type: string"),
                () -> outcome.assertAnyConstraint("#/t/0", "type", "string", "must be of type: string"),
                () -> outcome.assertAnyConstraint("#/t/1", "type", "integer", "must be of type: integer"),
                () -> assertTrue(outcome.json.contains("\"args\":{\"type\":[\"string\",\"null\"]}"), outcome.json),
                () -> assertFalse(outcome.json.contains("of type: true"), outcome.json),
                () -> assertFalse(outcome.json.contains("\"type\":true"), outcome.json));
    }

    @Test
    @DisplayName("A type detail renders three or more declared types as a list ending in 'or'")
    void typeDetailRendersALongerList() throws Exception {
        JsonObject schema = object(new JsonObject()
                .put(
                        "v",
                        new JsonObject()
                                .put(
                                        "type",
                                        new JsonArray()
                                                .add("string")
                                                .add("integer")
                                                .add("null"))));

        Outcome outcome = validate(schema, new JsonObject().put("v", true));

        outcome.assertAnyConstraint(
                "#/v", "type", List.of("string", "integer", "null"), "must be of type: string, integer or null");
    }

    // --- Harness ---

    private record Outcome(List<ValidationErrorDetail> errors, String json) {

        /** Asserts that some detail of {@code keyword} at {@code path} carries the given value and message. */
        void assertAnyConstraint(String path, String keyword, Object value, String message) {
            List<ValidationErrorDetail> atPath = errors.stream()
                    .filter(error -> keyword.equals(error.type()) && path.equals(error.path()))
                    .toList();
            assertTrue(
                    atPath.stream()
                            .anyMatch(detail ->
                                    Map.of(keyword, value).equals(detail.args()) && message.equals(detail.detail())),
                    "no " + keyword + " detail at " + path + " with " + value + " and '" + message + "': " + json);
        }

        void assertConstraint(String path, String keyword, Object value, String message) {
            ValidationErrorDetail detail = errors.stream()
                    .filter(error -> keyword.equals(error.type()) && path.equals(error.path()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + keyword + " detail at " + path + ": " + json));
            assertEquals(Map.of(keyword, value), detail.args(), json);
            assertEquals(message, detail.detail(), json);
        }

        Set<String> pathsFor(String keyword) {
            return errors.stream()
                    .filter(error -> keyword.equals(error.type()))
                    .map(ValidationErrorDetail::path)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Outcome validate(JsonObject bodySchema, JsonObject body) throws Exception {
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(bodySchema).build();
        WebValidationStrategy strategy = new WebValidationStrategy(
                JaxRsConfig.builder().validationMode("aggregate").build());
        var gate = strategy.gateFor(
                        StubDescriptors.builder()
                                .httpMethod("POST")
                                .routeTemplate("/things")
                                .parameters(List.of())
                                .body(new BodyDescriptor(Object.class, null, List.of()))
                                .build(),
                        schemas)
                .orElseThrow();
        RoutingContext ctx = mockContext(jsonBody(body));
        gate.handle(ctx);
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        RestValidationException ex = assertInstanceOf(RestValidationException.class, captor.getValue());
        String json = new ObjectMapper().writeValueAsString(ValidationProblemDetail.of(ex.getMessage(), ex.errors()));
        return new Outcome(ex.errors(), json);
    }

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
        RequestBody body = mock(RequestBody.class);
        when(body.asJsonObject()).thenReturn(json);
        when(body.asJsonArray()).thenReturn(null);
        when(body.buffer()).thenReturn(json.toBuffer());
        return body;
    }
}
