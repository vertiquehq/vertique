// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.job.delayed.DelayedJob;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies OUTBOX capture and byte-for-byte forwarding through the delayed-job destination. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DelayedJobOutboxDestinationScopeContextTest {

    @Singleton
    @Component(modules = ContextRuntimeModule.class)
    interface DestinationGraph {
        ContextHolder holder();

        DurableContextPropagator propagator();

        DelayedJobOutboxDestinationHandler destination();

        @Component.Factory
        interface Factory {
            DestinationGraph create(
                    @BindsInstance DelayedJobService service, @BindsInstance DelayedJobTargetResolver resolver);
        }
    }

    @Test
    @DisplayName("OUTBOX-captured scope is forwarded unchanged into the delayed job receiver's metadata")
    void forwardsCapturedScopeToTheDelayedJobReceiver(Vertx vertx, VertxTestContext ctx) {
        DelayedJobService service = mock(DelayedJobService.class);
        DelayedJobTargetResolver resolver = mock(DelayedJobTargetResolver.class);
        when(resolver.resolve("scope-job"))
                .thenReturn(new ResolvedDelayedJobTarget("scope-job", "scope-job", "test.scope.job", "default", 0, 3));
        AtomicReference<DelayedJob> forwarded = new AtomicReference<>();
        when(service.enqueue(any(DelayedJob.class))).thenAnswer(invocation -> {
            forwarded.set(invocation.getArgument(0));
            return Future.succeededFuture(UUID.randomUUID());
        });
        DestinationGraph graph = DaggerDelayedJobOutboxDestinationScopeContextTest_DestinationGraph.factory()
                .create(service, resolver);
        ContextInternal producer = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        producer.runOnContext(ignored -> {
            DurableMetadata captured;
            try (ContextHolder.Scope scope =
                    graph.holder().bind(ScopeContext.class, new ScopeContext(new ScopeKey("acme")))) {
                captured = graph.propagator().capture(DispatchBoundary.OUTBOX);
                assertEquals(
                        new JsonObject().put("scopeKey", "acme"),
                        captured.body("scope").orElse(null),
                        "OUTBOX must capture the exact scope body before destination forwarding");
            } catch (Throwable failure) {
                ctx.failNow(failure);
                return;
            }
            byte[] savedBytes = captured.toJson().toBuffer().getBytes();
            OutboxMetadata metadata = new OutboxMetadata(
                    captured,
                    new OutboxDeliveryMetadata(Optional.empty(), Optional.of(new DelayedJobControl("default", 0, 3))));
            OutboxEnvelope envelope = new OutboxEnvelope(
                    1L, "scope", "1", "scope.job", "scope-job", "payload", Map.of(), metadata, null, 0, Instant.now());
            graph.destination()
                    .publish(envelope)
                    .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                        assertInstanceOf(OutboxPublishResult.Success.class, result);
                        assertArrayEquals(
                                savedBytes,
                                forwarded.get().metadata().toJson().toBuffer().getBytes());
                        assertEquals(
                                captured.toJson(), forwarded.get().metadata().toJson());
                        assertEquals("scope-job", forwarded.get().handler());
                        ctx.completeNow();
                    })));
        });
    }
}
