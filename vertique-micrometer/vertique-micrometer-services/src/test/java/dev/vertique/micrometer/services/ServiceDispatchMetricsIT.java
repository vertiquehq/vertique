// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for {@link ServiceDispatchMetricsInterceptor} wired into a real Vert.x event bus
 * via a {@link ServiceMethodInvoker} consumer.
 *
 * <p>Each test dispatches a real event bus message and waits for {@link ServiceInterceptor#onTerminalComplete}
 * to confirm that the timer was recorded. The harness replicates the minimal invoker setup from
 * {@code ServiceMethodInvokerTest} in the {@code vertique-services} module.
 *
 * <p>Three scenarios are covered:
 * <ol>
 *   <li>Request/reply success — timer with {@code outcome=SUCCESS}, {@code oneway=false}</li>
 *   <li>Request/reply failure — timer with {@code outcome=ERROR} and a named {@code error.type}</li>
 *   <li>One-way (fire-and-forget) — timer with {@code oneway=true}</li>
 * </ol>
 *
 * <p>All tests are class-level timeout-guarded at 20 s to prevent hangs on CI.
 *
 * <p>Determinism: The 5-repetition stress loop is run per test to surface flakes that only appear
 * under scheduler pressure.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServiceDispatchMetricsIT {

    // --- Contract fixture ---

    /** Service contract interface used in all IT scenarios. */
    @ServiceContract(namespace = "it", value = "metrics-it")
    interface MetricsItContract {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("failAlways")
        Future<String> failAlways(String input);

        @dev.vertique.services.OneWay
        @ServiceOperation("fireAndForget")
        Future<Void> fireAndForget(String payload);
    }

    // --- Service implementation fixtures ---

    /** Implementation that succeeds with a greeting. */
    static class GreetImpl implements MetricsItContract {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<String> failAlways(String input) {
            return Future.succeededFuture("ok");
        }

        @Override
        public Future<Void> fireAndForget(String payload) {
            return Future.succeededFuture();
        }
    }

    /** Implementation whose {@code failAlways} always throws. */
    static class FailImpl implements MetricsItContract {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<String> failAlways(String input) {
            return Future.failedFuture(new IllegalArgumentException("bad-input"));
        }

        @Override
        public Future<Void> fireAndForget(String payload) {
            return Future.succeededFuture();
        }
    }

    // --- Shared state ---

    /** Stable per-class address counter to keep each test's address unique. */
    private static final AtomicInteger ADDR = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "it/metrics/" + base + "/" + ADDR.incrementAndGet();
    }

    /** Shared codecs registered once per Vert.x instance. */
    @BeforeAll
    static void registerCodecs(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered by a parallel test class — safe to ignore.
        }
    }

    /** Closes the shared Vert.x instance after all tests in this class. */
    @AfterAll
    static void tearDown(Vertx vertx, VertxTestContext ctx) {
        vertx.close().onComplete(ar -> ctx.completeNow());
    }

    // --- Codec helper ---

    private static final DeliveryOptions ENVELOPE_CODEC = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Meta builders ---

    /**
     * Builds {@link ServiceMethodMeta} for the {@code greet} method with the given address and
     * stable target id.
     *
     * @param impl             the service implementation instance
     * @param address          the event bus address
     * @param stableTargetId   the stable target id (e.g. {@code it.metrics-it.greet})
     * @return metadata for the greet operation
     * @throws Exception if method lookup fails
     */
    private static ServiceMethodMeta greetMeta(Object impl, String address, String stableTargetId) throws Exception {
        Method method = MetricsItContract.class.getMethod("greet", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                stableTargetId,
                "it",
                "metrics-it",
                "greet",
                String.class,
                String.class,
                List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    /**
     * Builds {@link ServiceMethodMeta} for the {@code failAlways} method.
     *
     * @param impl             the service implementation instance
     * @param address          the event bus address
     * @param stableTargetId   the stable target id
     * @return metadata for the failAlways operation
     * @throws Exception if method lookup fails
     */
    private static ServiceMethodMeta failMeta(Object impl, String address, String stableTargetId) throws Exception {
        Method method = MetricsItContract.class.getMethod("failAlways", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                stableTargetId,
                "it",
                "metrics-it",
                "failAlways",
                String.class,
                String.class,
                List.of(new ParamMeta("input", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    /**
     * Builds {@link ServiceMethodMeta} for the {@code fireAndForget} method.
     *
     * @param impl             the service implementation instance
     * @param address          the event bus address
     * @param stableTargetId   the stable target id
     * @return metadata for the fireAndForget operation
     * @throws Exception if method lookup fails
     */
    private static ServiceMethodMeta oneWayMeta(Object impl, String address, String stableTargetId) throws Exception {
        Method method = MetricsItContract.class.getMethod("fireAndForget", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                stableTargetId,
                "it",
                "metrics-it",
                "fireAndForget",
                String.class,
                Void.class,
                List.of(new ParamMeta("payload", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                true);
    }

    // --- Terminal-complete signalling interceptor ---

    /**
     * Returns a {@link ServiceInterceptor} that completes {@code signal} when
     * {@link ServiceInterceptor#onTerminalComplete} fires.
     *
     * @param signal the promise to complete once the terminal hook fires
     * @return a new interceptor that signals on terminal completion
     */
    private static ServiceInterceptor signalOnTerminal(Promise<Void> signal) {
        return new ServiceInterceptor() {
            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
                signal.tryComplete();
            }
        };
    }

    // --- Helper to build and find a timer ---

    /**
     * Looks up the timer for the given tag values in the registry.
     *
     * @param registry  the meter registry
     * @param target    the {@code target} tag value
     * @param outcome   the {@code outcome} tag value
     * @param oneway    the {@code oneway} tag value
     * @param errorType the {@code error.type} tag value
     * @return the matched timer, or {@code null} if not found
     */
    private static Timer findTimer(
            SimpleMeterRegistry registry, String target, String outcome, String oneway, String errorType) {
        return registry.find(ServiceDispatchMetricsInterceptor.METER_NAME)
                .tag(ServiceDispatchMetricsInterceptor.TAG_TARGET, target)
                .tag(ServiceDispatchMetricsInterceptor.TAG_OUTCOME, outcome)
                .tag(ServiceDispatchMetricsInterceptor.TAG_ONEWAY, oneway)
                .tag(ServiceDispatchMetricsInterceptor.TAG_ERROR_TYPE, errorType)
                .timer();
    }

    // =========================================================================
    // Test 1 — successful request/reply dispatch records timer with outcome=SUCCESS
    // =========================================================================

    /**
     * Runs a single iteration of the success scenario.
     *
     * @param vertx    the Vert.x instance
     * @param registry fresh registry for this iteration
     * @return a future that completes when the timer has been recorded and verified
     * @throws Exception if meta construction fails
     */
    private Future<Void> runSuccessIteration(Vertx vertx, SimpleMeterRegistry registry) throws Exception {
        String target = "it.metrics-it.greet";
        String address = uniqueAddress("greet");
        Promise<Void> terminal = Promise.promise();

        ServiceDispatchMetricsInterceptor interceptor =
                new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
        ServiceMethodMeta meta = greetMeta(new GreetImpl(), address, target);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(interceptor, signalOnTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        return vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), ENVELOPE_CODEC)
                .compose(reply -> terminal.future())
                .map(v -> {
                    Timer timer = findTimer(registry, target, "SUCCESS", "false", "none");
                    assertNotNull(timer, "timer with outcome=SUCCESS must be registered after a successful dispatch");
                    assertEquals(1, timer.count(), "timer count must be 1");
                    return null;
                });
    }

    @Test
    @DisplayName("Test 11: successful request/reply → outcome=SUCCESS, oneway=false, error.type=none, count=1")
    void successfulDispatchRecordsSuccessTimer(Vertx vertx, VertxTestContext ctx) throws Exception {
        Future<Void> loop = runSuccessIteration(vertx, new SimpleMeterRegistry());
        for (int i = 1; i < 5; i++) {
            loop = loop.compose(v -> {
                try {
                    return runSuccessIteration(vertx, new SimpleMeterRegistry());
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            });
        }
        loop.onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    // =========================================================================
    // Test 2 — failed dispatch records timer with outcome=ERROR and error.type
    // =========================================================================

    /**
     * Runs a single iteration of the failure scenario.
     *
     * @param vertx    the Vert.x instance
     * @param registry fresh registry for this iteration
     * @return a future that completes when the timer has been recorded and verified
     * @throws Exception if meta construction fails
     */
    private Future<Void> runFailureIteration(Vertx vertx, SimpleMeterRegistry registry) throws Exception {
        String target = "it.metrics-it.failAlways";
        String address = uniqueAddress("failAlways");
        Promise<Void> terminal = Promise.promise();

        ServiceDispatchMetricsInterceptor interceptor =
                new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
        ServiceMethodMeta meta = failMeta(new FailImpl(), address, target);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(interceptor, signalOnTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        return vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("bad"), ENVELOPE_CODEC)
                .compose(reply -> terminal.future())
                .map(v -> {
                    Timer timer = findTimer(registry, target, "ERROR", "false", "IllegalArgumentException");
                    assertNotNull(
                            timer, "timer with outcome=ERROR, error.type=IllegalArgumentException must be registered");
                    assertEquals(1, timer.count(), "timer count must be 1");
                    return null;
                });
    }

    @Test
    @DisplayName("Test 12: failing service method → outcome=ERROR, error.type=IllegalArgumentException, count=1")
    void failingDispatchRecordsErrorTimer(Vertx vertx, VertxTestContext ctx) throws Exception {
        Future<Void> loop = runFailureIteration(vertx, new SimpleMeterRegistry());
        for (int i = 1; i < 5; i++) {
            loop = loop.compose(v -> {
                try {
                    return runFailureIteration(vertx, new SimpleMeterRegistry());
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            });
        }
        loop.onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    // =========================================================================
    // Test 3 — one-way dispatch records timer with oneway=true
    // =========================================================================

    /**
     * Runs a single iteration of the one-way scenario.
     *
     * @param vertx    the Vert.x instance
     * @param registry fresh registry for this iteration
     * @return a future that completes when the timer has been recorded and verified
     * @throws Exception if meta construction fails
     */
    private Future<Void> runOneWayIteration(Vertx vertx, SimpleMeterRegistry registry) throws Exception {
        String target = "it.metrics-it.fireAndForget";
        String address = uniqueAddress("fireAndForget");
        Promise<Void> terminal = Promise.promise();

        ServiceDispatchMetricsInterceptor interceptor =
                new ServiceDispatchMetricsInterceptor(registry, Optional.empty());
        ServiceMethodMeta meta = oneWayMeta(new GreetImpl(), address, target);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(interceptor, signalOnTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        // One-way: use send(), not request()
        vertx.eventBus().send(address, DispatchEnvelope.of("event"), ENVELOPE_CODEC);

        // Wait for the terminal hook to fire to confirm recording
        return terminal.future().map(v -> {
            Timer timer = findTimer(registry, target, "SUCCESS", "true", "none");
            assertNotNull(timer, "timer with oneway=true must be registered for a fire-and-forget dispatch");
            assertEquals(1, timer.count(), "timer count must be 1");
            return null;
        });
    }

    @Test
    @DisplayName("Test 13: one-way (fire-and-forget) dispatch → oneway=true, outcome=SUCCESS, count=1")
    void oneWayDispatchRecordsTimerWithOnewayTrue(Vertx vertx, VertxTestContext ctx) throws Exception {
        Future<Void> loop = runOneWayIteration(vertx, new SimpleMeterRegistry());
        for (int i = 1; i < 5; i++) {
            loop = loop.compose(v -> {
                try {
                    return runOneWayIteration(vertx, new SimpleMeterRegistry());
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            });
        }
        loop.onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }
}
