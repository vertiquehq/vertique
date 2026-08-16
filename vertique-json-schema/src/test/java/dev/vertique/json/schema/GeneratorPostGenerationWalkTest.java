// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 */
class GeneratorPostGenerationWalkTest {

    /** Unbounded text a hostile walk failure carries, which no bounded diagnostic may echo. */
    private static final String HOSTILE_TEXT = "hostile-walk-text".repeat(64);

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

    // --- Helpers ---

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
