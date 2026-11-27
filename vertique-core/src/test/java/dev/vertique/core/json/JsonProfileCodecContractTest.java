// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.buffer.Buffer;
import java.io.OutputStream;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract shape of {@link JsonProfileCodec}: the declared operations and the absence of Jackson types. */
class JsonProfileCodecContractTest {

    private static String signature(Method method) {
        return method.getName()
                + Arrays.stream(method.getParameterTypes()).map(Class::getName).toList();
    }

    private static void collect(Type type, List<String> out) {
        if (type instanceof Class<?> c) {
            out.add(c.getName());
        } else if (type instanceof ParameterizedType p) {
            collect(p.getRawType(), out);
            for (Type a : p.getActualTypeArguments()) {
                collect(a, out);
            }
        } else if (type instanceof GenericArrayType g) {
            collect(g.getGenericComponentType(), out);
        } else if (type instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) {
                collect(b, out);
            }
            for (Type b : w.getLowerBounds()) {
                collect(b, out);
            }
        } else if (type instanceof TypeVariable<?> v) {
            for (Type b : v.getBounds()) {
                collect(b, out);
            }
        }
    }

    @Test
    @DisplayName("JsonProfileCodec declares exactly the neutral operations")
    void declaredMethods_areExactlyTheNeutralOperations() {
        // Given: the reflected declared methods of the interface.
        Set<String> actual = new TreeSet<>();
        for (Method m : JsonProfileCodec.class.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                actual.add(signature(m));
            }
        }

        // When: compared with the contract's operations (names plus parameter types).
        Set<String> expected = new TreeSet<>(List.of(
                "id[]",
                "encode[" + Object.class.getName() + "]",
                "encodeToString[" + Object.class.getName() + "]",
                "encodeTo[" + Object.class.getName() + ", " + OutputStream.class.getName() + "]",
                "decode[" + Buffer.class.getName() + ", " + Type.class.getName() + "]",
                "decode[" + String.class.getName() + ", " + Type.class.getName() + "]",
                "convert[" + Object.class.getName() + ", " + Type.class.getName() + "]",
                "unwrap[" + Class.class.getName() + "]",
                "jsonSchemaTypeOverrides[]"));

        // Then: the sets are equal.
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("no parameter, return or exception type of JsonProfileCodec is a Jackson type")
    void signatures_mentionNoJacksonType() {
        // Given: every type mentioned by every declared method, generic arguments included.
        List<String> mentioned = new ArrayList<>();
        for (Method m : JsonProfileCodec.class.getDeclaredMethods()) {
            collect(m.getGenericReturnType(), mentioned);
            for (Type p : m.getGenericParameterTypes()) {
                collect(p, mentioned);
            }
            for (Type e : m.getGenericExceptionTypes()) {
                collect(e, mentioned);
            }
        }

        // When/Then: none is in a com.fasterxml.jackson package.
        List<String> jackson = mentioned.stream()
                .filter(n -> n.startsWith("com.fasterxml.jackson"))
                .toList();
        assertTrue(jackson.isEmpty(), "Jackson types in the neutral contract: " + jackson);
    }

    @Test
    @DisplayName("unwrap returns an Optional")
    void unwrap_returnsOptional() throws Exception {
        assertEquals(
                Optional.class,
                JsonProfileCodec.class.getMethod("unwrap", Class.class).getReturnType());
    }
}
