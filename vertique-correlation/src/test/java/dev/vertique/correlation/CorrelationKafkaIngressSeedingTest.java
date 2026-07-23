// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.logging.MDCContexts;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the Kafka ingress seeding path: the substrate's
 * {@link InboundExecutionContextScope#installDurable} wired with the production
 * {@link CorrelationContextSeeder} as a registered {@link dev.vertique.core.context.InboundContextInitializer}.
 *
 * <p>Stands in for an end-to-end Kafka IT for the seed / preserve semantics — the actual
 * KafkaRecordDispatcher just calls {@code installDurable(headers, KAFKA)}, so the ingress
 * behaviour comes entirely from this composition. Both branches are covered:
 *
 * <ol>
 *   <li>Empty / no-correlation Kafka headers → seeder mints a fresh {@link CorrelationContext}
 *       tagged {@code source="seeded:kafka"} and projects mirrored MDC keys.</li>
 *   <li>Pre-bound {@link CorrelationContext} (decoded by an upstream durable decoder) → the
 *       seeder leaves the bound value alone but still projects its mirrored MDC keys, so the
 *       Kafka dispatch path sees the decoded correlation in MDC regardless of which boundary
 *       supplied it.</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CorrelationKafkaIngressSeedingTest {

    /** Builds an InboundExecutionContextScope wired with the real CorrelationContextSeeder. */
    private static InboundExecutionContextScope newScopeWithSeeder(DefaultContextHolder holder) {
        ContextScopeBinder binder = new ContextScopeBinder(holder);
        DurableContextPropagator propagator =
                new DurableContextPropagator(new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, binder);
        CorrelationContextSeeder seeder =
                new CorrelationContextSeeder(holder, new CorrelationContextFactory(Optional.empty()));
        return new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of(seeder));
    }

    @Test
    @DisplayName("Kafka boundary with no correlation metadata: seeder mints a fresh context + projects MDC")
    void kafkaNoCorrelationSeedsContext(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        InboundExecutionContextScope scope = newScopeWithSeeder(holder);

        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope s = scope.installDurable(DurableMetadata.empty(), DispatchBoundary.KAFKA)) {
                CorrelationContext bound =
                        holder.current(CorrelationContext.class).orElseThrow();
                assertEquals(
                        "seeded:" + DispatchBoundary.KAFKA,
                        bound.requestId().source(),
                        "seeder must tag source with 'seeded:<boundary>' when no decoded value exists");
                assertEquals(
                        "seeded:" + DispatchBoundary.KAFKA,
                        bound.correlationId().source());
                // MDC mirrored fields populated by the seeder's projectToMdc step.
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                assertNotNull(MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
                assertEquals(bound.requestId().value(), MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Kafka boundary with already-bound correlation: seeder preserves it (no reseed) + projects MDC")
    void kafkaWithExistingCorrelationPreserves(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        InboundExecutionContextScope scope = newScopeWithSeeder(holder);

        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
            CorrelationContext decoded = factory.create(
                    new CorrelationIdentifier("req-decoded", "durable"),
                    new CorrelationIdentifier("cor-decoded", "durable"));
            // Pre-bind: simulates the substrate's durable decoder having installed a
            // CorrelationContext into the holder before the seeder runs.
            try (ContextHolder.Scope outer = holder.bind(CorrelationContext.class, decoded);
                    ContextHolder.Scope inner = scope.installDurable(DurableMetadata.empty(), DispatchBoundary.KAFKA)) {
                // The bound value must be the decoded one (decoded wins per FR-COR-125).
                assertSame(
                        decoded,
                        holder.current(CorrelationContext.class).orElseThrow(),
                        "decoded CorrelationContext must win; seeder must not reseed");
                // MDC must reflect the DECODED context (not a freshly seeded value).
                assertEquals("req-decoded", MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
                assertEquals("cor-decoded", MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
            }
            ctx.completeNow();
        });
    }
}
