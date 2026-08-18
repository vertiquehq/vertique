// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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
    @DisplayName("@ArraySchema(schema = @Schema(implementation)) over an overridden element fails generation")
    void arraySchemaItemImplementationOnOverriddenElementFailsGeneration() {
        // Given: the strict profile, whose BigDecimal override applies in both directions.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the element redirect — resolved by the Swagger module only in a fake container
        // item scope — is caught, and the message names the overridden class.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.ArraySchemaItemImplementationDto.class, "amounts");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("@ArraySchema(arraySchema = @Schema(implementation)) over an overridden type fails generation")
    void arraySchemaContainerImplementationOnOverriddenTypeFailsGeneration() {
        // Given: the strict profile and a non-container property whose only redirect is declared
        // through the @ArraySchema container fallback the Swagger module reads when no direct
        // @Schema is present.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the fallback redirect is caught exactly like a direct one.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.ArraySchemaContainerImplementationDto.class, "amount");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("@ArraySchema over a non-overridden element type redirects normally")
    void arraySchemaWithoutOverriddenDeclaredTypeRedirectsNormally() {
        // Given: the strict profile and an element redirect whose element type carries no override.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.ArraySchemaWithoutOverrideDto.class);

        // Then: the redirect took effect — the guard must not over-fire on an @ArraySchema alone.
        assertTrue(
                canonical.contains("replacementMarker"),
                "the element redirect must replace the item schema; was: " + canonical);
    }

    @Test
    @DisplayName("An inherited generic member bound to an overridden class fails generation")
    void inheritedGenericMemberWithImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property declared as a type variable in a
        // supertype, which only a declaring-context resolution can bind to BigDecimal.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the binding subtype's member resolves to the overridden class and fails.
        String message =
                assertFailsNamingProperty(generator, HardeningFixtures.InheritedImplementationDto.class, "amount");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("A declared type graph nested past the supported depth fails closed")
    void deeplyNestedMemberTypeGraphFailsClosed() {
        // Given: the strict profile and a redirected property whose declared type graph nests the
        // overridden class deeper than the guard can walk.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the depth bound refuses the member rather than resolving to "no override found".
        String message =
                assertFailsNamingProperty(generator, HardeningFixtures.DeeplyNestedImplementationDto.class, "deep");
        assertTrue(
                message.contains(String.valueOf(TypeGrammar.MAX_DEPTH)),
                "the message must name the supported depth; was: " + message);
    }

    @Test
    @DisplayName("A redirect over a List subclass carrying the override as its element fails generation")
    void containerSubclassElementImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property typed as a List subclass that binds its
        // element in the extends clause, so the class declares no type parameters of its own while
        // Victools still publishes the element as the array's item schema.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the inherited element position is walked, so the drop is refused.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.ContainerSubclassImplementationDto.class, "amounts");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("A redirect over a Map subclass carrying the override as its value fails generation")
    void mapSubclassValueImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property typed as a Map subclass that binds both
        // its key and value in the extends clause, with the overridden class in the value position.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the inherited value position is walked exactly like a directly declared one.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.MapSubclassValueImplementationDto.class, "rates");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("A redirect over a Map subclass whose override is key-only does not fail generation")
    void mapSubclassKeyOnlyOverrideDoesNotFail() {
        // Given: the strict profile and a redirected property typed as a Map subclass whose only
        // overridden class is inherited into the excluded key position.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When: the document is generated.
        String canonical = generator.generateCanonical(HardeningFixtures.MapSubclassKeyOnlyImplementationDto.class);

        // Then: generation succeeds — descending an inherited map binding must preserve the key
        // exclusion, or the widened walk would turn a mapper-owned position into a false positive.
        assertNotNull(canonical, "an inherited map-key-only override must not trip the implementation guard");
        assertTrue(canonical.contains("keys"), "the redirected property must still be present; was: " + canonical);
    }

    @Test
    @DisplayName("A redirect over a container subclass nested in a wrapper fails generation")
    void nestedContainerSubclassImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property whose overridden class is two descents
        // away — through the wrapper's own parameter, then through the subclass's inherited element.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the walk stays sighted after descending into a nested container subclass.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.NestedContainerSubclassImplementationDto.class, "batch");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("A redirect over a Supplier implementor carrying the override as its payload fails generation")
    void supplierImplementorImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property typed as a concrete Supplier implementor
        // that binds its payload in the implements clause — no self-declared parameters, and neither
        // an Iterable nor a Map — while Victools flattens the wrapper and publishes the payload.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the inherited supplier payload is walked, so the drop is refused.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.SupplierImplementorImplementationDto.class, "amount");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
    }

    @Test
    @DisplayName("A redirect over a Supplier implementor nested in a container fails generation")
    void nestedSupplierImplementorImplementationRedirectFailsGeneration() {
        // Given: the strict profile and a redirected property whose overridden class is two descents
        // away — through the list's element, then through the implementor's inherited payload.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());

        // When/Then: the walk stays sighted after descending into a nested supplier implementor.
        String message = assertFailsNamingProperty(
                generator, HardeningFixtures.NestedSupplierImplementorImplementationDto.class, "amounts");
        assertTrue(
                message.contains("java.math.BigDecimal"),
                "the message must name the overridden class; was: " + message);
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
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the conflict message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertTrue(message.contains("type"), "the message must name the conflicting keyword; was: " + message);
    }

    @Test
    @DisplayName("An integer branch conjoined with a numeric property still generates")
    void integerAllOfOnNumericPropertyStillGenerates() {
        // Given: the REST body path's construction mode — no profile, no override — and properties
        // whose Swagger allOf contribution declares the integral subset of their own numeric type.
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();

        // When: the document is generated.
        String canonical = assertDoesNotThrow(
                () -> generator.generateCanonical(HardeningFixtures.NumericRefinementDto.class),
                "number conjoined with integer is the satisfiable narrowing integer, not a conflict");

        // Then: a document is emitted, and every conjunction survives into it — the walk narrows the
        // effective type rather than refusing the document.
        JsonNode document = SchemaAssertions.assertCanonicalForm(canonical);
        for (String property : List.of("narrowedDouble", "narrowedDecimal", "widenedInt")) {
            assertFalse(
                    document.at("/properties/" + property).isMissingNode(),
                    "the property " + property + " must be published; was: " + canonical);
        }
        assertTrue(canonical.contains("\"allOf\""), "the fixture must exercise a conjunction; was: " + canonical);
    }

    @Test
    @DisplayName("A sibling property's object type must not strip a shared definition's numeric bounds")
    void siblingPropertyTypeMustNotStripSharedDefinitionBounds() {
        // Given: a profile whose BigDecimal override declares numeric bounds and no type, and a DTO
        // whose two BigDecimal properties therefore share one generated definition — only the first of
        // which conjoins an object-shaped schema through @Schema(allOf = ...).
        JsonMapperProfile bounded = HardeningFixtures.profile(
                "shared-definition-bounds",
                List.of(JsonSchemaTypeOverride.both(BigDecimal.class, HardeningFixtures.boundedNumberFragment())));

        // When: the document is generated.
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(bounded)
                .generateCanonical(HardeningFixtures.SharedDefinitionAllOfDto.class);
        JsonNode document = SchemaAssertions.assertCanonicalForm(canonical);

        // Then: the shared definition still carries the profile's declared bounds. Numeric-keyword
        // suppression is decided from one referrer's effective type, so it may only ever rewrite that
        // referrer's own local branches — never a node other properties also reference.
        JsonNode definition = document.at("/$defs/BigDecimal");
        assertFalse(
                definition.isMissingNode(),
                "the fixture must exercise a shared $defs entry for the overridden class; was: " + canonical);
        assertEquals(
                "0",
                String.valueOf(definition.get("minimum")),
                "the shared definition must keep the profile's declared minimum; was: " + canonical);
        assertEquals(
                "1000",
                String.valueOf(definition.get("maximum")),
                "the shared definition must keep the profile's declared maximum; was: " + canonical);
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
            // integer is the integral subset of number, so conjoining them narrows to integer.
            assertNoConflict("{\"type\":\"number\",\"allOf\":[{\"type\":\"integer\"}]}");
            assertNoConflict("{\"type\":\"integer\",\"allOf\":[{\"type\":\"number\"}]}");
        }

        @Test
        @DisplayName("The numeric narrowing does not admit a genuinely disjoint conjunction")
        void numericNarrowingStillRejectsDisjointTypes() {
            // Neither numeric type is satisfiable together with a string or an object...
            assertConflict("{\"type\":\"number\",\"allOf\":[{\"type\":\"string\"}]}");
            assertConflict("{\"type\":\"integer\",\"allOf\":[{\"type\":\"object\"}]}");
            // ...and the narrowing to integer stays disjoint from everything number was.
            assertConflict("{\"type\":\"number\",\"allOf\":[{\"type\":\"integer\"},{\"type\":\"string\"}]}");
        }

        @Test
        @DisplayName("A property name containing / or ~ is escaped per RFC 6901 in the diagnostic path")
        void pointerTokensAreEscapedInTheDiagnosticPath() {
            // A JSON Pointer reserves '~' and '/', so a property literally named "a/b~c" must appear
            // in the path as "a~1b~0c" — otherwise the diagnostic names a location that cannot be
            // resolved back to the node it is about, and "a/b~c" reads as two path segments.
            JsonSchemaGenerationException failure = assertThrows(
                    JsonSchemaGenerationException.class,
                    () -> DisjointTypeDetector.requireNoDisjointTypes(HardeningFixtures.read(
                            "{\"properties\":{\"a/b~c\":{\"type\":\"string\",\"allOf\":[{\"type\":\"integer\"}]}}}")),
                    "the conflicting property must still be rejected");
            assertTrue(
                    failure.getMessage().contains("#/properties/a~1b~0c"),
                    "the path must escape both reserved characters; was: " + failure.getMessage());
            // The escaping must not be applied twice: a literal '/' becomes ~1, never ~01.
            assertFalse(
                    failure.getMessage().contains("~01"),
                    "escaping must not double-encode; was: " + failure.getMessage());
        }

        /**
         * Asserts the walk rejects a document with a bounded diagnostic.
         *
         * @param json the document text
         */
        private void assertConflict(String json) {
            JsonSchemaGenerationException failure = assertThrows(
                    JsonSchemaGenerationException.class,
                    () -> DisjointTypeDetector.requireNoDisjointTypes(HardeningFixtures.read(json)),
                    "the walk must reject " + json);
            assertNotNull(failure.getMessage(), "the conflict must carry a message");
            assertTrue(
                    failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                    "the conflict message must stay bounded; was " + failure.getMessage());
        }

        /**
         * Asserts the walk accepts a document.
         *
         * @param json the document text
         */
        private void assertNoConflict(String json) {
            assertDoesNotThrow(
                    () -> DisjointTypeDetector.requireNoDisjointTypes(HardeningFixtures.read(json)),
                    "must accept " + json);
        }
    }

    // --- Helpers ---

    /**
     * Asserts that generating a type fails with a bounded diagnostic naming the offending property.
     *
     * @param generator the generator under test
     * @param type      the body type to generate
     * @param property  the property the message must name
     * @return the bounded failure message, so a caller can assert further on its content
     */
    private static String assertFailsNamingProperty(
            AnnotationJsonSchemaGenerator generator, Class<?> type, String property) {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(type),
                type.getSimpleName() + " must fail generation");

        String message = failure.getMessage();
        assertNotNull(message, "the guard failure must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the guard message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertTrue(
                message.contains(property), "the message must name the property '" + property + "'; was: " + message);
        assertFalse(
                message.contains(HardeningFixtures.SENTINEL_VALUE), "the guard message must not echo a fixture value");
        return message;
    }
}
