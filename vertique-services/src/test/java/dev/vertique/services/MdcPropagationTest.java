// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.logging.DiagnosticContextSnapshot;
import dev.vertique.logging.MDCContexts;
import dev.vertique.resilience.Resilience;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end tests covering MDC propagation through the unified service-dispatch context channel.
 *
 * <p>After the MDC unification, MDC entries flow through {@code DispatchMetadata.dispatchContext()}
 * keyed by {@code MDCContext.class.getName()}, encoded as a {@link DiagnosticContextSnapshot} by the
 * built-in encoder and decoded back on the receive side by the matching decoder. These tests verify:
 *
 * <ul>
 *   <li>Ambient MDC bound via {@link MDCContexts#put} on a duplicated Vert.x context is captured by
 *       the built-in encoder and surfaces as a {@link DiagnosticContextSnapshot} on the wire.
 *   <li>When the caller has no ambient MDC, the dispatch-context map contains no MDC entry.
 *   <li>MDC entries do not leak across two consecutive dispatches on the same Vert.x instance.
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@DisplayName("MDC propagation through service dispatch")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MdcPropagationTest {

    @ServiceContract(namespace = "test", value = "mdc-test")
    interface MdcTestService {
        @ServiceOperation("echoMdc")
        Future<String> echoMdc(String payload);
    }

    static class MdcTestServiceImpl implements MdcTestService {
        @Override
        public Future<String> echoMdc(String payload) {
            return Future.succeededFuture("mdc:none");
        }
    }

    private static ServiceContractRegistry registry;
    private static ServiceRequestSender sender;
    private static ServiceMethodMeta echoMdcMeta;

    /**
     * Builds a {@link ServiceClientFactory} whose envelope builder has the built-in MDC encoder
     * registered. The default 2-arg constructor wires an empty registry, so the encoder pipeline
     * would never observe ambient MDC.
     */
    private static ServiceClientFactory mdcAwareFactory(ServiceRequestSender s, ServiceContractRegistry r) {
        ServiceDispatchContextRegistry ctxRegistry =
                new ServiceDispatchContextRegistry(Set.of(MDCContexts.serviceDispatchEncoder()), Set.of());
        DispatchEnvelopeBuilder builder = new DispatchEnvelopeBuilder(
                new ServiceDispatchContextCapturer(ctxRegistry, new DefaultContextHolder()));
        return new ServiceClientFactory(s, r, builder);
    }

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // already registered
        }
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // already registered
        }

        registry = ServiceContractRegistry.build(Set.of(new MdcTestServiceImpl()), new JsonObject(), configParser());
        ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
        when(supervisor.isAvailable(any())).thenReturn(true);

        EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
        EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
        sender = new ServiceRequestSender(
                eventBusClient,
                supervisor,
                new ServiceResilienceConfigAdapter(
                        Resilience.create(vertx),
                        new ServicesConfig(null, List.of()),
                        Map.of(),
                        java.util.Optional.empty()));

        ServiceContractRegistry.ContractEntry<MdcTestService> entry = registry.resolve(MdcTestService.class);
        echoMdcMeta = entry.operations().get("echoMdc");

        // Consumer reads the requestId out of the wire-format DiagnosticContextSnapshot under the
        // standard MDCContext FQCN key — this is what ServiceMethodInvoker's decoder feeds into
        // the inbound dispatch scope on a real invocation.
        DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
        vertx.eventBus().<DispatchEnvelope<?>>consumer(echoMdcMeta.address(), msg -> {
            DispatchEnvelope<?> body = msg.body();
            Object snap = body.metadata().dispatchContext().get("dev.vertique.logging.MDCContext");
            String requestId = null;
            if (snap instanceof DiagnosticContextSnapshot ds) {
                requestId = ds.entries().get("requestId");
            }
            msg.reply(Result.success("mdc:" + requestId), replyOptions);
        });

        ctx.completeNow();
    }

    @Nested
    @DisplayName("Ambient MDC bound on caller context")
    class WithAmbientMdc {

        @Test
        @DisplayName("Should propagate ambient MDC entries as a DiagnosticContextSnapshot on the wire")
        void shouldPropagateAmbientMdcToBody(Vertx vertx, VertxTestContext ctx) throws Throwable {
            ServiceClientFactory factory = mdcAwareFactory(sender, registry);
            MdcTestService proxy = factory.create(MdcTestService.class);

            // Dispatch from a duplicated Vert.x context with MDC bound — mirrors what
            // ContextualLoggingMiddleware / SecurityContextMiddleware do on a real request.
            ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
                MDCContexts.put("requestId", "trace-abc-123");
                proxy.echoMdc("any-payload").onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> assertEquals(
                            "mdc:trace-abc-123",
                            result,
                            "Consumer should see the requestId captured from ambient MDC"));
                    ctx.completeNow();
                }));
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        @Test
        @DisplayName("Should include every ambient MDC entry in the propagated snapshot")
        void shouldIncludeAllAmbientMdcEntries(Vertx vertx, VertxTestContext ctx) throws Throwable {
            @ServiceContract(namespace = "test", value = "mdc-multi")
            interface MultiMdcService {
                @ServiceOperation("echoMulti")
                Future<String> echoMulti(String payload);
            }

            class MultiMdcServiceImpl implements MultiMdcService {
                @Override
                public Future<String> echoMulti(String payload) {
                    return Future.succeededFuture("ok");
                }
            }

            ServiceContractRegistry localRegistry =
                    ServiceContractRegistry.build(Set.of(new MultiMdcServiceImpl()), new JsonObject(), configParser());
            ServiceContractRegistry.ContractEntry<MultiMdcService> entry = localRegistry.resolve(MultiMdcService.class);
            ServiceMethodMeta multiMeta = entry.operations().get("echoMulti");

            DeliveryOptions replyOptions = new DeliveryOptions().setCodecName("dispatch.result");
            vertx.eventBus().<DispatchEnvelope<?>>consumer(multiMeta.address(), msg -> {
                DispatchEnvelope<?> body = msg.body();
                Object raw = body.metadata().dispatchContext().get("dev.vertique.logging.MDCContext");
                String requestId = null;
                String traceId = null;
                if (raw instanceof DiagnosticContextSnapshot ds) {
                    requestId = ds.entries().get("requestId");
                    traceId = ds.entries().get("traceId");
                }
                msg.reply(Result.success(requestId + ":" + traceId), replyOptions);
            });

            ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
            when(supervisor.isAvailable(any())).thenReturn(true);
            EventBusExceptionMapper exceptionMapper = new EventBusExceptionMapper();
            EventBusClient eventBusClient = new EventBusClient(vertx, exceptionMapper);
            ServiceRequestSender localSender = new ServiceRequestSender(
                    eventBusClient,
                    supervisor,
                    new ServiceResilienceConfigAdapter(
                            Resilience.create(vertx),
                            new ServicesConfig(null, List.of()),
                            Map.of(),
                            java.util.Optional.empty()));
            ServiceClientFactory factory = mdcAwareFactory(localSender, localRegistry);
            MultiMdcService proxy = factory.create(MultiMdcService.class);

            ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
                MDCContexts.putAll(Map.of("requestId", "req-999", "traceId", "span-888"));
                proxy.echoMulti("payload").onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> assertEquals(
                            "req-999:span-888",
                            result,
                            "Both requestId and traceId should be propagated as part of the snapshot"));
                    ctx.completeNow();
                }));
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }
    }

    @Nested
    @DisplayName("No ambient MDC")
    class WithoutAmbientMdc {

        @Test
        @DisplayName("Should omit the MDC entry from the dispatch-context map when caller has no bound MDC")
        void shouldOmitMdcEntryWhenAbsent(Vertx vertx, VertxTestContext ctx) throws Throwable {
            ServiceClientFactory factory = mdcAwareFactory(sender, registry);
            MdcTestService proxy = factory.create(MdcTestService.class);

            // Dispatch from a duplicated Vert.x context with NO MDC bound — the encoder should not
            // emit a MDC key into the dispatch-context map.
            ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
                proxy.echoMdc("no-mdc-payload").onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> assertEquals(
                            "mdc:null",
                            result,
                            "Dispatch-context should carry no MDC snapshot when caller has none bound"));
                    ctx.completeNow();
                }));
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }

        @Test
        @DisplayName("Should not propagate MDC when called from outside a duplicated Vert.x context")
        void shouldOmitMdcEntryFromBareContext(VertxTestContext ctx) throws Throwable {
            // Caller is on the test thread (no Vert.x context bound) — the holder is empty, the
            // encoder yields nothing, and the consumer sees a null requestId.
            ServiceClientFactory factory = mdcAwareFactory(sender, registry);
            MdcTestService proxy = factory.create(MdcTestService.class);

            proxy.echoMdc("legacy-payload").onComplete(ctx.succeeding(result -> {
                ctx.verify(() ->
                        assertEquals("mdc:null", result, "Bare-thread caller must produce an empty MDC snapshot"));
                ctx.completeNow();
            }));

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }
    }

    @Nested
    @DisplayName("MDC isolation across dispatches")
    class MdcIsolation {

        @Test
        @DisplayName("Should not leak MDC entries from one dispatch to the next")
        void shouldNotLeakMdcEntriesBetweenDispatches(Vertx vertx, VertxTestContext ctx) throws Throwable {
            ServiceClientFactory factory = mdcAwareFactory(sender, registry);
            MdcTestService proxy = factory.create(MdcTestService.class);

            ((ContextInternal) vertx.getOrCreateContext()).duplicate().runOnContext(v -> {
                MDCContexts.put("requestId", "first-req");
                proxy.echoMdc("first")
                        .compose(firstResult -> {
                            assertEquals(
                                    "mdc:first-req", firstResult, "First dispatch should carry the bound requestId");
                            return Future.succeededFuture(firstResult);
                        })
                        .onComplete(ctx.succeeding(firstResult -> {
                            // Second dispatch from a fresh duplicated context with no MDC bound —
                            // must not see the first request's keys.
                            ((ContextInternal) vertx.getOrCreateContext())
                                    .duplicate()
                                    .runOnContext(vv -> {
                                        assertNull(
                                                MDCContexts.get("requestId"),
                                                "Fresh duplicated context must not inherit the first dispatch's MDC");
                                        proxy.echoMdc("second").onComplete(ctx.succeeding(secondResult -> {
                                            ctx.verify(() -> assertEquals(
                                                    "mdc:null",
                                                    secondResult,
                                                    "Second dispatch must not see the requestId from the first"));
                                            ctx.completeNow();
                                        }));
                                    });
                        }));
            });

            assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
            if (ctx.failed()) throw ctx.causeOfFailure();
        }
    }
}
