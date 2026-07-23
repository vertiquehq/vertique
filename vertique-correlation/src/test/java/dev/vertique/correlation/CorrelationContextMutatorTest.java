// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
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
 * Unit tests for {@link CorrelationContextMutator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Mirroring setters ({@code setCausationId}, {@code setTrace}) write the live context
 *       AND the matching {@link CorrelationMdcKeys} MDC entry; passing {@code null} clears
 *       both the field and the MDC key.</li>
 *   <li>Non-mirroring setters ({@code setSession}, {@code addProtocolCorrelation},
 *       {@code putAttribute}) write the live context only — MDC is untouched (FR-COR-163/165).</li>
 *   <li>Every setter throws {@link IllegalStateException} when no {@link CorrelationContext}
 *       is bound, so missing-binding bugs surface at the call site.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CorrelationContextMutatorTest {

    private static final CorrelationIdentifier REQ_ID = new CorrelationIdentifier("req-1", "test");
    private static final CorrelationIdentifier CORR_ID = new CorrelationIdentifier("corr-1", "test");

    private static CorrelationContextMutator newMutator() {
        return new CorrelationContextMutator(new DefaultContextHolder());
    }

    // --- Mirroring: setCausationId ---

    @Test
    @DisplayName("setCausationId writes the live context AND the causationId MDC key")
    void setCausationIdMirrorsToMdc(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                CorrelationIdentifier cause = new CorrelationIdentifier("cause-1", "upstream");
                mutator.setCausationId(cause);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals(cause, live.causationId());
                assertEquals("cause-1", MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
            }
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("setCausationId(null) clears the live field and removes the causationId MDC key")
    void setCausationIdNullClearsField(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                mutator.setCausationId(new CorrelationIdentifier("cause-1", "upstream"));
                mutator.setCausationId(null);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertNull(live.causationId());
                assertNull(MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
            }
            testContext.completeNow();
        });
    }

    // --- Mirroring: setTrace ---

    @Test
    @DisplayName("setTrace(trace) writes traceId and (when present) spanId MDC keys")
    void setTraceMirrorsTraceAndSpan(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                TraceReference trace = new TraceReference("trace-abc", "span-def", "traceparent");
                mutator.setTrace(trace);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals(trace, live.trace());
                assertEquals("trace-abc", MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                assertEquals("span-def", MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
            }
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("setTrace with null spanId clears the spanId MDC key but keeps traceId")
    void setTraceNoSpanIdClearsSpanMdc(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                // First set with a span, then overwrite with no span — span MDC must be removed.
                mutator.setTrace(new TraceReference("trace-1", "span-1", "b3"));
                mutator.setTrace(new TraceReference("trace-2", null, "b3"));

                assertEquals("trace-2", MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
            }
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("setTrace(null) clears both the live field and both trace MDC keys")
    void setTraceNullClearsBoth(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                mutator.setTrace(new TraceReference("trace-1", "span-1", "b3"));
                mutator.setTrace(null);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertNull(live.trace());
                assertNull(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
                assertNull(MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
            }
            testContext.completeNow();
        });
    }

    // --- Non-mirroring: setSession, addProtocolCorrelation, putAttribute ---

    @Test
    @DisplayName("setSession writes the live field but does NOT touch any MDC key")
    void setSessionDoesNotTouchMdc(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                CorrelationSessionRef session =
                        new CorrelationSessionRef("session-1", "jwt", "auth-filter", "jti", false, null);
                mutator.setSession(session);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals(session, live.session());
                // MDC must remain untouched for all five mirrored keys.
                for (String key : CorrelationMdcKeys.MIRRORED) {
                    assertNull(MDCContexts.get(key), "MDC key " + key + " must not be touched by setSession");
                }
            }
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("addProtocolCorrelation writes the live list but does NOT touch any MDC key")
    void addProtocolCorrelationDoesNotTouchMdc(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                ProtocolCorrelationRef ref = new ProtocolCorrelationRef(
                        "X-FAPI-Interaction-ID",
                        "value-1",
                        "http",
                        CorrelationResponseMode.ECHO_SAME_HEADER,
                        CorrelationPropagationMode.PROPAGATE_SAME_HEADER,
                        true,
                        null);
                mutator.addProtocolCorrelation(ref);

                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals(1, live.protocolCorrelations().size());
                assertEquals(ref, live.protocolCorrelations().get(0));
                for (String key : CorrelationMdcKeys.MIRRORED) {
                    assertNull(MDCContexts.get(key), "MDC key " + key + " must not be touched");
                }
            }
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("putAttribute writes the live map but does NOT touch any MDC key")
    void putAttributeDoesNotTouchMdc(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            try (ContextHolder.Scope scope =
                    new DefaultContextHolder().bind(CorrelationContext.class, factory.create(REQ_ID, CORR_ID))) {
                mutator.putAttribute("k", "v");
                CorrelationContext live = new DefaultContextHolder()
                        .current(CorrelationContext.class)
                        .orElseThrow();
                assertEquals("v", live.attributes().get("k"));
                for (String key : CorrelationMdcKeys.MIRRORED) {
                    assertNull(MDCContexts.get(key));
                }
            }
            testContext.completeNow();
        });
    }

    // --- Missing-binding fail-fast ---

    @Test
    @DisplayName("setCausationId throws IllegalStateException when no CorrelationContext is bound")
    void setCausationIdNoBindingThrows(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class, () -> mutator.setCausationId(new CorrelationIdentifier("c", "src")));
            assertTrue(ex.getMessage().contains("CorrelationContext"));
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("setTrace throws IllegalStateException when no CorrelationContext is bound")
    void setTraceNoBindingThrows(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            assertThrows(IllegalStateException.class, () -> mutator.setTrace(new TraceReference("trace", null, "src")));
            testContext.completeNow();
        });
    }

    @Test
    @DisplayName("non-mirroring setters also throw when no CorrelationContext is bound")
    void nonMirroringSettersAlsoFailFast(Vertx vertx, VertxTestContext testContext) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextMutator mutator = newMutator();
            assertThrows(
                    IllegalStateException.class,
                    () -> mutator.setSession(new CorrelationSessionRef("id", "kind", "src", null, false, null)));
            assertThrows(
                    IllegalStateException.class,
                    () -> mutator.addProtocolCorrelation(new ProtocolCorrelationRef(
                            "X-H",
                            "v",
                            "http",
                            CorrelationResponseMode.NONE,
                            CorrelationPropagationMode.NONE,
                            true,
                            null)));
            assertThrows(IllegalStateException.class, () -> mutator.putAttribute("k", "v"));
            testContext.completeNow();
        });
    }
}
