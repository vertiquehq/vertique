// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Characterizes the shared selector-path resolver and its family-prefixed diagnostics. */
class SelectorPathsTest {

    @Test
    @DisplayName("positional, named-record, and nested-bean paths resolve with family diagnostics")
    void resolvesPositionalNamedAndNestedPathsWithFamilyPrefixedFailures() throws NoSuchMethodException {
        MethodMetadata metadata = metadata();
        String id = "request-42";
        Profile profile = new Profile("eu-west-1");
        Tenant tenant = new Tenant("tenant-a");
        Object[] arguments = {id, profile, tenant};

        Object[] values = SelectorPaths.resolve(
                "cache key", new String[] {"0", "profile.region", "2.tenant"}, metadata, arguments);

        // Positional root row.
        assertEquals(id, values[0], "a positional root must resolve the live argument");
        // Named record accessor row.
        assertEquals(profile.region(), values[1], "a named root must resolve a record component");
        // Nested bean accessor row.
        assertEquals(tenant.getTenant(), values[2], "a nested path must resolve a getTenant accessor");

        // Family-prefixed blank-path failure row. The exact family prefix makes this assertion
        // sensitive to the family argument rather than only to the exception type.
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("rate-limit key", new String[] {""}, metadata, arguments));

        assertEquals("rate-limit key selector path must not be blank", failure.getMessage());
    }

    @Test
    @DisplayName("empty selector paths retain the family-prefixed empty-path diagnostic")
    void emptyPaths_areRejectedWithFamilyDiagnostic() throws NoSuchMethodException {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[0], metadata(), new Object[] {"request-42"}));

        assertEquals("cache key paths must not be empty", failure.getMessage());
    }

    @Test
    @DisplayName("unknown roots retain the family-prefixed unresolved-argument diagnostic")
    void unknownRoot_isRejectedWithPathDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, null};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"unknown"}, metadata(), arguments));

        assertEquals("cache key selector does not resolve to an argument: unknown", failure.getMessage());
    }

    @Test
    @DisplayName("null roots retain the family-prefixed unresolved-argument diagnostic")
    void nullRoot_isRejectedWithPathDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"profile.region"}, metadata(), arguments));

        assertEquals("cache key selector does not resolve to an argument: profile.region", failure.getMessage());
    }

    @Test
    @DisplayName("blank property segments retain the family-prefixed blank-segment diagnostic")
    void blankPropertySegment_isRejectedWithFamilyDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"2..tenant"}, metadata(), arguments));

        assertEquals("cache key property path contains a blank segment", failure.getMessage());
    }

    @Test
    @DisplayName("null property results retain the family-prefixed null-result diagnostic")
    void nullProperty_isRejectedWithPathDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"2.nullProperty"}, metadata(), arguments));

        assertEquals("cache key selector resolved to null: 2.nullProperty", failure.getMessage());
    }

    @Test
    @DisplayName("bean boolean properties resolve through an isX accessor")
    void beanBooleanIsAccessor_resolvesProperty() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        Object[] values = SelectorPaths.resolve("cache key", new String[] {"2.active"}, metadata(), arguments);

        assertEquals(Boolean.TRUE, values[0]);
    }

    @Test
    @DisplayName("missing bean getters retain the family-prefixed inaccessible-property diagnostic")
    void missingGetter_isRejectedWithFamilyDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"2.missing"}, metadata(), arguments));

        assertEquals("cache key property is not an accessible scalar path: missing", failure.getMessage());
    }

    @Test
    @DisplayName("inaccessible bean getters retain the family-prefixed inaccessible-property diagnostic")
    void inaccessibleGetter_isRejectedWithFamilyDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"2.hidden"}, metadata(), arguments));

        assertEquals("cache key property is not an accessible scalar path: hidden", failure.getMessage());
    }

    @Test
    @DisplayName("failing bean getters retain the family-prefixed inaccessible-property diagnostic")
    void failingGetter_isRejectedWithFamilyDiagnostic() throws NoSuchMethodException {
        Object[] arguments = {"request-42", null, new Tenant("tenant-a")};

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SelectorPaths.resolve("cache key", new String[] {"2.failure"}, metadata(), arguments));

        assertEquals("cache key property is not an accessible scalar path: failure", failure.getMessage());
    }

    private static MethodMetadata metadata() throws NoSuchMethodException {
        Method method = Target.class.getDeclaredMethod("resolve", String.class, Profile.class, Tenant.class);
        return new ReflectiveMethodMetadata(
                method,
                List.of(
                        new ReflectiveParameterMetadata(0, "id", String.class, String.class, null),
                        new ReflectiveParameterMetadata(1, "profile", Profile.class, Profile.class, null),
                        new ReflectiveParameterMetadata(2, "tenant", Tenant.class, Tenant.class, null)));
    }

    static final class Target {
        void resolve(String id, Profile profile, Tenant tenant) {}
    }

    static record Profile(String region) {}

    static final class Tenant {
        private final String value;

        Tenant(String value) {
            this.value = value;
        }

        public String getTenant() {
            return value;
        }

        public boolean isActive() {
            return true;
        }

        public String getNullProperty() {
            return null;
        }

        private String getHidden() {
            return "hidden";
        }

        public String getFailure() {
            throw new IllegalStateException("getter failure");
        }
    }
}
