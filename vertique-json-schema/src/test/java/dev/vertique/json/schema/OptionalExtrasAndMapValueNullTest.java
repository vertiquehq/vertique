// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rest-023 T002 ({@code D002}). An any-setter extras value of declared type {@code Optional<T>} must
 * render {@code T}'s own schema, marked nullable — not a bare non-nullable object — so an explicit
 * {@code null} binds successfully (closing N14n, the false reject) and a non-null value violating
 * {@code T}'s own constraint is rejected (closing N14, the previously undescribed constraint gap).
 * {@code evidence/probe-report-327531b4.md} § N14 and § N14n measure both gaps against {@code main}.
 *
 * <p>Fixture: an any-setter extras value of declared type {@code Optional<Plain>}, where {@code Plain}
 * carries its own {@code @Size(max = 3)} constraint on {@code name}. A companion control — the same
 * shape with a non-{@code Optional} extras value type — proves the fix is scoped to the declared-{@code
 * Optional} position: it must stay non-nullable today and every day after this task lands.
 */
class OptionalExtrasAndMapValueNullTest {

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /**
     * Generates a type's input-direction document under the {@code vertique} profile.
     *
     * @param type the body type
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same
     * schema whether the generator inlined the value type or shared it under {@code $defs}.
     *
     * @param document the whole document, which owns the definitions
     * @param node     the node that may be a reference
     * @return the referenced schema, or {@code node} when it is not a local reference
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    /**
     * @param node a candidate branch of a possibly-nullable schema
     * @return whether the branch is exactly the null-type marker {@code {"type":"null"}}
     */
    private static boolean isNullMarker(JsonNode node) {
        return "null".equals(node.path("type").asText(null));
    }

    /**
     * Determines whether a schema is marked nullable, in either rendering {@link
     * InputPropertyDescriber#applyNullability} produces: an {@code anyOf} including the null-type
     * marker, or a {@code type} array containing {@code "null"}.
     *
     * @param schema the extras value's own schema, as published (not yet {@code $ref}-resolved)
     * @return whether the schema is marked nullable
     */
    private static boolean isMarkedNullable(JsonNode schema) {
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray()) {
            for (JsonNode branch : anyOf) {
                if (isNullMarker(branch)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            for (JsonNode entry : type) {
                if ("null".equals(entry.asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the non-null branch of a schema — the {@code anyOf} member that is not the null-type
     * marker, resolved through a {@code $ref} where present, or the schema itself (resolved) when it
     * carries no {@code anyOf} at all.
     *
     * @param document the whole document, used to resolve a {@code $ref}
     * @param schema   the extras value's own schema, as published
     * @return the non-null branch, {@code $ref}-resolved
     */
    private static JsonNode nonNullBranch(JsonNode document, JsonNode schema) {
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray()) {
            for (JsonNode branch : anyOf) {
                if (!isNullMarker(branch)) {
                    return resolve(document, branch);
                }
            }
        }
        return resolve(document, schema);
    }

    /**
     * Recursively searches a schema subtree for any {@code type} keyword mentioning {@code "null"},
     * whether as a bare string or inside a {@code type} array.
     *
     * @param node the subtree to search
     * @return whether {@code "null"} appears as a {@code type} value anywhere in the subtree
     */
    private static boolean containsNullType(JsonNode node) {
        if (node.isObject()) {
            for (Entry<String, JsonNode> entry : node.properties()) {
                if (entry.getKey().equals("type")) {
                    JsonNode value = entry.getValue();
                    if ("null".equals(value.asText(null))) {
                        return true;
                    }
                    if (value.isArray()) {
                        for (JsonNode element : value) {
                            if ("null".equals(element.asText(null))) {
                                return true;
                            }
                        }
                    }
                }
                if (containsNullType(entry.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsNullType(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("An Optional-typed extras value describes T's own schema, marked nullable")
    void optionalTypedExtrasValueDescribesTsOwnSchemaMarkedNullable() {
        JsonNode document = inputDocument(OptionalValuedExtrasHolder.class);
        JsonNode extras = document.path("additionalProperties");
        JsonNode nonNull = nonNullBranch(document, extras);

        assertAll(
                () -> assertTrue(
                        isMarkedNullable(extras),
                        "N14n: an Optional<Plain> extras value must be marked nullable — either an anyOf"
                                + " including {\"type\":\"null\"}, or a type array containing \"null\" — so an"
                                + " explicit null binds successfully, matching Jackson's own Optional.empty()"
                                + " mapping; extras schema: " + extras + "; document: " + document),
                () -> assertEquals(
                        3,
                        nonNull.path("properties")
                                .path("name")
                                .path("maxLength")
                                .asInt(-1),
                        "N14: the non-null branch must describe Plain's own schema, including name's own"
                                + " maxLength: 3 — the constraint gap this task closes; non-null branch: "
                                + nonNull + "; extras schema: " + extras + "; document: " + document));
    }

    @Test
    @DisplayName("The non-null branch carries maxLength: 3")
    void nonNullBranchCarriesMaxLength() {
        JsonNode document = inputDocument(OptionalValuedExtrasHolder.class);
        JsonNode extras = document.path("additionalProperties");
        JsonNode nonNull = nonNullBranch(document, extras);

        assertEquals(
                3,
                nonNull.path("properties").path("name").path("maxLength").asInt(-1),
                "the non-null branch of an Optional<Plain> extras value must carry Plain's own maxLength: 3;"
                        + " non-null branch: " + nonNull + "; document: " + document);
    }

    @Test
    @DisplayName("A control non-Optional extras value stays non-nullable and still carries maxLength: 3")
    void controlNonOptionalExtrasValueStaysNonNullable() {
        JsonNode document = inputDocument(PlainValuedExtrasHolder.class);
        JsonNode extras = document.path("additionalProperties");
        JsonNode resolved = resolve(document, extras);

        assertAll(
                () -> assertFalse(
                        isMarkedNullable(extras),
                        "sensitivity proof: a non-Optional extras value must not be marked nullable by this"
                                + " task's own fix; extras schema: " + extras + "; document: " + document),
                () -> assertFalse(
                        containsNullType(extras),
                        "sensitivity proof: no null type keyword may appear anywhere in a non-Optional"
                                + " extras value's schema; extras schema: " + extras + "; document: " + document),
                () -> assertEquals(
                        3,
                        resolved.path("properties")
                                .path("name")
                                .path("maxLength")
                                .asInt(-1),
                        "the control must still carry Plain's own maxLength: 3 — this control must be GREEN"
                                + " today, before this task's own fix lands; resolved: " + resolved
                                + "; document: " + document));
    }

    @Test
    @DisplayName("An Optional<Object> extras value stays an open position, exactly like Map<String, Object>")
    void optionalOfUnconstrainedValueStaysAnOpenPosition() {
        JsonNode optionalDocument = inputDocument(OptionalObjectValuedExtrasHolder.class);
        JsonNode plainDocument = inputDocument(ObjectValuedExtrasHolder.class);
        JsonNode optionalExtras = optionalDocument.path("additionalProperties");
        JsonNode plainExtras = plainDocument.path("additionalProperties");

        assertAll(
                () -> assertEquals(
                        plainExtras,
                        optionalExtras,
                        "an Optional<Object> extras value must render additionalProperties identically to a"
                                + " non-Optional Object extras value — the renderer's unconstrained-T guard"
                                + " (ValuePositionRenderer#renderValueSchema) returns an open position before"
                                + " Object is ever wrapped or unwrapped by Optional; optional extras: "
                                + optionalExtras + "; plain extras: " + plainExtras),
                () -> assertEquals(
                        "{}",
                        optionalExtras.toString(),
                        "an open position must render as the canonical empty object schema {}; optional" + " extras: "
                                + optionalExtras + "; document: " + optionalDocument),
                () -> assertFalse(
                        isMarkedNullable(optionalExtras),
                        "the unconstrained-T guard returns before the nullability step, so no null marker /"
                                + " nullable rendering may appear even though the declared position is"
                                + " Optional<Object>; optional extras: " + optionalExtras + "; document: "
                                + optionalDocument),
                () -> assertFalse(
                        containsNullType(optionalExtras),
                        "no null type keyword may appear anywhere in an Optional<Object> extras value's"
                                + " schema; optional extras: " + optionalExtras + "; document: "
                                + optionalDocument));
    }

    // --- Fixtures ---

    /** {@code Plain}'s own declared constraint, translated exactly as for a non-Optional position. */
    static final class Plain {

        /** The constraint an Optional-typed extras value must still describe. */
        @Size(max = 3)
        public String name;
    }

    /** An any-setter extras value of declared type {@code Optional<Plain>} — the fixture under test. */
    static final class OptionalValuedExtrasHolder {

        /** An ordinary property, so the extras schema is proven beside a published property set. */
        public String label;

        /** The any-setter's backing storage: the declared-nullable value position under test. */
        @JsonAnySetter
        public Map<String, Optional<Plain>> extras = new LinkedHashMap<>();
    }

    /** The control: the same shape with a non-{@code Optional} extras value type. */
    static final class PlainValuedExtrasHolder {

        /** An ordinary property. */
        public String label;

        /** The any-setter's backing storage: a non-nullable value position, the control. */
        @JsonAnySetter
        public Map<String, Plain> extras = new LinkedHashMap<>();
    }

    /**
     * An any-setter extras value of declared type {@code Optional<Object>} — the unconstrained-{@code T}
     * fixture under test.
     */
    static final class OptionalObjectValuedExtrasHolder {

        /** An ordinary property, so the extras schema is proven beside a published property set. */
        public String label;

        /** The any-setter's backing storage: an Optional-wrapped, but unconstrained, value position. */
        @JsonAnySetter
        public Map<String, Optional<Object>> extras = new LinkedHashMap<>();
    }

    /** The control: the same shape with a non-{@code Optional}, unconstrained extras value type. */
    static final class ObjectValuedExtrasHolder {

        /** An ordinary property. */
        public String label;

        /** The any-setter's backing storage: an unconstrained, non-Optional value position. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }
}
