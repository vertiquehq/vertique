// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.annotation.Timeout;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.DispatchPipeline;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link PolicyChainBuilder}.
 *
 * <p>Verifies that {@link DispatchPipeline} instances are built correctly from resilience
 * annotation combinations, that {@code null} is returned when no annotations are present,
 * and that config-based overrides are applied without errors.
 *
 * <p>Requires a real Vert.x instance because {@link CircuitBreakerStage} internally creates
 * a Vert.x circuit breaker.
 */
@ExtendWith(VertxExtension.class)
@org.junit.jupiter.api.Timeout(value = 20, unit = TimeUnit.SECONDS)
class PolicyChainBuilderTest {

    // --- Annotated fixture interfaces ---

    @ServiceContract(namespace = "test", value = "svc")
    interface TestService {
        @ServiceOperation("op")
        Future<String> op();

        @Timeout(5000)
        @ServiceOperation("withTimeout")
        Future<String> withTimeout();

        @CircuitBreaker(maxFailures = 3, timeoutMs = 1000, resetTimeoutMs = 5000)
        @ServiceOperation("withCB")
        Future<String> withCB();

        @Retry(maxRetries = 2, delayMs = 100, backoffMultiplier = 2.0, maxDelayMs = 30000)
        @ServiceOperation("withRetry")
        Future<String> withRetry();

        @Timeout(2000)
        @CircuitBreaker(maxFailures = 5, resetTimeoutMs = 8000)
        @ServiceOperation("withTimeoutAndCB")
        Future<String> withTimeoutAndCB();

        @Timeout(1000)
        @Retry(maxRetries = 1, delayMs = 200, backoffMultiplier = 1.0, maxDelayMs = 5000)
        @ServiceOperation("withTimeoutAndRetry")
        Future<String> withTimeoutAndRetry();

        @Timeout(500)
        @CircuitBreaker(maxFailures = 3, resetTimeoutMs = 3000)
        @Retry(maxRetries = 2, delayMs = 50, backoffMultiplier = 1.5, maxDelayMs = 2000)
        @ServiceOperation("withAll")
        Future<String> withAll();
    }

    // --- Helpers ---

    private static ResilienceAnnotations resolveFor(String methodName) throws Exception {
        Method method = TestService.class.getMethod(methodName);
        return ResilienceAnnotations.resolve(TestService.class, method);
    }

    private static ServiceMethodMeta makeMeta(ResilienceAnnotations ra) throws Exception {
        Method method = TestService.class.getMethod("op");
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/test/svc/op",
                "test.svc.op",
                "test",
                "svc",
                "op",
                null,
                String.class,
                List.of(),
                ra,
                List.of(),
                List.of(),
                false);
    }

    // --- Tests ---

    private PolicyChainBuilder builder;

    @BeforeEach
    void setUp(Vertx vertx) {
        builder = new PolicyChainBuilder(vertx, Map.of());
    }

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Builds the typed {@code (type, name) -> ServiceConfig} index from a root config object via the
     * boundary parser, exactly as the Dagger provider does.
     *
     * @param rootConfig the root application config (may contain a {@code services} section)
     * @return the parsed service config index
     */
    private static Map<ServicesConfig.ServiceKey, ServiceConfig> indexFor(JsonObject rootConfig) {
        return ServicesConfig.fromConfig(rootConfig, configParser()).index();
    }

    @Nested
    @DisplayName("No annotations")
    class NoAnnotations {

        @Test
        @DisplayName("build() should return null when no resilience annotations are present")
        void noAnnotations_returnsNull() throws Exception {
            ServiceMethodMeta meta = makeMeta(ResilienceAnnotations.NONE);

            DispatchPipeline pipeline = builder.build(meta);

            assertNull(pipeline, "no annotations → null pipeline");
        }
    }

    @Nested
    @DisplayName("Single annotation")
    class SingleAnnotation {

        @Test
        @DisplayName("@Timeout only → non-null pipeline with stages")
        void timeoutOnly_returnsPipelineWithStage() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withTimeout"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages(), "pipeline should have at least one stage");
        }

        @Test
        @DisplayName("@CircuitBreaker only → non-null pipeline with stages")
        void circuitBreakerOnly_returnsPipeline() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withCB"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages());
        }

        @Test
        @DisplayName("@Retry only → non-null pipeline with stages")
        void retryOnly_returnsPipeline() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withRetry"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages());
        }
    }

    @Nested
    @DisplayName("Annotation combinations")
    class AnnotationCombinations {

        @Test
        @DisplayName("@Timeout + @CircuitBreaker → non-null pipeline")
        void timeoutAndCircuitBreaker_returnsPipeline() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withTimeoutAndCB"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages());
        }

        @Test
        @DisplayName("@Timeout + @Retry → non-null pipeline")
        void timeoutAndRetry_returnsPipeline() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withTimeoutAndRetry"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages());
        }

        @Test
        @DisplayName("@Timeout + @CircuitBreaker + @Retry → non-null pipeline")
        void allThree_returnsPipeline() throws Exception {
            ServiceMethodMeta meta = makeMeta(resolveFor("withAll"));

            DispatchPipeline pipeline = builder.build(meta);

            assertNotNull(pipeline);
            assertTrue(pipeline.hasStages());
        }
    }

    @Nested
    @DisplayName("Config overrides")
    class ConfigOverrides {

        /**
         * Counting terminal that always fails — its invocation count is the observable proof that
         * the effective retry/circuit-breaker policy was actually applied.
         *
         * @param counter the invocation counter incremented on each call
         * @return a supplier returning a failed future, incrementing {@code counter} each time
         */
        private static Supplier<Future<Object>> alwaysFails(AtomicInteger counter) {
            return () -> {
                counter.incrementAndGet();
                return Future.failedFuture(new RuntimeException("downstream failure"));
            };
        }

        @Test
        @DisplayName(
                "retry.maxRetries override is consumed: terminal is invoked (1 + override) times, not (1 + annotation)")
        void configOverrideMaxRetries_changesEffectiveRetryCount(Vertx vertx, VertxTestContext ctx) throws Throwable {
            // Annotation default is maxRetries=2 → 3 calls. Override to 5 → 6 calls. delayMs=1 keeps it fast.
            JsonObject config = operationConfig(
                    "retry",
                    new JsonObject().put("maxRetries", 5).put("delayMs", 1).put("maxDelayMs", 5));
            PolicyChainBuilder configuredBuilder = new PolicyChainBuilder(vertx, indexFor(config));
            ServiceMethodMeta meta = makeMeta(resolveFor("withRetry"));

            DispatchPipeline pipeline = configuredBuilder.build(meta);
            assertNotNull(pipeline);

            AtomicInteger calls = new AtomicInteger(0);
            pipeline.execute(null, dev.vertique.core.eventbus.DispatchEnvelope.empty(), alwaysFails(calls))
                    .onComplete(ar -> {
                        ctx.verify(() -> {
                            assertTrue(ar.failed(), "all attempts fail");
                            assertEquals(
                                    6,
                                    calls.get(),
                                    "override maxRetries=5 → 1 initial + 5 retries; annotation default (2) would give 3");
                        });
                        ctx.completeNow();
                    });

            assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        @Test
        @DisplayName(
                "timeout.valueMs override is consumed: a small override aborts a call the annotation timeout would allow")
        void configOverrideTimeoutValueMs_changesEffectiveTimeout(Vertx vertx, VertxTestContext ctx) throws Throwable {
            // withTimeout annotation is @Timeout(5000). Override down to 50ms; terminal completes at 300ms.
            // With the override consumed → timeout failure. If ignored (5000ms) → the call would succeed.
            JsonObject config = operationConfig("timeout", new JsonObject().put("valueMs", 50L));
            PolicyChainBuilder configuredBuilder = new PolicyChainBuilder(vertx, indexFor(config));
            ServiceMethodMeta meta = makeMeta(resolveFor("withTimeout"));

            DispatchPipeline pipeline = configuredBuilder.build(meta);
            assertNotNull(pipeline);

            Supplier<Future<Object>> slowTerminal = () -> {
                io.vertx.core.Promise<Object> promise = io.vertx.core.Promise.promise();
                vertx.setTimer(300, id -> promise.complete("too-late"));
                return promise.future();
            };
            pipeline.execute(null, dev.vertique.core.eventbus.DispatchEnvelope.empty(), slowTerminal)
                    .onComplete(ar -> {
                        ctx.verify(
                                () -> assertTrue(
                                        ar.failed(),
                                        "50ms override timeout must abort the 300ms call; the 5000ms annotation would have allowed it"));
                        ctx.completeNow();
                    });

            assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        @Test
        @DisplayName(
                "circuitBreaker.maxFailures override is consumed: a lower override trips the circuit sooner than the annotation")
        void configOverrideCbMaxFailures_changesEffectiveThreshold(Vertx vertx, VertxTestContext ctx) throws Throwable {
            // withCB annotation is maxFailures=3. Override down to 1 → circuit opens after the first failure,
            // so the terminal is NOT invoked on the second attempt. With the annotation (3) it would be.
            JsonObject config = operationConfig("circuitBreaker", new JsonObject().put("maxFailures", 1));
            PolicyChainBuilder configuredBuilder = new PolicyChainBuilder(vertx, indexFor(config));
            ServiceMethodMeta meta = makeMeta(resolveFor("withCB"));

            DispatchPipeline pipeline = configuredBuilder.build(meta);
            assertNotNull(pipeline);

            AtomicInteger calls = new AtomicInteger(0);
            Supplier<Future<Object>> alwaysFails = alwaysFails(calls);
            // First failure trips the circuit (override maxFailures=1).
            pipeline.execute(null, dev.vertique.core.eventbus.DispatchEnvelope.empty(), alwaysFails)
                    .onComplete(ar1 -> vertx.setTimer(50, id -> {
                        int afterFirst = calls.get();
                        pipeline.execute(null, dev.vertique.core.eventbus.DispatchEnvelope.empty(), alwaysFails)
                                .onComplete(ar2 -> {
                                    ctx.verify(() -> {
                                        assertEquals(1, afterFirst, "first attempt invokes the terminal once");
                                        assertTrue(ar2.failed(), "second attempt fails (circuit open)");
                                        assertEquals(
                                                1,
                                                calls.get(),
                                                "override maxFailures=1 opens the circuit; terminal not re-invoked (annotation 3 would re-invoke)");
                                    });
                                    ctx.completeNow();
                                });
                    }));

            assertTrue(ctx.awaitCompletion(15, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        /**
         * Builds a root config that places a single policy-override block under the {@code op}
         * operation of {@code services.contracts.test.svc} (external shape
         * {@code services.contracts.test.svc.operations.op.{policy}}).
         *
         * @param policy the policy key ({@code timeout}/{@code circuitBreaker}/{@code retry})
         * @param policyBlock the override block for that policy
         * @return the root config object
         */
        private static JsonObject operationConfig(String policy, JsonObject policyBlock) {
            return new JsonObject()
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "test",
                                                            new JsonObject()
                                                                    .put(
                                                                            "svc",
                                                                            new JsonObject()
                                                                                    .put(
                                                                                            "operations",
                                                                                            new JsonObject()
                                                                                                    .put(
                                                                                                            "op",
                                                                                                            new JsonObject()
                                                                                                                    .put(
                                                                                                                            policy,
                                                                                                                            policyBlock)))))));
        }
    }
}
