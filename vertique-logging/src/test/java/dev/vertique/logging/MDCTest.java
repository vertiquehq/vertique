// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for the {@link MDC} facade.
 *
 * <p>Verifies that the facade correctly delegates to the core {@code MDCContexts} substrate:
 *
 * <ul>
 *   <li>Write operations ({@code put}, {@code remove}, {@code clear}, {@code setContextMap}) throw
 *       {@link IllegalStateException} when called outside a Vert.x duplicated context.
 *   <li>Read operations ({@code get}, {@code getCopyOfContextMap}) are lenient: they return
 *       {@code null} / empty map outside a Vert.x context.
 *   <li>Full round-trip semantics on a duplicated context.
 *   <li>{@code syncToSlf4j} copies the Vert.x MDC into SLF4J's thread-local MDC.
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MDCTest {

    // --- lenient reads outside any Vert.x context ---

    @Test
    @DisplayName("get outside any Vert.x context returns null")
    void shouldReturnNullOutsideContext() {
        assertNull(MDC.get("nonexistent"));
    }

    @Test
    @DisplayName("getCopyOfContextMap outside any Vert.x context returns empty map")
    void shouldReturnEmptyMapOutsideContext() {
        assertTrue(MDC.getCopyOfContextMap().isEmpty());
    }

    // --- fail-fast writes outside any Vert.x context ---

    @Test
    @DisplayName("put outside any Vert.x context throws IllegalStateException")
    void shouldThrowOnPutOutsideContext() {
        assertThrows(IllegalStateException.class, () -> MDC.put("key", "value"));
    }

    @Test
    @DisplayName("remove outside any Vert.x context throws IllegalStateException")
    void shouldThrowOnRemoveOutsideContext() {
        assertThrows(IllegalStateException.class, () -> MDC.remove("key"));
    }

    @Test
    @DisplayName("clear outside any Vert.x context throws IllegalStateException")
    void shouldThrowOnClearOutsideContext() {
        assertThrows(IllegalStateException.class, MDC::clear);
    }

    // --- fail-fast writes on a non-duplicated context ---

    @Test
    @DisplayName("put on a non-duplicated Vert.x context throws IllegalStateException")
    void shouldThrowOnPutOnNonDuplicatedContext(Vertx vertx, VertxTestContext testContext) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, () -> MDC.put("key", "value"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("clear on a non-duplicated Vert.x context throws IllegalStateException")
    void shouldThrowOnClearOnNonDuplicatedContext(Vertx vertx, VertxTestContext testContext) {
        vertx.runOnContext(v -> {
            try {
                assertThrows(IllegalStateException.class, MDC::clear);
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    // --- full round-trip on a duplicated context ---

    @Test
    @DisplayName("put and get within a duplicated Vert.x context")
    void shouldPutAndGet(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("key1", "value1");
                assertEquals("value1", MDC.get("key1"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("get returns null for a missing key on a duplicated context")
    void shouldReturnNullForMissingKey(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertNull(MDC.get("nonexistent"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("remove removes entry on a duplicated context")
    void shouldRemoveEntry(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("key", "value");
                assertEquals("value", MDC.get("key"));
                MDC.remove("key");
                assertNull(MDC.get("key"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("clear removes all entries on a duplicated context")
    void shouldClearAllEntries(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("key1", "value1");
                MDC.put("key2", "value2");
                MDC.clear();
                assertNull(MDC.get("key1"));
                assertNull(MDC.get("key2"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("getCopyOfContextMap returns an immutable copy on a duplicated context")
    void shouldReturnCopyOfContextMap(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("key1", "value1");
                MDC.put("key2", "value2");
                Map<String, String> copy = MDC.getCopyOfContextMap();
                assertEquals(2, copy.size());
                assertEquals("value1", copy.get("key1"));
                assertEquals("value2", copy.get("key2"));
                // Returned map is immutable.
                assertThrows(UnsupportedOperationException.class, () -> copy.put("key3", "value3"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("setContextMap replaces all entries on a duplicated context")
    void shouldSetContextMap(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("oldKey", "oldValue");
                MDC.setContextMap(Map.of("newKey", "newValue"));
                assertNull(MDC.get("oldKey"));
                assertEquals("newValue", MDC.get("newKey"));
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("getCopyOfContextMap returns empty map when MDC is empty on a duplicated context")
    void shouldReturnEmptyMapWhenEmpty(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                assertTrue(MDC.getCopyOfContextMap().isEmpty());
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("syncToSlf4j copies Vert.x MDC to SLF4J MDC on a duplicated context")
    void shouldSyncToSlf4j(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try {
                MDC.put("requestId", "req-123");
                MDC.syncToSlf4j();
                assertEquals("req-123", org.slf4j.MDC.get("requestId"));
                // Clean up
                org.slf4j.MDC.clear();
                MDC.clear();
                testContext.completeNow();
            } catch (Throwable t) {
                testContext.failNow(t);
            }
        });
    }
}
