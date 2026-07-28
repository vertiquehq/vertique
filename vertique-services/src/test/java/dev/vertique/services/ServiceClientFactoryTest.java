// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextValues;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration-style tests for {@link ServiceClientFactory}.
 *
 * <p>Deploys real event bus consumers that echo the received payload back and verifies that the
 * proxy correctly identifies and forwards the PAYLOAD parameter regardless of its declared position
 * in the method signature. Transport is wired through a real {@link EventBusClient} and
 * {@link ServiceRequestSender} to cover the full dispatch path.
 *
 * <p>The {@code SecurityContextPropagation} nested class covers the four outbound SC propagation
 * scenarios: ambient-only, explicit-only, both-present (ambient wins, no collision), and
 * neither-present.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("ServiceClientFactory")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceClientFactoryTest {

    // --- Contract Fixtures ---

    /**
     * Test contract with three method signatures covering: SecurityContext-first, payload-only,
     * and no-payload variants.
     */
    @ServiceContract(namespace = "test", value = "proxy-test")
    interface ProxyTestService {
        /**
         * Method where SecurityContext is the first declared parameter and payload is second.
         *
         * @param sc the security context
         * @param id the payload string
         * @return a future of the echoed payload prefixed with "got:"
         */
        @ServiceOperation("withSecurityContext")
        Future<String> withSecurityContext(SecurityContext sc, String id);

        /**
         * Method with only a payload parameter and no SecurityContext.
         *
         * @param id the payload string
         * @return a future of the echoed payload prefixed with "got:"
         */
        @ServiceOperation("payloadOnly")
        Future<String> payloadOnly(String id);

        /**
         * Method with no parameters.
         *
         * @return a succeeded future of {@code null}
         */
        @ServiceOperation("noPayload")
        Future<Void> noPayload();

        /**
         * One-way method that fires and forgets.
         *
         * @param event the event payload
         * @return a succeeded future immediately after sending
         */
        @OneWay
        @ServiceOperation("fireEvent")
        Future<Void> fireEvent(String event);
    }

    /** Minimal implementation to satisfy {@link ServiceRegistrar} scanning. */
    static class ProxyTestServiceImpl implements ProxyTestService {
        @Override
        public Future<String> withSecurityContext(SecurityContext sc, String id) {
            return Future.succeededFuture("got:" + id);
        }

        @Override
        public Future<String> payloadOnly(String id) {
            return Future.succeededFuture("got:" + id);
        }

        @Override
        public Future<Void> noPayload() {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> fireEvent(String event) {
            return Future.succeededFuture();
        }
    }

    // --- Setup ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static ServiceContractRegistry registry;
    private static ServiceSupervisor supervisor;
    private static ServiceRequestSender sender;

    /**
     * Registers codecs, builds registry, wires sender, and installs echo consumers before any test runs.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx   the test context used to signal setup completion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        // Register codecs — guard against double-registration (e.g., in test suites)
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // Already registered — safe to continue
        }
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered — safe to continue
        }

        registry = ServiceContractRegistry.build(Set.of(new ProxyTestServiceImpl()), new JsonObject(), configParser());
        supervisor = mock(ServiceSupervisor.class);
        when(supervisor.isAvailable(any())).thenReturn(true);

        // Build sender via the real EventBusClient
        EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
        EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
        sender = new ServiceRequestSender(eventBusClient, supervisor, new ServicesConfig(null, List.of()), Map.of());

        // Register echo consumers that reply with the received payload prefixed with "got:"
        DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
        ServiceContractRegistry.ContractEntry<ProxyTestService> entry = registry.resolve(ProxyTestService.class);

        for (ServiceMethodMeta meta : entry.operations().values()) {
            vertx.eventBus().<DispatchEnvelope<?>>consumer(meta.address(), msg -> {
                DispatchEnvelope<?> body = msg.body();
                Object payload = body.payload();
                if (meta.operation().equals("fireEvent")) {
                    // One-way: do not reply — the proxy doesn't expect one
                    return;
                }
                if (meta.operation().equals("noPayload")) {
                    msg.reply(Result.success(null), replyOptions);
                } else if (meta.operation().equals("withSecurityContext")) {
                    // Echo payload and include SC userId to verify propagation
                    SecurityContext sc =
                            body.metadata().context(SecurityContext.class).orElse(null);
                    String scInfo = sc != null ? sc.identity().actor().id() : "no-sc";
                    msg.reply(Result.success("got:" + payload + ":sc=" + scInfo), replyOptions);
                } else {
                    msg.reply(Result.success("got:" + payload), replyOptions);
                }
            });
        }

        ctx.completeNow();
    }

    // --- Tests ---

    /**
     * Verifies that the proxy correctly skips the SecurityContext parameter at position 0 and
     * extracts the payload from position 1.
     *
     * @param ctx the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should extract payload from correct position when SecurityContext is first param")
    void shouldExtractPayloadFromCorrectPosition(VertxTestContext ctx) throws Throwable {
        ServiceClientFactory factory = new ServiceClientFactory(sender, registry);
        ProxyTestService proxy = factory.create(ProxyTestService.class);

        proxy.withSecurityContext(null, "user-42").onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> assertEquals("got:user-42:sc=no-sc", result));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that the proxy correctly extracts the payload when it is the only parameter.
     *
     * @param ctx the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should handle payload-only signature correctly")
    void shouldHandlePayloadOnlySignature(VertxTestContext ctx) throws Throwable {
        ServiceClientFactory factory = new ServiceClientFactory(sender, registry);
        ProxyTestService proxy = factory.create(ProxyTestService.class);

        proxy.payloadOnly("item-7").onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> assertEquals("got:item-7", result));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that the proxy sends a null payload when the method has no parameters.
     *
     * @param ctx the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should handle no-payload signature correctly")
    void shouldHandleNoPayloadSignature(VertxTestContext ctx) throws Throwable {
        ServiceClientFactory factory = new ServiceClientFactory(sender, registry);
        ProxyTestService proxy = factory.create(ProxyTestService.class);

        proxy.noPayload().onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> assertNull(result));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that the proxy returns a succeeded future immediately for a one-way operation
     * without waiting for a reply from the server.
     *
     * @param ctx the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should return succeeded future immediately for @OneWay operation")
    void shouldReturnImmediatelyForOneWayOperation(VertxTestContext ctx) throws Throwable {
        ServiceClientFactory factory = new ServiceClientFactory(sender, registry);
        ProxyTestService proxy = factory.create(ProxyTestService.class);

        proxy.fireEvent("test-event").onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> assertNull(result, "One-way operation should return null (Void)"));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that when a handler returns {@link Result#failure(Throwable)}, the proxy future
     * fails with the handler's original cause directly — without any {@link RuntimeException}
     * wrapping.
     *
     * @param vertx the Vert.x instance
     * @param ctx   the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should propagate handler Result.failure cause directly without RuntimeException wrapping")
    void shouldPropagateResultFailureCauseDirectly(Vertx vertx, VertxTestContext ctx) throws Throwable {
        // Register a dedicated contract on a unique service name with a consumer that returns failure
        @ServiceContract(namespace = "test", value = "failure-svc")
        interface FailureService {
            @ServiceOperation("fail")
            Future<String> fail();
        }

        class FailureServiceImpl implements FailureService {
            @Override
            public Future<String> fail() {
                return Future.succeededFuture("ok");
            }
        }

        ServiceContractRegistry localRegistry =
                ServiceContractRegistry.build(Set.of(new FailureServiceImpl()), new JsonObject(), configParser());
        ServiceContractRegistry.ContractEntry<FailureService> entry = localRegistry.resolve(FailureService.class);
        ServiceMethodMeta meta = entry.operations().get("fail");

        // Install a consumer that replies with Result.failure
        IllegalStateException handlerCause = new IllegalStateException("handler-error");
        DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
        vertx.eventBus().<DispatchEnvelope<?>>consumer(meta.address(), msg -> {
            msg.reply(Result.failure(handlerCause), replyOptions);
        });

        EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
        EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
        ServiceSupervisor availableSupervisor = mock(ServiceSupervisor.class);
        when(availableSupervisor.isAvailable(any())).thenReturn(true);
        ServiceRequestSender localSender = new ServiceRequestSender(
                eventBusClient, availableSupervisor, new ServicesConfig(null, List.of()), Map.of());
        ServiceClientFactory factory = new ServiceClientFactory(localSender, localRegistry);
        FailureService proxy = factory.create(FailureService.class);

        proxy.fail().onComplete(ctx.failing(cause -> {
            ctx.verify(() -> {
                // The cause must be the exact handler exception — no RuntimeException wrapping
                assertSame(handlerCause, cause, "Cause should be the exact handler exception, not a wrapper");
                assertInstanceOf(IllegalStateException.class, cause, "Cause type must be preserved through the proxy");
            });
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that calling a proxy method on a non-existent address produces a
     * {@link ServiceUnavailableException} (NO_HANDLERS translates to service unavailable).
     *
     * @param vertx the Vert.x instance
     * @param ctx   the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should fail with ServiceUnavailableException for NO_HANDLERS on request/reply")
    void shouldFailWithServiceUnavailableForNoHandlers(Vertx vertx, VertxTestContext ctx) throws Throwable {
        @ServiceContract(namespace = "test", value = "no-handler-svc")
        interface NoHandlerService {
            @ServiceOperation("ping")
            Future<Void> ping();
        }

        class NoHandlerServiceImpl implements NoHandlerService {
            @Override
            public Future<Void> ping() {
                return Future.succeededFuture();
            }
        }

        ServiceContractRegistry localRegistry =
                ServiceContractRegistry.build(Set.of(new NoHandlerServiceImpl()), new JsonObject(), configParser());

        EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
        EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
        ServiceSupervisor availableSupervisor = mock(ServiceSupervisor.class);
        when(availableSupervisor.isAvailable(any())).thenReturn(true);
        ServiceRequestSender localSender = new ServiceRequestSender(
                eventBusClient, availableSupervisor, new ServicesConfig(null, List.of()), Map.of());
        ServiceClientFactory factory = new ServiceClientFactory(localSender, localRegistry);
        NoHandlerService proxy = factory.create(NoHandlerService.class);

        proxy.ping().onComplete(ctx.failing(cause -> {
            ctx.verify(() -> assertInstanceOf(
                    ServiceUnavailableException.class,
                    cause,
                    "NO_HANDLERS should translate to ServiceUnavailableException"));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * Verifies that the {@code @OneWay} supervisor gate propagates correctly: when the supervisor
     * reports unavailable, {@code sendOneWay} returns a failed future with
     * {@link ServiceUnavailableException}.
     *
     * @param vertx the Vert.x instance
     * @param ctx   the test context
     * @throws Throwable if the test times out or the assertion fails
     */
    @Test
    @DisplayName("Should fail @OneWay with ServiceUnavailableException when supervisor reports unavailable")
    void shouldFailOneWayWhenSupervisorUnavailable(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ServiceSupervisor unavailableSupervisor = mock(ServiceSupervisor.class);
        when(unavailableSupervisor.isAvailable(any())).thenReturn(false);

        EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
        EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
        ServiceRequestSender localSender = new ServiceRequestSender(
                eventBusClient, unavailableSupervisor, new ServicesConfig(null, List.of()), Map.of());
        ServiceClientFactory factory = new ServiceClientFactory(localSender, registry);
        ProxyTestService proxy = factory.create(ProxyTestService.class);

        proxy.fireEvent("blocked-event").onComplete(ctx.failing(cause -> {
            ctx.verify(() -> assertInstanceOf(
                    ServiceUnavailableException.class,
                    cause,
                    "@OneWay should fail with ServiceUnavailableException when supervisor is unavailable"));
            ctx.completeNow();
        }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    // --- Helpers ---

    /**
     * Creates a minimal SecurityContext with the given user ID for testing.
     *
     * @param userId the user ID to embed in the security context
     * @return a test security context using the typed identity model
     */
    private static SecurityContext testSecurityContext(String userId) {
        SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, java.util.Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(),
                java.util.List.of(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Map.of());
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
            public java.util.Optional<RequestOrigin> origin() {
                return java.util.Optional.empty();
            }
        };
    }

    // --- Security Context Propagation Tests ---

    /**
     * Tests for outbound {@link SecurityContext} propagation semantics.
     *
     * <p>Covers the four cases: ambient-only, explicit-only, both-present (ambient wins, no
     * encoder-vs-override collision — FR-CTX-063), and neither-present. All cases that involve
     * binding ambient SC run on a duplicated Vert.x context so {@link ContextValues#bind} succeeds.
     *
     * <p>A dedicated service contract ({@code sc-prop-test}) is used with a consumer that reads
     * {@link dev.vertique.core.eventbus.DispatchMetadata#context(Class)} and echoes the SC userId
     * back to the caller, making propagation observable over the real event bus.
     */
    @Nested
    @DisplayName("SecurityContextPropagation")
    class SecurityContextPropagation {

        /**
         * Minimal contract whose sole operation carries a {@link SecurityContext} parameter so
         * the explicit-only and both-present scenarios can supply one.
         */
        @ServiceContract(namespace = "test", value = "sc-prop-test")
        interface ScPropService {
            /**
             * Operation with an explicit SecurityContext parameter and a payload.
             *
             * @param sc      optional caller-supplied security context
             * @param payload the string payload
             * @return a future echoing the SC userId from the dispatch context
             */
            @ServiceOperation("withSc")
            Future<String> withSc(SecurityContext sc, String payload);

            /**
             * Operation with no SecurityContext parameter — used for ambient-only and
             * neither-present scenarios.
             *
             * @param payload the string payload
             * @return a future echoing the SC userId from the dispatch context
             */
            @ServiceOperation("noScParam")
            Future<String> noScParam(String payload);
        }

        /** Minimal implementation required by {@link ServiceRegistrar} scanning. */
        static class ScPropServiceImpl implements ScPropService {
            @Override
            public Future<String> withSc(SecurityContext sc, String payload) {
                return Future.succeededFuture("ok");
            }

            @Override
            public Future<String> noScParam(String payload) {
                return Future.succeededFuture("ok");
            }
        }

        private ServiceContractRegistry localRegistry;
        private ServiceRequestSender localSender;

        /**
         * Builds a local registry and sender, then installs consumers that echo the SC userId
         * from the dispatch context back to the caller.
         *
         * @param vertx the Vert.x instance provided by the extension
         * @param ctx   the test context used to signal setup completion
         */
        @BeforeEach
        void setUpLocal(Vertx vertx, VertxTestContext ctx) {
            localRegistry =
                    ServiceContractRegistry.build(Set.of(new ScPropServiceImpl()), new JsonObject(), configParser());

            EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
            EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
            ServiceSupervisor availableSupervisor = mock(ServiceSupervisor.class);
            when(availableSupervisor.isAvailable(any())).thenReturn(true);
            localSender = new ServiceRequestSender(
                    eventBusClient, availableSupervisor, new ServicesConfig(null, List.of()), Map.of());

            DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
            ServiceContractRegistry.ContractEntry<ScPropService> entry = localRegistry.resolve(ScPropService.class);

            for (ServiceMethodMeta meta : entry.operations().values()) {
                vertx.eventBus().<DispatchEnvelope<?>>consumer(meta.address(), msg -> {
                    SecurityContext sc =
                            msg.body().metadata().context(SecurityContext.class).orElse(null);
                    String reply = sc != null ? sc.identity().actor().id() : "no-sc";
                    msg.reply(Result.success(reply), replyOptions);
                });
            }
            ctx.completeNow();
        }

        /**
         * Builds a {@link ServiceClientFactory} whose {@link DispatchEnvelopeBuilder} has a
         * SecurityContext pass-through encoder registered. The bespoke
         * {@code SecurityContextServiceDispatchEncoder} now lives in {@code vertique-rest-security};
         * for vertique-services tests we use the equivalent {@link ServiceDispatchCodecs#passThroughEncoder}
         * directly. This is required for ambient-SC tests: with empty SPI sets the encoder is absent
         * and no SC is captured.
         *
         * @return a factory with the SC encoder wired in
         */
        private ServiceClientFactory factoryWithScEncoder() {
            ServiceDispatchContextRegistry ctxRegistry = new ServiceDispatchContextRegistry(
                    Set.of(ServiceDispatchCodecs.passThroughEncoder(SecurityContext.class)), Set.of());
            DispatchEnvelopeBuilder builder = new DispatchEnvelopeBuilder(
                    new ServiceDispatchContextCapturer(ctxRegistry, new DefaultContextHolder()));
            return new ServiceClientFactory(localSender, localRegistry, builder);
        }

        /**
         * Ambient-only: SC bound in the Vert.x context holder, method has no SC param. The
         * encoder must capture the ambient SC into the outgoing envelope.
         *
         * @param vertx the Vert.x instance
         * @param ctx   the test context
         * @throws Throwable if the test times out or the assertion fails
         */
        @Test
        @DisplayName("Ambient only — encoder captures holder-bound SC; no SC param on method")
        void ambientOnly(Vertx vertx, VertxTestContext ctx) throws Throwable {
            SecurityContext ambientSc = testSecurityContext("ambient-user");
            ServiceClientFactory factory = factoryWithScEncoder();
            ScPropService proxy = factory.create(ScPropService.class);

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try (ContextHolder.Scope scope = ContextValues.bind(SecurityContext.class, ambientSc)) {
                    proxy.noScParam("x").onComplete(ctx.succeeding(result -> {
                        ctx.verify(() -> assertEquals(
                                "ambient-user",
                                result,
                                "Ambient SC must be captured by encoder into dispatch context"));
                        ctx.completeNow();
                    }));
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        /**
         * Explicit only: no ambient SC bound, method declares a {@link SecurityContext} param, and
         * the caller passes a non-null value. The factory must place it in {@code callerOverrides}.
         *
         * @param ctx the test context
         * @throws Throwable if the test times out or the assertion fails
         */
        @Test
        @DisplayName("Explicit only — caller SC arg forwarded via callerOverrides when no ambient SC")
        void explicitOnly(VertxTestContext ctx) throws Throwable {
            SecurityContext explicitSc = testSecurityContext("explicit-user");
            ServiceClientFactory factory = factoryWithScEncoder();
            ScPropService proxy = factory.create(ScPropService.class);

            // No ambient SC bound — runs outside a duplicated context intentionally
            proxy.withSc(explicitSc, "y").onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> assertEquals(
                        "explicit-user", result, "Explicit caller SC must be forwarded when no ambient SC is bound"));
                ctx.completeNow();
            }));

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        /**
         * Both present: ambient SC bound AND caller passes an explicit SC arg. The ambient SC must
         * win (encoder captures it; factory skips {@code callerOverrides} for SC). The call must
         * NOT throw, proving the encoder-vs-override collision (FR-CTX-063) is avoided.
         *
         * @param vertx the Vert.x instance
         * @param ctx   the test context
         * @throws Throwable if the test times out or the assertion fails
         */
        @Test
        @DisplayName("Both present — ambient SC wins; no encoder-vs-override collision (FR-CTX-063)")
        void bothPresentAmbientWins(Vertx vertx, VertxTestContext ctx) throws Throwable {
            SecurityContext ambientSc = testSecurityContext("ambient-user");
            SecurityContext explicitSc = testSecurityContext("explicit-user");
            ServiceClientFactory factory = factoryWithScEncoder();
            ScPropService proxy = factory.create(ScPropService.class);

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> {
                try (ContextHolder.Scope scope = ContextValues.bind(SecurityContext.class, ambientSc)) {
                    proxy.withSc(explicitSc, "z").onComplete(ctx.succeeding(result -> {
                        ctx.verify(() -> assertEquals(
                                "ambient-user",
                                result,
                                "Ambient SC must win over explicit arg; call must not throw (FR-CTX-063)"));
                        ctx.completeNow();
                    }));
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        /**
         * Neither present: no ambient SC and no SC method param. The envelope's dispatch context
         * must contain no {@link SecurityContext} entry.
         *
         * @param ctx the test context
         * @throws Throwable if the test times out or the assertion fails
         */
        @Test
        @DisplayName("Neither present — no SC in envelope dispatch context")
        void neitherPresent(VertxTestContext ctx) throws Throwable {
            ServiceClientFactory factory = factoryWithScEncoder();
            ScPropService proxy = factory.create(ScPropService.class);

            // No ambient SC and noScParam has no SC argument
            proxy.noScParam("w").onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> assertEquals(
                        "no-sc",
                        result,
                        "Dispatch context must have no SecurityContext when neither ambient nor explicit is present"));
                ctx.completeNow();
            }));

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }
    }

    // --- create()-time Completeness Check Tests (CG-015 S2) ---

    /**
     * Tests for the §4.2 step-2 {@code create()}-time completeness check (CG-015 S2).
     *
     * <p>Entries are hand-built via {@link ServiceContractEntries#deployable()} and registered
     * through the {@link ServiceContractContributor} SPI — the cleanest existing path for
     * installing a caller-controlled {@link ServiceContractRegistry.ContractEntry} from this
     * package, since {@link ServiceContractRegistry} exposes no direct entry-list constructor
     * (its constructor is private; {@code build(Set, ConfigParser)} always re-derives entries by
     * scanning {@code @ServiceContract} implementations).
     */
    @Nested
    @DisplayName("CreateTimeCompletenessCheck")
    class CreateTimeCompletenessCheck {

        /** Two-operation contract used to exercise a deliberately incomplete registry entry. */
        @ServiceContract("partial")
        interface PartialContract {
            /**
             * First operation, always present on hand-built entries in this nested class.
             *
             * @param x the payload string
             * @return a future of the echoed payload
             */
            @ServiceOperation("opA")
            Future<String> opA(String x);

            /**
             * Second operation, deliberately omitted from the incomplete-entry fixture.
             *
             * @param x the payload string
             * @return a future of the echoed payload
             */
            @ServiceOperation("opB")
            Future<String> opB(String x);
        }

        /** Minimal implementation satisfying {@link PartialContract} for entry construction. */
        static class PartialContractImpl implements PartialContract {
            @Override
            public Future<String> opA(String x) {
                return Future.succeededFuture(x);
            }

            @Override
            public Future<String> opB(String x) {
                return Future.succeededFuture(x);
            }
        }

        /**
         * Registers a single hand-built entry into a fresh registry via the
         * {@link ServiceContractContributor} SPI, so the registry's operation map is exactly what
         * the caller supplied (no {@code @ServiceContract} implementation scanning involved).
         *
         * @param entry the pre-built contract entry to register
         * @return a registry containing only {@code entry}
         */
        private ServiceContractRegistry registryOf(ServiceContractRegistry.ContractEntry<?> entry) {
            ServiceContractContributor contributor = config -> List.of(entry);
            return ServiceContractRegistry.build(Set.of(), Set.of(contributor), new JsonObject(), configParser());
        }

        /**
         * Builds a {@link ServiceRequestSender} backed by a real {@link EventBusClient} and a
         * permissive mocked {@link ServiceSupervisor}.
         *
         * @param vertx the Vert.x instance
         * @return a sender usable to construct a {@link ServiceClientFactory}
         */
        private ServiceRequestSender localSender(Vertx vertx) {
            EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
            EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
            ServiceSupervisor availableSupervisor = mock(ServiceSupervisor.class);
            when(availableSupervisor.isAvailable(any())).thenReturn(true);
            return new ServiceRequestSender(
                    eventBusClient, availableSupervisor, new ServicesConfig(null, List.of()), Map.of());
        }

        /**
         * §4.2 step 2: an entry that omits one of the contract's client-dispatchable operations
         * must fail {@code create()} fast with an {@link IllegalStateException} whose message
         * begins with the §4.2-pinned literal {@link ServiceClientFactory#CONTRACT_MISMATCH_PREFIX}
         * and names both the contract FQCN and the missing operation id ({@code opB}).
         *
         * <p>{@code create()} now runs {@code requireCompleteContract()} immediately after
         * {@code registry.resolve(contract)} and before proxy construction, superseding the prior
         * per-invocation {@code Future.failedFuture} behavior — the missing operation is caught at
         * {@code create()} time rather than only surfacing when {@code opB} is actually called. The
         * prefix constant equality is pinned separately by {@code prefixConstantMatchesPinnedLiteral}.
         *
         * @param vertx the Vert.x instance
         * @throws NoSuchMethodException never — {@code opA} is a real declared method
         */
        @Test
        @DisplayName("Should fail create() fast when a registry entry omits a contract operation")
        void shouldFailCreateWhenEntryMissingOperation(Vertx vertx) throws NoSuchMethodException {
            Method opAMethod = PartialContract.class.getMethod("opA", String.class);
            ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(PartialContract.class)
                    .serviceInstance(new PartialContractImpl())
                    .name("partial")
                    .operation("opA")
                    .method(opAMethod)
                    .payloadType(String.class)
                    .returnType(String.class)
                    .param("x", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();
            ServiceContractRegistry localRegistry = registryOf(entry);
            ServiceClientFactory factory = new ServiceClientFactory(localSender(vertx), localRegistry);

            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> factory.create(PartialContract.class));
            assertTrue(
                    ex.getMessage().startsWith("Service client contract mismatch: "),
                    "Message must start with the §4.2-pinned mismatch prefix, was: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains(PartialContract.class.getName()),
                    "Message must name the contract FQCN, was: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("opB"), "Message must name the missing operation id 'opB'");
        }

        /**
         * Ordering guard (§4.2 step 1 precedes step 2): an unregistered contract must still fail
         * with {@link IllegalArgumentException} from {@link ServiceContractRegistry#resolve}, never
         * reaching the completeness check. This test already passes today (the registry lookup is
         * unchanged) and must keep passing once step 2 lands.
         */
        @Test
        @DisplayName(
                "Should throw IllegalArgumentException for an unregistered contract before the completeness check runs")
        void shouldThrowIllegalArgumentForUnregisteredContractBeforeCompletenessCheck() {
            ServiceClientFactory factory = new ServiceClientFactory(sender, registry);
            assertThrows(IllegalArgumentException.class, () -> factory.create(PartialContract.class));
        }

        /**
         * Superset guard: a registry entry may declare MORE operations than the contract interface
         * (an extra {@code extraOp} key with no corresponding interface method). {@code create()}
         * must succeed and return a working proxy — the completeness check only requires interface
         * methods to be present in {@code entry.operations()}, never the reverse. This test already
         * passes today and must keep passing once step 2 lands.
         *
         * @param vertx the Vert.x instance
         * @param ctx   the test context
         * @throws Throwable if the test times out or the assertion fails
         */
        @Test
        @DisplayName("Should allow a registry entry whose operations are a superset of the contract's methods")
        void shouldAllowRegistrySupersetEntries(Vertx vertx, VertxTestContext ctx) throws Throwable {
            Method opAMethod = PartialContract.class.getMethod("opA", String.class);
            Method opBMethod = PartialContract.class.getMethod("opB", String.class);
            ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                    .contract(PartialContract.class)
                    .serviceInstance(new PartialContractImpl())
                    .name("partial-superset")
                    .operation("opA")
                    .method(opAMethod)
                    .payloadType(String.class)
                    .returnType(String.class)
                    .param("x", ParamSource.PAYLOAD, String.class)
                    .done()
                    .operation("opB")
                    .method(opBMethod)
                    .payloadType(String.class)
                    .returnType(String.class)
                    .param("x", ParamSource.PAYLOAD, String.class)
                    .done()
                    .operation("extraOp")
                    .method(opAMethod)
                    .payloadType(String.class)
                    .returnType(String.class)
                    .param("x", ParamSource.PAYLOAD, String.class)
                    .done()
                    .build();
            ServiceContractRegistry localRegistry = registryOf(entry);
            ServiceRequestSender localSender = localSender(vertx);

            DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
            for (ServiceMethodMeta meta : entry.operations().values()) {
                vertx.eventBus().<DispatchEnvelope<?>>consumer(meta.address(), msg -> {
                    Object payload = msg.body().payload();
                    msg.reply(Result.success("got:" + payload), replyOptions);
                });
            }

            ServiceClientFactory factory = new ServiceClientFactory(localSender, localRegistry);
            PartialContract proxy = factory.create(PartialContract.class);
            assertNotNull(proxy, "create() must return a proxy when the entry is a superset of the contract");

            proxy.opA("hi").onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> assertEquals("got:hi", result));
                ctx.completeNow();
            }));

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        /**
         * §4.2 protocol pin: {@link ServiceClientFactory#CONTRACT_MISMATCH_PREFIX} must equal the
         * exact literal frozen by the plan. The emitter-side twin assertion lives in
         * {@code ServiceClientProxyEmissionTest} (codegen-services) — drift in either
         * implementation breaks a test.
         */
        @Test
        @DisplayName("Prefix constant must match the §4.2-pinned literal")
        void prefixConstantMatchesPinnedLiteral() {
            assertEquals("Service client contract mismatch: ", ServiceClientFactory.CONTRACT_MISMATCH_PREFIX);
        }
    }

    // MDC propagation through the unified service-dispatch channel is covered by
    // MdcPropagationTest at the same package level.
}
