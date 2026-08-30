// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-001 red proof for the executable runtime and timeout composition contract.
 *
 * <p>The scenarios deliberately cross the public construction boundary: a standalone timeout,
 * an inline pipeline timeout, and a prebuilt timeout must share one timeout fence while retaining
 * the pipeline's execution identity. The same proof also freezes eager builder validation,
 * captured-context execution, and runtime close semantics before the runtime implementation exists.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 10, unit = TimeUnit.SECONDS)
class ResiliencePipelineCompositionTest {

    private static final Duration TEST_TIMEOUT = Duration.ofMillis(25);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Nested
    @DisplayName("timeout construction and execution")
    class TimeoutConstruction {

        /**
         * Proves all three supported timeout construction forms execute through the same fence.
         * The late supplier completions are intentional: a timeout is a fence and must not cancel
         * or subsequently overwrite the supplier future.
         */
        @Test
        @DisplayName("direct, inline, and prebuilt timeouts all fence a pending supplier")
        void supportsDirectInlineAndPrebuiltTimeout(Vertx vertx) throws Exception {
            resilience = Resilience.create(vertx);

            Timeout direct = Timeout.builder(resilience, "direct-timeout")
                    .duration(TEST_TIMEOUT)
                    .build();
            ResiliencePipeline inline = resilience
                    .pipeline("inline-timeout")
                    .timeout(timeout -> timeout.duration(TEST_TIMEOUT))
                    .build();
            Timeout prebuilt = Timeout.builder(resilience, "prebuilt-timeout")
                    .duration(TEST_TIMEOUT)
                    .build();
            ResiliencePipeline composed =
                    resilience.pipeline("composed-timeout").timeout(prebuilt).build();

            Promise<String> directSupplier = Promise.promise();
            Promise<String> inlineSupplier = Promise.promise();
            Promise<String> prebuiltSupplier = Promise.promise();

            Future<String> directResult = direct.execute(directSupplier::future);
            Future<String> inlineResult = inline.execute(inlineSupplier::future);
            Future<String> composedResult = composed.execute(prebuiltSupplier::future);

            ResilienceTimeoutException directFailure = timeoutFailure(directResult);
            ResilienceTimeoutException inlineFailure = timeoutFailure(inlineResult);
            ResilienceTimeoutException composedFailure = timeoutFailure(composedResult);

            assertEquals(TEST_TIMEOUT.toMillis(), directFailure.timeoutMs());
            assertEquals(TEST_TIMEOUT.toMillis(), inlineFailure.timeoutMs());
            assertEquals(TEST_TIMEOUT.toMillis(), composedFailure.timeoutMs());
            assertEquals(inline.operationKey(), inlineFailure.operationKey());
            assertEquals(composed.operationKey(), composedFailure.operationKey());

            assertFalse(directSupplier.future().isComplete(), "timeout must not cancel the supplier future");
            assertFalse(inlineSupplier.future().isComplete(), "timeout must not cancel the supplier future");
            assertFalse(prebuiltSupplier.future().isComplete(), "timeout must not cancel the supplier future");

            directSupplier.complete("late-direct");
            inlineSupplier.complete("late-inline");
            prebuiltSupplier.complete("late-prebuilt");

            assertSame(directFailure, directResult.cause());
            assertSame(inlineFailure, inlineResult.cause());
            assertSame(composedFailure, composedResult.cause());
        }

        @Test
        @DisplayName("a same-owner prebuilt timeout is accepted but failures use the pipeline operation key")
        void pipelineUsesItsOwnOperationKeyForPrebuiltTimeoutFailures(Vertx vertx) throws Exception {
            resilience = Resilience.create(vertx);
            Timeout prebuilt = Timeout.builder(resilience, "construction-label")
                    .duration(TEST_TIMEOUT)
                    .build();
            ResiliencePipeline pipeline =
                    resilience.pipeline("execution-operation").timeout(prebuilt).build();

            Future<String> result = pipeline.execute(Promise.<String>promise()::future);

            ResilienceTimeoutException failure = timeoutFailure(result);
            assertEquals(pipeline.operationKey(), failure.operationKey());
            assertFalse(failure.getMessage().contains("construction-label"));
            assertFalse(failure.getMessage().contains("execution-operation"));
        }
    }

    @Nested
    @DisplayName("construction guards")
    class ConstructionGuards {

        @Test
        @DisplayName("a timeout from another runtime is rejected when composing a pipeline")
        void rejectsForeignOwnerTimeout(Vertx vertx) throws Exception {
            resilience = Resilience.create(vertx);
            Vertx foreignVertx = Vertx.vertx();
            Resilience foreignResilience = Resilience.create(foreignVertx);
            try {
                Timeout foreignTimeout = Timeout.builder(foreignResilience, "foreign-timeout")
                        .duration(TEST_TIMEOUT)
                        .build();

                assertThrows(IllegalArgumentException.class, () -> resilience
                        .pipeline("local-operation")
                        .timeout(foreignTimeout)
                        .build());
            } finally {
                await(foreignResilience.close());
                await(foreignVertx.close());
            }
        }

        @Test
        @DisplayName("repeated timeout and pipeline setters fail eagerly and builders cannot be reused")
        void rejectsRepeatedSettersAndReusedBuilders(Vertx vertx) {
            resilience = Resilience.create(vertx);

            Timeout.Builder timeoutBuilder = Timeout.builder(resilience, "builder-timeout");
            timeoutBuilder.duration(TEST_TIMEOUT);
            assertThrows(IllegalStateException.class, () -> timeoutBuilder.duration(Duration.ofMillis(50)));
            timeoutBuilder.build();
            assertThrows(IllegalStateException.class, timeoutBuilder::build);
            assertThrows(IllegalStateException.class, () -> timeoutBuilder.duration(Duration.ofMillis(75)));

            ResiliencePipeline.Builder pipelineBuilder = resilience.pipeline("builder-pipeline");
            pipelineBuilder.timeout(timeout -> timeout.duration(TEST_TIMEOUT));
            assertThrows(
                    IllegalStateException.class,
                    () -> pipelineBuilder.timeout(timeout -> timeout.duration(TEST_TIMEOUT)));
            pipelineBuilder.build();
            assertThrows(IllegalStateException.class, pipelineBuilder::build);
            assertThrows(
                    IllegalStateException.class,
                    () -> pipelineBuilder.timeout(timeout -> timeout.duration(TEST_TIMEOUT)));
        }
    }

    @Nested
    @DisplayName("context and lifecycle")
    class ContextAndLifecycle {

        @Test
        @DisplayName("supplier and completion remain on the context captured by execute")
        void preservesCapturedContext(Vertx vertx) throws InterruptedException {
            resilience = Resilience.create(vertx);
            ResiliencePipeline pipeline = resilience
                    .pipeline("captured-context")
                    .timeout(timeout -> timeout.duration(Duration.ofSeconds(1)))
                    .build();

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Context> captured = new AtomicReference<>();
            AtomicReference<Context> supplierContext = new AtomicReference<>();
            AtomicReference<Context> completionContext = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

            vertx.runOnContext(ignored -> {
                captured.set(Vertx.currentContext());
                try {
                    pipeline.execute(() -> {
                                supplierContext.set(Vertx.currentContext());
                                return Future.succeededFuture("completed");
                            })
                            .onComplete(result -> {
                                try {
                                    assertTrue(result.succeeded(), "the context probe operation must succeed");
                                    completionContext.set(Vertx.currentContext());
                                } catch (Throwable failure) {
                                    callbackFailure.set(failure);
                                } finally {
                                    completed.countDown();
                                }
                            });
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                    completed.countDown();
                }
            });

            assertTrue(completed.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertNotNull(captured.get());
            assertSame(captured.get(), supplierContext.get());
            assertSame(captured.get(), completionContext.get());
            assertEquals(null, callbackFailure.get());
        }

        @Test
        @DisplayName("close is idempotent, fences active futures, cancels long timers, and ignores late suppliers")
        void closesOnceAndSettlesActiveExecution(Vertx vertx) throws Exception {
            resilience = Resilience.create(vertx);
            ResiliencePipeline pipeline = resilience
                    .pipeline("close-active")
                    .timeout(timeout -> timeout.duration(Duration.ofHours(1)))
                    .build();
            Promise<String> supplier = Promise.promise();

            Future<String> result = pipeline.execute(supplier::future);
            assertFalse(result.isComplete(), "the active supplier must still be pending before close");

            Future<Void> firstClose = resilience.close();
            Future<Void> secondClose = resilience.close();
            assertSame(firstClose, secondClose, "repeated close calls must return the same terminal future");
            await(firstClose);
            await(secondClose);

            assertTrue(result.isComplete(), "close must settle active public futures without waiting for suppliers");
            assertInstanceOf(ResilienceClosedException.class, result.cause());
            assertFalse(supplier.future().isComplete(), "close must not claim cancellation of supplier work");

            ResilienceClosedException closed = assertInstanceOf(ResilienceClosedException.class, result.cause());
            supplier.complete("late");
            assertSame(closed, result.cause());
        }

        @Test
        @DisplayName("close before scheduled start prevents supplier admission")
        void closeBeforeScheduledStartDoesNotInvokeSupplier(Vertx vertx) throws Exception {
            resilience = Resilience.create(vertx);
            ResiliencePipeline pipeline = resilience
                    .pipeline("close-before-start")
                    .timeout(timeout -> timeout.duration(Duration.ofHours(1)))
                    .build();
            AtomicBoolean supplierStarted = new AtomicBoolean();
            AtomicReference<Future<String>> result = new AtomicReference<>();
            AtomicReference<Future<Void>> close = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            CountDownLatch scheduled = new CountDownLatch(1);

            vertx.runOnContext(ignored -> {
                try {
                    result.set(pipeline.execute(() -> {
                        supplierStarted.set(true);
                        return Promise.<String>promise().future();
                    }));
                    close.set(resilience.close());
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                } finally {
                    scheduled.countDown();
                }
            });

            assertTrue(scheduled.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertNull(callbackFailure.get(), "the context callback must complete successfully");
            await(close.get());

            assertFalse(supplierStarted.get(), "close must prevent a queued supplier from starting");
            assertInstanceOf(ResilienceClosedException.class, result.get().cause());
        }
    }

    private static ResilienceTimeoutException timeoutFailure(Future<?> result) throws Exception {
        try {
            await(result);
        } catch (java.util.concurrent.ExecutionException expectedFailure) {
            // The expected failed future is inspected below.
        }
        assertTrue(result.failed(), "the timeout fence must fail the public future");
        return assertInstanceOf(ResilienceTimeoutException.class, result.cause());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
