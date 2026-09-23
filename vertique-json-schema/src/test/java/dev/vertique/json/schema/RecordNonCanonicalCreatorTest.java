// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link InputPropertyDescriber#backingField} joins a record creator parameter to {@code
 * getRecordComponents()[parameter.getIndex()]} unconditionally whenever {@code builtClass.isRecord()}
 * — without checking that {@code parameter}'s declaring constructor is the record's <em>canonical</em>
 * one. Component {@code i} is parameter {@code i} by language definition only for the canonical
 * constructor; for any other constructor (reachable via an explicit {@code @JsonCreator} on a
 * non-canonical constructor whose parameter order differs from the component order) the index join
 * borrows an unrelated component's type and constraints.
 *
 * <p>This class pins the bypass with a record whose {@code @JsonCreator} constructor parameter order
 * is the reverse of its component order: the published document swaps each property's constraint onto
 * the wrong wire name, and a body the swapped document wrongly accepts is demonstrated end to end
 * against the real {@code io.vertx.json.schema} validator.
 */
class RecordNonCanonicalCreatorTest {

    private static final JsonSchemaOptions VALIDATOR_OPTIONS =
            new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/");

    private static JsonMapperProfile profile() {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of();
            }
        };
    }

    private static JsonNode document(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    /**
     * Follows a local {@code $ref} into the document's own {@code $defs}, so an assertion reads the
     * same schema whether the generator inlined the value type or shared it under {@code $defs}.
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    private static boolean accepted(JsonNode document, JsonObject instance) {
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(document.toString())), VALIDATOR_OPTIONS);
        Boolean result = validator.validate(instance).getValid();
        return Boolean.TRUE.equals(result);
    }

    // --- Fixtures ---

    /** A small type with one constrained field, whose own creator is canonical (the record's implicit one). */
    record Plain(@Size(max = 3) String name) {}

    /**
     * The record under test: component order is {@code (child, raw)}, but the explicit {@code
     * @JsonCreator} constructor's parameter order is reversed, {@code (raw, child)}. A by-index join
     * ({@code parameter.getIndex()} against {@code getRecordComponents()}) therefore pairs the "raw"
     * wire name with the {@code child} component (type {@link Plain}) and the "child" wire name with
     * the {@code raw} component (type {@link JsonNode}) — exactly backwards.
     */
    record Holder(Plain child, JsonNode raw) {
        @JsonCreator
        public Holder(@JsonProperty("raw") JsonNode raw, @JsonProperty("child") Plain child) {
            this(child, raw);
        }
    }

    /** The control: same shape and component order, but no explicit creator (the canonical one binds). */
    record HolderCanonical(Plain child, JsonNode raw) {}

    record PlainA(@Size(max = 3) String name) {}

    record PlainB(@Size(max = 5) String name) {}

    /**
     * A same-typed-family reordered pair: if the join were sound (by the parameter's own backing
     * relationship) each of {@code a} and {@code b} would carry its own component's constraint no
     * matter the creator's parameter order. If the join is purely positional, {@code a} (component
     * index 0, type {@link PlainA}) instead borrows whatever the creator's own parameter index 0
     * ({@code b}, backed by component index 0 once more) resolves to, and vice versa.
     */
    record Pair(PlainA a, PlainB b) {
        @JsonCreator
        public Pair(@JsonProperty("b") PlainB b, @JsonProperty("a") PlainA a) {
            this(a, b);
        }
    }

    /**
     * Same component order and reversed wire order as {@link Holder}, but the creator is a static
     * factory method rather than a constructor: {@code AnnotatedParameter#getOwner()} is an {@code
     * AnnotatedMethod}, never an {@code AnnotatedConstructor}, so {@code isCanonicalRecordConstructor}
     * must reject the by-index branch outright and fall back to the wire-name join — the same join a
     * non-record creator always used. Before the fix, {@code backingField} took the by-index branch for
     * any record regardless of what kind of member {@code parameter}'s owner was, so a record's own
     * static factory was just as exposed as a reordered constructor.
     */
    record Holder2(Plain child, JsonNode raw) {
        @JsonCreator
        static Holder2 of(@JsonProperty("raw") JsonNode raw, @JsonProperty("child") Plain child) {
            return new Holder2(child, raw);
        }
    }

    // --- (1) Document-shape proof ---

    @Test
    @DisplayName("a non-canonical creator's reversed parameter order must not swap each property's own"
            + " component constraint onto the other property's wire name")
    void nonCanonicalCreatorDoesNotSwapComponentConstraints() {
        JsonNode document = document(Holder.class);
        JsonNode child = resolve(document, document.at("/properties/child"));
        JsonNode raw = resolve(document, document.at("/properties/raw"));

        assertEquals(
                3,
                resolve(document, child.at("/properties/name")).at("/maxLength").asInt(),
                "\"child\" (bound to the record's Plain component) must carry Plain.name's own"
                        + " @Size(max=3), not whatever the creator's differently-ordered parameter index"
                        + " happens to resolve to; document: " + document);
        assertTrue(
                resolve(document, raw.at("/properties/name")).at("/maxLength").isMissingNode(),
                "\"raw\" (bound to the record's JsonNode component, which carries no constraint of its"
                        + " own) must never borrow Plain.name's @Size(max=3) through a positional"
                        + " index-only join; document: " + document);
    }

    // --- (2) End-to-end validator proof ---

    @Test
    @DisplayName("a body whose \"child\" value violates Plain.name's own @Size(max=3) must be rejected"
            + " by the generated document, not accepted because the constraint was published on \"raw\""
            + " instead")
    void wrongPropertyBypassesValidationEndToEnd() {
        JsonNode document = document(Holder.class);
        JsonObject body = new JsonObject()
                .put("raw", new JsonObject().put("name", "ab"))
                .put("child", new JsonObject().put("name", "TOOLONG"));

        assertFalse(
                accepted(document, body),
                "\"child\":{\"name\":\"TOOLONG\"} (7 characters) violates Plain.name's own"
                        + " @Size(max=3) and must be rejected; the real io.vertx.json.schema validator"
                        + " accepted it, meaning the published document enforces the maxLength on the"
                        + " wrong wire name (\"raw\") instead: " + document);
    }

    // --- (3) Control: canonical constructor, same shape, join is exact ---

    @Test
    @DisplayName("control: with no explicit non-canonical creator, the component join is exact")
    void canonicalConstructorJoinsExactly() {
        JsonNode document = document(HolderCanonical.class);
        JsonNode child = resolve(document, document.at("/properties/child"));
        JsonNode raw = resolve(document, document.at("/properties/raw"));

        assertEquals(
                3,
                resolve(document, child.at("/properties/name")).at("/maxLength").asInt(),
                "the canonical constructor's component join must publish Plain.name's own"
                        + " @Size(max=3) on \"child\"; document: " + document);
        assertTrue(
                resolve(document, raw.at("/properties/name")).at("/maxLength").isMissingNode(),
                "\"raw\" must carry no borrowed maxLength under the canonical (exact) join; document: " + document);
    }

    // --- (4) Same-typed-family reordered pair: each property keeps its own component's constraint ---

    @Test
    @DisplayName("a same-typed-family reordered creator must still give each property its own"
            + " component's constraint, not the constraint at its positional index")
    void reorderedSameFamilyPairKeepsOwnConstraints() {
        JsonNode document = document(Pair.class);
        JsonNode a = resolve(document, document.at("/properties/a"));
        JsonNode b = resolve(document, document.at("/properties/b"));

        assertEquals(
                3,
                resolve(document, a.at("/properties/name")).at("/maxLength").asInt(),
                "\"a\" is bound to the PlainA component and must carry its own @Size(max=3), not the"
                        + " @Size(max=5) that only PlainB (the creator's own parameter index 0) declares;"
                        + " document: " + document);
        assertEquals(
                5,
                resolve(document, b.at("/properties/name")).at("/maxLength").asInt(),
                "\"b\" is bound to the PlainB component and must carry its own @Size(max=5), not the"
                        + " @Size(max=3) that only PlainA (the creator's own parameter index 1) declares;"
                        + " document: " + document);
    }

    // --- (5) A record's static @JsonCreator factory joins by wire name, never by position ---

    @Test
    @DisplayName("a record's static @JsonCreator factory method joins its parameters by wire name, not"
            + " by the record's component index")
    void staticFactoryOnARecordJoinsByWireName() {
        JsonNode document = document(Holder2.class);
        JsonNode child = resolve(document, document.at("/properties/child"));
        JsonNode raw = resolve(document, document.at("/properties/raw"));

        assertEquals(
                3,
                resolve(document, child.at("/properties/name")).at("/maxLength").asInt(),
                "\"child\" (bound to the record's Plain component through the wire-name join) must carry"
                        + " Plain.name's own @Size(max=3); document: " + document);
        assertTrue(
                resolve(document, raw.at("/properties/name")).at("/maxLength").isMissingNode(),
                "\"raw\" must carry no borrowed maxLength: a static factory's parameter is never joined"
                        + " by the record's component index; document: " + document);

        JsonObject bypassBody = new JsonObject()
                .put("raw", new JsonObject().put("name", "ab"))
                .put("child", new JsonObject().put("name", "TOOLONG"));
        assertFalse(
                accepted(document, bypassBody),
                "\"child\":{\"name\":\"TOOLONG\"} (7 characters) violates Plain.name's own"
                        + " @Size(max=3) and must be rejected by the static-factory-joined document: "
                        + document);
    }
}
