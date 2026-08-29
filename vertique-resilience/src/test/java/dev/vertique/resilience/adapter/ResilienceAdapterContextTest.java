// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.BulkheadConfig;
import dev.vertique.resilience.CircuitBreaker;
import dev.vertique.resilience.CircuitBreakerConfig;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.TimeoutConfig;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** T003 TP-002 proof for explicit adapter-context and prebuilt-component ownership. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ResilienceAdapterContextTest {

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Test
    @DisplayName("only one explicitly reused breaker shares state and context close fences work")
    void sharesOnlyOneExplicitComponentInstance(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterSupport support = resilience.adapterSupport();
        ResilienceAdapterContext context = support.newContext();
        CircuitBreakerConfig config =
                CircuitBreakerConfig.builder().maxFailures(1).build();
        AdapterOperationIdentity stateIdentity = new AdapterOperationIdentity("rest-client", List.of("shared"));
        CircuitBreaker shared = context.circuitBreaker(stateIdentity, config);
        ResiliencePipeline first = context.pipeline(operation("first"), timeoutPolicy(), shared, failure -> true);
        ResiliencePipeline second = context.pipeline(operation("second"), timeoutPolicy(), shared, failure -> true);

        awaitFailure(first.execute(() -> Future.failedFuture(new IllegalStateException("first"))));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(second.execute(() -> Future.succeededFuture("late"))));

        ResilienceAdapterContext isolatedContext = support.newContext();
        CircuitBreaker isolated = isolatedContext.circuitBreaker(stateIdentity, config);
        ResiliencePipeline isolatedPipeline =
                isolatedContext.pipeline(operation("isolated"), timeoutPolicy(), isolated, failure -> true);
        assertTrue(await(isolatedPipeline.execute(() -> Future.succeededFuture("isolated")))
                .equals("isolated"));

        CircuitBreaker activeBreaker =
                context.circuitBreaker(new AdapterOperationIdentity("rest-client", List.of("active")), config);
        ResiliencePipeline activePipeline =
                context.pipeline(operation("active"), longTimeoutPolicy(), activeBreaker, failure -> true);
        Promise<String> pending = Promise.promise();
        Future<String> active = activePipeline.execute(pending::future);
        await(context.close());
        assertInstanceOf(ResilienceClosedException.class, awaitFailure(active));
        assertThrows(IllegalStateException.class, () -> context.circuitBreaker(stateIdentity, config));
        pending.complete("late");
        await(isolatedContext.close());
    }

    @Test
    @DisplayName("classifier and policy overloads enforce breaker presence and fail closed")
    void validatesClassifierAndPolicyBoundaries(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        AdapterOperationIdentity identity = operation("validation");
        CircuitFailureClassifier classifier = failure -> true;

        assertThrows(ResiliencePolicyException.class, () -> context.pipeline(identity, timeoutPolicy(), classifier));
        ResiliencePolicyException unsupported = assertInstanceOf(
                ResiliencePolicyException.class, assertThrows(ResiliencePolicyException.class, () -> resilience
                        .adapterSupport()
                        .pipeline(
                                identity,
                                new ResolvedResiliencePolicy(
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of(
                                                CircuitBreakerConfig.builder().build()),
                                        Optional.empty()))));
        assertTrue(unsupported.getMessage().contains("Invalid resilience policy"));
    }

    @Test
    @DisplayName("supports a circuit-only adapter policy")
    void supportsCircuitOnlyPolicy(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        ResolvedResiliencePolicy circuitOnly = new ResolvedResiliencePolicy(
                Optional.empty(),
                Optional.empty(),
                Optional.of(CircuitBreakerConfig.builder().maxFailures(1).build()),
                Optional.empty());

        ResiliencePipeline pipeline = context.pipeline(operation("circuit-only"), circuitOnly, failure -> true);
        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("first"))));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(pipeline.execute(() -> Future.succeededFuture("closed"))));
        await(context.close());
    }

    @Test
    @DisplayName("context owns and enforces an annotation-shaped reject bulkhead")
    void supportsBulkheadOnlyPolicy(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        ResolvedResiliencePolicy bulkheadOnly = new ResolvedResiliencePolicy(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(BulkheadConfig.reject(1)));
        ResiliencePipeline pipeline = context.pipeline(operation("bulkhead-only"), bulkheadOnly);
        Promise<String> active = Promise.promise();

        Future<String> running = pipeline.execute(active::future);
        Future<String> rejected = pipeline.execute(() -> Future.succeededFuture("must-not-run"));

        assertInstanceOf(BulkheadRejectedException.class, awaitFailure(rejected));
        active.complete("released");
        assertEquals("released", await(running));
        await(context.close());
    }

    @Test
    @DisplayName("context owns and drains a bounded queue bulkhead")
    void supportsQueuedBulkheadOnlyPolicy(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        ResolvedResiliencePolicy bulkheadOnly = new ResolvedResiliencePolicy(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(BulkheadConfig.queue(1, 1, Duration.ofSeconds(1))));
        ResiliencePipeline pipeline = context.pipeline(operation("bulkhead-queue"), bulkheadOnly);
        Promise<String> active = Promise.promise();
        Future<String> running = pipeline.execute(active::future);
        Future<String> queued = pipeline.execute(() -> Future.succeededFuture("queued"));

        assertFalse(queued.isComplete());
        active.complete("released");
        assertEquals("released", await(running));
        assertEquals("queued", await(queued));
        await(context.close());
    }

    @Test
    @DisplayName("a classifier can exclude a final failure from breaker accounting")
    void classifierControlsFinalFailureAccounting(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        CircuitBreaker breaker = context.circuitBreaker(
                new AdapterOperationIdentity("rest-client", List.of("classified")),
                CircuitBreakerConfig.builder().maxFailures(1).build());
        ResiliencePipeline pipeline =
                context.pipeline(operation("classified"), timeoutPolicy(), breaker, failure -> false);

        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("ignored"))));
        assertEquals("accepted", await(pipeline.execute(() -> Future.succeededFuture("accepted"))));
        await(context.close());
    }

    @Test
    @DisplayName("a classifier failure preserves the original failure and opens conservatively")
    void classifierFailureCountsOriginalFailure(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        CircuitBreaker breaker = context.circuitBreaker(
                new AdapterOperationIdentity("rest-client", List.of("classifier-failure")),
                CircuitBreakerConfig.builder().maxFailures(1).build());
        ResiliencePipeline pipeline =
                context.pipeline(operation("classifier-failure"), timeoutPolicy(), breaker, failure -> {
                    throw new IllegalStateException("classifier failure");
                });
        IllegalArgumentException original = new IllegalArgumentException("original");

        assertInstanceOf(
                IllegalArgumentException.class, awaitFailure(pipeline.execute(() -> Future.failedFuture(original))));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(pipeline.execute(() -> Future.succeededFuture("late"))));
    }

    @Test
    @DisplayName("context close fences pipelines that do not use a breaker")
    void contextCloseFencesTimeoutOnlyPipeline(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        ResiliencePipeline pipeline = context.pipeline(operation("timeout-only"), longTimeoutPolicy());
        Promise<String> pending = Promise.promise();
        Future<String> active = pipeline.execute(pending::future);

        await(context.close());
        assertInstanceOf(ResilienceClosedException.class, awaitFailure(active));
        pending.complete("late");
    }

    @Test
    @DisplayName("execution registration handles remove completed work from the context")
    void registrationHandleRemovesCompletedWork(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        AtomicInteger closeCalls = new AtomicInteger();

        Runnable deregister = context.registerExecution(closeCalls::incrementAndGet);
        deregister.run();

        await(context.close());
        assertEquals(0, closeCalls.get());
    }

    @Test
    @DisplayName("registration after close is fenced synchronously")
    void registrationAfterCloseRunsCloseActionImmediately(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        await(context.close());
        AtomicInteger closeCalls = new AtomicInteger();

        Runnable deregister = context.registerExecution(closeCalls::incrementAndGet);
        assertEquals(1, closeCalls.get());
        deregister.run();
        assertEquals(1, closeCalls.get());
    }

    @Test
    @DisplayName("registration racing with close is fenced while close is in progress")
    void registrationRacingWithCloseCannotEscapeFence(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        ResilienceAdapterContext context = resilience.adapterSupport().newContext();
        AtomicBoolean firstCloseActionStarted = new AtomicBoolean();
        Promise<Void> releaseFirstCloseAction = Promise.promise();
        Runnable firstDeregister = context.registerExecution(() -> {
            firstCloseActionStarted.set(true);
            releaseFirstCloseAction
                    .future()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();
        });

        Promise<Void> closing = Promise.promise();
        new Thread(() -> context.close().onComplete(closing)).start();
        awaitCondition(firstCloseActionStarted);

        AtomicBoolean racedCloseAction = new AtomicBoolean();
        context.registerExecution(() -> racedCloseAction.set(true));
        assertTrue(racedCloseAction.get());

        releaseFirstCloseAction.complete();
        firstDeregister.run();
        await(closing.future());
    }

    @Test
    @DisplayName("adapter factories expose structured identities and no application classifier hook")
    void publicSurfaceHasNoRawAdapterKeysOrApplicationClassifier() {
        assertFalse(hasPublicMethodWithStringParameter(Resilience.class, "adapterPipeline"));
        assertFalse(hasPublicMethodWithStringParameter(Resilience.class, "adapterCircuitBreaker"));
        assertFalse(Arrays.stream(ResiliencePipeline.Builder.class.getMethods())
                .anyMatch(method -> method.getName().equals("recordFailureWhen")));
    }

    @Test
    @DisplayName("shared breaker rejects a breaker owned by another runtime")
    void rejectsForeignSharedBreaker(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Resilience foreignRuntime = Resilience.create(vertx);
        try {
            CircuitBreaker foreign =
                    CircuitBreaker.builder(foreignRuntime, "foreign").build();
            ResilienceAdapterContext context = resilience.adapterSupport().newContext();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.pipeline(operation("foreign"), timeoutPolicy(), foreign, failure -> true));
            await(context.close());
        } finally {
            await(foreignRuntime.close());
        }
    }

    private static boolean hasPublicMethodWithStringParameter(Class<?> type, String name) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name))
                .map(Method::getParameterTypes)
                .anyMatch(parameters -> Arrays.stream(parameters).anyMatch(String.class::equals));
    }

    private static AdapterOperationIdentity operation(String name) {
        return new AdapterOperationIdentity("rest-client.method", List.of("client", name));
    }

    private static ResolvedResiliencePolicy timeoutPolicy() {
        return new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(500)), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ResolvedResiliencePolicy longTimeoutPolicy() {
        return new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(Duration.ofHours(1).toMillis())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (Exception failure) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitCondition(AtomicBoolean condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.get(), "condition was not reached");
    }
}
