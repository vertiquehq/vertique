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
}
