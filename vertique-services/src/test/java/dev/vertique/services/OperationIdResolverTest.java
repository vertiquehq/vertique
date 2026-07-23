// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link OperationIdResolver} correctly resolves:
 * <ul>
 *   <li>the runtime operation name (always available, method-name fallback)</li>
 *   <li>the stable operation id (only when {@link ServiceOperation} is present)</li>
 * </ul>
 */
class OperationIdResolverTest {

    // --- Contract Fixtures ---

    interface AnnotatedService {
        @ServiceOperation("my-op")
        Future<String> annotatedMethod(String input);

        Future<String> unannotatedMethod(String input);

        @ServiceOperation("")
        Future<String> blankAnnotationMethod(String input);
    }

    // --- Tests ---

    @Nested
    @DisplayName("resolveOperationName")
    class ResolveOperationName {

        @Test
        @DisplayName("returns annotation value when @ServiceOperation is present")
        void returnsAnnotationValue() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("annotatedMethod", String.class);
            assertEquals("my-op", OperationIdResolver.resolveOperationName(method));
        }

        @Test
        @DisplayName("falls back to method name when @ServiceOperation is absent")
        void fallsBackToMethodName() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("unannotatedMethod", String.class);
            assertEquals("unannotatedMethod", OperationIdResolver.resolveOperationName(method));
        }

        @Test
        @DisplayName("falls back to method name when @ServiceOperation value is blank")
        void fallsBackWhenBlank() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("blankAnnotationMethod", String.class);
            assertEquals("blankAnnotationMethod", OperationIdResolver.resolveOperationName(method));
        }
    }

    @Nested
    @DisplayName("resolveStableOperationId")
    class ResolveStableOperationId {

        @Test
        @DisplayName("returns annotation value when @ServiceOperation is present")
        void returnsAnnotationValue() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("annotatedMethod", String.class);
            assertEquals("my-op", OperationIdResolver.resolveStableOperationId(method));
        }

        @Test
        @DisplayName("returns null when @ServiceOperation is absent")
        void returnsNullWhenAbsent() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("unannotatedMethod", String.class);
            assertNull(OperationIdResolver.resolveStableOperationId(method));
        }

        @Test
        @DisplayName("throws IllegalStateException when @ServiceOperation value is blank")
        void throwsWhenBlank() throws NoSuchMethodException {
            Method method = AnnotatedService.class.getMethod("blankAnnotationMethod", String.class);
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class, () -> OperationIdResolver.resolveStableOperationId(method));
            assertTrue(ex.getMessage().contains("blank value"), "message should mention blank value");
        }
    }
}
