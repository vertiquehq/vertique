// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves {@link GeneratedRestApplicationRegistration#of}'s fail-closed re-checks (name grammar,
 * reserved names, normalized path form, and exactly-one-of membership), and pins the factory's
 * signature and the class's shape for generated code (AR3-005).
 *
 * <p>{@code of(...)} is the runtime backstop for a registration a well-behaved annotation
 * processor never produces: every row here calls it directly, bypassing the compile-time checks
 * {@code RestApplicationScanner} normally applies first.
 */
class GeneratedRestApplicationRegistrationTest {

    /** {@code [a-z0-9][a-z0-9_-]{0,63}} — the application name grammar (also FR-022, TP-002). */
    private static final String GRAMMAR_PHRASE = "[a-z0-9][a-z0-9_-]{0,63}";

    private static final String RESERVED_PHRASE = "is reserved";
    private static final String NORMALIZED_PHRASE = "normalized";
    private static final String MEMBERSHIP_PHRASE = "exactly one of";

    /** Minimal declaring-type test double: this class exercises only the registration factory. */
    private interface Declared {}

    /** Minimal resource-class test double for the {@code resources} argument. */
    private static final class R {}

    // -----------------------------------------------------------------------------------------
    // TP-009 — the registration factory re-checks name, path, and membership, failing closed
    // -----------------------------------------------------------------------------------------

    /** One TP-009 row: a display name and the full verification it runs. */
    private record FactoryCase(String name, Runnable verification) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Stream<FactoryCase> factoryCases() {
        List<FactoryCase> cases = new ArrayList<>();

        cases.add(validResourcesFormCase());
        cases.add(validDiscoveryFormCase());

        cases.add(nullArgumentCase(
                "declaringType",
                () -> GeneratedRestApplicationRegistration.of(
                        null, "api", "/api/v1", List.of(R.class), false, "", true)));
        cases.add(nullArgumentCase(
                "name",
                () -> GeneratedRestApplicationRegistration.of(
                        Declared.class, null, "/api/v1", List.of(R.class), false, "", true)));
        cases.add(nullArgumentCase(
                "path",
                () -> GeneratedRestApplicationRegistration.of(
                        Declared.class, "api", null, List.of(R.class), false, "", true)));
        cases.add(nullArgumentCase(
                "resources",
                () -> GeneratedRestApplicationRegistration.of(
                        Declared.class, "api", "/api/v1", null, false, "", true)));
        cases.add(nullArgumentCase(
                "openapiPath",
                () -> GeneratedRestApplicationRegistration.of(
                        Declared.class, "api", "/api/v1", List.of(R.class), false, null, true)));

        for (String name : List.of("Api", "-api", "api.v1", "", "a".repeat(65))) {
            cases.add(invalidNameCase(name, GRAMMAR_PHRASE));
        }
        for (String name : List.of("none", "null")) {
            cases.add(invalidNameCase(name, RESERVED_PHRASE));
        }

        for (String path : List.of("", "api", "/api/", "/api/*", "//x", "/a/../b", "/a/./b", "/a%2Fb", "/a b")) {
            cases.add(invalidPathCase(path));
        }

        cases.add(invalidMembershipCase("both resources and discover", List.of(R.class), true));
        cases.add(invalidMembershipCase("neither resources nor discover", List.of(), false));

        return cases.stream();
    }

    private static FactoryCase validResourcesFormCase() {
        return new FactoryCase("valid call — resources form", () -> {
            GeneratedRestApplicationRegistration registration = GeneratedRestApplicationRegistration.of(
                    Declared.class, "api", "/api/v1", List.of(R.class), false, "", true);
            assertEquals(Declared.class, registration.declaringType(), "declaringType()");
            assertEquals("api", registration.name(), "name()");
            assertEquals("/api/v1", registration.path(), "path()");
            assertEquals(List.of(R.class), registration.resources(), "resources()");
            assertFalse(registration.discover(), "discover()");
            assertEquals("", registration.openapiPath(), "openapiPath()");
            assertTrue(registration.active(), "active()");
        });
    }

    private static FactoryCase validDiscoveryFormCase() {
        String name64 = "a".repeat(64);
        return new FactoryCase("valid call — discovery form", () -> {
            GeneratedRestApplicationRegistration registration = GeneratedRestApplicationRegistration.of(
                    Declared.class, name64, "/", List.of(), true, "contract.yaml", false);
            assertEquals(Declared.class, registration.declaringType(), "declaringType()");
            assertEquals(name64, registration.name(), "name()");
            assertEquals("/", registration.path(), "path()");
            assertEquals(List.of(), registration.resources(), "resources()");
            assertTrue(registration.discover(), "discover()");
            assertEquals("contract.yaml", registration.openapiPath(), "openapiPath()");
            assertFalse(registration.active(), "active()");
        });
    }

    private static FactoryCase nullArgumentCase(String argumentName, Executable invocation) {
        return new FactoryCase("null " + argumentName, () -> {
            NullPointerException thrown = assertThrows(
                    NullPointerException.class,
                    invocation,
                    () -> "a null " + argumentName + " must throw NullPointerException");
            assertTrue(
                    thrown.getMessage() != null && thrown.getMessage().contains(argumentName),
                    () -> "Expected the NullPointerException to name '" + argumentName + "' but was: "
                            + thrown.getMessage());
        });
    }

    private static FactoryCase invalidNameCase(String name, String expectedPhrase) {
        return new FactoryCase("invalid name '" + name + "'", () -> {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> GeneratedRestApplicationRegistration.of(
                            Declared.class, name, "/api/v1", List.of(R.class), false, "", true),
                    () -> "name '" + name + "' must be rejected");
            assertMessageNamesDeclaredAnd(thrown, expectedPhrase);
        });
    }

    private static FactoryCase invalidPathCase(String path) {
        return new FactoryCase("invalid path '" + path + "'", () -> {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> GeneratedRestApplicationRegistration.of(
                            Declared.class, "api", path, List.of(R.class), false, "", true),
                    () -> "path '" + path + "' must be rejected");
            assertMessageNamesDeclaredAnd(thrown, NORMALIZED_PHRASE);
        });
    }

    private static FactoryCase invalidMembershipCase(String label, List<Class<?>> resources, boolean discover) {
        return new FactoryCase("invalid membership — " + label, () -> {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> GeneratedRestApplicationRegistration.of(
                            Declared.class, "api", "/api", resources, discover, "", true),
                    () -> "membership (" + label + ") must be rejected");
            assertMessageNamesDeclaredAnd(thrown, MEMBERSHIP_PHRASE);
        });
    }

    private static void assertMessageNamesDeclaredAnd(IllegalArgumentException thrown, String expectedPhrase) {
        String message = thrown.getMessage();
        assertNotNull(message, "Expected a non-null exception message");
        assertTrue(
                message.contains(Declared.class.getName()),
                () -> "Expected the message to name '" + Declared.class.getName() + "' but was: " + message);
        assertTrue(
                message.contains(expectedPhrase),
                () -> "Expected the message to contain '" + expectedPhrase + "' but was: " + message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("factoryCases")
    @DisplayName("The registration factory re-checks name, path, and membership, failing closed")
    void factoryRechecksNamePathAndMembership(FactoryCase testCase) {
        testCase.verification().run();
    }

    // -----------------------------------------------------------------------------------------
    // TP-011 — the registration factory's signature is pinned for generated code
    // -----------------------------------------------------------------------------------------

    /** The expected {@code of(...)} parameter types, in order (AR3-005: pinned for generated code). */
    private static final Class<?>[] OF_PARAMETER_TYPES = {
        Class.class, String.class, String.class, List.class, boolean.class, String.class, boolean.class
    };

    @Test
    @DisplayName("The registration factory's signature is pinned for generated code")
    void factorySignatureIsPinnedForGeneratedCode() throws NoSuchMethodException {
        Class<GeneratedRestApplicationRegistration> type = GeneratedRestApplicationRegistration.class;

        assertTrue(
                Modifier.isFinal(type.getModifiers()), "GeneratedRestApplicationRegistration must be declared final");
        assertTrue(
                Arrays.stream(type.getDeclaredConstructors()).noneMatch(c -> Modifier.isPublic(c.getModifiers())),
                "GeneratedRestApplicationRegistration must declare no public constructor");

        Method of = type.getDeclaredMethod("of", OF_PARAMETER_TYPES);
        assertTrue(
                Modifier.isPublic(of.getModifiers()) && Modifier.isStatic(of.getModifiers()),
                "of(Class, String, String, List, boolean, String, boolean) must be public static");
        assertEquals(type, of.getReturnType(), "of(...) must return GeneratedRestApplicationRegistration");

        assertAccessor(type, "declaringType", Class.class);
        assertAccessor(type, "name", String.class);
        assertAccessor(type, "path", String.class);
        assertAccessor(type, "resources", List.class);
        assertAccessor(type, "discover", boolean.class);
        assertAccessor(type, "openapiPath", String.class);
        assertAccessor(type, "active", boolean.class);
    }

    private static void assertAccessor(Class<?> type, String name, Class<?> returnType) throws NoSuchMethodException {
        Method accessor = type.getDeclaredMethod(name);
        assertTrue(Modifier.isPublic(accessor.getModifiers()), () -> name + "() must be public");
        assertEquals(returnType, accessor.getReturnType(), () -> name + "() must return " + returnType);
    }
}
