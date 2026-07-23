// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import io.prometheus.metrics.tracer.common.SpanContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeferredSpanContext}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Without a delegate, all methods return null / false / no-op safely.</li>
 *   <li>With a delegate wired in, all methods pass through to the real implementation.</li>
 * </ul>
 */
class DeferredSpanContextTest {

    // --- Null-delegate semantics ---

    @Nested
    @DisplayName("null delegate — safe defaults")
    class NullDelegate {

        @Test
        @DisplayName("getCurrentTraceId() returns null when no delegate is set")
        void traceIdNullWithoutDelegate() {
            DeferredSpanContext ctx = new DeferredSpanContext();
            assertNull(ctx.getCurrentTraceId(), "trace id must be null without delegate");
        }

        @Test
        @DisplayName("getCurrentSpanId() returns null when no delegate is set")
        void spanIdNullWithoutDelegate() {
            DeferredSpanContext ctx = new DeferredSpanContext();
            assertNull(ctx.getCurrentSpanId(), "span id must be null without delegate");
        }

        @Test
        @DisplayName("isCurrentSpanSampled() returns false when no delegate is set")
        void sampledFalseWithoutDelegate() {
            DeferredSpanContext ctx = new DeferredSpanContext();
            assertFalse(ctx.isCurrentSpanSampled(), "sampled must be false without delegate");
        }

        @Test
        @DisplayName("markCurrentSpanAsExemplar() is a no-op when no delegate is set")
        void markExemplarNoOpWithoutDelegate() {
            DeferredSpanContext ctx = new DeferredSpanContext();
            // Must not throw
            assertDoesNotThrow(ctx::markCurrentSpanAsExemplar);
        }
    }

    // --- Passthrough with a fake delegate ---

    @Nested
    @DisplayName("with delegate — passthrough")
    class WithDelegate {

        @Test
        @DisplayName("all methods delegate to the real SpanContext after wiring")
        void passthroughAfterWiring() {
            DeferredSpanContext ctx = new DeferredSpanContext();

            SpanContext fake = new SpanContext() {
                @Override
                public String getCurrentTraceId() {
                    return "trace-123";
                }

                @Override
                public String getCurrentSpanId() {
                    return "span-456";
                }

                @Override
                public boolean isCurrentSpanSampled() {
                    return true;
                }

                @Override
                public void markCurrentSpanAsExemplar() {
                    // captured by the fake — no state needed for this assertion
                }
            };

            ctx.delegate(fake);

            assertEquals("trace-123", ctx.getCurrentTraceId());
            assertEquals("span-456", ctx.getCurrentSpanId());
            assertTrue(ctx.isCurrentSpanSampled());
            assertDoesNotThrow(ctx::markCurrentSpanAsExemplar);
        }
    }
}
