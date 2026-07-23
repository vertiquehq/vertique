// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@link ServiceMethodInvoker}'s inbound-scope install seeds the ambient
 * {@link InvocationOrigin} for the lifetime of a service dispatch (identity-002 P2.S5b-ii), and
 * that a dispatch carrying root-ingress provenance (e.g. a delayed-job {@link
 * DeferredExecutionOrigin}) is reflected in the derived origin's {@code kind()} rather than always
 * being clobbered to {@link DispatchBoundary#SERVICE_DISPATCH} (identity-002 review fix W_f).
 *
 * <p>Drives a real event-bus dispatch through {@link ServiceMethodInvoker#handle} (rather than
 * mocking the inbound scope) so the assertion exercises the actual production wiring: the merge
 * performed in {@code ServiceMethodInvoker.withInvocationOrigin} plus the
 * {@link dev.vertique.context.InboundDispatchScope#install} scope open, read back through a real
 * {@link DefaultContextHolder} — not a mock.
 */
@ExtendWith(VertxExtension.class)
class InvocationOriginPropagationTest {

    // --- Contract fixture ---

    @ServiceContract(namespace = "test", value = "origin")
    interface OriginService {
        @ServiceOperation("ping")
        Future<String> ping(String name);
    }

    static class OriginServiceImpl implements OriginService {
        @Override
        public Future<String> ping(String name) {
            return Future.succeededFuture("pong " + name);
        }
    }

    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "invocation-origin-test/" + base + "/" + ADDRESS_COUNTER.incrementAndGet();
    }

    private static final DeliveryOptions BODY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");

    @BeforeAll
    static void setup(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException e) {
            // Already registered by another test class sharing the same Vertx instance — ignore.
        }
    }

    private ServiceMethodMeta pingMeta(Object impl, String address) throws Exception {
        Method method = OriginService.class.getMethod("ping", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "origin",
                "ping",
                String.class,
                String.class,
                List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    // --- Test ---

    @Test
    @DisplayName(
            "an ordinary service dispatch (no upstream provenance) still seeds InvocationOrigin at the SERVICE_DISPATCH boundary")
    void ordinaryServiceDispatchStillSeedsServiceDispatch(Vertx vertx, VertxTestContext ctx) throws Exception {
        ContextHolder contextHolder = new DefaultContextHolder();
        List<InvocationOrigin> captured = new ArrayList<>();
        ServiceInterceptor capturingInterceptor = new ServiceInterceptor() {
            @Override
            public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext dispatchCtx) {
                contextHolder.current(InvocationOrigin.class).ifPresent(captured::add);
                return Future.succeededFuture(dispatchCtx);
            }
        };

        String address = uniqueAddress("ping");
        ServiceMethodMeta meta = pingMeta(new OriginServiceImpl(), address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(capturingInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        vertx.eventBus()
                .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(
                                1,
                                captured.size(),
                                "the interceptor must observe exactly one ambient InvocationOrigin");
                        assertEquals(
                                DispatchBoundary.SERVICE_DISPATCH,
                                captured.get(0).kind(),
                                "with no upstream provenance in the decoded context, the ambient origin's kind "
                                        + "must fall back to the SERVICE_DISPATCH boundary this invoker installs "
                                        + "with");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "a delayed-job-originated dispatch carrying a DeferredExecutionOrigin seeds InvocationOrigin with kind "
                    + "\"delayed-job\", not the SERVICE_DISPATCH boundary")
    void delayedJobDispatchSeedsDelayedJobOrigin(Vertx vertx, VertxTestContext ctx) throws Exception {
        ContextHolder contextHolder = new DefaultContextHolder();
        List<InvocationOrigin> captured = new ArrayList<>();
        ServiceInterceptor capturingInterceptor = new ServiceInterceptor() {
            @Override
            public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext dispatchCtx) {
                contextHolder.current(InvocationOrigin.class).ifPresent(captured::add);
                return Future.succeededFuture(dispatchCtx);
            }
        };

        String address = uniqueAddress("ping");
        ServiceMethodMeta meta = pingMeta(new OriginServiceImpl(), address);
        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(capturingInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        // Mirrors DelayedJobPoller.dispatch: it installs a DeferredExecutionOrigin(kind="delayed-job", ...)
        // into the outgoing envelope's FQCN-keyed dispatch-context map — vertique-job-delayed has no
        // dependency on vertique-security-core, so it cannot install InvocationOrigin itself.
        DeferredExecutionOrigin deferredOrigin = DeferredExecutionOrigin.of("delayed-job", "test.handler.address");
        DispatchEnvelope<String> envelope = DispatchEnvelope.of(
                "World", DispatchMetadata.of(Map.of(DeferredExecutionOrigin.class.getName(), deferredOrigin)));

        vertx.eventBus().<Result<?>>request(address, envelope, BODY_OPTIONS).onComplete(ctx.succeeding(reply -> {
            ctx.verify(() -> {
                assertEquals(1, captured.size(), "the interceptor must observe exactly one ambient InvocationOrigin");
                assertEquals(
                        "delayed-job",
                        captured.get(0).kind(),
                        "a delayed-job-originated dispatch must not have its InvocationOrigin clobbered "
                                + "to service-dispatch — it must be distinguishable as deferred work");
            });
            ctx.completeNow();
        }));
    }
}
