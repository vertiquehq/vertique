// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.InboundContextInitializationContext;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.logging.MDCContexts;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link CorrelationContextSeeder} (FR-COR-125).
 *
 * <p>Verifies idempotency (already-bound context wins; seed is no-op), seeding semantics on a
 * fresh duplicated context (both ids minted, source tagged {@code "seeded:" + boundary}), and
 * scope close removes the seeded binding.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CorrelationContextSeederTest {

    private static CorrelationContextSeeder newSeeder() {
        ContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return new CorrelationContextSeeder(holder, factory);
    }

    @Test
    @DisplayName("seeds a fresh CorrelationContext when none is bound, tagging source with boundary")
    void seedsWhenAbsent(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextSeeder seeder = newSeeder();
            ContextHolder.Scope scope = seeder.initialize(new InboundContextInitializationContext("kafka"));

            CorrelationContext bound =
                    new DefaultContextHolder().current(CorrelationContext.class).orElseThrow();
            CorrelationIdentifier requestId = bound.requestId();
            CorrelationIdentifier correlationId = bound.correlationId();
            assertNotNull(requestId);
            assertNotNull(correlationId);
            assertEquals("seeded:kafka", requestId.source());
            assertEquals("seeded:kafka", correlationId.source());

            // Scope close removes the seeded binding.
            scope.close();
            assertTrue(
                    new DefaultContextHolder().current(CorrelationContext.class).isEmpty());

            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("returns a no-op scope when a CorrelationContext is already bound (decoded value wins)")
    void noOpWhenAlreadyBound(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            CorrelationIdentifier upstream = new CorrelationIdentifier("upstream-req", "service-dispatch");
            CorrelationContext alreadyBound = factory.create(upstream, upstream);
            try (ContextHolder.Scope outer = new DefaultContextHolder().bind(CorrelationContext.class, alreadyBound)) {
                CorrelationContextSeeder seeder = newSeeder();
                ContextHolder.Scope seedScope = seeder.initialize(new InboundContextInitializationContext("kafka"));

                // The bound value must still be the upstream one — the seeder did not overwrite.
                CorrelationContext current = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertSame(alreadyBound, current);
                assertEquals("service-dispatch", current.requestId().source());

                seedScope.close(); // idempotent no-op
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("projects mirrored MDC keys from seeded context (requestId/correlationId present, others absent)")
    void projectsMdcFromSeed(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextSeeder seeder = newSeeder();
            try (ContextHolder.Scope scope = seeder.initialize(new InboundContextInitializationContext("kafka"))) {
                // Seeded context exposes only requestId + correlationId; causationId / trace are
                // null, so those MDC keys must NOT be written.
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
            }
            // Scope close restores prior MDC values (all absent before initialize).
            assertNull(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
            assertNull(MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("projects mirrored MDC keys from already-bound (decoded) context including causation + trace")
    void projectsMdcFromAlreadyBound(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            CorrelationIdentifier req = new CorrelationIdentifier("req-1", "http-header");
            CorrelationIdentifier cor = new CorrelationIdentifier("cor-1", "http-header");
            CorrelationContext upstream = factory.create(req, cor);
            // Decoded upstream had causationId + trace; the seeder must project these into MDC
            // even though it didn't seed (decoded value wins).
            CorrelationIdentifier cause = new CorrelationIdentifier("cause-1", "http-header");
            ((MutableCorrelationContext) upstream).setCausationId(cause);
            ((MutableCorrelationContext) upstream).setTrace(new TraceReference("trace-1", "span-1", "trace-context"));
            try (ContextHolder.Scope outer = new DefaultContextHolder().bind(CorrelationContext.class, upstream)) {
                CorrelationContextSeeder seeder = newSeeder();
                try (ContextHolder.Scope inner = seeder.initialize(new InboundContextInitializationContext("kafka"))) {
                    assertEquals("req-1", MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                    assertEquals("cor-1", MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
                    assertEquals("cause-1", MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
                    assertEquals("trace-1", MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                    assertEquals("span-1", MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
                }
                // Inner close restores MDC to the pre-initialize state (all absent), even
                // though the bound CorrelationContext is still present (outer scope).
                assertNull(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                // Bound context unchanged — decoded value still wins.
                assertSame(
                        upstream,
                        new DefaultContextHolder()
                                .current(CorrelationContext.class)
                                .orElseThrow());
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("clears mirrored MDC keys absent on the current context (no stale outer-scope bleed)")
    void clearsAbsentMirroredKeys(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            // Pre-populate MDC with values an outer scope might have written (causationId +
            // traceId + spanId) and then run the seeder on an inbound boundary whose
            // CorrelationContext has none of those — the seeder must remove them so the
            // dispatch's logs are not polluted by the outer state.
            MDCContexts.put(CorrelationMdcKeys.CAUSATION_ID, "outer-cause");
            MDCContexts.put(CorrelationMdcKeys.TRACE_ID, "outer-trace");
            MDCContexts.put(CorrelationMdcKeys.SPAN_ID, "outer-span");

            CorrelationContextSeeder seeder = newSeeder();
            try (ContextHolder.Scope scope = seeder.initialize(new InboundContextInitializationContext("kafka"))) {
                // Seeded context has only requestId + correlationId — the other three must be
                // actively cleared, not merely "not written" (snapshotKeys' restore-on-close
                // would still leave them visible during the dispatch).
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
                assertNull(
                        MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID),
                        "absent causationId on the bound context must clear stale MDC");
                assertNull(
                        MDCContexts.get(CorrelationMdcKeys.TRACE_ID),
                        "absent trace on the bound context must clear stale MDC traceId");
                assertNull(
                        MDCContexts.get(CorrelationMdcKeys.SPAN_ID),
                        "absent trace on the bound context must clear stale MDC spanId");
            }
            // Scope close restores the pre-initialize state — the outer values come back.
            assertEquals("outer-cause", MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
            assertEquals("outer-trace", MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
            assertEquals("outer-span", MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("clears MDC spanId when bound trace has no spanId (partial trace state)")
    void clearsSpanIdWhenTraceLacksSpan(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            MDCContexts.put(CorrelationMdcKeys.SPAN_ID, "outer-span");

            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            CorrelationContext upstream = factory.create(
                    new CorrelationIdentifier("r", "http-header"), new CorrelationIdentifier("c", "http-header"));
            // Bound trace has a traceId but no spanId — MDC spanId from any outer scope must
            // not leak through.
            ((MutableCorrelationContext) upstream).setTrace(new TraceReference("trace-only", null, "trace-context"));

            try (ContextHolder.Scope outer = new DefaultContextHolder().bind(CorrelationContext.class, upstream)) {
                CorrelationContextSeeder seeder = newSeeder();
                try (ContextHolder.Scope inner = seeder.initialize(new InboundContextInitializationContext("kafka"))) {
                    assertEquals("trace-only", MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                    assertNull(
                            MDCContexts.get(CorrelationMdcKeys.SPAN_ID),
                            "trace without spanId must clear any stale MDC spanId");
                }
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("different boundaries produce different 'seeded:<boundary>' source strings")
    void boundaryTaggedSource(Vertx vertx, VertxTestContext ctx) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            // First seed: kafka.
            CorrelationContextSeeder seeder = newSeeder();
            try (ContextHolder.Scope first = seeder.initialize(new InboundContextInitializationContext("kafka"))) {
                CorrelationContext bound = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals("seeded:kafka", bound.requestId().source());
            }
            // After scope close the slot is empty again; seeding a different boundary tags accordingly.
            try (ContextHolder.Scope second =
                    seeder.initialize(new InboundContextInitializationContext("workflow-branch"))) {
                CorrelationContext bound = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals("seeded:workflow-branch", bound.requestId().source());
            }
            ctx.completeNow();
        });
    }
}
