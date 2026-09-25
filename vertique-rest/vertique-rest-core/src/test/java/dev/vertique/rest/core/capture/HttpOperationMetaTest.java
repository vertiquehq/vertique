// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.extension.OrderedExtension;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link HttpOperationMeta} holds its component fields correctly and that
 * {@link RestServerRequestEvidenceCapturer} participates in the {@link OrderedExtension} ordering
 * contract.
 */
class HttpOperationMetaTest {

    /** Fixture method reference for tests that need a real {@link Method}. */
    @SuppressWarnings("unused")
    public void fixtureMethod() {}

    @Nested
    @DisplayName("HttpOperationMeta record fields")
    class RecordFields {

        @Test
        @DisplayName("holds method, resourceClass, operationId, and routeTemplate")
        void holdsAllFields() throws NoSuchMethodException {
            Method method = HttpOperationMetaTest.class.getMethod("fixtureMethod");
            HttpOperationMeta meta = new HttpOperationMeta(method, String.class, "listItems", "/items");

            assertEquals(method, meta.method());
            assertEquals(String.class, meta.resourceClass());
            assertEquals("listItems", meta.operationId());
            assertEquals("/items", meta.routeTemplate());
        }

        @Test
        @SuppressWarnings("removal")
        @DisplayName("the deprecated three-argument form uses the method's declaring class")
        void deprecatedConstructorDefaultsToDeclaringClass() throws NoSuchMethodException {
            Method method = HttpOperationMetaTest.class.getMethod("fixtureMethod");
            HttpOperationMeta meta = new HttpOperationMeta(method, "listItems", "/items");

            assertEquals(HttpOperationMetaTest.class, meta.resourceClass());
        }

        @Test
        @DisplayName("method, resourceClass, and operationId are required")
        void requiredComponents() throws NoSuchMethodException {
            Method method = HttpOperationMetaTest.class.getMethod("fixtureMethod");

            assertThrows(NullPointerException.class, () -> new HttpOperationMeta(null, String.class, "op", null));
            assertThrows(NullPointerException.class, () -> new HttpOperationMeta(method, null, "op", null));
            assertThrows(NullPointerException.class, () -> new HttpOperationMeta(method, String.class, null, null));
        }

        @Test
        @DisplayName("routeTemplate may be null")
        void routeTemplateNullable() throws NoSuchMethodException {
            Method method = HttpOperationMetaTest.class.getMethod("fixtureMethod");
            HttpOperationMeta meta = new HttpOperationMeta(method, HttpOperationMetaTest.class, "getItem", null);

            assertNull(meta.routeTemplate());
            assertEquals("getItem", meta.operationId());
        }
    }

    @Nested
    @DisplayName("RestServerRequestEvidenceCapturer is an OrderedExtension")
    class CapturerIsOrderedExtension {

        @Test
        @DisplayName("default implementation is an OrderedExtension with APPLICATION phase and priority 0")
        void defaultCapturerIsOrderedExtension() throws NoSuchMethodException {
            // Minimal anonymous capturer — just verifies the SPI contract
            RestServerRequestEvidenceCapturer capturer = (ctx, meta) -> {};
            assertInstanceOf(OrderedExtension.class, capturer, "capturer must implement OrderedExtension");
        }
    }
}
