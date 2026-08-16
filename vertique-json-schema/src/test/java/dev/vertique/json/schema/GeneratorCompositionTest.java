// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the two composition safety contracts of {@link AnnotationJsonSchemaGenerator}: the fail-fast
 * guard against a non-default {@code @Schema(implementation = ...)} on a property whose declared type
 * graph carries an effective profile override, and the detection of a disjoint explicit {@code type}
 * conflict in a generated document.
 *
 * <p>Both exist because the pinned Victools 4.38.0 would otherwise emit a <em>silently wrong</em>
 * document. {@code Swagger2Module.resolveTargetTypeOverrides} redirects a member's resolved type
 * before general-type definition lookup, so an {@code implementation} redirect drops the profile
 * fragment without a trace; and an explicit conjunction of two disjoint types is an unsatisfiable
 * contract no consumer could ever fulfil.
 */
class GeneratorCompositionTest {

    /** Maximum length, in UTF-16 code units, a bounded failure message may reach. */
    private static final int MAX_MESSAGE_LENGTH = 512;

    @Test
    @DisplayName("@Schema(implementation) on an override-bearing declared type fails generation")
    void schemaImplementationOnOverriddenDeclaredTypeFailsGeneration() {
        // Given: the real vertique-strict profile, whose BigDecimal override applies in both directions.
        JsonMapperProfile strict = HardeningFixtures.strictProfile();
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(strict);

        // When/Then: a directly overridden declared type fails, naming the property.
        assertFailsNamingProperty(generator, HardeningFixtures.ImplementationOnOverriddenDto.class, "amount");

        // When/Then: an overridden class nested in the declared type graph fails too.
        assertFailsNamingProperty(generator, HardeningFixtures.ImplementationOnNestedOverriddenDto.class, "amounts");

        // When/Then: an annotation carried by the accessor rather than the field fails identically,
        // proving the guard resolves annotations with the Swagger module's field/getter visibility.
        assertFailsNamingProperty(generator, HardeningFixtures.ImplementationOnGetterDto.class, "amount");
    }

    @Test
    @DisplayName("@Schema(implementation) without an overridden declared type redirects normally")
    void schemaImplementationWithoutOverriddenDeclaredTypeRedirectsNormally() {
        // Given: the strict profile and a property whose declared type carries no override.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.ImplementationWithoutOverrideDto.class);

        // Then: the redirect took effect — the property carries the replacement type's shape.
        assertTrue(
                canonical.contains("replacementMarker"),
                "the implementation redirect must replace the property schema; was: " + canonical);
    }

    @Test
    @DisplayName("@Schema(implementation) over a map-key-only override does not fail generation")
    void schemaImplementationOnMapKeyOnlyOverrideDoesNotFail() {
        // Given: the strict profile and a property whose only overridden class is a map key.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.ImplementationOnMapKeyDto.class);

        // Then: generation succeeds — a map key position is excluded from the declared type graph walk.
        assertNotNull(canonical, "a map-key-only override must not trip the implementation guard");
        assertTrue(canonical.contains("byAmount"), "the redirected property must still be present");
    }

    @Test
    @DisplayName("A disjoint explicit type conflict fails generation end to end")
    void disjointExplicitTypeConflictFailsGeneration() {
        // Given: the strict profile, whose BigDecimal fragment declares type "string", and a property
        // whose Swagger allOf contribution declares an object type in the same conjunctive location.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: generation is attempted.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.DisjointAllOfDto.class),
                "an allOf branch typed 'object' beside a fragment typed 'string' must fail generation");

        // Then: the diagnostic is bounded.
        String message = failure.getMessage();
        assertNotNull(message, "the conflict must carry a message");
        assertTrue(
                message.length() <= MAX_MESSAGE_LENGTH,
                "the conflict message must stay within " + MAX_MESSAGE_LENGTH + " code units; was " + message.length());
        assertTrue(message.contains("type"), "the message must name the conflicting keyword; was: " + message);
    }

    @Test
    @DisplayName("An alternation of otherwise disjoint types is not a conflict")
    void alternationOfDisjointTypesGeneratesNormally() {
        // Given: the strict profile and a nullable overridden property, which Victools represents as an
        // anyOf alternation of a null branch and the string fragment.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.NullableOverriddenDto.class);

        // Then: generation succeeds — an anyOf branch is an alternative, never a conjunction.
        assertTrue(canonical.contains("anyOf"), "the fixture must exercise an alternation; was: " + canonical);
        assertTrue(canonical.contains("\"type\":\"null\""), "the null alternative must survive; was: " + canonical);
        assertTrue(canonical.contains("\"type\":\"string\""), "the string alternative must survive; was: " + canonical);
    }

    /**
     * Direct unit proof of the post-generation conflict walk.
     *
     * <p>The walk is exercised on constructed documents as well as end to end, because the shapes it
     * must survive — a reference cycle through {@code $defs}, a literal {@code "type": []} — are not
     * reachable from any annotation combination the pinned Victools version emits, while the walk
     * still has to be correct for them.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("Disjoint type walk")
    class DisjointTypeWalk {

        @Test
        @DisplayName("A node's own type conflicting with a direct allOf branch fails")
        void siblingTypeConflictFails() {
            assertConflict("{\"type\":\"object\",\"allOf\":[{\"type\":\"string\"}]}");
        }

        @Test
        @DisplayName("Two disjoint allOf branches fail")
        void allOfBranchConflictFails() {
            assertConflict("{\"allOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}");
        }

        @Test
        @DisplayName("A conflict reached through a locally resolved $ref fails")
        void refResolvedConflictFails() {
            assertConflict("{\"$defs\":{\"A\":{\"type\":\"integer\"}},\"$ref\":\"#/$defs/A\",\"type\":\"string\"}");
        }

        @Test
        @DisplayName("A literal empty type array fails")
        void emptyTypeArrayFails() {
            assertConflict("{\"type\":[]}");
        }

        @Test
        @DisplayName("A cycle of local references terminates without a conflict")
        void cyclicReferencesTerminate() {
            String document = "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/B\",\"type\":\"object\"},"
                    + "\"B\":{\"$ref\":\"#/$defs/A\",\"type\":\"object\"}},\"$ref\":\"#/$defs/A\"}";
            assertTimeoutPreemptively(
                    Duration.ofSeconds(5), () -> assertNoConflict(document), "the walk must be cycle-safe");
        }

        @Test
        @DisplayName("Overlapping, absent, and alternative type declarations pass")
        void nonConflictingDocumentsPass() {
            // An overlapping type set intersects to a satisfiable type.
            assertNoConflict("{\"type\":[\"string\",\"null\"],\"allOf\":[{\"type\":\"string\"}]}");
            // Sibling properties are separate conjunctive locations.
            assertNoConflict("{\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"integer\"}}}");
            // Alternatives are never conjunctions.
            assertNoConflict("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}");
            assertNoConflict("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}");
            // An array's item schema is its own location.
            assertNoConflict("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
            // A document declaring no explicit type keyword at all.
            assertNoConflict("{\"allOf\":[{\"maxLength\":3},{\"pattern\":\"^a$\"}]}");
        }

        /**
         * Asserts the walk rejects a document with a bounded diagnostic.
         *
         * @param json the document text
         */
        private void assertConflict(String json) {
            JsonSchemaGenerationException failure = assertThrows(
                    JsonSchemaGenerationException.class,
                    () -> DisjointTypeDetector.requireNoDisjointTypes(read(json)),
                    "the walk must reject " + json);
            assertNotNull(failure.getMessage(), "the conflict must carry a message");
            assertTrue(
                    failure.getMessage().length() <= MAX_MESSAGE_LENGTH,
                    "the conflict message must stay bounded; was " + failure.getMessage());
        }

        /**
         * Asserts the walk accepts a document.
         *
         * @param json the document text
         */
        private void assertNoConflict(String json) {
            assertDoesNotThrow(() -> DisjointTypeDetector.requireNoDisjointTypes(read(json)), "must accept " + json);
        }

        /**
         * Parses a constructed document.
         *
         * @param json the document text
         * @return the parsed tree
         */
        private JsonNode read(String json) {
            try {
                return new ObjectMapper().readTree(json);
            } catch (JsonProcessingException malformed) {
                throw new IllegalArgumentException("test document is not valid JSON: " + json, malformed);
            }
        }
    }

    // --- Helpers ---

    /**
     * Asserts that generating a type fails with a bounded diagnostic naming the offending property.
     *
     * @param generator the generator under test
     * @param type      the body type to generate
     * @param property  the property the message must name
     */
    private static void assertFailsNamingProperty(
            AnnotationJsonSchemaGenerator generator, Class<?> type, String property) {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(type),
                type.getSimpleName() + " must fail generation");

        String message = failure.getMessage();
        assertNotNull(message, "the guard failure must carry a message");
        assertTrue(
                message.length() <= MAX_MESSAGE_LENGTH,
                "the guard message must stay within " + MAX_MESSAGE_LENGTH + " code units; was " + message.length());
        assertTrue(
                message.contains(property), "the message must name the property '" + property + "'; was: " + message);
        assertFalse(
                message.contains(HardeningFixtures.SENTINEL_VALUE), "the guard message must not echo a fixture value");
    }
}
