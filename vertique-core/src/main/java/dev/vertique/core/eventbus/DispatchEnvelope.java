// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import java.util.Optional;

/**
 * Event-bus carrier for service dispatch.
 *
 * <p>Carries the request payload, the {@link DispatchMetadata} (MDC + typed dispatch context),
 * and an optional fire-and-report reply address.
 *
 * <p>Local codecs are registered at startup to ensure no serialization overhead for local
 * delivery.
 *
 * <p>{@code SecurityContext} is not a special carrier — it is one
 * ordinary typed value stored in {@link DispatchMetadata#dispatchContext()} under
 * {@code SecurityContext.class.getName()}, like any other context type. There is no SC-specific
 * factory on this carrier; framework dispatchers construct envelopes through
 * {@link dev.vertique.context.DispatchEnvelopeBuilder} (PRD FR-CTX-015), which captures
 * holder-bound values through the registered service-dispatch encoder SPIs.
 *
 * @param <T> the payload type
 */
public final class DispatchEnvelope<T> {

    private final T payload;
    private final DispatchMetadata metadata;
    private final String replyAddress; // nullable

    private DispatchEnvelope(T payload, DispatchMetadata metadata, String replyAddress) {
        this.payload = payload;
        this.metadata = metadata != null ? metadata : DispatchMetadata.empty();
        this.replyAddress = replyAddress;
    }

    // --- Factory Methods ---

    /**
     * Creates an envelope with the given payload and metadata; no reply address.
     *
     * @param payload  the request payload
     * @param metadata the dispatch metadata
     * @param <T>      the payload type
     * @return the envelope instance
     */
    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata) {
        return new DispatchEnvelope<>(payload, metadata, null);
    }

    /**
     * Creates an envelope with the given payload, metadata, and fire-and-report reply address.
     *
     * @param payload      the request payload
     * @param metadata     the dispatch metadata
     * @param replyAddress the event bus address to publish the result to
     * @param <T>          the payload type
     * @return the envelope instance
     */
    public static <T> DispatchEnvelope<T> of(T payload, DispatchMetadata metadata, String replyAddress) {
        return new DispatchEnvelope<>(payload, metadata, replyAddress);
    }

    /**
     * Creates an envelope with just a payload and empty metadata. Convenience for tests, codecs,
     * and simple consumers that have no metadata to attach; framework dispatchers MUST use
     * {@link dev.vertique.context.DispatchEnvelopeBuilder} so they pick up holder-bound
     * values through registered encoders.
     *
     * @param payload the request payload
     * @param <T>     the payload type
     * @return the envelope instance
     */
    public static <T> DispatchEnvelope<T> of(T payload) {
        return new DispatchEnvelope<>(payload, DispatchMetadata.empty(), null);
    }

    /**
     * Creates an empty envelope with no payload and empty metadata.
     *
     * @return the empty envelope instance
     */
    public static DispatchEnvelope<Void> empty() {
        return new DispatchEnvelope<>(null, DispatchMetadata.empty(), null);
    }

    // --- Accessors ---

    /**
     * Returns the request payload.
     *
     * @return the payload, or {@code null} for empty envelopes
     */
    public T payload() {
        return payload;
    }

    /**
     * Returns the dispatch metadata (never {@code null}).
     *
     * @return the dispatch metadata
     */
    public DispatchMetadata metadata() {
        return metadata;
    }

    /**
     * Returns the optional fire-and-report reply address. When present, the service dispatch
     * framework publishes the {@link Result} to that address instead of calling
     * {@code message.reply()}.
     *
     * @return the optional reply address
     */
    public Optional<String> replyAddress() {
        return Optional.ofNullable(replyAddress);
    }
}
