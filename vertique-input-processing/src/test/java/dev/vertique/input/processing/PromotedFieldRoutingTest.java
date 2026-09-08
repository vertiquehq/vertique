// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.ProjectedDto;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.TestStripControlsSanitizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GH-376 — the engine routes a key its codec <em>promoted</em> out of a nested member to the
 * policies declared on the type that field actually lives on.
 *
 * <p>The resolver here is a hand-written stub rather than the Jackson one, which is the correct unit
 * boundary: what is under test is that the engine honors
 * {@link InputFieldNameResolver#promotedFields}, not how one codec composes it.
 * {@code JacksonFieldNameResolverTest} covers the composition — a bare {@code @JsonUnwrapped}, a
 * prefixed one, and nested unwrapping — against real Jackson introspection.
 */
class PromotedFieldRoutingTest {

    private DefaultInputObjectProcessor processor;

    /** The type an unwrapped member's fields are declared on. */
    static class Address {

        @Sanitize(TestStripControlsSanitizer.class)
        String street;

        String city;
    }

    /** The enclosing type: on the wire, its member's fields arrive as its own keys. */
    static class UnwrappedOwner {
        String name;
        Address address;
    }

    /** A promoted type that declares no policy at all. */
    static class Plain {
        String value;
    }

    /** Enclosing type whose promoted member carries nothing. */
    static class PlainOwner {
        String name;
        Plain plain;
    }

    /** Projection that promotes the given wire keys onto one owner, as unwrapping does. */
    private static InputFieldNameResolver promoting(Class<?> owner, Map<String, PromotedField> promoted) {
        return new InputFieldNameResolver() {
            @Override
            public String logicalName(Class<?> ownerType, String wireName) {
                return wireName;
            }

            @Override
            public Map<String, PromotedField> promotedFields(Class<?> ownerType) {
                return ownerType == owner ? promoted : Map.of();
            }
        };
    }

    @BeforeEach
    void setUp() {
        processor = new DefaultInputObjectProcessor(
                new InputPolicyMetadataResolver(),
                cls -> {
                    throw new IllegalArgumentException("Unknown canonicalizer: " + cls);
                },
                cls -> {
                    if (cls == TestStripControlsSanitizer.class) {
                        return new TestStripControlsSanitizer();
                    }
                    throw new IllegalArgumentException("Unknown sanitizer: " + cls);
                });
    }

    @Test
    @DisplayName("a promoted key receives the policies declared on the type it is bound into")
    void promotedKeyReceivesTheDeclaringTypesPolicies() {
        InputFieldNameResolver resolver = promoting(
                UnwrappedOwner.class,
                Map.of(
                        "street", new PromotedField(Address.class, "street", List.of("address")),
                        "city", new PromotedField(Address.class, "city", List.of("address"))));

        // The wire is FLAT: street and city arrive as keys of UnwrappedOwner, which declares neither.
        Map<String, Object> input = Map.of("name", "ada", "street", "main\u0000street", "city", "lo\u0000ndon");

        Object result = processor.processInput(
                input, UnwrappedOwner.class, EffectiveInputPolicies.NONE, InputLocation.BODY, resolver);

        Map<?, ?> out = (Map<?, ?>) result;
        assertEquals("mainstreet", out.get("street"), "Address.street's declared @Sanitize must run");
        assertEquals("lo\u0000ndon", out.get("city"), "city declares nothing, so nothing is applied");
        assertEquals("ada", out.get("name"), "the owner's own field is untouched");
    }

    @Test
    @DisplayName("the emitted map keeps the wire key, so the codec still binds it")
    void promotedKeyKeepsItsWireKey() {
        InputFieldNameResolver resolver = promoting(
                UnwrappedOwner.class, Map.of("street", new PromotedField(Address.class, "street", List.of("address"))));

        Object result = processor.processInput(
                Map.of("street", "a\u0000b"),
                UnwrappedOwner.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                resolver);

        assertTrue(
                ((Map<?, ?>) result).containsKey("street"),
                "the projection selects metadata, it never renames what the codec binds");
    }

    @Test
    @DisplayName("a key promoted from a field with no declared policy is left alone")
    void promotedKeyWithNoPolicyIsUntouched() {
        InputFieldNameResolver resolver =
                promoting(PlainOwner.class, Map.of("value", new PromotedField(Plain.class, "value", List.of("plain"))));

        Object result = processor.processInput(
                Map.of("value", "keep\u0000me"),
                PlainOwner.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                resolver);

        assertEquals("keep\u0000me", ((Map<?, ?>) result).get("value"), "nothing declared, nothing applied");
    }

    @Test
    @DisplayName("an inherited invocation-level chain still reaches a promoted key")
    void inheritedChainStillReachesAPromotedKey() {
        InputFieldNameResolver resolver =
                promoting(PlainOwner.class, Map.of("value", new PromotedField(Plain.class, "value", List.of("plain"))));

        Object result = processor.processInput(
                Map.of("value", "x\u0000y"),
                PlainOwner.class,
                new EffectiveInputPolicies(List.of(), List.of(TestStripControlsSanitizer.class)),
                InputLocation.BODY,
                resolver);

        assertEquals("xy", ((Map<?, ?>) result).get("value"), "the inherited chain applies as before");
    }

    @Test
    @DisplayName("a resolver that promotes nothing behaves exactly as before")
    void aResolverThatPromotesNothingIsUnaffected() {
        Object result = assertDoesNotThrow(() -> processor.processInput(
                Map.of("name", "ada"),
                UnwrappedOwner.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                InputFieldNameResolver.IDENTITY));

        assertEquals("ada", ((Map<?, ?>) result).get("name"));
    }

    @Test
    @DisplayName("registration accepts a promoted policy the reflective walker can route")
    void registrationAcceptsAPromotedPolicyOnTheReflectivePath() {
        InputFieldNameResolver resolver = promoting(
                UnwrappedOwner.class, Map.of("street", new PromotedField(Address.class, "street", List.of("address"))));

        // No generated processor exists for UnwrappedOwner, so the reflective walker routes the key.
        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(UnwrappedOwner.class, resolver));
    }

    @Test
    @DisplayName("a promoted policy the generated path cannot route fails registration, naming all four")
    void promotedPolicyUnderAGeneratedProcessorFailsRegistration() {
        InputFieldNameResolver resolver = promoting(
                ProjectedDto.class, Map.of("street", new PromotedField(Address.class, "street", List.of("address"))));

        // ProjectedDto HAS a generated processor, whose field-name switch is emitted from its own
        // declared fields, so a promoted key would fall to its default branch and lose the chain.
        // ConfigurationException, as every neighbouring startup failure throws.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(ProjectedDto.class, resolver));

        String message = ex.getMessage();
        assertTrue(message.contains("ProjectedDto"), "names the owner: " + message);
        assertTrue(message.contains("street"), "names the promoted key and field: " + message);
        assertTrue(message.contains(Address.class.getName()), "names the declaring type: " + message);
        assertTrue(message.contains("generated input processor"), "says why: " + message);
    }

    @Test
    @DisplayName("a promoted field with no policy under a generated processor is accepted")
    void promotedFieldWithoutPolicyUnderAGeneratedProcessorIsAccepted() {
        InputFieldNameResolver resolver = promoting(
                ProjectedDto.class, Map.of("value", new PromotedField(Plain.class, "value", List.of("plain"))));

        // Nothing is declared on Plain.value, so nothing can be silently skipped.
        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(ProjectedDto.class, resolver));
    }

    /** Enclosing type whose unwrapped member suppresses sanitization for everything below it. */
    static class SkippingOwner {
        String name;

        @SkipSanitization
        Address address;
    }

    @Test
    @DisplayName("a @SkipSanitization on the unwrapped member still suppresses the promoted field's chain")
    void enclosingMemberSkipFlagsStillApplyToAPromotedKey() {
        InputFieldNameResolver resolver = promoting(
                SkippingOwner.class, Map.of("street", new PromotedField(Address.class, "street", List.of("address"))));

        // Address.street declares @Sanitize, but the member it is promoted out of declares
        // @SkipSanitization, and a skip is sticky for everything below it. The engine descends
        // through the enclosing member before applying the promoted field's own chain, so the skip
        // reaches it exactly as it would through a named nested property.
        Object result = processor.processInput(
                Map.of("street", "keep\u0000me"),
                SkippingOwner.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                resolver);

        assertEquals(
                "keep\u0000me",
                ((Map<?, ?>) result).get("street"),
                "the enclosing member's skip must survive promotion");
    }

    // --- ADR-0247 Amendment 3: registration verifies a promoted key can be routed ---

    /** Inner type governed at type level only, so no field of its own carries a chain. */
    @Sanitize(TestStripControlsSanitizer.class)
    static class Branded {
        String value;
    }

    /** Enclosing type for {@link Branded}. */
    static class BrandedOwner {
        Branded branded;
    }

    /** Inner type that opts out of sanitization at type level and declares nothing to run. */
    @SkipSanitization
    static class Quiet {
        String token;
    }

    @Test
    @DisplayName(
            "a type-level skip on the promoted type is refused under a generated processor — a skip changes what runs")
    void typeLevelSkipUnderAGeneratedProcessorFailsRegistration() {
        InputFieldNameResolver resolver = promoting(
                ProjectedDto.class, Map.of("token", new PromotedField(Quiet.class, "token", List.of("quiet"))));

        // Quiet declares no chain, so declaresPolicies sees nothing; routing would honor its skip
        // where the generated default arm applies the owner's inherited chain instead.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(ProjectedDto.class, resolver));
        assertTrue(ex.getMessage().contains("generated input processor"), ex.getMessage());
    }

    /** Owner whose member is declared as one type while the projection promotes from another. */
    static class MismatchedOwner {
        Plain value;
    }

    @Test
    @DisplayName("a type-level policy on the promoted type is refused under a generated processor too")
    void typeLevelPolicyUnderAGeneratedProcessorFailsRegistration() {
        InputFieldNameResolver resolver = promoting(
                ProjectedDto.class, Map.of("value", new PromotedField(Branded.class, "value", List.of("branded"))));

        // Branded.value carries no chain of its own; the chain is on the type. Routing the key would
        // apply it, the generated switch cannot, so the gate must fire on the type-level policy.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(ProjectedDto.class, resolver));
        assertTrue(ex.getMessage().contains("generated input processor"), ex.getMessage());
    }

    @Test
    @DisplayName("a promoted policy whose path the reflective walker cannot descend fails registration")
    void unreachablePathWithAPolicyFailsRegistration() {
        InputFieldNameResolver resolver = promoting(
                UnwrappedOwner.class, Map.of("street", new PromotedField(Address.class, "street", List.of("nowhere"))));

        // UnwrappedOwner tracks no field 'nowhere', so the engine could never reach Address.street's
        // chain through it; the request path would fall back to the owner's treatment silently.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(UnwrappedOwner.class, resolver));
        String message = ex.getMessage();
        assertTrue(message.contains("[nowhere]"), "names the path: " + message);
        assertTrue(message.contains(Address.class.getName() + ".street"), "names the site: " + message);
    }

    @Test
    @DisplayName("a promoted policy whose path lands on a different type than the projection promotes from fails")
    void mismatchedLandingTypeFailsRegistration() {
        InputFieldNameResolver resolver = promoting(
                MismatchedOwner.class, Map.of("street", new PromotedField(Address.class, "street", List.of("value"))));

        // The projection says the key binds into Address; descending 'value' lands this engine on
        // Plain — the shape a generic member produces when the codec resolves its type argument and
        // the declared field type erases. Address.street's chain would never be found.
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(MismatchedOwner.class, resolver));
        String message = ex.getMessage();
        assertTrue(message.contains(Plain.class.getName()), "names where the engine lands: " + message);
        assertTrue(message.contains(Address.class.getName()), "and where the projection points: " + message);
    }

    @Test
    @DisplayName("a promoted key whose routing would apply nothing is accepted whatever its path")
    void promotedKeyThatChangesNothingIsAccepted() {
        // Plain declares nothing and PlainOwner.plain carries nothing, so the default treatment is
        // already exactly what routing would produce; an unreachable path loses nothing.
        InputFieldNameResolver resolver =
                promoting(PlainOwner.class, Map.of("value", new PromotedField(Plain.class, "value", List.of("gone"))));

        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(PlainOwner.class, resolver));
    }

    @Test
    @DisplayName("a promoted key receives the declaring type's object-level chain even without field metadata")
    void promotedKeyReceivesTheDeclaringTypesObjectLevelChain() {
        // 'label' is not a field Branded tracks — the shape of an inner property the codec binds
        // through an accessor of another name. The value is still processed AS Branded's, so the
        // type-level chain runs, exactly as for an unknown key inside a named nested object.
        InputFieldNameResolver resolver = promoting(
                BrandedOwner.class, Map.of("label", new PromotedField(Branded.class, "label", List.of("branded"))));

        Object result = processor.processInput(
                Map.of("label", "a\u0000b"),
                BrandedOwner.class,
                EffectiveInputPolicies.NONE,
                InputLocation.BODY,
                resolver);

        assertEquals("ab", ((Map<?, ?>) result).get("label"), "Branded's type-level sanitizer must run");
    }
}
