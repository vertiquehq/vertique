// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies value semantics of substituted reflective member types. */
class TypeResolverMemberTypeTest {
    static class Base<T> {
        List<T>[] array;
        List<List<T>> nested;
    }

    static class Concrete extends Base<String> {}

    static class Expected {
        List<String>[] array;
        List<List<String>> nested;
    }

    @Test
    @DisplayName("Resolved member types retain reflection value semantics")
    void resolvedTypesMatchReflectionValues() throws Exception {
        for (String name : List.of("array", "nested")) {
            Type declared = Base.class.getDeclaredField(name).getGenericType();
            Type expected = Expected.class.getDeclaredField(name).getGenericType();
            Type actual = TypeResolver.resolveMemberType(Concrete.class, declared);
            assertEquals(expected, actual);
            assertEquals(actual, expected);
            assertEquals(expected.hashCode(), actual.hashCode());
            assertEquals(expected.getTypeName(), actual.getTypeName());
            assertEquals(expected.toString(), actual.toString());
        }
    }
}
