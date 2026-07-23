// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.localization.context.LocalizationContext;
import dev.vertique.localization.context.LocalizationContextServiceDispatchDecoder;
import dev.vertique.localization.context.LocalizationContextServiceDispatchEncoder;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for AC-LOC-006: a bound {@link LocalizationContext} propagates through service
 * dispatch to a downstream handler.
 *
 * <p>Exercises the real inbound path through {@link ServiceMethodInvoker} with a
 * {@link ServiceDispatchContextRegistry} that includes the shipped localization codecs
 * ({@link LocalizationContextServiceDispatchEncoder} / {@link LocalizationContextServiceDispatchDecoder}).
 * The handler reads the propagated value via {@link DispatchContext#current(Class)}.
 *
 * <p>Two scenarios are verified:
 * <ul>
 *   <li><b>Positive (AC-LOC-006):</b> a {@link LocalizationContext} placed in the envelope's
 *       dispatch-context map is decoded and observable to the handler.</li>
 *   <li><b>Negative (FR-LOC-232):</b> an envelope with no localization entry results in the
 *       handler observing {@link Optional#empty()} — nothing is rebound.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
class LocalizationDispatchPropagationTest {

    // --- Contract Fixture ---

    @ServiceContract(namespace = "test", value = "localization-svc")
    interface LocalizationAwareService {
        @ServiceOperation("process")
        Future<String> process(String input);
    }

    // --- Shared state ---

    /**
     * Captured {@link LocalizationContext} values observed by the handler during dispatch.
     * One slot per invocation.
     */
    private static final List<Optional<LocalizationContext>> CAPTURED = new ArrayList<>();

    /**
     * Counter for generating unique event bus addresses per test to avoid cross-test interference.
     */
    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger(0);

    /** A well-known {@link LocalizationContext} used as the expected value in the positive test. */
    private static final LocalizationContext SAMPLE_LOCALE_CTX = new LocalizationContext(
            Locale.forLanguageTag("sv"),
            ZoneId.of("UTC"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            "test",
            "test");

    /** Delivery options using the local {@code dispatch.envelope} codec. */
    private static final DeliveryOptions BODY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Service implementation ---

    /**
     * Service implementation that captures whatever {@link LocalizationContext} is currently
     * visible via {@link DispatchContext#current(Class)} and returns the observed locale tag.
     */
    private static final LocalizationAwareService CAPTURING_IMPL = input -> {
        Optional<LocalizationContext> observed = DispatchContext.current(LocalizationContext.class);
        CAPTURED.add(observed);
        String tag = observed.map(LocalizationContext::languageTag).orElse("none");
        return Future.succeededFuture("processed:" + tag);
    };

    // --- Setup ---

    @BeforeAll
    static void setup(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException e) {
            // Already registered — safe to ignore
        }
    }

    // --- Helpers ---

    private static String uniqueAddress(String base) {
        return "test-loc-propagation/" + base + "/" + ADDRESS_COUNTER.incrementAndGet();
    }

    /**
     * Builds a {@link ServiceMethodInvoker} wired with the localization service-dispatch codecs
     * so that a {@link LocalizationContext} placed in the envelope's dispatch-context map is
     * decoded and installed into the holder before the handler is invoked.
     *
     * @param meta  the operation metadata
     * @param vertx the Vert.x instance
     * @return the configured invoker
     */
    private ServiceMethodInvoker invokerWithLocalizationDecoders(ServiceMethodMeta meta, Vertx vertx) {
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(
                Set.of(new LocalizationContextServiceDispatchEncoder()),
                Set.of(new LocalizationContextServiceDispatchDecoder()));
        return new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(), null, null, vertx, registry);
    }

    /**
     * Builds the {@link ServiceMethodMeta} for the {@code process} operation, wiring the given
     * implementation instance to the provided event bus address.
     *
     * @param impl    the service implementation to invoke
     * @param address the event bus address to register on
     * @return the configured operation metadata
     * @throws Exception if reflection fails (not expected under normal test conditions)
     */
    private ServiceMethodMeta processMeta(Object impl, String address) throws Exception {
        Method method = LocalizationAwareService.class.getMethod("process", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "localization-svc",
                "process",
                String.class,
                String.class,
                List.of(new ParamMeta("input", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    // --- Tests ---

    @Test
    @DisplayName("AC-LOC-006: LocalizationContext in envelope dispatch context is observable in the handler")
    void shouldPropagateLocalizationContextToHandler(Vertx vertx, VertxTestContext ctx) throws Exception {
        CAPTURED.clear();
        String address = uniqueAddress("process-with-loc");

        ServiceMethodMeta meta = processMeta(CAPTURING_IMPL, address);
        ServiceMethodInvoker invoker = invokerWithLocalizationDecoders(meta, vertx);
        vertx.eventBus().consumer(address, invoker);

        DispatchEnvelope<String> envelope = DispatchEnvelope.of(
                "hello", DispatchMetadata.of(Map.of(LocalizationContext.class.getName(), SAMPLE_LOCALE_CTX)));

        vertx.eventBus().<Result<?>>request(address, envelope, BODY_OPTIONS).onComplete(ctx.succeeding(reply -> {
            ctx.verify(() -> {
                assertTrue(reply.body().isSuccess(), "dispatch must succeed");
                assertFalse(CAPTURED.isEmpty(), "handler must have been invoked");

                Optional<LocalizationContext> observed = CAPTURED.get(0);
                assertTrue(observed.isPresent(), "LocalizationContext must be present inside the handler");

                LocalizationContext lc = observed.get();
                assertEquals(
                        SAMPLE_LOCALE_CTX.locale(),
                        lc.locale(),
                        "propagated locale must match the one seeded into the envelope");
                assertEquals(
                        SAMPLE_LOCALE_CTX.zone(),
                        lc.zone(),
                        "propagated zone must match the one seeded into the envelope");
                assertEquals(
                        SAMPLE_LOCALE_CTX,
                        lc,
                        "full LocalizationContext must equal the value seeded into the envelope");
                assertNotNull(reply.body().get(), "reply payload must not be null");
                assertTrue(
                        reply.body().get().toString().contains("sv"),
                        "handler must have read the propagated locale tag");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("FR-LOC-232: absent LocalizationContext results in empty Optional in the handler")
    void shouldObserveEmptyWhenNoLocalizationContextInEnvelope(Vertx vertx, VertxTestContext ctx) throws Exception {
        CAPTURED.clear();
        String address = uniqueAddress("process-without-loc");

        ServiceMethodMeta meta = processMeta(CAPTURING_IMPL, address);
        ServiceMethodInvoker invoker = invokerWithLocalizationDecoders(meta, vertx);
        vertx.eventBus().consumer(address, invoker);

        // Envelope carries no localization entry — dispatch-context map is empty
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello");

        vertx.eventBus().<Result<?>>request(address, envelope, BODY_OPTIONS).onComplete(ctx.succeeding(reply -> {
            ctx.verify(() -> {
                assertTrue(reply.body().isSuccess(), "dispatch must succeed");
                assertFalse(CAPTURED.isEmpty(), "handler must have been invoked");

                Optional<LocalizationContext> observed = CAPTURED.get(0);
                assertFalse(
                        observed.isPresent(), "LocalizationContext must be absent when not present in the envelope");
                assertTrue(
                        reply.body().get().toString().contains("none"),
                        "handler must have fallen back to the 'none' tag when no locale was propagated");
            });
            ctx.completeNow();
        }));
    }
}
