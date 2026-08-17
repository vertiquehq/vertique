// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the availability and diagnostics contract of the two post-generation walks
 * {@link AnnotationJsonSchemaGenerator#generateCanonical(Type)} runs — {@link DisjointTypeDetector}
 * and {@link NumericDomainKeywordFilter}.
 *
 * <p>Two properties are proven. First, a document carrying a {@code #}-rooted reference that is not a
 * JSON pointer — a JSON Schema {@code $anchor}, which the Swagger module publishes verbatim from a
 * {@code @Schema(ref = ...)} value — generates cleanly: the closure follows only pointers it can
 * locally resolve, so an unresolvable reference is skipped rather than crashing generation. Second,
 * whatever escapes a post-generation walk surfaces as a bounded {@link JsonSchemaGenerationException}
 * (FR-JSON-075/076), never as a raw runtime exception carrying unbounded third-party text.
 *
 * <p>The class additionally pins the walks' <strong>position awareness</strong>. Draft 2020-12 treats
 * an unknown keyword as an annotation — arbitrary JSON data by specification — so a walk that
 * descends into every object member acts on data: the numeric-domain filter mutates it and the
 * disjoint-type detector fails generation on it. Both walks therefore descend only into a closed
 * allowlist of known subschema positions, and both halves of that contract are proven here: data in a
 * {@code default}, {@code const}, or {@code definitions} position is left alone, while a real
 * conflict at <em>every</em> allowlisted position still fails generation.
 */
class GeneratorPostGenerationWalkTest {

    /** Unbounded text a hostile walk failure carries, which no bounded diagnostic may echo. */
    private static final String HOSTILE_TEXT = "hostile-walk-text".repeat(64);

    /**
     * A self-contained subschema whose own {@code type} is disjoint from its single {@code allOf}
     * branch's. Placed at a genuine subschema position it is an unsatisfiable contract the
     * disjoint-type detector must refuse; placed in a data position it is inert JSON.
     */
    private static final String CONFLICT = "{\"type\":\"string\",\"allOf\":[{\"type\":\"integer\"}]}";

    @Test
    @DisplayName("An $anchor-style #ref generates without throwing and survives into the document")
    void anchorStyleRefGeneratesWithoutThrowing() {
        // Given: the REST body path's construction mode and a property whose Swagger metadata
        // publishes an $anchor-style reference rather than a JSON pointer.
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();

        // When: the document is generated.
        String canonical = assertDoesNotThrow(
                () -> generator.generateCanonical(HardeningFixtures.AnchorRefDto.class),
                "a #-rooted reference that is not a JSON pointer must not fail generation");

        // Then: the reference reaches the published document verbatim.
        assertTrue(
                canonical.contains("\"$ref\":\"" + HardeningFixtures.ANCHOR_REF + "\""),
                "the $anchor-style reference must survive into the document; was: " + canonical);
    }

    @Test
    @DisplayName("A RuntimeException escaping either post-generation walk normalizes to a bounded failure")
    void postGenerationWalkFailureNormalizesToJsonSchemaGenerationException() {
        // Given: a generator whose Victools stage yields a document that makes the disjoint-type walk
        // fail with an unbounded third-party runtime exception.
        assertNormalizes(new AnnotationJsonSchemaGenerator(new HostileDocumentGenerator(new WalkHostileNode())));

        // Given: a generator whose numeric-keyword filter — the second walk — fails the same way.
        ObjectNode stripHostile = new StripHostileNode();
        stripHostile.put(ConjunctiveLocations.TYPE, "string");
        assertNormalizes(new AnnotationJsonSchemaGenerator(new HostileDocumentGenerator(stripHostile), true));
    }

    // --- Position awareness: data positions are never treated as schema positions ---

    @Test
    @DisplayName("A schema-shaped object in a default position keeps its numeric keywords")
    void dataBearingObjectIsNotTreatedAsSchema() {
        // Given: a profile whose BigDecimal override republishes the class as a string — so the
        // property's effective type excludes number and the numeric-domain filter is active — and
        // whose fragment carries a JSON *data* default value that merely looks like a schema.
        AnnotationJsonSchemaGenerator generator = generatorFor(HardeningFixtures.dataBearingDefaultFragment());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.OverriddenDecimalDto.class);

        // Then: the default value reaches the document untouched. A `default` value is annotation
        // data, not a subschema, so no walk may strip its `minimum`.
        assertTrue(
                canonical.contains(HardeningFixtures.DATA_BEARING_DEFAULT),
                "the data-bearing default must survive untouched; was: " + canonical);
    }

    @Test
    @DisplayName("A schema-shaped object in a const position does not fail generation")
    void dataBearingObjectDoesNotFailGeneration() {
        // Given: a profile whose override fragment declares a JSON *data* const value which, read as
        // a schema, would conjoin the disjoint types "a" and "b".
        AnnotationJsonSchemaGenerator generator = generatorFor(HardeningFixtures.dataBearingConstFragment());

        // When: the document is generated.
        String canonical = assertDoesNotThrow(
                () -> generator.generateCanonical(HardeningFixtures.OverriddenDecimalDto.class),
                "a data-bearing const value must not be read as a subschema");

        // Then: the const value reaches the document unchanged.
        assertTrue(
                canonical.contains(HardeningFixtures.DATA_BEARING_CONST),
                "the data-bearing const must survive untouched; was: " + canonical);
    }

    @Test
    @DisplayName("A fragment-authored definitions member is data, not a subschema map")
    void definitionsMemberIsTreatedAsData() {
        // Given: a profile whose override fragment carries a `definitions` member. Under the pinned
        // fixed DRAFT_2020_12 dialect Victools emits `$defs`, and JsonSchemaFragment rejects `$defs`
        // but not `definitions` — so this member can only be annotation data.
        AnnotationJsonSchemaGenerator generator = generatorFor(HardeningFixtures.legacyDefinitionsFragment());

        // When: the document is generated.
        String canonical = assertDoesNotThrow(
                () -> generator.generateCanonical(HardeningFixtures.OverriddenDecimalDto.class),
                "a fragment-authored definitions member must not be descended as a subschema map");

        // Then: it reaches the document unchanged.
        assertTrue(
                canonical.contains(HardeningFixtures.DATA_BEARING_DEFINITIONS),
                "the data-bearing definitions member must survive untouched; was: " + canonical);
    }

    // --- Position awareness: every allowlisted subschema position is still checked ---

    @ParameterizedTest(name = "a disjoint-type conflict at {0} still fails")
    @MethodSource("allowlistedSubschemaPositions")
    @DisplayName("Every allowlisted subschema position is still checked")
    void everySubschemaPositionIsStillChecked(String keyword, String document) {
        // Given: a document placing a genuinely unsatisfiable subschema at one allowlisted position.
        JsonNode parsed = read(document);

        // When/Then: the detector still reaches it. This is the anti-hole proof: an allowlist that
        // silently omitted this keyword would let an unsatisfiable subschema publish.
        assertThrows(
                JsonSchemaGenerationException.class,
                () -> DisjointTypeDetector.requireNoDisjointTypes(parsed),
                "a conflict at the '" + keyword + "' position must fail generation; document: " + document);
    }

    /**
     * Supplies one case per keyword on the frozen Draft 2020-12 subschema-position allowlist, each
     * placing {@link #CONFLICT} at that position.
     *
     * <p>The {@code allOf} case is deliberately two levels deep. A conflict placed <em>directly</em>
     * in an {@code allOf} branch is already part of the enclosing location's conjunctive closure, so
     * it would fail even if the walk never descended into {@code allOf} at all — which would make the
     * case unable to detect the hole it exists to detect. Reaching the conflict through the branch's
     * own {@code properties} makes descent into {@code allOf} load-bearing.
     *
     * @return the {@code (keyword, document)} cases
     */
    private static Stream<Arguments> allowlistedSubschemaPositions() {
        return Stream.concat(
                Stream.concat(
                        // Single subschema.
                        Stream.of(
                                        "not",
                                        "if",
                                        "then",
                                        "else",
                                        "items",
                                        "contains",
                                        "additionalProperties",
                                        "propertyNames",
                                        "unevaluatedItems",
                                        "unevaluatedProperties",
                                        "contentSchema")
                                .map(keyword -> Arguments.of(keyword, "{\"" + keyword + "\":" + CONFLICT + "}")),
                        // Array of subschemas. `allOf` carries its own, deeper case below.
                        Stream.of("anyOf", "oneOf", "prefixItems")
                                .map(keyword -> Arguments.of(keyword, "{\"" + keyword + "\":[" + CONFLICT + "]}"))),
                Stream.concat(
                        // Map of subschemas.
                        Stream.of("properties", "patternProperties", "$defs", "dependentSchemas")
                                .map(keyword ->
                                        Arguments.of(keyword, "{\"" + keyword + "\":{\"member\":" + CONFLICT + "}}")),
                        Stream.of(Arguments.of(
                                "allOf", "{\"allOf\":[{\"properties\":{\"member\":" + CONFLICT + "}}]}"))));
    }

    // --- Helpers ---

    /**
     * Builds a profile-aware generator whose only override republishes {@link BigDecimal} through the
     * given fragment, so both post-generation walks run over the fragment's published members.
     *
     * @param fragment the override fragment the profile declares for {@link BigDecimal}
     * @return the configured generator
     */
    private static AnnotationJsonSchemaGenerator generatorFor(JsonSchemaFragment fragment) {
        JsonMapperProfile profile = HardeningFixtures.profile(
                "schema-position-fixture", List.of(JsonSchemaTypeOverride.both(BigDecimal.class, fragment)));
        return AnnotationJsonSchemaGenerator.forInputProfile(profile);
    }

    /**
     * Parses a hand-built document. Both walks take a {@code JsonNode}, so a document shape the
     * generator cannot itself emit is still a legitimate input to prove the walk against.
     *
     * @param json the document text
     * @return the parsed tree
     */
    private static JsonNode read(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException("test document is not valid JSON: " + json, malformed);
        }
    }

    /**
     * Asserts that a generation call fails with a bounded {@link JsonSchemaGenerationException} rather
     * than the raw runtime exception a post-generation walk raised.
     *
     * @param generator the generator whose injected Victools stage yields a walk-hostile document
     */
    private static void assertNormalizes(AnnotationJsonSchemaGenerator generator) {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.SimpleDto.class),
                "a failure escaping a post-generation walk must normalize to the module's bounded type");

        String message = failure.getMessage();
        assertNotNull(message, "the normalized failure must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the normalized message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertFalse(
                message.contains(HOSTILE_TEXT),
                "the normalized message must not echo the raw third-party text; was: " + message);
    }

    /**
     * Builds the unbounded runtime failure a post-generation walk raises in this test, mirroring the
     * shape a third-party library raises: an {@link IllegalArgumentException} quoting its input.
     *
     * @return the failure to throw from inside a walk
     */
    private static RuntimeException hostileFailure() {
        return new IllegalArgumentException("Invalid input: " + HOSTILE_TEXT);
    }

    // --- Probes ---

    /**
     * Victools generator whose generation stage succeeds and hands back a caller-supplied document, so
     * a test can drive the post-generation walks with a document Victools itself would never emit.
     */
    private static final class HostileDocumentGenerator extends SchemaGenerator {

        /** The document every generation call on this probe returns. */
        private final ObjectNode document;

        /**
         * @param document the document this probe returns from every generation call
         */
        private HostileDocumentGenerator(ObjectNode document) {
            super(new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON).build());
            this.document = document;
        }

        @Override
        public ObjectNode generateSchema(Type mainTargetType, Type... typeParameters) {
            return document;
        }
    }

    /**
     * Document node that fails the structural walk: {@link DisjointTypeDetector} enumerates a node's
     * members, and this node refuses to be enumerated.
     */
    private static final class WalkHostileNode extends ObjectNode {

        private WalkHostileNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public Set<Map.Entry<String, JsonNode>> properties() {
            throw hostileFailure();
        }
    }

    /**
     * Document node that fails the numeric-keyword filter: it declares a non-numeric explicit type, so
     * the filter's suppression pass reaches it, and it refuses the keyword removal.
     */
    private static final class StripHostileNode extends ObjectNode {

        private StripHostileNode() {
            super(JsonNodeFactory.instance);
        }

        @Override
        public JsonNode remove(String fieldName) {
            throw hostileFailure();
        }
    }
}
