// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link KeyedBy} lives at the canonical {@code dev.vertique.core.json} package and
 * that the old {@code dev.vertique.core.config.KeyedBy} location no longer exists.
 *
 * <p>Proofs:
 * <ul>
 *   <li>{@code dev.vertique.core.json.KeyedBy} loads, is an annotation with {@code @Target(FIELD)}
 *       and {@code @Retention(RUNTIME)}, and declares a {@code String value()} method;</li>
 *   <li>{@code dev.vertique.core.config.KeyedBy} throws {@link ClassNotFoundException}, confirming
 *       no lingering copy at the old location.</li>
 * </ul>
 */
class KeyedByPackageTest {

    @Test
    @DisplayName("dev.vertique.core.json.KeyedBy loads and is an annotation with FIELD target and RUNTIME retention")
    void keyedBy_isInCoreJsonPackage() throws Exception {
        Class<?> cls = Class.forName("dev.vertique.core.json.KeyedBy");

        assertTrue(cls.isAnnotation(), "KeyedBy must be an annotation type");

        Retention retention = cls.getAnnotation(Retention.class);
        assertNotNull(retention, "KeyedBy must declare @Retention");
        assertEquals(RetentionPolicy.RUNTIME, retention.value(), "KeyedBy must have RUNTIME retention");

        Target target = cls.getAnnotation(Target.class);
        assertNotNull(target, "KeyedBy must declare @Target");
        assertEquals(1, target.value().length, "KeyedBy must target exactly one element type");
        assertEquals(ElementType.FIELD, target.value()[0], "KeyedBy must target FIELD");
    }

    @Test
    @DisplayName("dev.vertique.core.json.KeyedBy declares a String value() method")
    void keyedBy_declaresStringValueMethod() throws Exception {
        Class<?> cls = Class.forName("dev.vertique.core.json.KeyedBy");

        var valueMethod = cls.getDeclaredMethod("value");
        assertNotNull(valueMethod, "KeyedBy must declare a value() method");
        assertEquals(String.class, valueMethod.getReturnType(), "KeyedBy.value() must return String");
    }

    @Test
    @DisplayName("dev.vertique.core.config.KeyedBy no longer exists at the old location")
    void keyedBy_notInCoreConfigPackage() {
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("dev.vertique.core.config.KeyedBy"),
                "dev.vertique.core.config.KeyedBy must not exist after the package move");
    }
}
