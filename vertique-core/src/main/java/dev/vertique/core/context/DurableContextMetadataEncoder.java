// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * SPI for encoding a typed context value into a {@link DurableMetadata} document for persistence
 * through a durable boundary such as Kafka headers or outbox message metadata.
 *
 * <p>Registered via Dagger multibindings as {@code Set<DurableContextMetadataEncoder<?>>}. At
 * produce/enqueue time, {@link DurableContextPropagator} reads the current binding for
 * {@link #type()} from {@link ContextHolder} and, if present, calls {@link #encode} to produce a
 * single-namespace {@link DurableMetadata} document.
 *
 * <p>Each encoder owns exactly one short namespace name (e.g. {@code "correlation"},
 * {@code "localization"}). Implementations MUST return either a {@link DurableMetadata} document
 * whose only namespace is {@link #namespace()} (FR-CTX-120), or {@link DurableMetadata#empty()} to
 * signal that the currently bound value has nothing legitimate to encode for this namespace (e.g. a
 * receive-side value that carries only a degradation marker, with no content to durably re-sign).
 * {@link DurableContextPropagator} treats an empty document as "skip this namespace" and does not
 * merge it — this is not a failure. Returning a non-empty document with any namespace other than
 * {@link #namespace()} is a contract violation and fails the produce/enqueue operation.
 *
 * <p>Durable encode and durable decode are independent capabilities. A type MAY support encode
 * without providing a decoder (FR-CTX-005).
 *
 * <p>An implementation MAY throw {@link dev.vertique.core.exception.DurableEncodeRejectedException}
 * from {@link #encode} to reject the entire enclosing capture/merge operation — distinct from
 * returning {@link DurableMetadata#empty()}, which only skips this namespace and lets the operation
 * proceed. The exception propagates synchronously out of {@code DurableContextPropagator#mergeCaptured},
 * so a producer surfaces it as a failed enqueue rather than persisting a document the encoder has
 * determined is unsafe to write.
 *
 * <p>Implementations MUST NOT block the Vert.x event loop.
 *
 * @param <T> the context value type this encoder handles; must implement {@link ContextValue}
 */
public interface DurableContextMetadataEncoder<T extends ContextValue> {

    /**
     * Returns the context type this encoder handles.
     *
     * @return the context type; never {@code null}
     */
    Class<T> type();

    /**
     * Returns the single namespace name this encoder writes. The namespace must be a short,
     * stable, lowercase identifier (e.g. {@code "correlation"}). Boot-time validation rejects
     * duplicate namespaces across encoders.
     *
     * @return the namespace name; never {@code null} or blank
     */
    String namespace();

    /**
     * Encodes the given context value into a single-namespace {@link DurableMetadata} document, or
     * returns {@link DurableMetadata#empty()} when the currently bound value has nothing legitimate
     * to encode — a "skip this namespace" signal {@link DurableContextPropagator} understands, not a
     * partial failure. Implementations should use
     * {@link DurableMetadata#of(String, io.vertx.core.json.JsonObject)} with {@link #namespace()} as
     * the namespace when they do have content to encode. A non-empty returned document MUST contain
     * exactly one namespace equal to {@link #namespace()}; any other non-empty shape is a contract
     * violation.
     *
     * @param value   the currently bound context value; never {@code null}
     * @param context the encode context identifying the durable boundary
     * @return a single-namespace {@link DurableMetadata} document, or {@link DurableMetadata#empty()}
     *         to skip encoding this namespace; never {@code null}
     * @throws dev.vertique.core.exception.DurableEncodeRejectedException if the encoder determines
     *         the enclosing capture/merge operation must not proceed at all (see class javadoc)
     */
    DurableMetadata encode(T value, DurableEncodeContext context);
}
