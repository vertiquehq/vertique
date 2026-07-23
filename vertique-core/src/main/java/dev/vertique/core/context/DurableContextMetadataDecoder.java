// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for decoding a typed context value from a {@link DurableMetadata} document read from a
 * durable boundary such as Kafka headers or outbox message metadata.
 *
 * <p>Registered via Dagger multibindings as {@code Set<DurableContextMetadataDecoder<?>>}. At
 * consume time, {@link DurableContextPropagator} passes the full {@link DurableMetadata} document
 * to each decoder's {@link #decode} method. Decoders read their own namespace body via
 * {@link DurableMetadata#body(String) metadata.body(namespace())}.
 *
 * <p>Decode failures are recoverable: they log a throttled WARN and do not block other decoders
 * (FR-CTX-118, FR-CTX-156).
 *
 * <p>Implementations MUST NOT return {@code null} — a null return is an SPI contract violation
 * treated as a decode failure with a throttled WARN (FR-CTX-122).
 *
 * <p>Implementations MUST NOT block the Vert.x event loop.
 *
 * @param <T> the context value type this decoder produces; must implement {@link ContextValue}
 */
public interface DurableContextMetadataDecoder<T extends ContextValue> {

    /**
     * Returns the context type this decoder produces.
     *
     * @return the context type; never {@code null}
     */
    Class<T> type();

    /**
     * Returns the single namespace name this decoder reads. Implementations use
     * {@link DurableMetadata#body(String) metadata.body(namespace())} inside {@link #decode} to
     * retrieve their namespace body. If absent, returning {@link ContextDecodeResult#empty()} is
     * appropriate. Boot-time validation rejects duplicate namespaces across decoders.
     *
     * @return the namespace name; never {@code null} or blank
     */
    String namespace();

    /**
     * Decodes the given {@link DurableMetadata} document into a typed context value.
     *
     * <p>The full document is provided; implementations retrieve their namespace body via
     * {@code metadata.body(namespace())}. If the namespace is absent,
     * {@link ContextDecodeResult#empty()} should be returned.
     *
     * @param metadata the full durable metadata document; never {@code null}
     * @param context  the decode context identifying the durable boundary
     * @return the decode result; must not be {@code null}
     */
    ContextDecodeResult<T> decode(DurableMetadata metadata, DurableDecodeContext context);

    /**
     * Declares whether this decoder's namespace may be accepted from a sender-supplied explicit
     * carrier — e.g. the workflow signal carrier threaded through
     * {@code TransactionalWorkflowOperations#signal(..., DurableMetadata, ...)}.
     *
     * <p>A decoder returning {@code false} declares its namespace <b>authenticated-only</b>: it must
     * never be accepted from a sender-supplied explicit carrier. Such a namespace is stripped at the
     * sanitization seam ({@code DurableContextPropagator#sanitizeInboundCarrier}) and can only enter
     * a drive via ambient/authenticated derivation or a framework-persisted carrier (timer, branch,
     * or instance metadata) — those are framework-captured, not sender-supplied, and are therefore
     * trusted regardless of this flag. See ADR-0147.
     *
     * <p>Defaults to {@code true} — the flag is additive and opt-in; every namespace remains
     * explicit-carrier-eligible until its decoder overrides this method.
     *
     * @return {@code true} if this namespace may be bound from an explicit carrier; {@code false} if
     *         it is authenticated-only and must be stripped before an explicit carrier is bound
     */
    default boolean acceptsExplicitCarrier() {
        return true;
    }
}
