// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the construction-time contract of the profile-aware generator factories: malformed profile
 * declarations are rejected with bounded diagnostics, whole-declaration override conflicts fail both
 * direction factories, and the surviving declarations are applied by exact raw class, in the selected
 * direction only, and never to a map-key position.
 *
 * <p>Every fragment used here carries a unique {@code format} marker, so fragment application is
 * asserted by the marker's presence in — or absence from — the canonical document. That keeps the
 * assertions independent of the exact post-{@code ALLOF_CLEANUP_AT_THE_END} normal form.
 */
class GeneratorOverrideValidationTest {

    /** The id every well-formed test profile is registered under. */
    private static final String PROFILE_ID = "hardening-test-profile";

    @Test
    @DisplayName("A null profile id, mapper, override list, or list element fails construction, bounded")
    void constructionFailsOnNullProfileIdMapperListOrOverrideMember() {
        // Given: a profile declaring a null id.
        JsonMapperProfile nullId = HardeningFixtures.malformedProfile(null, new ObjectMapper(), List.of());

        // When/Then: construction fails without inventing an id for the profile.
        String nullIdMessage = assertBothDirectionsFail(nullId, "a null profile id");
        assertFalse(nullIdMessage.contains("'null'"), "a missing id must not be rendered as a quoted 'null'");
        assertTrue(nullIdMessage.contains("id"), "the message must name the violated rule; was: " + nullIdMessage);

        // Given/When/Then: a profile declaring a null mapper names its id.
        JsonMapperProfile nullMapper =
                HardeningFixtures.malformedProfile(JsonProfileId.of(PROFILE_ID), null, List.of());
        assertNamesProfileId(assertBothDirectionsFail(nullMapper, "a null mapper"));

        // Given/When/Then: a profile declaring a null override list names its id.
        JsonMapperProfile nullList =
                HardeningFixtures.malformedProfile(JsonProfileId.of(PROFILE_ID), new ObjectMapper(), null);
        assertNamesProfileId(assertBothDirectionsFail(nullList, "a null override list"));

        // Given/When/Then: a profile whose override list contains a null element names its id.
        List<JsonSchemaTypeOverride> withNullElement = new ArrayList<>(Arrays.asList(
                JsonSchemaTypeOverride.both(BigDecimal.class, HardeningFixtures.markerFragment("null-element")), null));
        JsonMapperProfile nullElement =
                HardeningFixtures.malformedProfile(JsonProfileId.of(PROFILE_ID), new ObjectMapper(), withNullElement);
        assertNamesProfileId(assertBothDirectionsFail(nullElement, "a null override list element"));
    }

    @Test
    @DisplayName("A duplicate effective (class, direction) mapping fails both direction factories")
    void wholeDeclarationConflictFailsBothDirections() {
        // Given: BOTH plus INPUT for the same class — the OUTPUT direction alone is conflict-free.
        JsonMapperProfile bothPlusInput = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(
                        JsonSchemaTypeOverride.both(BigDecimal.class, HardeningFixtures.markerFragment("both")),
                        JsonSchemaTypeOverride.input(BigDecimal.class, HardeningFixtures.markerFragment("input"))));

        // When/Then: both factories reject it, including the direction whose own view is clean.
        assertNamesProfileId(assertBothDirectionsFail(bothPlusInput, "BOTH plus INPUT"));

        // Given/When/Then: BOTH plus OUTPUT is symmetric — the INPUT direction alone is conflict-free.
        JsonMapperProfile bothPlusOutput = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(
                        JsonSchemaTypeOverride.both(BigDecimal.class, HardeningFixtures.markerFragment("both")),
                        JsonSchemaTypeOverride.output(BigDecimal.class, HardeningFixtures.markerFragment("output"))));
        assertNamesProfileId(assertBothDirectionsFail(bothPlusOutput, "BOTH plus OUTPUT"));

        // Given/When/Then: a plain INPUT duplicate also fails the conflict-free OUTPUT direction.
        JsonMapperProfile inputTwice = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(
                        JsonSchemaTypeOverride.input(BigDecimal.class, HardeningFixtures.markerFragment("first")),
                        JsonSchemaTypeOverride.input(BigDecimal.class, HardeningFixtures.markerFragment("second"))));
        assertNamesProfileId(assertBothDirectionsFail(inputTwice, "INPUT declared twice"));
    }

    @Test
    @DisplayName("Direction filtering applies an override to its own direction only")
    void directionFilteringSelectsOnlyApplicableOverrides() {
        // Given: a profile declaring an INPUT-only override for BigDecimal.
        JsonMapperProfile inputOnly = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.input(
                        BigDecimal.class, HardeningFixtures.markerFragment(HardeningFixtures.INPUT_MARKER))));

        // When: both directions generate the same BigDecimal-bearing type.
        String asInput = AnnotationJsonSchemaGenerator.forInputProfile(inputOnly)
                .generateCanonical(ProofFixtures.AmountDto.class);
        String asOutput = AnnotationJsonSchemaGenerator.forOutputProfile(inputOnly)
                .generateCanonical(ProofFixtures.AmountDto.class);

        // Then: only the input document carries the fragment; the output keeps the mapper's number type.
        assertTrue(asInput.contains(HardeningFixtures.INPUT_MARKER), "the INPUT document must carry the fragment");
        assertFalse(asOutput.contains(HardeningFixtures.INPUT_MARKER), "the OUTPUT document must not carry it");
        assertTrue(
                asOutput.contains("\"type\":\"number\""),
                "the un-overridden OUTPUT property must keep the numeric wire type; was: " + asOutput);

        // Given/When/Then: the OUTPUT-only mirror behaves symmetrically.
        JsonMapperProfile outputOnly = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.output(
                        BigDecimal.class, HardeningFixtures.markerFragment(HardeningFixtures.OUTPUT_MARKER))));
        String mirroredInput = AnnotationJsonSchemaGenerator.forInputProfile(outputOnly)
                .generateCanonical(ProofFixtures.AmountDto.class);
        String mirroredOutput = AnnotationJsonSchemaGenerator.forOutputProfile(outputOnly)
                .generateCanonical(ProofFixtures.AmountDto.class);
        assertTrue(
                mirroredOutput.contains(HardeningFixtures.OUTPUT_MARKER),
                "the OUTPUT document must carry the fragment");
        assertFalse(
                mirroredInput.contains(HardeningFixtures.OUTPUT_MARKER),
                "the INPUT document must not carry the OUTPUT-only fragment");
        assertTrue(
                mirroredInput.contains("\"type\":\"number\""),
                "the un-overridden INPUT property must keep the numeric wire type; was: " + mirroredInput);
    }

    @Test
    @DisplayName("An override matches the exact raw class only, never a subclass")
    void overrideMatchesExactRawClassOnly() {
        // Given: an override declared for the supertype only.
        JsonMapperProfile profile = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.both(
                        HardeningFixtures.Money.class,
                        HardeningFixtures.markerFragment(HardeningFixtures.EXACT_CLASS_MARKER))));
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(profile);

        // When/Then: the exact class receives the fragment.
        String exact = generator.generateCanonical(HardeningFixtures.MoneyDto.class);
        assertTrue(
                exact.contains(HardeningFixtures.EXACT_CLASS_MARKER),
                "the exactly matching property must receive the fragment; was: " + exact);

        // When/Then: a subclass does not.
        String subclass = generator.generateCanonical(HardeningFixtures.TaxedMoneyDto.class);
        assertFalse(
                subclass.contains(HardeningFixtures.EXACT_CLASS_MARKER),
                "a subclass of the overridden class must not receive the fragment; was: " + subclass);
    }

    /**
     * A map key position never receives a profile fragment, while an ordinary (non-key) type position
     * does.
     *
     * <p>The non-key half is proven with a resolved {@code List<BigDecimal>} element rather than a map
     * <em>value</em>: at the pinned Victools 4.38.0 {@code OptionPreset.PLAIN_JSON} configuration a
     * resolved {@code Map} generates a bare {@code {"type":"object"}} with no {@code
     * additionalProperties} or {@code patternProperties} member at all, so neither the key nor the
     * value type is resolved into the document and a value-side fragment assertion would be
     * unfalsifiable. That absence is asserted explicitly below so the test fails — rather than
     * silently changing meaning — if a future version starts emitting map value schemas.
     */
    @Test
    @DisplayName("A map key position never receives a fragment, while a non-key position does")
    void overrideNotAppliedToMapKeys() {
        // Given: an override declared for BigDecimal in both directions.
        JsonMapperProfile profile = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.both(
                        BigDecimal.class, HardeningFixtures.markerFragment(HardeningFixtures.MAP_POSITION_MARKER))));
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(profile);

        // When/Then: a Map<BigDecimal, String> keeps a mapper-owned key position.
        String keyed = generator.generateCanonical(HardeningFixtures.DecimalKeyedMapDto.class);
        assertFalse(
                keyed.contains(HardeningFixtures.MAP_POSITION_MARKER),
                "a map key position must stay mapper-owned; was: " + keyed);

        // When/Then: the pinned configuration resolves no map value type either — pinned explicitly.
        String valued = generator.generateCanonical(HardeningFixtures.DecimalValuedMapDto.class);
        assertFalse(
                valued.contains("additionalProperties") || valued.contains("patternProperties"),
                "the pinned configuration must still emit no map value schema; was: " + valued);

        // When/Then: an ordinary non-key type position does receive the fragment.
        String element = generator.generateCanonical(ProofFixtures.LIST_OF_BIG_DECIMAL);
        assertTrue(
                element.contains(HardeningFixtures.MAP_POSITION_MARKER),
                "a resolved collection element must receive the fragment; was: " + element);
    }

    // --- F6 (security review round 1, MEDIUM): an INPUT override for a bean-like type must close it ---

    @Test
    @DisplayName(
            "F6: an INPUT override for a bean-like type declaring neither properties nor additionalProperties"
                    + " is refused at construction, bounded")
    void openObjectOverrideForBeanLikeTypeAtInputIsRefused() {
        // Given: the documented remedy for a generator refusal, applied bare — {"type":"object"} — for a
        // bean-like type (a public-field DTO, exactly what F1's own beanLike check would also treat as
        // bean-like).
        JsonMapperProfile profile = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.input(
                        HardeningFixtures.Money.class, JsonSchemaFragment.parse("{\"type\":\"object\"}"))));

        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile),
                "an open bean-like override must be refused at construction, not accepted and left open at"
                        + " every position it resolves");

        assertNotNull(failure.getMessage(), "the refusal must carry a message");
        assertTrue(
                failure.getMessage().contains("properties"),
                "the message must name the remedy; was: " + failure.getMessage());
        assertTrue(
                failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "was: " + failure.getMessage());
    }

    @Test
    @DisplayName("F6: declaring properties (or additionalProperties) remedies the open-override refusal")
    void closedOverrideForBeanLikeTypeAtInputIsAccepted() {
        JsonMapperProfile withProperties = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.input(
                        HardeningFixtures.Money.class,
                        JsonSchemaFragment.parse("{\"type\":\"object\",\"properties\":{}}"))));
        AnnotationJsonSchemaGenerator.forInputProfile(withProperties); // must not throw

        JsonMapperProfile withAdditionalProperties = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.input(
                        HardeningFixtures.Money.class,
                        JsonSchemaFragment.parse("{\"type\":\"object\",\"additionalProperties\":false}"))));
        AnnotationJsonSchemaGenerator.forInputProfile(withAdditionalProperties); // must not throw
    }

    @Test
    @DisplayName("F6: a non-bean-like type's open override, and an OUTPUT-direction override, are unaffected")
    void openOverrideIsScopedToInputBeanLikeTypesOnly() {
        // A non-bean-like type (no introspected properties — a private final field with no accessor,
        // exactly the shape F1's own beanLike check exempts) carries no open-object risk.
        JsonMapperProfile scalarOverride = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.input(NotBeanLike.class, JsonSchemaFragment.parse("{\"type\":\"object\"}"))));
        AnnotationJsonSchemaGenerator.forInputProfile(scalarOverride); // must not throw

        // An OUTPUT-direction override is not the F1/F3 refusal's documented remedy and is unaffected.
        JsonMapperProfile outputOverride = HardeningFixtures.profile(
                PROFILE_ID,
                List.of(JsonSchemaTypeOverride.output(
                        HardeningFixtures.Money.class, JsonSchemaFragment.parse("{\"type\":\"object\"}"))));
        AnnotationJsonSchemaGenerator.forOutputProfile(outputOverride); // must not throw
    }

    /** No introspected properties: a private final field with no getter, no setter. */
    static final class NotBeanLike {
        private final String raw;

        NotBeanLike(String raw) {
            this.raw = raw;
        }
    }

    // --- Helpers ---

    /**
     * Asserts that both direction factories reject a profile, and that they agree on the message.
     *
     * @param profile the profile under test
     * @param label   the scenario label used in assertion messages
     * @return the bounded failure message both factories produced
     */
    private static String assertBothDirectionsFail(JsonMapperProfile profile, String label) {
        JsonSchemaGenerationException input = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile),
                "forInputProfile must reject " + label);
        JsonSchemaGenerationException output = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forOutputProfile(profile),
                "forOutputProfile must reject " + label);

        String message = input.getMessage();
        assertNotNull(message, "the " + label + " rejection must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the " + label + " message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertNotNull(output.getMessage(), "the " + label + " output-direction rejection must carry a message");
        return message;
    }

    /**
     * Asserts a bounded message names the profile it rejected.
     *
     * @param message the failure message
     */
    private static void assertNamesProfileId(String message) {
        assertTrue(message.contains(PROFILE_ID), "the message must name the profile id; was: " + message);
    }
}
