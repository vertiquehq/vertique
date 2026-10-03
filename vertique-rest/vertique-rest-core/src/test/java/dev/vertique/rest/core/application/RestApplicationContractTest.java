// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pins the declaration surface of {@link RestApplication}: its meta-annotations, its exact
 * attribute set with return types, its defaults, and that a declaration is readable at runtime.
 */
class RestApplicationContractTest {

    /** A declaration read back by reflection. */
    @RestApplication(name = "x", path = "/x", discover = true)
    interface DiscoveringApi {}

    private static Method attribute(String name) throws NoSuchMethodException {
        return RestApplication.class.getDeclaredMethod(name);
    }

    @Test
    void isRetainedAtRuntime() {
        Retention retention = RestApplication.class.getAnnotation(Retention.class);
        assertNotNull(retention);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void targetsTypesOnly() {
        Target target = RestApplication.class.getAnnotation(Target.class);
        assertNotNull(target);
        assertArrayEquals(new ElementType[] {ElementType.TYPE}, target.value());
    }

    @Test
    void isDocumented() {
        assertTrue(RestApplication.class.isAnnotationPresent(Documented.class));
    }

    @Test
    void declaresExactlyNamePathResourcesDiscoverAndOpenapiPathWithTheirTypes() {
        Map<String, Class<?>> actual = new TreeMap<>();
        Arrays.stream(RestApplication.class.getDeclaredMethods())
                .forEach(method -> actual.put(method.getName(), method.getReturnType()));

        Map<String, Class<?>> expected = new TreeMap<>(Map.of(
                "name", String.class,
                "path", String.class,
                "resources", Class[].class,
                "discover", boolean.class,
                "openapiPath", String.class));
        assertEquals(expected, actual);
    }

    @Test
    void nameAndPathAreRequired() throws NoSuchMethodException {
        assertNull(attribute("name").getDefaultValue());
        assertNull(attribute("path").getDefaultValue());
    }

    @Test
    void resourcesDefaultsToAnEmptyArray() throws NoSuchMethodException {
        Object defaultValue = attribute("resources").getDefaultValue();
        assertArrayEquals(new Class<?>[0], (Class<?>[]) defaultValue);
    }

    @Test
    void discoverDefaultsToFalse() throws NoSuchMethodException {
        assertEquals(Boolean.FALSE, attribute("discover").getDefaultValue());
    }

    @Test
    void openapiPathDefaultsToEmpty() throws NoSuchMethodException {
        assertEquals("", attribute("openapiPath").getDefaultValue());
    }

    @Test
    void aDeclarationOnAnInterfaceIsReadableAtRuntimeWithItsValuesAndDefaults() {
        RestApplication declaration = DiscoveringApi.class.getAnnotation(RestApplication.class);

        assertNotNull(declaration);
        assertEquals("x", declaration.name());
        assertEquals("/x", declaration.path());
        assertTrue(declaration.discover());
        assertEquals(0, declaration.resources().length);
        assertEquals("", declaration.openapiPath());
    }
}
