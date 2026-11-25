// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ExecutionStarted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A pipeline built through the adapter context from a resolved policy that enables no concern
 * runs the supplier as one recorded attempt, while every other construction path still refuses it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EmptyAdapterPipelineTest {

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceObserver recorder = events::add;

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            resilience.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("an adapter pipeline with no concerns runs one recorded attempt")
    void adapterPipelineWithNoConcernsRunsOneRecordedAttempt(Vertx vertx) throws Exception {
        // Given a runtime with a recording observer and a resolved policy that enables no concern
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResolvedResiliencePolicy policy =
                new ResolvedResiliencePolicy(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // When a pipeline is built through the adapter context
        ResiliencePipeline pipeline = resilience
                .adapterSupport()
                .newContext()
                .pipeline(new AdapterOperationIdentity("empty.adapter", List.of("scenario", "none")), policy);

        // And it runs a succeeding supplier once
        AtomicInteger successCalls = new AtomicInteger();
        String result = await(pipeline.execute(() -> {
            successCalls.incrementAndGet();
            return Future.succeededFuture("ok");
        }));

        // Then it returns "ok" after one call and records exactly one attempt's lifecycle, nothing else
        assertEquals("ok", result);
        assertEquals(1, successCalls.get());
        awaitCondition(() -> events.size() == 4);
        assertEquals(
                List.of(ExecutionStarted.class, AttemptStarted.class, AttemptCompleted.class, ExecutionCompleted.class),
                events.stream().map(ResilienceEvent::getClass).toList());
        assertEquals(ResilienceOutcomeCategory.SUCCESS, ((AttemptCompleted) events.get(2)).outcome());
        assertEquals(ResilienceOutcomeCategory.SUCCESS, ((ExecutionCompleted) events.get(3)).outcome());

        // When it runs a supplier failing with its own key's timeout of 300 ms
        events.clear();
        ResilienceTimeoutException timeout = new ResilienceTimeoutException(pipeline.operationKey(), 300L);
        AtomicInteger failureCalls = new AtomicInteger();
        Throwable cause = awaitFailure(pipeline.execute(() -> {
            failureCalls.incrementAndGet();
            return Future.failedFuture(timeout);
        }));

        // Then it fails with that same instance after one call, recording TimeoutTriggered(300) then
        // AttemptCompleted(TIMEOUT), and no retry event
        assertSame(timeout, cause);
        assertEquals(1, failureCalls.get());
        awaitCondition(() -> events.size() == 5);
        assertEquals(
                List.of(
                        ExecutionStarted.class,
                        AttemptStarted.class,
                        TimeoutTriggered.class,
                        AttemptCompleted.class,
                        ExecutionCompleted.class),
                events.stream().map(ResilienceEvent::getClass).toList());
        assertEquals(300L, ((TimeoutTriggered) events.get(2)).timeoutMs());
        assertEquals(ResilienceOutcomeCategory.TIMEOUT, ((AttemptCompleted) events.get(3)).outcome());
        assertEquals(ResilienceOutcomeCategory.TIMEOUT, ((ExecutionCompleted) events.get(4)).outcome());

        // And building a pipeline with no concern outside the adapter context is still refused
        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> resilience.pipeline("empty-direct").build());
        assertEquals("a pipeline must configure at least one concern", refused.getMessage());
    }

    @Test
    @DisplayName("the direct adapter bridge outside a context still refuses an empty policy")
    void directAdapterBridgeStillRefusesAnEmptyPolicy(Vertx vertx) {
        // Given a runtime and a resolved policy that enables no concern
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResolvedResiliencePolicy policy =
                new ResolvedResiliencePolicy(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // When the bridge builds a pipeline directly, outside an adapter context
        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> resilience.adapterPipeline(
                        new AdapterOperationIdentity("empty.adapter", List.of("scenario", "direct")), policy));

        // Then it throws the existing IllegalStateException
        assertEquals("a pipeline must configure at least one concern", refused.getMessage());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(5L);
        }
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
