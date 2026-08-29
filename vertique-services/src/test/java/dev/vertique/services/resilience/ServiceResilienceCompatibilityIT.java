// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.annotation.Bulkhead;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceResilienceCompatibilityIT {

    interface CompatibilityService {
        @ServiceOperation("plain")
        Future<String> plain();

        @dev.vertique.resilience.annotation.Timeout(100)
        @Retry(maxRetries = 1, delayMs = 0, maxDelayMs = 0)
        @CircuitBreaker(maxFailures = 2, timeoutMs = 100, resetTimeoutMs = 1000)
        @ServiceOperation("resilient")
        Future<String> resilient();

        @Bulkhead(maxConcurrentCalls = 1)
        @ServiceOperation("bulkhead-only")
        Future<String> bulkheadOnly();
    }

    private static ServiceMethodMeta meta(String operation) throws Exception {
        Method method = CompatibilityService.class.getMethod(operation);
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/test/compatibility/" + operation,
                "test.compatibility." + operation,
                "test",
                "compatibility",
                operation,
                null,
                String.class,
                List.of(),
                ResilienceAnnotations.resolve(CompatibilityService.class, method),
                List.of(),
                List.of(),
                false);
    }

    @Test
    void preservesActivationTimeoutRetryBreakerAndTransport(Vertx vertx, VertxTestContext ctx) throws Throwable {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // The shared test Vert.x may already have the protocol codec.
        }
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // The shared test Vert.x may already have the protocol codec.
        }

        Resilience resilience = Resilience.create(vertx);
        try {
            ServiceResilienceConfigAdapter adapter =
                    new ServiceResilienceConfigAdapter(resilience, new ServicesConfig(1_000L, List.of()), Map.of());
            ServiceResiliencePipelineFactory factory = new ServiceResiliencePipelineFactory(adapter, resilience);

            ServiceMethodMeta plain = meta("plain");
            assertNull(factory.pipeline(plain), "no annotation must not activate a pipeline");

            ServiceMethodMeta resilient = meta("resilient");
            ResiliencePipeline pipeline = factory.pipeline(resilient);
            assertNotNull(pipeline);
            AtomicInteger attempts = new AtomicInteger();
            String result = pipeline.execute(() -> attempts.incrementAndGet() == 1
                            ? Future.failedFuture(new IllegalStateException("sentinel-attempt"))
                            : Future.succeededFuture("ok"))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
            assertEquals("ok", result);
            assertEquals(2, attempts.get());

            ServiceMethodMeta bulkheadOnly = meta("bulkheadOnly");
            ResiliencePipeline bulkheadPipeline = factory.pipeline(bulkheadOnly);
            assertNotNull(bulkheadPipeline);
            Promise<String> active = Promise.promise();
            Future<String> running = bulkheadPipeline.execute(active::future);
            Future<String> rejected = bulkheadPipeline.execute(() -> Future.succeededFuture("must-not-run"));
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> rejected.toCompletionStage().toCompletableFuture().join());
            assertInstanceOf(BulkheadRejectedException.class, failure.getCause());
            active.complete("bulkhead-ok");
            assertEquals(
                    "bulkhead-ok",
                    running.toCompletionStage().toCompletableFuture().join());

            EventBusClient eventBusClient = new EventBusClient(vertx, new EventBusExceptionMapper());
            ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
            when(supervisor.isAvailable(any())).thenReturn(true);
            ServiceRequestSender sender = new ServiceRequestSender(
                    eventBusClient,
                    supervisor,
                    new ServiceResilienceConfigAdapter(resilience, new ServicesConfig(null, List.of()), Map.of()));
            DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
            vertx.eventBus()
                    .consumer(
                            resilient.address(),
                            message -> message.reply(Result.success("transport-ok"), replyOptions));

            sender.send(ResolvedServiceTarget.of(CompatibilityService.class, resilient), DispatchEnvelope.empty())
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        assertEquals("transport-ok", reply.get());
                        ctx.completeNow();
                    })));
            assertTrueCompleted(ctx);
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private static void assertTrueCompleted(VertxTestContext ctx) throws Throwable {
        if (!ctx.awaitCompletion(5, TimeUnit.SECONDS)) {
            throw new AssertionError("transport request did not complete");
        }
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }
}
