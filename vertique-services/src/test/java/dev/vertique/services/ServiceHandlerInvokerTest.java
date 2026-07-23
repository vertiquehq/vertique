// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.ResilienceAnnotations;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ServiceMethodInvoker} when using the {@link ServiceHandler} pattern.
 *
 * <p>Verifies that the invoker correctly resolves handler method arguments, including:
 * <ul>
 *   <li>{@link ParamSource#DISPATCH_CONTEXT}: SecurityContext read from the dispatch context map
 *       (stored in the incoming {@link DispatchEnvelope}'s dispatch context under
 *       {@code SecurityContext.class.getName()}).</li>
 *   <li>{@link ParamSource#PAYLOAD}: payload extracted from the {@link DispatchEnvelope}.</li>
 * </ul>
 *
 * <p>Uses real Vert.x event bus with local codecs to exercise the full dispatch path.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@ExtendWith(VertxExtension.class)
class ServiceHandlerInvokerTest {

    // --- Contract Fixture ---

    /** Contract interface for handler-pattern invocation tests. */
    @ServiceContract(namespace = "test", value = "handler-invoker")
    interface HandlerInvokerContract {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("ping")
        Future<String> ping();

        @ServiceOperation("noPayload")
        Future<Void> noPayload();
    }

    // --- Handler Fixtures ---

    /**
     * Handler that captures the {@link SecurityContext} injected via {@link DispatchContext}
     * during invocation of {@code greet}.
     */
    static class CapturingHandler {

        final List<SecurityContext> capturedSc = new ArrayList<>();
        final List<String> capturedPayloads = new ArrayList<>();

        public Future<String> greet(String name, SecurityContext sc) {
            capturedSc.add(sc);
            capturedPayloads.add(name);
            return Future.succeededFuture("hi:" + name);
        }

        public Future<String> ping() {
            return Future.succeededFuture("pong");
        }

        public Future<Void> noPayload(SecurityContext sc) {
            capturedSc.add(sc);
            return Future.succeededFuture();
        }
    }

    // --- Setup ---

    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "test-handler-invoker/" + base + "/" + ADDRESS_COUNTER.incrementAndGet();
    }

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

    /**
     * Builds a {@link ServiceMethodMeta} for the handler pattern, where the contract method
     * and handler method are distinct reflective targets.
     *
     * @param handler the handler instance to invoke
     * @param contractMethod the contract interface method (for address and client-side metadata)
     * @param handlerMethod the handler class method (invoked on the server side)
     * @param address the event bus address
     * @param contractParams parameter metadata for the contract method
     * @param handlerParams parameter metadata for the handler method
     * @param payloadType the payload parameter type, or {@code null}
     * @param returnType the unwrapped return type
     * @return a new metadata instance with distinct contract and handler methods
     */
    private ServiceMethodMeta handlerMeta(
            Object handler,
            Method contractMethod,
            Method handlerMethod,
            String address,
            List<ParamMeta> contractParams,
            List<ParamMeta> handlerParams,
            Class<?> payloadType,
            Class<?> returnType) {
        String operation = contractMethod.getName();
        return new ServiceMethodMeta(
                handler,
                ServiceMethodDescriptor.of(contractMethod),
                ServiceMethodDescriptor.of(handlerMethod),
                address,
                null,
                "test",
                "handler-invoker",
                operation,
                payloadType,
                returnType,
                contractParams,
                handlerParams,
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceExceptionMapper emptyExceptionMapper() {
        return new ServiceExceptionMapper();
    }

    // --- Tests ---

    @Test
    @DisplayName("Should inject SecurityContext from DispatchContext for handler method")
    void shouldInjectSecurityContextFromDispatchContext(Vertx vertx, VertxTestContext ctx) throws Exception {
        SecurityContext testSc = testSecurityContext("user-handler-1");
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("greet", String.class);
        Method handlerMethod = CapturingHandler.class.getMethod("greet", String.class, SecurityContext.class);
        String address = uniqueAddress("greet-inject-sc");

        // Contract params: 1 PAYLOAD (String)
        List<ParamMeta> contractParams = List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class));
        // Handler params: 1 PAYLOAD (String) + 1 DISPATCH_CONTEXT (SecurityContext)
        List<ParamMeta> handlerParams = List.of(
                new ParamMeta("name", ParamSource.PAYLOAD, String.class),
                new ParamMeta(
                        "sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class, SecurityContext.class.getName()));

        ServiceMethodMeta meta = handlerMeta(
                handler,
                contractMethod,
                handlerMethod,
                address,
                contractParams,
                handlerParams,
                String.class,
                String.class);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                                        dev.vertique.security.SecurityContext.class.getName(), testSc))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertEquals("hi:World", reply.body().get());
                    assertEquals(1, handler.capturedSc.size(), "Handler must have been called once");
                    assertSame(testSc, handler.capturedSc.get(0), "DISPATCH_CONTEXT must resolve to body's SC");
                    assertEquals("World", handler.capturedPayloads.get(0));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should handle handler method with payload only (no injectable params)")
    void shouldHandlePayloadOnly(Vertx vertx, VertxTestContext ctx) throws Exception {
        // Use ExactSignatureHandler via ServiceRegistrar to get realistic metadata, or build manually
        // Using manual construction for consistency with the rest of the test class
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("greet", String.class);
        // Handler method with the same signature as the contract (no SC)
        Method handlerMethod = contractMethod; // intentionally same — tests direct path
        String address = uniqueAddress("greet-payload-only");

        List<ParamMeta> params = List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class));
        // For this test, use a plain direct-impl style metadata to verify PAYLOAD path
        ServiceMethodMeta meta = ServiceMethodMeta.ofDirect(
                new HandlerInvokerContract() {
                    @Override
                    public Future<String> greet(String name) {
                        handler.capturedPayloads.add(name);
                        return Future.succeededFuture("hi:" + name);
                    }

                    @Override
                    public Future<String> ping() {
                        return Future.succeededFuture("pong");
                    }

                    @Override
                    public Future<Void> noPayload() {
                        return Future.succeededFuture();
                    }
                },
                ServiceMethodDescriptor.of(contractMethod),
                address,
                null,
                "test",
                "handler-invoker",
                "greet",
                String.class,
                String.class,
                params,
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);

        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("Alice"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertEquals("hi:Alice", reply.body().get());
                    assertEquals("Alice", handler.capturedPayloads.get(0));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should handle handler method with SecurityContext only (no payload)")
    void shouldHandleSecurityContextOnly(Vertx vertx, VertxTestContext ctx) throws Exception {
        SecurityContext testSc = testSecurityContext("user-sc-only");
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("noPayload");
        Method handlerMethod = CapturingHandler.class.getMethod("noPayload", SecurityContext.class);
        String address = uniqueAddress("noPayload-sc-only");

        // Contract params: empty (no payload, no SC in contract)
        List<ParamMeta> contractParams = List.of();
        // Handler params: 1 DISPATCH_CONTEXT (SecurityContext)
        List<ParamMeta> handlerParams = List.of(new ParamMeta(
                "sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class, SecurityContext.class.getName()));

        ServiceMethodMeta meta = handlerMeta(
                handler, contractMethod, handlerMethod, address, contractParams, handlerParams, null, Void.class);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                null,
                                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                                        dev.vertique.security.SecurityContext.class.getName(), testSc))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertNull(reply.body().get());
                    assertEquals(1, handler.capturedSc.size(), "Handler must have been called once");
                    assertSame(testSc, handler.capturedSc.get(0), "DISPATCH_CONTEXT must resolve to body's SC");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should pass null SecurityContext when DispatchContext is empty (no SC in body)")
    void shouldPassNullSecurityContextWhenEmpty(Vertx vertx, VertxTestContext ctx) throws Exception {
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("greet", String.class);
        Method handlerMethod = CapturingHandler.class.getMethod("greet", String.class, SecurityContext.class);
        String address = uniqueAddress("greet-null-sc");

        List<ParamMeta> contractParams = List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class));
        List<ParamMeta> handlerParams = List.of(
                new ParamMeta("name", ParamSource.PAYLOAD, String.class),
                new ParamMeta(
                        "sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class, SecurityContext.class.getName()));

        ServiceMethodMeta meta = handlerMeta(
                handler,
                contractMethod,
                handlerMethod,
                address,
                contractParams,
                handlerParams,
                String.class,
                String.class);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        // Send body with no security context — dispatch context map will not contain SC
        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("Bob"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertEquals("hi:Bob", reply.body().get());
                    assertEquals(1, handler.capturedSc.size(), "Handler must have been called once");
                    assertNull(handler.capturedSc.get(0), "DISPATCH_CONTEXT must be null when body has no SC");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should correctly invoke handler method with no params (ping)")
    void shouldInvokeHandlerMethodWithNoParams(Vertx vertx, VertxTestContext ctx) throws Exception {
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("ping");
        Method handlerMethod = CapturingHandler.class.getMethod("ping");
        String address = uniqueAddress("ping-no-params");

        ServiceMethodMeta meta =
                handlerMeta(handler, contractMethod, handlerMethod, address, List.of(), List.of(), null, String.class);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.empty(), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertEquals("pong", reply.body().get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should invoke handler method via handlerMethod, not contract method")
    void shouldInvokeHandlerMethodNotContractMethod(Vertx vertx, VertxTestContext ctx) throws Exception {
        SecurityContext testSc = testSecurityContext("user-method-ref");
        CapturingHandler handler = new CapturingHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("greet", String.class);
        Method handlerMethod = CapturingHandler.class.getMethod("greet", String.class, SecurityContext.class);
        String address = uniqueAddress("greet-handler-method-check");

        List<ParamMeta> contractParams = List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class));
        List<ParamMeta> handlerParams = List.of(
                new ParamMeta("name", ParamSource.PAYLOAD, String.class),
                new ParamMeta(
                        "sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class, SecurityContext.class.getName()));

        ServiceMethodMeta meta = handlerMeta(
                handler,
                contractMethod,
                handlerMethod,
                address,
                contractParams,
                handlerParams,
                String.class,
                String.class);

        // Verify metadata is correctly set up: handlerMethod differs from contract method
        assertNotEquals(meta.method(), meta.handlerMethod());
        assertEquals(HandlerInvokerContract.class, meta.method().declaringClass());
        assertEquals(CapturingHandler.class, meta.handlerMethod().declaringClass());

        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "Tester",
                                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                                        dev.vertique.security.SecurityContext.class.getName(), testSc))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    // Verify the handler method was actually called (not the contract method on some other object)
                    assertEquals(1, handler.capturedSc.size());
                    assertSame(testSc, handler.capturedSc.get(0));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("Should inject SecurityContext subtype via dispatch context map lookup using SC interface key")
    void shouldInjectSecurityContextSubtype(Vertx vertx, VertxTestContext ctx) throws Exception {
        ExtendedSecurityContext testSc = extendedSecurityContext("user-subtype");

        // Handler that declares ExtendedSecurityContext (a subtype) as its SC parameter
        ExtendedScHandler handler = new ExtendedScHandler();

        Method contractMethod = HandlerInvokerContract.class.getMethod("greet", String.class);
        Method handlerMethod = ExtendedScHandler.class.getMethod("greet", String.class, ExtendedSecurityContext.class);
        String address = uniqueAddress("greet-subtype-sc");

        List<ParamMeta> contractParams = List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class));
        // lookupKey must be SC_KEY (SecurityContext.class.getName()), NOT ExtendedSecurityContext.class.getName()
        List<ParamMeta> handlerParams = List.of(
                new ParamMeta("name", ParamSource.PAYLOAD, String.class),
                new ParamMeta(
                        "sc",
                        ParamSource.DISPATCH_CONTEXT,
                        ExtendedSecurityContext.class,
                        SecurityContext.class.getName()));

        ServiceMethodMeta meta = handlerMeta(
                handler,
                contractMethod,
                handlerMethod,
                address,
                contractParams,
                handlerParams,
                String.class,
                String.class);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(), null);
        vertx.eventBus().consumer(address, invoker);

        // DispatchEnvelope.of(payload, sc) stores sc under SecurityContext.class.getName()
        vertx.eventBus()
                .<Result<?>>request(
                        address,
                        DispatchEnvelope.of(
                                "World",
                                dev.vertique.core.eventbus.DispatchMetadata.of(java.util.Map.of(
                                        dev.vertique.security.SecurityContext.class.getName(), testSc))),
                        BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    assertTrue(reply.body().isSuccess());
                    assertEquals("hi-ext:World", reply.body().get());
                    assertSame(testSc, handler.capturedSc, "Subtype SC must resolve via SC interface key");
                    ctx.completeNow();
                }));
    }

    // --- Extra Fixtures for Subtype Test ---

    /** Extended security context subtype for subtype-injection testing. */
    interface ExtendedSecurityContext extends SecurityContext {
        /** Returns the user ID directly (test convenience). */
        String id();
    }

    /** Handler that declares {@link ExtendedSecurityContext} as its SC parameter. */
    static class ExtendedScHandler {

        SecurityContext capturedSc;

        public Future<String> greet(String name, ExtendedSecurityContext sc) {
            capturedSc = sc;
            return Future.succeededFuture("hi-ext:" + name);
        }
    }

    /**
     * Creates an {@link ExtendedSecurityContext} test instance backed by the typed identity model.
     *
     * @param userId the user id
     * @return new instance
     */
    private ExtendedSecurityContext extendedSecurityContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new ExtendedSecurityContext() {
            @Override
            public String id() {
                return userId;
            }

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

    // --- Helper ---

    /**
     * Creates a minimal {@link SecurityContext} implementation for testing, backed by the typed
     * identity model.
     *
     * @param userId the user ID to embed in the identity actor
     * @return a new anonymous {@code SecurityContext} instance
     */
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
