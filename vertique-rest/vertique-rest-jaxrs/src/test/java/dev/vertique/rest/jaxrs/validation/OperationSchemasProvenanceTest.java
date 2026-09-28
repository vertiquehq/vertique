// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import io.vertx.core.json.JsonObject;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Proves {@code OperationSchemas}' INTERNAL provenance carrier (C-CARRIER): the typed accessor
 * {@code bodySchemaProvenance(Class)} answers only for an instance of the requested type, the
 * two-argument {@code Builder#bodySchema(JsonObject, Object)} rejects either argument being
 * {@code null}, the existing one-argument form always leaves provenance empty (including after an
 * earlier two-argument call on the same builder), and {@code toBuilder()} copies the body, its
 * provenance, and every parameter schema into an independent builder without ever changing the
 * instance it came from.
 *
 * <p>TP-001 and TP-005 of {@code docs/specs/rest-022-application-scoped-openapi/tasks/T003-manifest-carrier.md}.
 */
class OperationSchemasProvenanceTest {

    /** Opaque, test-local provenance token: {@code rest-jaxrs} never inspects it, only stores it. */
    private record Token(String value) {}

    private static final JsonObject SCHEMA = new JsonObject().put("type", "object");
    private static final JsonObject OTHER = new JsonObject().put("type", "string");
    private static final Token TOKEN = new Token("t1");

    @TestFactory
    @DisplayName("The body provenance travels with the body schema")
    Stream<DynamicTest> bodyProvenanceTravelsWithTheBodySchema() {
        return Stream.of(
                dynamicTest("bodySchema(schema, token) carries token as typed provenance", () -> {
                    OperationSchemas built =
                            OperationSchemas.builder().bodySchema(SCHEMA, TOKEN).build();

                    assertSame(SCHEMA, built.bodySchema().orElseThrow());
                    assertSame(TOKEN, built.bodySchemaProvenance(Token.class).orElseThrow());
                    assertSame(TOKEN, built.bodySchemaProvenance(Object.class).orElseThrow());
                    assertTrue(
                            built.bodySchemaProvenance(String.class).isEmpty(),
                            "the typed accessor answers only for an instance of the requested type");
                }),
                dynamicTest("the one-argument form leaves provenance empty for every type", () -> {
                    OperationSchemas built =
                            OperationSchemas.builder().bodySchema(SCHEMA).build();

                    assertSame(SCHEMA, built.bodySchema().orElseThrow());
                    assertTrue(built.bodySchemaProvenance(Token.class).isEmpty());
                    assertTrue(built.bodySchemaProvenance(Object.class).isEmpty());
                    assertTrue(built.bodySchemaProvenance(String.class).isEmpty());
                }),
                dynamicTest(
                        "a one-argument call after an earlier two-argument call replaces the body and clears"
                                + " provenance",
                        () -> {
                            OperationSchemas built = OperationSchemas.builder()
                                    .bodySchema(SCHEMA, TOKEN)
                                    .bodySchema(OTHER)
                                    .build();

                            assertSame(OTHER, built.bodySchema().orElseThrow());
                            assertTrue(built.bodySchemaProvenance(Token.class).isEmpty());
                            assertTrue(built.bodySchemaProvenance(Object.class).isEmpty());
                            assertTrue(built.bodySchemaProvenance(String.class).isEmpty());
                        }),
                dynamicTest(
                        "a two-argument call after an earlier one-argument call sets body and provenance" + " together",
                        () -> {
                            OperationSchemas built = OperationSchemas.builder()
                                    .bodySchema(OTHER)
                                    .bodySchema(SCHEMA, TOKEN)
                                    .build();

                            assertSame(SCHEMA, built.bodySchema().orElseThrow());
                            assertSame(
                                    TOKEN,
                                    built.bodySchemaProvenance(Token.class).orElseThrow());
                        }),
                dynamicTest("an operation with no body has empty provenance for every type", () -> {
                    OperationSchemas built = OperationSchemas.builder().build();

                    assertTrue(built.bodySchema().isEmpty());
                    assertTrue(built.bodySchemaProvenance(Token.class).isEmpty());
                    assertTrue(built.bodySchemaProvenance(Object.class).isEmpty());
                    assertTrue(built.bodySchemaProvenance(String.class).isEmpty());
                }),
                dynamicTest("OperationSchemas.empty() has empty provenance for every type", () -> {
                    OperationSchemas empty = OperationSchemas.empty();

                    assertTrue(empty.bodySchema().isEmpty());
                    assertTrue(empty.bodySchemaProvenance(Token.class).isEmpty());
                    assertTrue(empty.bodySchemaProvenance(Object.class).isEmpty());
                    assertTrue(empty.bodySchemaProvenance(String.class).isEmpty());
                }),
                dynamicTest(
                        "a null provenance in the two-argument form throws NullPointerException naming"
                                + " 'provenance'",
                        () -> {
                            OperationSchemas.Builder builder = OperationSchemas.builder();

                            NullPointerException thrown =
                                    assertThrows(NullPointerException.class, () -> builder.bodySchema(SCHEMA, null));
                            assertEquals("provenance", thrown.getMessage());
                        }),
                dynamicTest(
                        "a null schema in the two-argument form throws NullPointerException naming 'schema'", () -> {
                            OperationSchemas.Builder builder = OperationSchemas.builder();

                            NullPointerException thrown =
                                    assertThrows(NullPointerException.class, () -> builder.bodySchema(null, TOKEN));
                            assertEquals("schema", thrown.getMessage());
                        }),
                dynamicTest("parameter schema lookups are unaffected by body provenance", () -> {
                    JsonObject limitSchema = new JsonObject().put("type", "integer");
                    OperationSchemas built = OperationSchemas.builder()
                            .bodySchema(SCHEMA, TOKEN)
                            .parameterSchema(ParamLocation.QUERY, "limit", limitSchema)
                            .build();

                    assertSame(TOKEN, built.bodySchemaProvenance(Token.class).orElseThrow());
                    assertSame(
                            limitSchema,
                            built.parameterSchema(ParamLocation.QUERY, "limit").orElseThrow());
                }));
    }

    @TestFactory
    @DisplayName("toBuilder() copies the body, its provenance, and the parameter schemas")
    Stream<DynamicTest> toBuilderCopiesBodyProvenanceAndParameters() {
        JsonObject limitSchema = new JsonObject().put("type", "integer");
        JsonObject idSchema = new JsonObject().put("type", "string");
        Token token2 = new Token("t2");
        OperationSchemas original = OperationSchemas.builder()
                .bodySchema(SCHEMA, TOKEN)
                .parameterSchema(ParamLocation.QUERY, "limit", limitSchema)
                .parameterSchema(ParamLocation.PATH, "id", idSchema)
                .build();

        return Stream.of(
                dynamicTest("built unchanged, the copy equals the original's body, provenance, and parameters", () -> {
                    OperationSchemas copy = original.toBuilder().build();

                    assertEquals(SCHEMA, copy.bodySchema().orElseThrow());
                    assertSame(TOKEN, copy.bodySchemaProvenance(Token.class).orElseThrow());
                    assertEquals(
                            limitSchema,
                            copy.parameterSchema(ParamLocation.QUERY, "limit").orElseThrow());
                    assertEquals(
                            idSchema,
                            copy.parameterSchema(ParamLocation.PATH, "id").orElseThrow());
                    assertOriginalUnchanged(original, limitSchema, idSchema);
                }),
                dynamicTest(
                        "built after a one-argument bodySchema, the copy has the new body, empty provenance, and"
                                + " both parameters",
                        () -> {
                            OperationSchemas copy =
                                    original.toBuilder().bodySchema(OTHER).build();

                            assertSame(OTHER, copy.bodySchema().orElseThrow());
                            assertTrue(copy.bodySchemaProvenance(Token.class).isEmpty());
                            assertEquals(
                                    limitSchema,
                                    copy.parameterSchema(ParamLocation.QUERY, "limit")
                                            .orElseThrow());
                            assertEquals(
                                    idSchema,
                                    copy.parameterSchema(ParamLocation.PATH, "id")
                                            .orElseThrow());
                            assertOriginalUnchanged(original, limitSchema, idSchema);
                        }),
                dynamicTest(
                        "built after a two-argument bodySchema, the copy has the new body, the new provenance,"
                                + " and both parameters",
                        () -> {
                            OperationSchemas copy = original.toBuilder()
                                    .bodySchema(OTHER, token2)
                                    .build();

                            assertSame(OTHER, copy.bodySchema().orElseThrow());
                            assertSame(
                                    token2,
                                    copy.bodySchemaProvenance(Token.class).orElseThrow());
                            assertEquals(
                                    limitSchema,
                                    copy.parameterSchema(ParamLocation.QUERY, "limit")
                                            .orElseThrow());
                            assertEquals(
                                    idSchema,
                                    copy.parameterSchema(ParamLocation.PATH, "id")
                                            .orElseThrow());
                            assertOriginalUnchanged(original, limitSchema, idSchema);
                        }),
                dynamicTest(
                        "built after adding a parameter, the copy keeps body and provenance and gains a third"
                                + " parameter",
                        () -> {
                            JsonObject extraSchema = new JsonObject().put("type", "boolean");
                            OperationSchemas copy = original.toBuilder()
                                    .parameterSchema(ParamLocation.QUERY, "extra", extraSchema)
                                    .build();

                            assertEquals(SCHEMA, copy.bodySchema().orElseThrow());
                            assertSame(
                                    TOKEN,
                                    copy.bodySchemaProvenance(Token.class).orElseThrow());
                            assertEquals(
                                    limitSchema,
                                    copy.parameterSchema(ParamLocation.QUERY, "limit")
                                            .orElseThrow());
                            assertEquals(
                                    idSchema,
                                    copy.parameterSchema(ParamLocation.PATH, "id")
                                            .orElseThrow());
                            assertEquals(
                                    extraSchema,
                                    copy.parameterSchema(ParamLocation.QUERY, "extra")
                                            .orElseThrow());
                            assertOriginalUnchanged(original, limitSchema, idSchema);
                        }),
                dynamicTest(
                        "empty().toBuilder() builds an instance with no body, no provenance, and no parameters", () -> {
                            OperationSchemas copy =
                                    OperationSchemas.empty().toBuilder().build();

                            assertTrue(copy.bodySchema().isEmpty());
                            assertTrue(copy.bodySchemaProvenance(Object.class).isEmpty());
                            assertTrue(copy.parameterSchema(ParamLocation.QUERY, "limit")
                                    .isEmpty());
                            assertTrue(copy.parameterSchema(ParamLocation.PATH, "id")
                                    .isEmpty());
                            assertOriginalUnchanged(original, limitSchema, idSchema);
                        }));
    }

    /**
     * Asserts that {@code original} still has body {@link #SCHEMA}, provenance {@link #TOKEN}, and
     * exactly its two parameter schemas — {@code toBuilder()} and later calls on the returned builder
     * must never mutate the instance it came from.
     */
    private static void assertOriginalUnchanged(
            OperationSchemas original, JsonObject limitSchema, JsonObject idSchema) {
        assertSame(SCHEMA, original.bodySchema().orElseThrow());
        assertSame(TOKEN, original.bodySchemaProvenance(Token.class).orElseThrow());
        assertEquals(
                limitSchema,
                original.parameterSchema(ParamLocation.QUERY, "limit").orElseThrow());
        assertEquals(
                idSchema, original.parameterSchema(ParamLocation.PATH, "id").orElseThrow());
        assertTrue(original.parameterSchema(ParamLocation.QUERY, "extra").isEmpty());
    }
}
