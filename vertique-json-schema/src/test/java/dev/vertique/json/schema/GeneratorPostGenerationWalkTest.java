// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 *
 * <p>Position awareness covers the <strong>conjunctive closure</strong> as well as the outer
 * traversal. A developer-authored {@code @Schema(ref = "#/...")} reaches the generated document
 * verbatim and may point anywhere in it, so a target is conjoined only when it is itself a schema
 * head: a pointer onto a data position or onto a subschema-map container contributes nothing, while a
 * pointer onto a {@code $defs} entry or a sibling property's schema still contributes in full.
 */
class GeneratorPostGenerationWalkTest {

    /** Unbounded text a hostile walk failure carries, which no bounded diagnostic may echo. */
    private static final String HOSTILE_TEXT = "hostile-walk-text".repeat(64);

    /**
     * A document whose only {@code $ref} points at a {@code default} <em>value</em>. The referring
     * property is a genuine subschema the outer traversal reaches; its target is annotation data that
     * happens to carry a {@code type}. Conjoining the two would reject a publishable document.
     */
    private static final String REF_INTO_DATA_CONFLICT = "{\"default\":{\"type\":\"integer\",\"minimum\":3},"
            + "\"properties\":{\"victim\":{\"$ref\":\"#/default\",\"type\":\"string\"}}}";

    /**
     * A document whose root refers to a {@code default} value declaring {@code "type":"string"} and
     * carries a genuine {@code minimum} of its own. Reading the data's type as the root's effective
     * type would silently delete that {@code minimum}.
     */
    private static final String REF_INTO_DATA_SUPPRESSION =
            "{\"default\":{\"type\":\"string\"},\"$ref\":\"#/default\",\"minimum\":5}";

    /**
     * A document whose {@code $ref} points at a {@code properties} <em>container</em> — the object
     * mapping property names to subschemas — one of whose property names is literally {@code type}.
     *
     * <p>The container's member <em>values</em> are irrelevant to the misread this pins: what matters
     * is that a container's keys are property names, so reading them as keywords turns a property
     * named {@code type} into a type declaration about the referring location. The member is written
     * as a bare string only because that is the shape which makes the misread observable through
     * {@link ConjunctiveLocations#explicitTypes(JsonNode)}.
     */
    private static final String REF_ONTO_CONTAINER = "{\"$defs\":{\"Money\":{\"properties\":{\"type\":\"string\"}}},"
            + "\"properties\":{\"victim\":{\"$ref\":\"#/$defs/Money/properties\",\"type\":\"integer\"}}}";

    /** A document whose {@code $ref} points at a real {@code $defs} schema head with a disjoint type. */
    private static final String REF_ONTO_DEFINITION = "{\"$defs\":{\"Money\":{\"type\":\"integer\"}},"
            + "\"properties\":{\"victim\":{\"$ref\":\"#/$defs/Money\",\"type\":\"string\"}}}";

    /** A document whose {@code $ref} points at a sibling property's schema head with a disjoint type. */
    private static final String REF_ONTO_PROPERTY = "{\"properties\":{\"anchor\":{\"type\":\"integer\"},"
            + "\"victim\":{\"$ref\":\"#/properties/anchor\",\"type\":\"string\"}}}";

    /**
     * A document whose {@code $ref} points at a real {@code $defs} schema head declaring a
     * non-numeric type, so the referring property's own {@code minimum} is genuinely inapplicable.
     */
    private static final String REF_ONTO_DEFINITION_SUPPRESSION = "{\"$defs\":{\"Kind\":{\"type\":\"string\"}},"
            + "\"properties\":{\"victim\":{\"$ref\":\"#/$defs/Kind\",\"minimum\":5}}}";

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

    // --- The pinned-dialect premise the position allowlist rests on ---

    @Test
    @DisplayName("A generator configured for another dialect is rejected at construction")
    void nonDraft202012GeneratorIsRejected() {
        // Given: a Victools generator configured for Draft-07 — a dialect in which the walks'
        // allowlist is wrong: Victools spells `$defs` as `definitions` and `dependentSchemas` as
        // `dependencies` there, and neither spelling is on the allowlist, so the whole definition
        // graph would silently escape both passes.
        SchemaGenerator foreignDialect = new SchemaGenerator(
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON).build());

        // When: it is handed to the package-private injection seam.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> new AnnotationJsonSchemaGenerator(foreignDialect),
                "a generator configured for another dialect must be rejected, not silently accepted");

        // Then: the failure is bounded and names both the required and the supplied dialect.
        String message = failure.getMessage();
        assertNotNull(message, "the rejection must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the rejection must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertTrue(message.contains("DRAFT_2020_12"), "the rejection must name the required dialect; was: " + message);
        assertTrue(message.contains("DRAFT_7"), "the rejection must name the supplied dialect; was: " + message);
    }

    @Test
    @DisplayName("Both public factories pin the dialect the allowlist is defined for")
    void publicFactoriesPinTheDialect() {
        // Given/When/Then: the two construction modes an application uses both build a Draft 2020-12
        // generator, so the seam's constraint is never in their way.
        assertDoesNotThrow(
                () -> AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                        .generateCanonical(HardeningFixtures.SimpleDto.class),
                "the Victools-defaults factory must construct and generate under the pinned dialect");
        assertDoesNotThrow(
                () -> AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile())
                        .generateCanonical(HardeningFixtures.OverriddenDecimalDto.class),
                "the profile-aware factory must construct and generate under the pinned dialect");
    }

    // --- Position awareness: a $ref target is conjoined only when it is a schema head ---

    @Test
    @DisplayName("A $ref onto a data position does not conjoin the data's type")
    void refOntoDataPositionIsNotConjoined() {
        // Given: a document whose property refers to a `default` value — annotation data by
        // specification — that happens to declare a type disjoint from the property's own.
        JsonNode document = HardeningFixtures.read(REF_INTO_DATA_CONFLICT);

        // When/Then: generation is clean. The outer traversal correctly reaches only the property, so
        // the closure must not reintroduce the data position the traversal deliberately skipped.
        assertDoesNotThrow(
                () -> DisjointTypeDetector.requireNoDisjointTypes(document),
                "a $ref onto a data position must not be read as a conjoined subschema");
    }

    @Test
    @DisplayName("A $ref onto a data position does not strip a genuine numeric keyword")
    void refOntoDataPositionDoesNotDriveSuppression() {
        // Given: a document whose root refers to a `default` value declaring a string type, and which
        // carries a genuine numeric bound of its own.
        JsonNode document = HardeningFixtures.read(REF_INTO_DATA_SUPPRESSION);

        // When: the numeric-domain filter runs.
        NumericDomainKeywordFilter.suppressInapplicableNumericKeywords(document);

        // Then: the bound survives. Its location has no explicit type at all — the only `type` in the
        // document is data — so there is nothing to declare the bound inapplicable.
        assertTrue(
                document.has("minimum"),
                "a genuine schema's numeric keyword must not be suppressed from a data-position type; was: "
                        + document);
    }

    @Test
    @DisplayName("A $ref onto a subschema-map container does not conjoin the container's keys")
    void refOntoContainerIsNotConjoined() {
        // Given: a document whose property refers to a `properties` container carrying a property
        // literally named `type`.
        JsonNode document = HardeningFixtures.read(REF_ONTO_CONTAINER);

        // When/Then: generation is clean. A container is not a schema head — its keys are property
        // names — so a pointer landing on one contributes no keyword to the referring location.
        assertDoesNotThrow(
                () -> DisjointTypeDetector.requireNoDisjointTypes(document),
                "a $ref onto a subschema-map container must not be read as a conjoined subschema");
    }

    @Test
    @DisplayName("A $ref onto a real schema head still contributes to the conjunction")
    void refOntoSchemaHeadStillContributes() {
        // Given/When/Then: the two pointer forms a developer legitimately authors — into `$defs` and
        // onto a sibling property — both name real schema heads, and both must still be conjoined.
        // This is the anti-over-restriction control: narrowing the closure to `$defs` alone, or
        // dropping $ref resolution entirely, would let each of these unsatisfiable documents publish.
        assertThrows(
                JsonSchemaGenerationException.class,
                () -> DisjointTypeDetector.requireNoDisjointTypes(HardeningFixtures.read(REF_ONTO_DEFINITION)),
                "a $ref onto a $defs schema head must still be conjoined");
        assertThrows(
                JsonSchemaGenerationException.class,
                () -> DisjointTypeDetector.requireNoDisjointTypes(HardeningFixtures.read(REF_ONTO_PROPERTY)),
                "a $ref onto a property's schema head must still be conjoined");
    }

    @Test
    @DisplayName("A $ref onto a real schema head still drives numeric suppression")
    void refOntoSchemaHeadStillDrivesSuppression() {
        // Given: a property whose referenced definition declares a string type, so the property's own
        // numeric bound targets a wire type that can never satisfy it.
        JsonNode document = HardeningFixtures.read(REF_ONTO_DEFINITION_SUPPRESSION);

        // When: the numeric-domain filter runs.
        NumericDomainKeywordFilter.suppressInapplicableNumericKeywords(document);

        // Then: the inapplicable bound is gone from the referring location, and the shared definition
        // — which no referrer may rewrite — is untouched.
        assertFalse(
                document.at("/properties/victim").has("minimum"),
                "a numeric keyword conjoined with a referenced string type must be suppressed; was: " + document);
        assertEquals(
                "string",
                document.at("/$defs/Kind/type").textValue(),
                "the shared definition must survive the referrer's suppression; was: " + document);
    }

    // --- Position awareness: every allowlisted subschema position is still checked ---

    @ParameterizedTest(name = "a disjoint-type conflict at {0} still fails")
    @MethodSource("allowlistedSubschemaPositions")
    @DisplayName("Every allowlisted subschema position is still checked")
    void everySubschemaPositionIsStillChecked(String keyword, String document) {
        // Given: a document placing a genuinely unsatisfiable subschema at one allowlisted position.
        JsonNode parsed = HardeningFixtures.read(document);

        // When/Then: the detector still reaches it. This is the anti-hole proof: an allowlist that
        // silently omitted this keyword would let an unsatisfiable subschema publish.
        assertThrows(
                JsonSchemaGenerationException.class,
                () -> DisjointTypeDetector.requireNoDisjointTypes(parsed),
                "a conflict at the '" + keyword + "' position must fail generation; document: " + document);
    }

    @Test
    @DisplayName("The subschema-position allowlist and this test's cases name exactly the same keywords")
    void allowlistAndCaseSetAgree() {
        // Given: the union of SchemaPositions' two subschema-position allowlists — the keywords the
        // implementation actually descends into.
        Set<String> allowlisted = new HashSet<>(SchemaPositions.subschemaKeywords());
        allowlisted.addAll(SchemaPositions.subschemaMapKeywords());

        // Given: the keywords this class's hardcoded @MethodSource actually yields a case for.
        Set<String> covered = allowlistedSubschemaPositions()
                .map(arguments -> (String) arguments.get()[0])
                .collect(Collectors.toSet());

        // Then: the two sets agree in both directions. A keyword added to the implementation with no
        // matching case, or a case naming a keyword the implementation no longer allows, fails here
        // instead of silently drifting apart.
        assertEquals(
                allowlisted,
                covered,
                "the SchemaPositions allowlist and this test's cases must name exactly the same keywords");
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
        // Single subschema.
        Stream<Arguments> singles = Stream.of(
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
                .map(keyword -> Arguments.of(keyword, "{\"" + keyword + "\":" + CONFLICT + "}"));
        // Array of subschemas. `allOf` carries its own, deeper case below.
        Stream<Arguments> arrays = Stream.of("anyOf", "oneOf", "prefixItems")
                .map(keyword -> Arguments.of(keyword, "{\"" + keyword + "\":[" + CONFLICT + "]}"));
        // Map of subschemas.
        Stream<Arguments> maps = Stream.of("properties", "patternProperties", "$defs", "dependentSchemas")
                .map(keyword -> Arguments.of(keyword, "{\"" + keyword + "\":{\"member\":" + CONFLICT + "}}"));
        Arguments allOfCase = Arguments.of("allOf", "{\"allOf\":[{\"properties\":{\"member\":" + CONFLICT + "}}]}");

        return Stream.of(singles, arrays, maps, Stream.<Arguments>of(allOfCase)).flatMap(Function.identity());
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
