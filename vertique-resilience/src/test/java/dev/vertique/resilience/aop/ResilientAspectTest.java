// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResilienceDefaults;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResiliencePolicyOverrides;
import dev.vertique.resilience.ResiliencePolicyRegistry;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** TP-001 proof for the ResilientAspect construction, execution, and lifecycle contract. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ResilientAspectTest {

    private static final String PAYMENTS = "payments";
    private static final String UNKNOWN = "unknown-policy";

    @DisplayName("enforces the T010 contract matrix")
    @ParameterizedTest(name = "{0}")
    @MethodSource("t010ContractMatrix")
    void shouldEnforceT010ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    static List<MatrixRow> t010ContractMatrix() {
        return List.of(
                new MatrixRow("resolve once", ResilientAspectTest::shouldResolveThePipelineOnceAtConstructionTime),
                new MatrixRow(
                        "unknown policy construction failure",
                        ResilientAspectTest::shouldFailAtConstructionTimeForAnUnknownPolicyName),
                new MatrixRow("re-entrant retry", ResilientAspectTest::shouldRetryThroughReEntrantProceed),
                new MatrixRow(
                        "transport event parity with retry+500ms timeout and 600ms first-attempt completion",
                        ResilientAspectTest::shouldEmitTheSameEventSequenceAsTheTransportPipeline),
                new MatrixRow(
                        "named tier over inline declaration",
                        ResilientAspectTest::shouldApplyTheNamedTierOverInlineDeclarations),
                new MatrixRow(
                        "shared breaker pipeline across interceptor builds",
                        ResilientAspectTest::shouldShareOnePipelineAcrossProxyInstances),
                new MatrixRow(
                        "VALIDATE shutdown ordering", ResilientAspectTest::shouldCloseTheContextBeforeTheRuntimeStep));
    }

    static void shouldResolveThePipelineOnceAtConstructionTime() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            CountingRegistry registry = fixture.registry();
            ResilientAspect aspect = new ResilientAspect(fixture.resilience(), Optional.of(registry));
            try {
                MethodMetadata metadata = metadata("retryOnce");

                MethodInterceptor interceptor = aspect.interceptor(metadata, annotation("retryOnce"));
                assertEquals(1, registry.lookups());
                await(interceptor.intercept(invocation(metadata, () -> Future.succeededFuture("ok"))));
                await(interceptor.intercept(invocation(metadata, () -> Future.succeededFuture("ok"))));
                assertEquals(1, registry.lookups(), "policy resolution belongs to interceptor construction");
            } finally {
                await(aspect.close());
            }
        }
    }

    static void shouldFailAtConstructionTimeForAnUnknownPolicyName() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            CountingRegistry registry = fixture.registry();
            ResilientAspect aspect = new ResilientAspect(fixture.resilience(), Optional.of(registry));
            try {
                assertThrows(
                        ConfigurationException.class,
                        () -> aspect.interceptor(metadata("unknownPolicy"), annotation("unknownPolicy")));
                assertEquals(1, registry.lookups());
            } finally {
                await(aspect.close());
            }
        }
    }

    static void shouldRetryThroughReEntrantProceed() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            MethodMetadata metadata = metadata("retryOnce");
            MethodInterceptor interceptor = fixture.aspect().interceptor(metadata, annotation("retryOnce"));
            AtomicInteger attempts = new AtomicInteger();

            Object result = await(interceptor.intercept(invocation(
                    metadata,
                    () -> attempts.incrementAndGet() == 1
                            ? Future.failedFuture(new IllegalStateException("transient"))
                            : Future.succeededFuture("recovered"))));

            assertEquals("recovered", result);
            assertEquals(2, attempts.get(), "retry must re-enter the captured proceed continuation");
        }
    }

    static void shouldEmitTheSameEventSequenceAsTheTransportPipeline() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            MethodMetadata metadata = metadata("transportParity");
            List<Class<? extends ResilienceEvent>> aspectEvents = fixture.events();
            MethodInterceptor interceptor = fixture.aspect().interceptor(metadata, annotation("transportParity"));
            AtomicInteger aspectAttempts = new AtomicInteger();
            assertEquals(
                    "ok",
                    await(interceptor.intercept(
                            invocation(metadata, () -> slowFirstAttempt(fixture, aspectAttempts)))));
            List<Class<? extends ResilienceEvent>> expected = List.copyOf(aspectEvents);

            aspectEvents.clear();
            ResilienceAnnotations annotations = ResilienceAnnotations.resolve(metadata);
            ResolvedResiliencePolicy policy = resolve(fixture.resilience(), fixture.registry(), annotations);
            dev.vertique.resilience.adapter.ResilienceAdapterContext transportContext =
                    fixture.resilience().adapterSupport().newContext();
            try {
                ResiliencePipeline transportPipeline = transportContext.pipeline(operation(metadata), policy);
                AtomicInteger transportAttempts = new AtomicInteger();
                assertEquals(
                        "ok", await(transportPipeline.execute(() -> slowFirstAttempt(fixture, transportAttempts))));

                assertEquals(expected, aspectEvents, "AOP and transport must expose the same event type sequence");
                assertEquals(2, aspectAttempts.get());
                assertEquals(2, transportAttempts.get());
            } finally {
                await(transportContext.close());
            }
        }
    }

    static void shouldApplyTheNamedTierOverInlineDeclarations() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            MethodMetadata metadata = metadata("namedTier");
            ResilientAspect namedTierAspect =
                    new ResilientAspect(fixture.resilience(), Optional.of(new CountingRegistry(0)));
            try {
                MethodInterceptor interceptor = namedTierAspect.interceptor(metadata, annotation("namedTier"));
                AtomicInteger attempts = new AtomicInteger();

                assertInstanceOf(
                        IllegalStateException.class, failureOf(interceptor.intercept(invocation(metadata, () -> {
                            attempts.incrementAndGet();
                            return Future.failedFuture(new IllegalStateException("no retry from named tier"));
                        }))));
                assertEquals(1, attempts.get(), "named maxRetries=0 must override inline maxRetries=2");
            } finally {
                await(namedTierAspect.close());
            }
        }
    }

    static void shouldShareOnePipelineAcrossProxyInstances() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            MethodMetadata metadata = metadata("sharedBreaker");
            MethodInterceptor first = fixture.aspect().interceptor(metadata, annotation("sharedBreaker"));
            MethodInterceptor second = fixture.aspect().interceptor(metadata, annotation("sharedBreaker"));

            assertInstanceOf(
                    IllegalStateException.class,
                    failureOf(first.intercept(
                            invocation(metadata, () -> Future.failedFuture(new IllegalStateException("trip"))))));
            assertInstanceOf(
                    CircuitOpenException.class,
                    failureOf(
                            second.intercept(invocation(metadata, () -> Future.succeededFuture("must be rejected")))));
        }
    }

    static void shouldCloseTheContextBeforeTheRuntimeStep() throws Exception {
        try (RuntimeFixture fixture = RuntimeFixture.create()) {
            ApplicationShutdownStep aspectStep = ResilienceAopModule.resilienceAopShutdownStep(fixture.aspect());
            ApplicationShutdownStep runtimeStep = new ApplicationShutdownStep() {
                @Override
                public LifecyclePhase phase() {
                    return LifecyclePhase.CONFIGURE;
                }

                @Override
                public int priority() {
                    return Integer.MAX_VALUE;
                }

                @Override
                public Future<Void> stop() {
                    return Future.succeededFuture();
                }
            };
            List<ApplicationShutdownStep> ordered = new ArrayList<>(List.of(aspectStep, runtimeStep));
            ordered.sort(LifecycleOrdered.comparator().reversed());

            assertSame(aspectStep, ordered.get(0));
            assertEquals(LifecyclePhase.VALIDATE, aspectStep.phase());
            assertEquals(0, aspectStep.priority());
            await(aspectStep.stop());
            assertTrue(fixture.aspect().close().isComplete());
        }
    }

    private static ResolvedResiliencePolicy resolve(
            Resilience resilience, CountingRegistry registry, ResilienceAnnotations annotations) {
        return resilience
                .policyResolver()
                .resolve(
                        annotations,
                        registry.layer(annotations, ResiliencePolicyOverrides.none()),
                        ResilienceDefaults.none());
    }

    private static MethodMetadata metadata(String methodName) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName);
        return new ReflectiveMethodMetadata(method, List.of());
    }

    private static Resilient annotation(String methodName) throws Exception {
        return Fixture.class.getDeclaredMethod(methodName).getAnnotation(Resilient.class);
    }

    private static dev.vertique.resilience.adapter.AdapterOperationIdentity operation(MethodMetadata metadata) {
        return new dev.vertique.resilience.adapter.AdapterOperationIdentity(
                "aop", List.of(metadata.declaringType().getName(), metadata.name()));
    }

    private static Invocation invocation(MethodMetadata metadata, java.util.function.Supplier<Future<Object>> proceed) {
        return new Invocation() {
            @Override
            public MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return new Object[0];
            }

            @Override
            public Object instance() {
                return new Fixture();
            }

            @Override
            public Future<Object> proceed() {
                return proceed.get();
            }
        };
    }

    private static Future<Object> slowFirstAttempt(RuntimeFixture fixture, AtomicInteger attempts) {
        if (attempts.incrementAndGet() > 1) {
            return Future.succeededFuture("ok");
        }
        return Future.future(promise -> fixture.vertx().setTimer(600L, ignored -> promise.complete("late")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            await(future);
            throw new AssertionError("expected a failed future");
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }

    static final class Fixture {
        @Resilient(policy = PAYMENTS)
        @dev.vertique.resilience.annotation.Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
        Future<String> retryOnce() {
            return Future.succeededFuture("unused");
        }

        @Resilient(policy = UNKNOWN)
        Future<String> unknownPolicy() {
            return Future.succeededFuture("unused");
        }

        @Resilient(policy = PAYMENTS)
        @dev.vertique.resilience.annotation.Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
        @dev.vertique.resilience.annotation.Timeout(500)
        Future<String> transportParity() {
            return Future.succeededFuture("unused");
        }

        @Resilient(policy = PAYMENTS)
        @dev.vertique.resilience.annotation.Retry(maxRetries = 2, delayMs = 1, maxDelayMs = 1)
        Future<String> namedTier() {
            return Future.succeededFuture("unused");
        }

        @Resilient
        @dev.vertique.resilience.annotation.CircuitBreaker(maxFailures = 1, resetTimeoutMs = 60_000)
        @dev.vertique.resilience.annotation.Retry(maxRetries = 0, delayMs = 1, maxDelayMs = 1)
        Future<String> sharedBreaker() {
            return Future.succeededFuture("unused");
        }
    }

    private static final class RuntimeFixture implements AutoCloseable {
        private final Vertx vertx;
        private final Resilience resilience;
        private final CountingRegistry registry;
        private final List<Class<? extends ResilienceEvent>> events;
        private final ResilientAspect aspect;

        private RuntimeFixture(
                Vertx vertx,
                Resilience resilience,
                CountingRegistry registry,
                List<Class<? extends ResilienceEvent>> events,
                ResilientAspect aspect) {
            this.vertx = vertx;
            this.resilience = resilience;
            this.registry = registry;
            this.events = events;
            this.aspect = aspect;
        }

        static RuntimeFixture create() {
            Vertx vertx = Vertx.vertx();
            List<Class<? extends ResilienceEvent>> events = new ArrayList<>();
            ResilienceObserver observer = event -> events.add(event.getClass());
            Resilience resilience = Resilience.create(vertx, Set.of(observer));
            CountingRegistry registry = new CountingRegistry();
            return new RuntimeFixture(
                    vertx, resilience, registry, events, new ResilientAspect(resilience, Optional.of(registry)));
        }

        Resilience resilience() {
            return resilience;
        }

        Vertx vertx() {
            return vertx;
        }

        CountingRegistry registry() {
            return registry;
        }

        ResilientAspect aspect() {
            return aspect;
        }

        List<Class<? extends ResilienceEvent>> events() {
            return events;
        }

        @Override
        public void close() throws Exception {
            try {
                await(aspect.close());
            } finally {
                try {
                    await(resilience.close());
                } finally {
                    await(vertx.close());
                }
            }
        }
    }

    private static final class CountingRegistry implements ResiliencePolicyRegistry {
        private final ResiliencePolicyRegistry delegate;
        private final AtomicInteger lookups = new AtomicInteger();

        private CountingRegistry() {
            this(2);
        }

        private CountingRegistry(int maxRetries) {
            delegate = ResiliencePolicyRegistry.of(List.of(new ResiliencePolicyConfig(
                    PAYMENTS, null, new RetryPolicyConfig(maxRetries, 1L, 1.0d, 1L), null, null)));
        }

        @Override
        public ResiliencePolicyOverrides require(String name) {
            lookups.incrementAndGet();
            if (UNKNOWN.equals(name))
                throw new ConfigurationException("resilience.policies." + name + " is not defined");
            return delegate.require(name);
        }

        int lookups() {
            return lookups.get();
        }
    }

    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }
}
