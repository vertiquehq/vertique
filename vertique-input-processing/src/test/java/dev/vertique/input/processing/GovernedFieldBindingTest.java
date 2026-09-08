// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.TestStripControlsSanitizer;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0247 Amendment 3 — a field carrying a declared chain that the codec binds no wire key into
 * fails registration instead of silently never running.
 *
 * <p>The engine keys metadata on the Java field name and a codec keys its binding on the property
 * name it derives from accessors, so {@code setStreet} writing {@code streetName} leaves the field's
 * policy unreachable from the wire. The resolver here is a stub reporting what a codec would bind,
 * which is the unit boundary: what is under test is that the engine honors
 * {@link InputFieldNameResolver#boundJavaNames}, not how one codec enumerates them.
 */
class GovernedFieldBindingTest {

    private DefaultInputObjectProcessor processor;

    /** A governed field the codec reaches through accessors of a different name. */
    static class AccessorNamed {
        @Sanitize(TestStripControlsSanitizer.class)
        String streetName;

        String city;
    }

    /** A field with only a skip flag that the codec never binds. */
    static class SkipOnly {
        @SkipSanitization
        String hidden;

        String shown;
    }

    /** Owner whose nested field's type carries the unbound governed field. */
    static class Outer {
        AccessorNamed inner;
    }

    /** Projection reporting, per type, the Java names a codec binds into. */
    private static InputFieldNameResolver binding(Map<Class<?>, Set<String>> bound) {
        return binding(bound, Map.of());
    }

    /** Projection reporting bound Java names and, per type, keys the codec cannot route. */
    private static InputFieldNameResolver binding(
            Map<Class<?>, Set<String>> bound, Map<Class<?>, Set<String>> unroutable) {
        return new InputFieldNameResolver() {
            @Override
            public String logicalName(Class<?> ownerType, String wireName) {
                return wireName;
            }

            @Override
            public Set<String> boundJavaNames(Class<?> ownerType) {
                return bound.get(ownerType);
            }

            @Override
            public Set<String> unroutableWireNames(Class<?> ownerType) {
                return unroutable.getOrDefault(ownerType, Set.of());
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
                cls -> new TestStripControlsSanitizer());
    }

    @Test
    @DisplayName("a governed field the codec binds no key into fails registration, naming the field and what is bound")
    void governedFieldOutsideTheBoundNamesFailsRegistration() {
        InputFieldNameResolver resolver = binding(Map.of(AccessorNamed.class, Set.of("street", "city")));

        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(AccessorNamed.class, resolver));

        String message = ex.getMessage();
        assertTrue(message.contains(AccessorNamed.class.getName()), "names the type: " + message);
        assertTrue(message.contains("'streetName'"), "names the governed field: " + message);
        assertTrue(message.contains("street"), "names what the codec binds: " + message);
        assertTrue(message.contains("silently never run"), "says why: " + message);
    }

    @Test
    @DisplayName("a governed field among the bound names is accepted")
    void governedFieldAmongTheBoundNamesIsAccepted() {
        InputFieldNameResolver resolver = binding(Map.of(AccessorNamed.class, Set.of("streetName", "city")));

        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(AccessorNamed.class, resolver));
    }

    @Test
    @DisplayName("a resolver that cannot enumerate what it binds is trusted")
    void unknownBoundNamesAreTrusted() {
        assertDoesNotThrow(
                () -> processor.precomputeFieldNameResolution(AccessorNamed.class, InputFieldNameResolver.IDENTITY));
        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(AccessorNamed.class, binding(Map.of())));
    }

    @Test
    @DisplayName("an unbound field carrying only a skip flag is accepted — it suppresses nothing that would run")
    void unboundSkipOnlyFieldIsAccepted() {
        InputFieldNameResolver resolver = binding(Map.of(SkipOnly.class, Set.of("shown")));

        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(SkipOnly.class, resolver));
    }

    @Test
    @DisplayName("the check reaches a nested type through the owner walk")
    void nestedTypeIsCheckedThroughTheWalk() {
        InputFieldNameResolver resolver =
                binding(Map.of(Outer.class, Set.of("inner"), AccessorNamed.class, Set.of("street", "city")));

        ConfigurationException ex = assertThrows(
                ConfigurationException.class, () -> processor.precomputeFieldNameResolution(Outer.class, resolver));
        assertTrue(ex.getMessage().contains(AccessorNamed.class.getName()), ex.getMessage());
    }

    /** A type with no governed field at all. */
    static class Ungoverned {
        String a;

        String b;
    }

    @Test
    @DisplayName("an unroutable key on an owner with a governed field fails registration — undecidable is refused")
    void unroutableKeyBesideAGovernedFieldFailsRegistration() {
        // Every governed field IS bound, so the bound check passes; the creator parameter behind
        // 'street_name' may still write any of them, and nothing can tell which.
        InputFieldNameResolver resolver = binding(
                Map.of(AccessorNamed.class, Set.of("streetName", "city")),
                Map.of(AccessorNamed.class, Set.of("street_name")));

        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> processor.precomputeFieldNameResolution(AccessorNamed.class, resolver));
        String message = ex.getMessage();
        assertTrue(message.contains("[street_name]"), "names the key: " + message);
        assertTrue(message.contains("@JsonProperty"), "names the remedy: " + message);
    }

    @Test
    @DisplayName("an unroutable key on an owner with no governed field is accepted")
    void unroutableKeyWithoutAGovernedFieldIsAccepted() {
        InputFieldNameResolver resolver =
                binding(Map.of(Ungoverned.class, Set.of("a")), Map.of(Ungoverned.class, Set.of("b")));

        assertDoesNotThrow(() -> processor.precomputeFieldNameResolution(Ungoverned.class, resolver));
    }
}
