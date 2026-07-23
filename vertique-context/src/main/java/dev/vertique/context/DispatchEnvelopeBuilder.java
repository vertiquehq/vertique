// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Objects;

/**
 * Shared envelope-construction helper used by all framework dispatchers.
 *
 * <p>Centralizes the steps of merging caller-supplied dispatch-context overrides with values
 * produced by registered {@link ServiceDispatchContextEncoder}s, and constructing a
 * {@link DispatchEnvelope}. Framework dispatchers (service client factory, Kafka consumer
 * dispatcher, outbox-service destination handler) MUST use this builder rather than calling
 * {@link DispatchEnvelope#of(Object, DispatchMetadata)} directly (see PRD FR-CTX-015).
 *
 * <p>MDC propagation flows through the same encoder/decoder pipeline as other typed context
 * values. The MDC service-dispatch encoder registered by {@code LoggingContextModule}
 * (via {@code ServiceDispatchCodecs.snapshotEncoder}) captures the holder-bound
 * {@code MDCContext} at send time; no separate MDC capture SPI is needed.
 *
 * <p>Instances are {@link Singleton} and should be injected via Dagger. This class is wired by
 * {@link ContextRuntimeModule} and provided automatically whenever {@code ContextRuntimeModule}
 * is included in the application component.
 */
@Singleton
public final class DispatchEnvelopeBuilder {

    private final ServiceDispatchContextCapturer capturer;

    /**
     * Constructs the builder.
     *
     * @param capturer the service-dispatch context capturer; must not be {@code null}
     */
    @Inject
    public DispatchEnvelopeBuilder(ServiceDispatchContextCapturer capturer) {
        this.capturer = Objects.requireNonNull(capturer, "capturer must not be null");
    }

    /**
     * Returns a fresh no-op builder backed by empty SPI registries, intended for tests that need
     * to instantiate a dispatcher without a Dagger graph. The returned builder constructs envelopes
     * whose dispatch-context contains only caller-supplied overrides — no encoders run.
     *
     * <p>Production code MUST use the Dagger-injected builder, not this factory.
     *
     * @return a no-op builder suitable for plain-JUnit test fixtures
     */
    public static DispatchEnvelopeBuilder forTesting() {
        ContextHolder holder = new DefaultContextHolder();
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(
                new ServiceDispatchContextRegistry(java.util.Set.of(), java.util.Set.of()), holder);
        return new DispatchEnvelopeBuilder(capturer);
    }

    /**
     * Builds a {@link DispatchEnvelope} for the given payload.
     *
     * <p>Merges any caller-supplied dispatch-context overrides with values produced by registered
     * {@link ServiceDispatchContextEncoder}s (including the built-in MDC encoder). The resulting
     * envelope carries the merged dispatch context, with no fire-and-report reply address.
     *
     * @param payload                the request payload; may be {@code null} for void operations
     * @param callerContextOverrides dispatch-context entries the caller wants to attach directly
     *                               (e.g., {@code SecurityContext}, {@code KafkaRecordContext}).
     *                               Keys MUST be type FQCNs. May be empty, but must not be
     *                               {@code null}.
     * @param boundary               the boundary identifier passed into the encode context
     *                               (e.g., {@code "service-dispatch"}, {@code "kafka"},
     *                               {@code "outbox-service"}); must not be {@code null}
     * @param <T>                    the payload type
     * @return the constructed envelope; never {@code null}
     * @throws IllegalStateException if any registered encoder returns {@code null} (FR-CTX-050)
     *                               or if a caller key collides with an encoder-produced key
     *                               (FR-CTX-063)
     * @throws NullPointerException  if {@code callerContextOverrides} or {@code boundary} is
     *                               {@code null}
     */
    public <T> DispatchEnvelope<T> build(T payload, Map<String, Object> callerContextOverrides, String boundary) {
        Objects.requireNonNull(callerContextOverrides, "callerContextOverrides must not be null");
        Objects.requireNonNull(boundary, "boundary must not be null");
        Map<String, Object> dispatchContext =
                capturer.mergeCaptured(callerContextOverrides, new ServiceDispatchEncodeContext(boundary));
        return DispatchEnvelope.of(payload, DispatchMetadata.of(dispatchContext));
    }

    /**
     * Builds a {@link DispatchEnvelope} for the given payload and sets a fire-and-report reply
     * address.
     *
     * <p>Identical to {@link #build(Object, Map, String)} but additionally sets the reply address
     * on the envelope so that the service invoker publishes the result to that event bus address
     * instead of calling {@code message.reply()}.
     *
     * @param payload                the request payload; may be {@code null} for void operations
     * @param callerContextOverrides dispatch-context entries the caller wants to attach directly.
     *                               Keys MUST be type FQCNs. May be empty, but must not be
     *                               {@code null}.
     * @param boundary               the boundary identifier passed into the encode context;
     *                               must not be {@code null}
     * @param replyAddress           the event bus address to publish the result to;
     *                               must not be {@code null}
     * @param <T>                    the payload type
     * @return the constructed envelope with the reply address set; never {@code null}
     * @throws IllegalStateException if any registered encoder returns {@code null} (FR-CTX-050)
     *                               or if a caller key collides with an encoder-produced key
     *                               (FR-CTX-063)
     * @throws NullPointerException  if any required argument is {@code null}
     */
    public <T> DispatchEnvelope<T> build(
            T payload, Map<String, Object> callerContextOverrides, String boundary, String replyAddress) {
        Objects.requireNonNull(callerContextOverrides, "callerContextOverrides must not be null");
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(replyAddress, "replyAddress must not be null");
        Map<String, Object> dispatchContext =
                capturer.mergeCaptured(callerContextOverrides, new ServiceDispatchEncodeContext(boundary));
        return DispatchEnvelope.of(payload, DispatchMetadata.of(dispatchContext), replyAddress);
    }
}
