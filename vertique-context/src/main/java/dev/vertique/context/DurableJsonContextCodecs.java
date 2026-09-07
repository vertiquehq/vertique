// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * Static factory methods for {@link DurableContextMetadataEncoder} and
 * {@link DurableContextMetadataDecoder} implementations that serialise and deserialise an envelope
 * object to and from a single namespace of a {@link DurableMetadata} document via Jackson JSON.
 *
 * <p>Both factory methods use {@link JsonObject#mapFrom(Object)} / {@link JsonObject#mapTo(Class)},
 * which delegate to the Vert.x shared mapper, so codec configuration (modules, feature flags)
 * applied at startup is automatically inherited.
 *
 * <p>Typical use:
 * <pre>{@code
 * DurableContextMetadataEncoder<CorrelationId> encoder =
 *     DurableJsonContextCodecs.jsonEncoder(
 *         CorrelationId.class,
 *         "correlation",
 *         id -> new CorrelationEnvelope(id.value()));
 *
 * DurableContextMetadataDecoder<CorrelationId> decoder =
 *     DurableJsonContextCodecs.jsonDecoder(
 *         CorrelationId.class,
 *         "correlation",
 *         CorrelationEnvelope.class,
 *         env -> new CorrelationId(env.value()));
 * }</pre>
 */
public final class DurableJsonContextCodecs {

    private DurableJsonContextCodecs() {}

    // --- JSON encoder ---

    /**
     * Returns an encoder that maps {@code envelopeFn(value)} to a JSON object and emits it as the
     * body of {@code namespace} in the returned {@link DurableMetadata}.
     *
     * @param namespace  the namespace this encoder owns; must not be {@code null}
     * @param type       the context value type this encoder handles; must not be {@code null}
     * @param envelopeFn the function that transforms the live value into the serialisable envelope;
     *                   must not be {@code null}
     * @param <T>        the context value type
     * @param <E>        the envelope type
     * @return the encoder; never {@code null}
     */
    public static <T extends ContextValue, E> DurableContextMetadataEncoder<T> jsonEncoder(
            Class<T> type, String namespace, Function<T, E> envelopeFn) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(envelopeFn, "envelopeFn must not be null");
        return new JsonEncoder<>(type, namespace, envelopeFn);
    }

    // --- JSON decoder ---

    /**
     * Returns a decoder that reads the {@code namespace} body, deserialises it into
     * {@code envelopeType}, and rebuilds the live value via {@code restoreFn}.
     *
     * <p>If the namespace is absent, {@link ContextDecodeResult#empty()} is returned. If the body
     * cannot be mapped to {@code envelopeType} or {@code restoreFn} throws, a
     * {@link ContextDecodeResult#failure(List)} with a descriptive warning is returned.
     *
     * @param type         the context value type this decoder produces; must not be {@code null}
     * @param namespace    the namespace this decoder reads; must not be {@code null}
     * @param envelopeType the class to map the namespace body into; must not be {@code null}
     * @param restoreFn    the function that rebuilds the live value from the envelope; must not be
     *                     {@code null}
     * @param <T>          the context value type
     * @param <E>          the envelope type
     * @return the decoder; never {@code null}
     */
    public static <T extends ContextValue, E> DurableContextMetadataDecoder<T> jsonDecoder(
            Class<T> type, String namespace, Class<E> envelopeType, Function<E, T> restoreFn) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(envelopeType, "envelopeType must not be null");
        Objects.requireNonNull(restoreFn, "restoreFn must not be null");
        return new JsonDecoder<>(type, namespace, envelopeType, restoreFn);
    }

    // --- Implementations ---

    /** JSON encoder implementation. */
    private static final class JsonEncoder<T extends ContextValue, E> implements DurableContextMetadataEncoder<T> {

        private final Class<T> type;
        private final String namespace;
        private final Function<T, E> envelopeFn;

        JsonEncoder(Class<T> type, String namespace, Function<T, E> envelopeFn) {
            this.type = type;
            this.namespace = namespace;
            this.envelopeFn = envelopeFn;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public DurableMetadata encode(T value, DurableEncodeContext context) {
            E envelope = envelopeFn.apply(value);
            return DurableMetadata.of(namespace, JsonObject.mapFrom(envelope));
        }
    }

    /** JSON decoder implementation. */
    private static final class JsonDecoder<T extends ContextValue, E> implements DurableContextMetadataDecoder<T> {

        private final Class<T> type;
        private final String namespace;
        private final Class<E> envelopeType;
        private final Function<E, T> restoreFn;

        JsonDecoder(Class<T> type, String namespace, Class<E> envelopeType, Function<E, T> restoreFn) {
            this.type = type;
            this.namespace = namespace;
            this.envelopeType = envelopeType;
            this.restoreFn = restoreFn;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public ContextDecodeResult<T> decode(DurableMetadata metadata, DurableDecodeContext context) {
            Optional<JsonObject> body = metadata.body(namespace);
            if (body.isEmpty()) {
                return ContextDecodeResult.empty();
            }
            try {
                E envelope = body.get().mapTo(envelopeType);
                return ContextDecodeResult.of(restoreFn.apply(envelope));
            } catch (RuntimeException ex) {
                String reason = "Failed to decode JSON envelope: " + ex.getMessage();
                return ContextDecodeResult.failure(
                        List.of(new ContextDecodeWarning(namespace, body.get().encode(), reason)));
            }
        }
    }
}
