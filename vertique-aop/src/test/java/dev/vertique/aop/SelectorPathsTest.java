// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Characterizes the shared selector-path resolver and its family-prefixed diagnostics. */
class SelectorPathsTest {

    @Test
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

        assertTrue(
                failure.getMessage().startsWith("rate-limit key selector path must not be blank"),
                "blank selector diagnostics must retain the supplied family prefix");
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
    }
}
