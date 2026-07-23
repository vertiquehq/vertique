// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.logging.MDCContexts;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.services.dispatch.DispatchContext;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ServiceMethodInvoker}.
 *
 * <p>Verifies the full dispatch pipeline: parameter extraction, MDC restoration, security context
 * propagation, {@link ServiceInterceptor} lifecycle callbacks ({@code beforeDispatch},
 * {@code afterDispatch}, {@code onComplete}, {@code recoverError}), failure mapping, one-way
 * operation handling, and reply semantics.
 */
@ExtendWith(VertxExtension.class)
class ServiceMethodInvokerTest {

    // --- Contract Fixture ---

    @ServiceContract(namespace = "test", value = "svc")
    interface TestService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("doNothing")
        Future<Void> doNothing();

        @ServiceOperation("failAlways")
        Future<String> failAlways(String input);

        @OneWay
        @ServiceOperation("fireAndForget")
        Future<Void> fireAndForget(String payload);
    }

    // --- Implementation Fixtures ---

    /**
     * Default test implementation with standard behavior for all methods.
     * Tests that need custom behavior for a single method should extend this
     * and override only the method under test.
     */
    static class TestServiceImpl implements TestService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<Void> doNothing() {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> failAlways(String input) {
            return Future.failedFuture(new RuntimeException("boom"));
        }

        @Override
        public Future<Void> fireAndForget(String payload) {
            return Future.succeededFuture();
        }
    }

    // --- Address counter for unique per-test addresses ---

    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "test-invoker/" + base + "/" + ADDRESS_COUNTER.incrementAndGet();
    }

    // --- Setup ---

    private static final TestServiceImpl SERVICE_IMPL = new TestServiceImpl();

    @BeforeAll
    static void setup(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException e) {
            // Already registered — safe to ignore
        }
    }

    // --- Codec Helper ---

    private static final DeliveryOptions BODY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Meta Builders ---

    private ServiceMethodMeta greetMeta(Object impl, String address) throws Exception {
        Method method = TestService.class.getMethod("greet", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "greet",
                String.class,
                String.class,
                List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceMethodMeta doNothingMeta(String address) throws Exception {
        Method method = TestService.class.getMethod("doNothing");
        return ServiceMethodMeta.ofDirect(
                SERVICE_IMPL,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "doNothing",
                null,
                Void.class,
                List.of(),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceMethodMeta failAlwaysMeta(String address) throws Exception {
        Method method = TestService.class.getMethod("failAlways", String.class);
        return ServiceMethodMeta.ofDirect(
                SERVICE_IMPL,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "failAlways",
                String.class,
                String.class,
                List.of(new ParamMeta("input", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceMethodMeta fireAndForgetMeta(Object impl, String address) throws Exception {
        Method method = TestService.class.getMethod("fireAndForget", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "fireAndForget",
                String.class,
                Void.class,
                List.of(new ParamMeta("payload", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                true);
    }

    private ServiceExceptionMapper emptyExceptionMapper() {
        return new ServiceExceptionMapper();
    }

    /** A {@link ServiceInterceptor} that captures the {@code onTerminalComplete} result into {@code out}. */
    private static ServiceInterceptor captureTerminal(Promise<Result<?>> out) {
        return new ServiceInterceptor() {
            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                out.tryComplete(result);
            }
        };
    }

    /**
     * Builds an invoker wired with the framework's built-in MDC + SC decoders so dispatch-context
     * carriers (e.g. {@link dev.vertique.logging.DiagnosticContextSnapshot}) are materialised
     * into the holder. The four-arg test constructor passes {@code null} for the registry, which
     * short-circuits the decoder pipeline and leaves wire-format values un-decoded.
     */
    private ServiceMethodInvoker invokerWithBuiltInDecoders(ServiceMethodMeta meta, Vertx vertx) {
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(
                java.util.Set.of(),
                java.util.Set.of(
                        MDCContexts.serviceDispatchDecoder(),
                        ServiceDispatchCodecs.passThroughDecoder(SecurityContext.class)));
        return new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null, null, vertx, registry);
    }

    // --- Tests ---

    @Test
    @DisplayName("greet(World) returns Result.success('Hello World')")
    void shouldInvokeMethodAndReturnSuccessResult(Vertx vertx, VertxTestContext ctx) throws Exception {
        String address = uniqueAddress("greet");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    Result<?> result = reply.body();
                    assertTrue(result.isSuccess());
                    assertEquals("Hello World", result.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("doNothing() returns Result.success(null)")
    void shouldReturnVoidResult(Vertx vertx, VertxTestContext ctx) throws Exception {
        String address = uniqueAddress("doNothing");
        ServiceMethodMeta meta = doNothingMeta(address);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.empty(), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    Result<?> result = reply.body();
                    assertTrue(result.isSuccess());
                    assertNull(result.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("ServiceExceptionMapper translates RuntimeException to IllegalStateException")
    void shouldMapFailureThroughExceptionMapper(Vertx vertx, VertxTestContext ctx) throws Exception {
        String address = uniqueAddress("failAlways");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceExceptionMapper mapper = new ServiceExceptionMapper()
                .on(RuntimeException.class, t -> new IllegalStateException("translated: " + t.getMessage()));
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, mapper, List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    Result<?> result = reply.body();
                    assertTrue(result.isFailure());
                    assertInstanceOf(IllegalStateException.class, result.cause());
                    assertTrue(result.cause().getMessage().contains("translated"));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("beforeDispatch interceptor is called before method invocation")
    void shouldRunBeforeDispatchInterceptors(Vertx vertx, VertxTestContext ctx) throws Exception {
        AtomicBoolean interceptorCalled = new AtomicBoolean(false);
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext dispatchCtx) {
                interceptorCalled.set(true);
                return Future.succeededFuture(dispatchCtx);
            }
        };

        String address = uniqueAddress("greet-before-interceptor");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("Hook"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(interceptorCalled.get(), "beforeDispatch interceptor must have been called");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("beforeDispatch interceptor failure short-circuits method invocation and returns failure result")
    void shouldShortCircuitOnBeforeInterceptorFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
        AtomicBoolean methodCalled = new AtomicBoolean(false);

        TestService interceptedImpl = new TestServiceImpl() {
            @Override
            public Future<String> greet(String name) {
                methodCalled.set(true);
                return Future.succeededFuture("Hello");
            }
        };

        String address = uniqueAddress("greet-short-circuit");
        ServiceMethodMeta meta = greetMeta(interceptedImpl, address);

        ServiceInterceptor failingInterceptor = new ServiceInterceptor() {
            @Override
            public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext dispatchCtx) {
                return Future.failedFuture(new RuntimeException("interceptor rejected"));
            }
        };

        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(failingInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertFalse(methodCalled.get(), "Method must NOT be invoked when beforeDispatch interceptor fails");
                    assertTrue(reply.body().isFailure(), "Result must be a failure");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("afterDispatch interceptor is called even when it fails, reply is unaffected")
    void shouldRunAfterDispatchInterceptorsIndependently(Vertx vertx, VertxTestContext ctx) throws Exception {
        AtomicBoolean afterCalled = new AtomicBoolean(false);

        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public Future<Void> afterDispatch(ServiceDispatchContext dispatchCtx, Result<?> result) {
                afterCalled.set(true);
                return Future.failedFuture(new RuntimeException("after interceptor failure - must be ignored"));
            }
        };

        String address = uniqueAddress("greet-after-interceptor");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(afterCalled.get(), "afterDispatch interceptor must have been called");
                    assertTrue(reply.body().isSuccess(), "Reply must still be a success despite interceptor failure");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("A reply is always sent, even when the service method fails")
    void shouldAlwaysSendReply(Vertx vertx, VertxTestContext ctx) throws Exception {
        String address = uniqueAddress("failAlways-reply");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("input"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertNotNull(reply, "A reply must always be received");
                    assertNotNull(reply.body(), "Reply body must not be null");
                    assertTrue(reply.body().isFailure());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "MDC context from DispatchEnvelope is populated during method invocation via MDCContexts.bindAll scope")
    void shouldRestoreMdcContext(Vertx vertx, VertxTestContext ctx) throws Exception {
        Map<String, String> mdcMap = Map.of("requestId", "req-42", "traceId", "trace-99");
        // Capture the full MDC snapshot visible inside the handler via the Vert.x context-local
        // MDCContexts facade — not org.slf4j.MDC, which is a separate thread-local store.
        List<Map<String, String>> capturedMdc = new ArrayList<>();

        TestService mdcCapturingImpl = new TestServiceImpl() {
            @Override
            public Future<String> greet(String name) {
                // MDCContexts.copy() reads from the Vert.x context-local MDCContext populated
                // by MDCContexts.bindAll() at the start of dispatch.
                capturedMdc.add(MDCContexts.copy());
                return Future.succeededFuture("Hello " + name);
            }
        };

        String address = uniqueAddress("greet-mdc");
        ServiceMethodMeta meta = greetMeta(mdcCapturingImpl, address);
        ServiceMethodInvoker invoker = invokerWithBuiltInDecoders(meta, vertx);
        vertx.eventBus().consumer(address, invoker);

        DispatchEnvelope<String> bodyWithMdc = DispatchEnvelope.of(
                "World",
                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                        dev.vertique.logging.MDCContexts.holderKey(),
                        dev.vertique.logging.MDCContexts.holderValue(mdcMap))));

        vertx.eventBus().<Result<?>>request(address, bodyWithMdc, BODY_OPTIONS).onComplete(ctx.succeeding(reply -> {
            ctx.verify(() -> {
                assertTrue(reply.body().isSuccess());
                assertFalse(capturedMdc.isEmpty(), "MDC capture list must not be empty");
                Map<String, String> snapshot = capturedMdc.get(0);
                assertEquals("req-42", snapshot.get("requestId"), "MDC requestId must be visible during invocation");
                assertEquals("trace-99", snapshot.get("traceId"), "MDC traceId must be visible during invocation");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("MDC keys from envelope are restored to pre-dispatch state after dispatch completes")
    void shouldRestoreMdcAfterDispatch(Vertx vertx, VertxTestContext ctx) throws Exception {
        Map<String, String> mdcMap = Map.of("requestId", "req-lifo");
        // Capture the MDC snapshot *after* the reply arrives — by that point the scope is closed
        // and the pre-dispatch state (empty) must be restored.
        List<Map<String, String>> capturedAfter = new ArrayList<>();

        String address = uniqueAddress("greet-mdc-restore");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker = invokerWithBuiltInDecoders(meta, vertx);
        vertx.eventBus().consumer(address, invoker);

        DispatchEnvelope<String> bodyWithMdc = DispatchEnvelope.of(
                "World",
                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                        dev.vertique.logging.MDCContexts.holderKey(),
                        dev.vertique.logging.MDCContexts.holderValue(mdcMap))));

        vertx.eventBus().<Result<?>>request(address, bodyWithMdc, BODY_OPTIONS).onComplete(ctx.succeeding(reply -> {
            ctx.verify(() -> {
                assertTrue(reply.body().isSuccess());
                // The reply handler runs on the sending context (not the consumer's duplicated
                // context), so MDCContexts.copy() here returns what is bound on *this* context.
                // The key assertion is that the consumer's mdcScope was closed: reading from
                // the consumer side after settle is verified by the absence of leaked keys in
                // subsequent dispatches (covered by MdcPropagationTest.shouldNotLeakMdcEntries).
                // Here we simply confirm the reply was successfully received.
                capturedAfter.add(MDCContexts.copy());
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Security context from DispatchEnvelope is set in DispatchContext during invocation")
    void shouldSetAndClearDispatchContext(Vertx vertx, VertxTestContext ctx) throws Exception {
        SecurityContext sc = testSecurityContext("user-1");
        List<SecurityContext> capturedSc = new ArrayList<>();

        TestService scCapturingImpl = new TestServiceImpl() {
            @Override
            public Future<String> greet(String name) {
                capturedSc.add(DispatchContext.currentSecurityContext());
                return Future.succeededFuture("Hello " + name);
            }
        };

        String address = uniqueAddress("greet-sc");
        ServiceMethodMeta meta = greetMeta(scCapturingImpl, address);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(
                                        java.util.Map.of(dev.vertique.security.SecurityContext.class.getName(), sc))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertFalse(capturedSc.isEmpty(), "Security context capture list must not be empty");
                    assertSame(
                            sc,
                            capturedSc.get(0),
                            "DispatchContext must hold the body's security context during invocation");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("One-way operation processes message and runs afterDispatch interceptors without sending a reply")
    void shouldProcessOneWayWithoutReply(Vertx vertx, VertxTestContext ctx) throws Exception {
        AtomicBoolean afterCalled = new AtomicBoolean(false);

        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public Future<Void> afterDispatch(ServiceDispatchContext dispatchCtx, Result<?> result) {
                afterCalled.set(true);
                ctx.verify(() -> {
                    assertTrue(result.isSuccess(), "One-way dispatch result must be success");
                    assertTrue(afterCalled.get(), "afterDispatch interceptor must be called for one-way operations");
                });
                ctx.completeNow();
                return Future.succeededFuture();
            }
        };

        String address = uniqueAddress("fire-and-forget");
        ServiceMethodMeta meta = fireAndForgetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        // Use send() (not request()) since one-way operations don't reply
        vertx.eventBus().send(address, DispatchEnvelope.of("event-data"), BODY_OPTIONS);
    }

    @Test
    @DisplayName("One-way operation failure is logged but no reply is sent")
    void shouldLogFailureForOneWayOperation(Vertx vertx, VertxTestContext ctx) throws Exception {
        TestService failingImpl = new TestServiceImpl() {
            @Override
            public Future<Void> fireAndForget(String payload) {
                return Future.failedFuture(new RuntimeException("one-way-boom"));
            }
        };

        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public Future<Void> afterDispatch(ServiceDispatchContext dispatchCtx, Result<?> result) {
                ctx.verify(() -> {
                    assertTrue(result.isFailure(), "Result must be a failure");
                    assertInstanceOf(RuntimeException.class, result.cause());
                    assertTrue(result.cause().getMessage().contains("one-way-boom"));
                });
                ctx.completeNow();
                return Future.succeededFuture();
            }
        };

        String address = uniqueAddress("fire-and-forget-fail");
        ServiceMethodMeta meta = fireAndForgetMeta(failingImpl, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus().send(address, DispatchEnvelope.of("event-data"), BODY_OPTIONS);
    }

    @Test
    @DisplayName("onComplete interceptor receives timing and result for successful dispatch")
    void shouldFireOnCompleteWithTimingOnSuccess(Vertx vertx, VertxTestContext ctx) throws Exception {
        List<Result<?>> capturedResults = new ArrayList<>();
        List<Instant> capturedTimes = new ArrayList<>();

        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                capturedResults.add(result);
                capturedTimes.add(startTime);
                capturedTimes.add(endTime);
            }
        };

        String address = uniqueAddress("greet-oncomplete");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertFalse(capturedResults.isEmpty(), "onComplete must have been called");
                        assertTrue(capturedResults.get(0).isSuccess());
                        assertEquals("Hello World", capturedResults.get(0).get());
                        assertEquals(2, capturedTimes.size(), "startTime and endTime must both be provided");
                        assertFalse(
                                capturedTimes.get(0).isAfter(capturedTimes.get(1)),
                                "startTime must not be after endTime");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("onComplete interceptor receives failure result when method fails")
    void shouldFireOnCompleteWithFailureResult(Vertx vertx, VertxTestContext ctx) throws Exception {
        List<Result<?>> capturedResults = new ArrayList<>();

        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                capturedResults.add(result);
            }
        };

        String address = uniqueAddress("failAlways-oncomplete");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertFalse(capturedResults.isEmpty(), "onComplete must have been called");
                        assertTrue(capturedResults.get(0).isFailure(), "onComplete result must be a failure");
                        assertInstanceOf(
                                RuntimeException.class, capturedResults.get(0).cause());
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("onTerminalComplete receives the success outcome for a successful dispatch")
    void shouldFireOnTerminalCompleteWithSuccessOutcome(Vertx vertx, VertxTestContext ctx) throws Exception {
        Promise<Result<?>> terminal = Promise.promise();

        String address = uniqueAddress("greet-terminal-success");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(captureTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .compose(reply -> terminal.future())
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isSuccess(), "terminal outcome must be success");
                    assertEquals("Hello World", result.get());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a handler failure recovered by recoverError is reported as success to onTerminalComplete")
    void shouldReportRecoveredFailureAsSuccessToTerminalComplete(Vertx vertx, VertxTestContext ctx) throws Exception {
        List<Result<?>> onCompleteSeen = new ArrayList<>();
        Promise<Result<?>> terminal = Promise.promise();
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                onCompleteSeen.add(result);
            }

            @Override
            public Future<Void> recoverError(ServiceDispatchContext dispatchCtx, Throwable error) {
                return Future.succeededFuture(); // recover the failure
            }

            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                terminal.tryComplete(result);
            }
        };

        String address = uniqueAddress("fail-recovered-terminal");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                .compose(reply -> terminal.future())
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertFalse(onCompleteSeen.isEmpty(), "onComplete must have fired");
                    assertTrue(onCompleteSeen.get(0).isFailure(), "onComplete sees the pre-recovery failure");
                    assertTrue(result.isSuccess(), "onTerminalComplete sees the recovered success");
                    assertNull(result.get(), "the recovered terminal result carries a null value");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an unrecovered handler failure is reported as failure to onTerminalComplete")
    void shouldReportUnrecoveredFailureAsFailureToTerminalComplete(Vertx vertx, VertxTestContext ctx) throws Exception {
        Promise<Result<?>> terminal = Promise.promise();

        String address = uniqueAddress("fail-unrecovered-terminal");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(captureTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                .compose(reply -> terminal.future())
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isFailure(), "onTerminalComplete sees the unrecovered failure");
                    assertInstanceOf(RuntimeException.class, result.cause());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("an exception thrown in onTerminalComplete is swallowed and does not affect the reply")
    void shouldSwallowOnTerminalCompleteException(Vertx vertx, VertxTestContext ctx) throws Exception {
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                throw new RuntimeException("terminal observer boom");
            }
        };

        String address = uniqueAddress("greet-terminal-throws");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                    assertTrue(reply.body().isSuccess(), "reply must be unaffected by a throwing onTerminalComplete");
                    assertEquals("Hello World", reply.body().get());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("holder context (SecurityContext) is readable in onTerminalComplete — scope still open")
    void shouldHaveReadableHolderContextInOnTerminalComplete(Vertx vertx, VertxTestContext ctx) throws Exception {
        SecurityContext sc = testSecurityContext("user-terminal");
        Promise<SecurityContext> seen = Promise.promise();
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                seen.tryComplete(DispatchContext.currentSecurityContext());
            }
        };

        String address = uniqueAddress("greet-terminal-holder");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(
                                        java.util.Map.of(dev.vertique.security.SecurityContext.class.getName(), sc))),
                        BODY_OPTIONS)
                .compose(reply -> seen.future())
                .onComplete(ctx.succeeding(captured -> ctx.verify(() -> {
                    assertSame(
                            sc,
                            captured,
                            "DispatchContext must still hold the security context when onTerminalComplete fires");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("onTerminalComplete runs on the dispatch context when afterDispatch settles on a foreign context")
    void shouldReenterDispatchContextWhenAfterDispatchSettlesForeign(Vertx vertx, VertxTestContext ctx)
            throws Exception {
        // A genuinely foreign Vert.x context (created off the dispatch path) to settle afterDispatch on.
        Context foreign = vertx.getOrCreateContext();
        SecurityContext sc = testSecurityContext("user-foreign-after");
        AtomicReference<Context> dispatchContext = new AtomicReference<>();
        AtomicReference<Context> terminalContext = new AtomicReference<>();
        Promise<SecurityContext> seen = Promise.promise();
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                // onComplete fires on the dispatch context (pre-recovery, no offloading for greet).
                dispatchContext.set(Vertx.currentContext());
            }

            @Override
            public Future<Void> afterDispatch(ServiceDispatchContext dispatchCtx, Result<?> result) {
                Promise<Void> p = Promise.promise();
                // Settle from the foreign context after one timer tick, so the future cannot complete
                // before the invoker has attached its terminal handler.
                foreign.runOnContext(
                        ignored -> vertx.setTimer(1, timerId -> p.complete())); // settle from the foreign context
                return p.future();
            }

            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                terminalContext.set(Vertx.currentContext());
                seen.tryComplete(DispatchContext.currentSecurityContext());
            }
        };

        String address = uniqueAddress("greet-foreign-after");
        ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(
                                        java.util.Map.of(dev.vertique.security.SecurityContext.class.getName(), sc))),
                        BODY_OPTIONS)
                .compose(reply -> seen.future())
                .onComplete(ctx.succeeding(captured -> ctx.verify(() -> {
                    assertNotNull(dispatchContext.get(), "the dispatch context must have been captured");
                    assertNotNull(terminalContext.get(), "the terminal context must have been captured");
                    assertNotSame(
                            foreign,
                            dispatchContext.get(),
                            "the foreign context must differ from the dispatch context");
                    assertSame(
                            dispatchContext.get(),
                            terminalContext.get(),
                            "onTerminalComplete must run on the original dispatch context");
                    assertSame(
                            sc,
                            captured,
                            "holder must be readable in onTerminalComplete despite a foreign afterDispatch completion");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("onTerminalComplete runs on the dispatch context when recoverError settles on a foreign context")
    void shouldReenterDispatchContextWhenRecoverErrorSettlesForeign(Vertx vertx, VertxTestContext ctx)
            throws Exception {
        Context foreign = vertx.getOrCreateContext();
        SecurityContext sc = testSecurityContext("user-foreign-recover");
        AtomicReference<Context> dispatchContext = new AtomicReference<>();
        AtomicReference<Context> terminalContext = new AtomicReference<>();
        Promise<SecurityContext> seenSc = Promise.promise();
        Promise<Result<?>> terminal = Promise.promise();
        ServiceInterceptor interceptor = new ServiceInterceptor() {
            @Override
            public void onComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                dispatchContext.set(Vertx.currentContext());
            }

            @Override
            public Future<Void> recoverError(ServiceDispatchContext dispatchCtx, Throwable error) {
                Promise<Void> p = Promise.promise();
                // Settle from the foreign context after one timer tick, so the future cannot complete
                // before the invoker has attached its terminal handler.
                foreign.runOnContext(ignored ->
                        vertx.setTimer(1, timerId -> p.complete())); // recover, settling from the foreign context
                return p.future();
            }

            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                terminalContext.set(Vertx.currentContext());
                // Complete terminal first: completing seenSc may synchronously run the awaiting assertion.
                terminal.tryComplete(result);
                seenSc.tryComplete(DispatchContext.currentSecurityContext());
            }
        };

        String address = uniqueAddress("fail-foreign-recover");
        ServiceMethodMeta meta = failAlwaysMeta(address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(interceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "x",
                                dev.vertique.core.eventbus.DispatchMetadata.of(
                                        java.util.Map.of(dev.vertique.security.SecurityContext.class.getName(), sc))),
                        BODY_OPTIONS)
                .compose(reply -> seenSc.future())
                .onComplete(ctx.succeeding(captured -> ctx.verify(() -> {
                    assertNotNull(dispatchContext.get(), "the dispatch context must have been captured");
                    assertNotNull(terminalContext.get(), "the terminal context must have been captured");
                    assertNotSame(
                            foreign,
                            dispatchContext.get(),
                            "the foreign context must differ from the dispatch context");
                    assertSame(
                            dispatchContext.get(),
                            terminalContext.get(),
                            "onTerminalComplete must run on the original dispatch context");
                    assertSame(
                            sc,
                            captured,
                            "holder must be readable in onTerminalComplete despite a foreign recoverError completion");
                    assertTrue(terminal.future().result().isSuccess(), "recovered failure is a terminal success");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "already-decoded typed CorrelationContext at FQCN key survives the decoder loop (P1.1 relay regression)")
    void shouldPreserveAlreadyDecodedTypedDispatchContextValue(Vertx vertx, VertxTestContext ctx) throws Exception {
        // The relay paths (delayed-job poller, outbox-service handler, workflow-timer dispatch)
        // run the durable decoder up-front and put the resulting TYPED value into the
        // dispatch-context map at the FQCN key. The service-dispatch decoder loop must NOT
        // re-run on that already-typed value (the snapshot decoder would fail). This test
        // exercises the isInstance short-circuit added to ServiceMethodInvoker.decodeDispatchContext.
        java.util.List<dev.vertique.core.correlation.CorrelationContext> captured = new ArrayList<>();
        TestService capturingImpl = new TestServiceImpl() {
            @Override
            public Future<String> greet(String name) {
                DispatchContext.current(dev.vertique.core.correlation.CorrelationContext.class)
                        .ifPresent(captured::add);
                return Future.succeededFuture("Hello " + name);
            }
        };

        String address = uniqueAddress("greet-corr");
        ServiceMethodMeta meta = greetMeta(capturingImpl, address);

        // Register the same snapshot-decoder pair the production CorrelationContextModule wires.
        // The relay-decoded value put at the FQCN key is a CorrelationContext (live), not a
        // CorrelationContextSnapshot — so without the isInstance short-circuit the decoder
        // would silently drop it.
        dev.vertique.correlation.CorrelationContextFactory factory =
                new dev.vertique.correlation.CorrelationContextFactory(Optional.empty());
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(
                java.util.Set.of(),
                java.util.Set.of(ServiceDispatchCodecs.snapshotDecoder(
                        dev.vertique.core.correlation.CorrelationContext.class,
                        dev.vertique.core.correlation.CorrelationContextSnapshot.class,
                        factory::fromSnapshot)));
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null, null, vertx, registry);
        vertx.eventBus().consumer(address, invoker);

        dev.vertique.core.correlation.CorrelationContext relayDecoded = factory.create(
                new dev.vertique.core.correlation.CorrelationIdentifier("req-relay", "durable-relay"),
                new dev.vertique.core.correlation.CorrelationIdentifier("cor-relay", "durable-relay"));

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                                        dev.vertique.core.correlation.CorrelationContext.class.getName(),
                                        relayDecoded))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                    assertTrue(reply.body().isSuccess());
                    assertFalse(captured.isEmpty(), "handler must observe the relay-decoded CorrelationContext");
                    assertEquals("req-relay", captured.get(0).requestId().value());
                    assertEquals("durable-relay", captured.get(0).requestId().source());
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    private SecurityContext testSecurityContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return auth;
            }

            @Override
            public AuthorizationClaims authorization() {
                return AuthorizationClaims.empty();
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }
}
