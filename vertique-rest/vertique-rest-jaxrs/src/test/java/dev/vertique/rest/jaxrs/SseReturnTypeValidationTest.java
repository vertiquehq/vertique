// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SSE return type validation logic in {@link JaxRsRouteRegistrar}.
 *
 * <p>Tests {@link JaxRsRouteRegistrar#validateSseReturnTypes(List)} directly (package-private
 * visibility) using constructed {@link ResourceMethodMeta} instances based on test resource methods
 * declared in this class.
 */
class SseReturnTypeValidationTest {

    // --- Test resource methods (used for reflection) ---

    /** Valid: returns ReadStream<SseEvent>. */
    ReadStream<SseEvent> validSseMethod() {
        return null;
    }

    /** Valid: returns Future<ReadStream<SseEvent>>. */
    Future<ReadStream<SseEvent>> validFutureSseMethod() {
        return null;
    }

    /** Invalid: returns String with text/event-stream. */
    String invalidStringSseMethod() {
        return null;
    }

    /** Invalid: returns ReadStream<String> (wrong type arg). */
    ReadStream<String> wrongTypeArgSseMethod() {
        return null;
    }

    /** Invalid: returns raw Future. */
    @SuppressWarnings("rawtypes")
    Future invalidRawFutureSseMethod() {
        return null;
    }

    /** Not SSE: produces application/json, so validation should not apply. */
    String nonSseMethod() {
        return null;
    }

    // --- Helper ---

    /**
     * Constructs a {@link ResourceMethodMeta} with the given method, operationId, and produces list.
     *
     * @param method      the reflected method from this class
     * @param operationId an arbitrary operationId string for error messages
     * @param produces    list of produced media types
     * @return a minimal {@link ResourceMethodMeta} suitable for validation
     */
    private ResourceMethodMeta metaFor(Method method, String operationId, List<String> produces) {
        return new ResourceMethodMeta(
                this,
                method,
                operationId,
                "GET",
                "/test",
                List.of(),
                Object.class,
                false,
                false,
                null,
                new ResourceMethodMeta.MediaTypes(List.of(), produces),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * Looks up a method on this test class by name (no-arg, returns by matching name).
     *
     * @param name the method name
     * @return the reflected {@link Method}
     */
    private Method method(String name) throws NoSuchMethodException {
        for (Method m : SseReturnTypeValidationTest.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    // --- Valid cases ---

    @Nested
    @DisplayName("Valid SSE return types")
    class Valid {

        @Test
        @DisplayName("Should pass validation for ReadStream<SseEvent>")
        void shouldPassForReadStreamSseEvent() throws NoSuchMethodException {
            ResourceMethodMeta meta = metaFor(method("validSseMethod"), "validOp", List.of("text/event-stream"));

            assertDoesNotThrow(() -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
        }

        @Test
        @DisplayName("Should pass validation for Future<ReadStream<SseEvent>>")
        void shouldPassForFutureReadStreamSseEvent() throws NoSuchMethodException {
            ResourceMethodMeta meta =
                    metaFor(method("validFutureSseMethod"), "validFutureOp", List.of("text/event-stream"));

            assertDoesNotThrow(() -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
        }

        @Test
        @DisplayName("Should pass for empty method list")
        void shouldPassForEmptyList() {
            assertDoesNotThrow(() -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of()));
        }

        @Test
        @DisplayName("Should skip validation for non-SSE methods")
        void shouldSkipNonSseMethods() throws NoSuchMethodException {
            ResourceMethodMeta meta = metaFor(method("nonSseMethod"), "jsonOp", List.of("application/json"));

            assertDoesNotThrow(() -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
        }
    }

    // --- Invalid cases ---

    @Nested
    @DisplayName("Invalid SSE return types")
    class Invalid {

        @Test
        @DisplayName("Should reject String return type with text/event-stream")
        void shouldRejectStringReturnType() throws NoSuchMethodException {
            ResourceMethodMeta meta = metaFor(method("invalidStringSseMethod"), "badOp", List.of("text/event-stream"));

            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class, () -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
            assertTrue(ex.getMessage().contains("badOp"), "Message should include operationId");
            assertTrue(ex.getMessage().contains("text/event-stream"), "Message should reference the media type");
        }

        @Test
        @DisplayName("Should reject ReadStream<String> return type")
        void shouldRejectReadStreamString() throws NoSuchMethodException {
            ResourceMethodMeta meta =
                    metaFor(method("wrongTypeArgSseMethod"), "wrongTypeOp", List.of("text/event-stream"));

            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class, () -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
            assertTrue(ex.getMessage().contains("wrongTypeOp"));
        }

        @Test
        @DisplayName("Should reject raw Future return type")
        void shouldRejectRawFutureReturnType() throws NoSuchMethodException {
            ResourceMethodMeta meta =
                    metaFor(method("invalidRawFutureSseMethod"), "rawFutureOp", List.of("text/event-stream"));

            assertThrows(
                    RestConfigurationException.class, () -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
        }

        @Test
        @DisplayName("Should report all violations in a single exception")
        void shouldReportAllViolations() throws NoSuchMethodException {
            ResourceMethodMeta bad1 = metaFor(method("invalidStringSseMethod"), "op1", List.of("text/event-stream"));
            ResourceMethodMeta bad2 = metaFor(method("wrongTypeArgSseMethod"), "op2", List.of("text/event-stream"));

            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class,
                    () -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(bad1, bad2)));
            assertTrue(ex.getMessage().contains("op1"), "Should mention first violation");
            assertTrue(ex.getMessage().contains("op2"), "Should mention second violation");
        }
    }

    // --- Null mediaTypes ---

    @Nested
    @DisplayName("Null mediaTypes")
    class NullMediaTypes {

        @Test
        @DisplayName("Should skip methods with null mediaTypes")
        void shouldSkipMethodsWithNullMediaTypes() throws NoSuchMethodException {
            ResourceMethodMeta meta = new ResourceMethodMeta(
                    this,
                    method("nonSseMethod"),
                    "nullMtOp",
                    "GET",
                    "/test",
                    List.of(),
                    Object.class,
                    false,
                    false,
                    null,
                    null, // null mediaTypes
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());

            assertDoesNotThrow(() -> JaxRsRouteRegistrar.validateSseReturnTypes(List.of(meta)));
        }
    }
}
