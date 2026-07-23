// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableMetadata.MergePolicy;
import dev.vertique.core.context.DurablePropagationMetadata;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates durable context metadata propagation across persistent boundaries such as Kafka
 * headers and outbox message metadata.
 *
 * <p>Provides five operations:
 * <ul>
 *   <li>{@link #capture(String)} — iterates registered durable encoders and captures currently
 *       bound context values into a {@link DurableMetadata} document suitable for the native
 *       carrier.
 *   <li>{@link #mergeCaptured(DurableMetadata, String)} — merges captured metadata into
 *       caller-provided metadata, enforcing namespace collision rules.
 *   <li>{@link #bindFrom(DurableMetadata, String)} — decodes metadata from the native carrier and
 *       installs typed bindings plus raw {@link DurablePropagationMetadata} into
 *       {@link ContextHolder}.
 *   <li>{@link #decodeToDispatchContext(DurableMetadata, String)} — decodes metadata into an
 *       FQCN-keyed dispatch-context map without touching the holder.
 *   <li>{@link #sanitizeInboundCarrier(DurableMetadata)} — strips authenticated-only namespaces
 *       (ADR-0147) from a sender-supplied explicit carrier before it is bound.
 * </ul>
 *
 * <p>Decode failures are recoverable: a WARN is logged and the affected decoder's type is simply
 * not bound. Other decoders proceed normally (FR-CTX-118, FR-CTX-156).
 *
 * <p>Durable producers MUST use {@link #mergeCaptured} and durable consumers MUST use
 * {@link #bindFrom}. Direct encoder/decoder calls bypass collision detection and atomic binding.
 */
@Singleton
public final class DurableContextPropagator {

    private static final Logger log = LoggerFactory.getLogger(DurableContextPropagator.class);

    private final DurableContextMetadataRegistry registry;
    private final ContextHolder holder;
    private final ContextScopeBinder binder;
    private final WarningThrottle warningThrottle = new WarningThrottle();

    /**
     * Constructs the propagator backed by the given registry, holder, and binder.
     *
     * @param registry the durable context metadata registry; must not be {@code null}
     * @param holder   the context holder used to read and bind context values; must not be
     *                 {@code null}
     * @param binder   the scope binder used to install multiple context values atomically; must not
     *                 be {@code null}
     */
    @Inject
    public DurableContextPropagator(
            DurableContextMetadataRegistry registry, ContextHolder holder, ContextScopeBinder binder) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
        this.binder = Objects.requireNonNull(binder, "binder must not be null");
    }

    /**
     * Captures the currently bound context values by invoking registered durable encoders. Encoders
     * whose type is not currently bound are skipped. Returns a {@link DurableMetadata} document
     * suitable for the boundary's native carrier.
     *
     * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
     * @return the captured metadata document; never {@code null}
     * @throws IllegalStateException if any encoder returns a non-empty document whose namespaces
     *                               differ from exactly {@code Set.of(encoder.namespace())}
     *                               (FR-CTX-120); an encoder returning {@link DurableMetadata#empty()}
     *                               is skipped rather than treated as a violation
     */
    public DurableMetadata capture(String boundary) {
        return capture(boundary, Optional.empty(), Optional.empty());
    }

    /**
     * Captures the currently bound context values, threading the given durable row-carrier binding
     * (F5 row binding, PRD identity-002) and, optionally, an intended fire-time (F5 doomed-expiry
     * detection, PRD identity-002 §14.6 A9) into the {@link DurableEncodeContext} each encoder
     * receives.
     *
     * @param boundary the durable boundary identifier (e.g., {@code "kafka"}, {@code "outbox"})
     * @param carrier  the durable row-carrier binding to place on the encode context, or
     *                 {@link Optional#empty()} when the boundary carries no carrier identity; must not
     *                 be {@code null}
     * @param fireTime the intended dispatch/fire time to place on the encode context, or
     *                 {@link Optional#empty()} when the boundary carries no fixed fire time; must not
     *                 be {@code null}
     * @return the captured metadata document; never {@code null}
     * @throws IllegalStateException if any encoder returns a non-empty document whose namespaces
     *                               differ from exactly {@code Set.of(encoder.namespace())}
     *                               (FR-CTX-120); an encoder returning {@link DurableMetadata#empty()}
     *                               is skipped rather than treated as a violation
     * @throws dev.vertique.core.exception.DurableEncodeRejectedException if any encoder rejects the
     *                               entire capture operation (see
     *                               {@link DurableContextMetadataEncoder#encode})
     */
    private DurableMetadata capture(
            String boundary, Optional<DurableCarrierDescriptor> carrier, Optional<Instant> fireTime) {
        DurableMetadata result = DurableMetadata.empty();
        DurableEncodeContext ctx = new DurableEncodeContext(boundary, carrier, fireTime);
        for (DurableContextMetadataEncoder<?> encoder : registry.encoders()) {
            result = captureOne(encoder, ctx, result);
        }
        return result;
    }

    /**
     * Merges captured durable metadata into caller-provided metadata using the namespace collision
     * rules:
     * <ul>
     *   <li>If a captured namespace already appears in {@code callerContext} AND the encoder's type
     *       is currently bound → {@link IllegalStateException} (FR-CTX-153).
     *   <li>If a captured namespace already appears in {@code callerContext} but the encoder's type
     *       is NOT currently bound → the caller namespace passes through unchanged (FR-CTX-154).
     *   <li>Otherwise the captured namespace is added to the merged result.
     * </ul>
     *
     * @param callerContext the caller-provided metadata to merge into; must not be {@code null}
     * @param boundary      the durable boundary identifier
     * @return the merged metadata document; never {@code null}
     * @throws IllegalStateException if a namespace collision is detected and the encoder's type is
     *                               currently bound (FR-CTX-153)
     */
    public DurableMetadata mergeCaptured(DurableMetadata callerContext, String boundary) {
        return mergeCaptured(callerContext, boundary, Optional.empty(), Optional.empty());
    }

    /**
     * Carrier-aware {@link #mergeCaptured(DurableMetadata, String)}: identical merge semantics, but
     * threads the supplied durable row-carrier binding into the {@link DurableEncodeContext} each
     * encoder receives so a boundary that owns a persisted row (e.g. a delayed-job row) can bind the
     * captured durable envelope to that exact row (F5 row binding, PRD identity-002). A durable
     * envelope signed for one row's carrier then cannot be transplanted onto another.
     *
     * @param callerContext the caller-provided metadata to merge into; must not be {@code null}
     * @param boundary      the durable boundary identifier
     * @param carrier       the durable row-carrier binding to place on the encode context; must not be
     *                      {@code null}
     * @return the merged metadata document; never {@code null}
     * @throws IllegalStateException if a namespace collision is detected and the encoder's type is
     *                               currently bound (FR-CTX-153)
     */
    public DurableMetadata mergeCaptured(
            DurableMetadata callerContext, String boundary, DurableCarrierDescriptor carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        return mergeCaptured(callerContext, boundary, Optional.of(carrier), Optional.empty());
    }

    /**
     * Fire-time-aware {@link #mergeCaptured(DurableMetadata, String, DurableCarrierDescriptor)}:
     * identical merge semantics, but additionally threads the supplied intended dispatch/fire time
     * into the {@link DurableEncodeContext} each encoder receives, so an encoder can detect that
     * content it is about to sign would already be stale by the time the row fires (F5 doomed-expiry
     * detection, PRD identity-002 §14.6 A9). Only the delayed-job schedule path populates this today —
     * cron, outbox, and Kafka have no fixed fire time and keep using the carrier-only overload.
     *
     * @param callerContext the caller-provided metadata to merge into; must not be {@code null}
     * @param boundary      the durable boundary identifier
     * @param carrier       the durable row-carrier binding to place on the encode context; must not be
     *                      {@code null}
     * @param fireTime      the intended dispatch/fire time of the row being enqueued; must not be
     *                      {@code null}
     * @return the merged metadata document; never {@code null}
     * @throws IllegalStateException if a namespace collision is detected and the encoder's type is
     *                               currently bound (FR-CTX-153)
     * @throws dev.vertique.core.exception.DurableEncodeRejectedException if any encoder rejects the
     *                               entire capture operation because the row is doomed to expire
     *                               before {@code fireTime} (see
     *                               {@link DurableContextMetadataEncoder#encode})
     */
    public DurableMetadata mergeCaptured(
            DurableMetadata callerContext, String boundary, DurableCarrierDescriptor carrier, Instant fireTime) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(fireTime, "fireTime must not be null");
        return mergeCaptured(callerContext, boundary, Optional.of(carrier), Optional.of(fireTime));
    }

    private DurableMetadata mergeCaptured(
            DurableMetadata callerContext,
            String boundary,
            Optional<DurableCarrierDescriptor> carrier,
            Optional<Instant> fireTime) {
        Objects.requireNonNull(callerContext, "callerContext must not be null");
        DurableMetadata captured = capture(boundary, carrier, fireTime);
        DurableMetadata merged = callerContext;
        for (String ns : captured.namespaces()) {
            if (merged.has(ns)) {
                // Find the encoder that owns this namespace to check whether its type is bound
                DurableContextMetadataEncoder<?> owningEncoder = findEncoderForNamespace(ns);
                if (owningEncoder != null
                        && holder.current(owningEncoder.type()).isPresent()) {
                    throw new IllegalStateException(
                            "Durable context metadata namespace collision: namespace '%s' is already present in caller metadata and encoder %s has type %s currently bound (FR-CTX-153)"
                                    .formatted(
                                            ns,
                                            owningEncoder.getClass().getName(),
                                            owningEncoder.type().getName()));
                }
                // Caller namespace wins (FR-CTX-154) — do not overwrite
            } else {
                merged = merged.with(ns, captured.body(ns).orElseThrow());
            }
        }
        return merged;
    }

    /**
     * Decodes metadata from the durable boundary's native carrier and authoritatively installs
     * typed bindings plus raw {@link DurablePropagationMetadata} into {@link ContextHolder}.
     *
     * <p>Always binds {@link DurablePropagationMetadata} first (FR-CTX-141, FR-CTX-155). Then
     * invokes each registered decoder with the full {@code metadata} document. Successfully decoded
     * values are added to the binding map. Decode failures are logged at WARN level and do not
     * prevent other decoders from binding their values (FR-CTX-156).
     *
     * <p><b>Authoritative semantics (FR-CTX-178).</b> The metadata represents the full durable
     * snapshot taken at the boundary's producer site. Every type registered with the
     * {@link DurableContextMetadataRegistry} that is <i>not</i> decoded from this metadata is
     * <i>cleared</i> for the scope's lifetime, so a subsequent {@link #mergeCaptured} inside the
     * scope cannot accidentally capture ambient values that did not cross the boundary. Without
     * this, a workflow branch resumed inside a service handler with unrelated ambient context would
     * leak that ambient context into any timer it creates.
     *
     * <p>The returned scope unwinds all bindings (typed + raw metadata) AND re-installs every
     * cleared value when closed (FR-CTX-157).
     *
     * <p>{@code bindFrom} is the durable-consumer API for installing values into
     * {@link ContextHolder}. Per FR-CTX-157b it MUST be called on a duplicated Vert.x context;
     * callers that cannot guarantee that — e.g. transport relays whose callback runs on a
     * non-duplicated context such as the outbox-service destination handler — MUST use
     * {@link #decodeToDispatchContext(DurableMetadata, String)} instead, which materialises the
     * decoded map into the outgoing service envelope's caller-overrides without touching the holder.
     *
     * <p>The method returns a no-op scope with a throttled WARN only when there is no Vert.x
     * context at all — plain-JUnit unit tests of dispatcher classes that never enter the
     * framework's dispatch loop. On a non-duplicated Vert.x context the call is treated as a
     * misconfigured caller and the holder's write guard's {@link IllegalStateException} propagates
     * — silently no-oping there would mask context loss if any production assumption ever changed.
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must
     *                 not be {@code null}
     * @param boundary the durable boundary identifier
     * @return a scope that unwinds all installed bindings when closed; never {@code null}
     */
    public ContextHolder.Scope bindFrom(DurableMetadata metadata, String boundary) {
        return bindFrom(metadata, boundary, Optional.empty());
    }

    /**
     * Carrier-aware {@link #bindFrom(DurableMetadata, String)}: identical binding semantics, but
     * threads the supplied durable row-carrier binding into the {@link DurableDecodeContext} each
     * decoder receives so a boundary that owns a persisted row can verify the decoded durable envelope
     * was signed for that exact row (F5 row binding, PRD identity-002).
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must not
     *                 be {@code null}
     * @param boundary the durable boundary identifier
     * @param carrier  the durable row-carrier binding to place on the decode context; must not be
     *                 {@code null}
     * @return a scope that unwinds all installed bindings when closed; never {@code null}
     */
    public ContextHolder.Scope bindFrom(DurableMetadata metadata, String boundary, DurableCarrierDescriptor carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        return bindFrom(metadata, boundary, Optional.of(carrier));
    }

    private ContextHolder.Scope bindFrom(
            DurableMetadata metadata, String boundary, Optional<DurableCarrierDescriptor> carrier) {
        Objects.requireNonNull(metadata, "metadata must not be null");

        io.vertx.core.Context vertxCtx = io.vertx.core.Vertx.currentContext();
        if (vertxCtx == null) {
            warningThrottle.once(
                    "bindFrom-no-vertx-context|" + boundary,
                    k -> log.warn(
                            "DurableContextPropagator.bindFrom called outside a Vert.x-associated context for boundary '{}' — returning no-op scope; downstream consumers will not observe the durable bindings. This is expected in plain-JUnit unit tests; in production it usually indicates the caller is running on a non-Vert.x thread (further occurrences for this boundary suppressed).",
                            boundary));
            return () -> {};
        }
        Map<Class<?>, Object> bindings = new HashMap<>();

        // Always bind DurablePropagationMetadata (FR-CTX-141)
        bindings.put(DurablePropagationMetadata.class, new DurablePropagationMetadata(boundary, metadata));

        // Invoke each decoder with the full DurableMetadata document (FR-CTX-116)
        DurableDecodeContext ctx = new DurableDecodeContext(boundary, carrier);
        for (DurableContextMetadataDecoder<?> decoder : registry.decoders()) {
            decodeOne(decoder, metadata, ctx, bindings);
        }

        // Authoritative: every registered durable type not bound by this call is cleared so
        // subsequent capture/mergeCaptured inside the scope cannot leak ambient context that did
        // not cross the boundary. DurablePropagationMetadata is always bound, so the substrate
        // never needs to clear it.
        Set<Class<?>> clearTypes = new HashSet<>(registry.registeredTypes());
        clearTypes.removeAll(bindings.keySet());

        return binder.bindAllAuthoritative(bindings, clearTypes);
    }

    /**
     * Decodes the durable native carrier into an FQCN-keyed dispatch-context map without touching
     * the {@link ContextHolder}. Intended for transport boundaries that are <b>not</b> running on
     * a duplicated Vert.x context and therefore cannot install values into the holder — most
     * notably the outbox-service relay and the delayed-job poller, whose callbacks land on the
     * verticle's deployment context. (Kafka record dispatch runs on a per-record duplicated
     * context — see {@code KafkaReadStreamImpl.run()} in vertx-kafka-client 5.x — and uses
     * {@link #bindFrom(DurableMetadata, String)} directly; do not route Kafka through this method.)
     * Callers merge the returned map into {@code DispatchEnvelopeBuilder.build(...)} caller
     * overrides so the decoded values cross the event-bus boundary inside the envelope's
     * {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext() dispatch-context map}.
     * Once the receiving {@code ServiceMethodInvoker} dispatches the envelope on its duplicated
     * event-bus context, the values are installed into the holder there.
     *
     * <p>The returned map always contains {@link DurablePropagationMetadata} keyed by its FQCN
     * (FR-CTX-141). Each registered decoder is then invoked with the full {@code metadata} document;
     * successful decodes contribute one entry to the map keyed by the decoder's value type FQCN.
     * Decoder exceptions are logged at WARN level and do not block other decoders (FR-CTX-156).
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must
     *                 not be {@code null}
     * @param boundary the durable boundary identifier
     * @return the decoded dispatch-context map; never {@code null}, always contains the boundary's
     *         raw metadata under {@link DurablePropagationMetadata}
     */
    public Map<String, Object> decodeToDispatchContext(DurableMetadata metadata, String boundary) {
        return decodeToDispatchContext(metadata, boundary, Optional.empty());
    }

    /**
     * Carrier-aware {@link #decodeToDispatchContext(DurableMetadata, String)}: identical decode
     * semantics, but threads the supplied durable row-carrier binding into the
     * {@link DurableDecodeContext} each decoder receives so a boundary that owns a persisted row (e.g.
     * a delayed-job row) can verify the decoded durable envelope was signed for that exact row (F5 row
     * binding, PRD identity-002). A durable envelope signed for a different row's carrier then fails
     * closed at the receiving decoder.
     *
     * @param metadata the full durable metadata document from the boundary's native carrier; must not
     *                 be {@code null}
     * @param boundary the durable boundary identifier
     * @param carrier  the durable row-carrier binding to place on the decode context; must not be
     *                 {@code null}
     * @return the decoded dispatch-context map; never {@code null}, always contains the boundary's raw
     *         metadata under {@link DurablePropagationMetadata}
     */
    public Map<String, Object> decodeToDispatchContext(
            DurableMetadata metadata, String boundary, DurableCarrierDescriptor carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        return decodeToDispatchContext(metadata, boundary, Optional.of(carrier));
    }

    private Map<String, Object> decodeToDispatchContext(
            DurableMetadata metadata, String boundary, Optional<DurableCarrierDescriptor> carrier) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Map<Class<?>, Object> bindings = new HashMap<>();
        bindings.put(DurablePropagationMetadata.class, new DurablePropagationMetadata(boundary, metadata));
        DurableDecodeContext ctx = new DurableDecodeContext(boundary, carrier);
        for (DurableContextMetadataDecoder<?> decoder : registry.decoders()) {
            decodeOne(decoder, metadata, ctx, bindings);
        }
        Map<String, Object> result = new HashMap<>(bindings.size());
        for (Map.Entry<Class<?>, Object> e : bindings.entrySet()) {
            result.put(e.getKey().getName(), e.getValue());
        }
        return Map.copyOf(result);
    }

    /**
     * Strips every namespace whose registered {@link DurableContextMetadataDecoder} declares
     * {@link DurableContextMetadataDecoder#acceptsExplicitCarrier()} {@code == false} from a
     * sender-supplied explicit carrier (ADR-0147).
     *
     * <p>A namespace with no registered decoder passes through unchanged — it is inert, since
     * {@link #bindFrom} and the drive-time binder only bind namespaces owned by a registered decoder.
     * When at least one namespace is stripped, a single throttle-free WARN is logged naming the
     * stripped namespace(s) only — never their bodies. When nothing is stripped, this method returns
     * the same {@code carrier} instance (no defensive copy on the common path).
     *
     * @param carrier the sender-supplied explicit carrier to sanitize; must not be {@code null}
     * @return a document with every authenticated-only namespace removed; the same instance as
     *         {@code carrier} when no namespace was stripped
     */
    public DurableMetadata sanitizeInboundCarrier(DurableMetadata carrier) {
        Objects.requireNonNull(carrier, "carrier must not be null");
        Set<String> toStrip = new HashSet<>();
        for (DurableContextMetadataDecoder<?> decoder : registry.decoders()) {
            if (!decoder.acceptsExplicitCarrier() && carrier.has(decoder.namespace())) {
                toStrip.add(decoder.namespace());
            }
        }
        if (toStrip.isEmpty()) {
            return carrier;
        }
        DurableMetadata sanitized = carrier;
        for (String namespace : toStrip) {
            sanitized = sanitized.without(namespace);
        }
        log.warn(
                "Stripped authenticated-only namespace(s) {} from an inbound explicit carrier — these namespaces must never be accepted from a sender-supplied carrier (ADR-0147)",
                toStrip);
        return sanitized;
    }

    // --- Internal helpers ---

    /**
     * Captures a single encoder's current context value and merges it into the accumulator.
     * If the type is not currently bound, the encoder is skipped. An encoder that returns
     * {@link DurableMetadata#empty()} — its "nothing legitimate to encode" signal — is also skipped:
     * no merge, no exception. Otherwise, validates that the returned document's namespace set
     * equals exactly {@code Set.of(encoder.namespace())}.
     *
     * @param encoder     the encoder to invoke
     * @param ctx         the encode context
     * @param accumulator the current accumulated metadata document
     * @param <T>         the context value type
     * @return the updated accumulator with the encoder's namespace merged in, or unchanged if the
     *         type was not bound or the encoder signalled nothing to encode
     * @throws IllegalStateException if the encoder returns a non-empty document with the wrong
     *                               namespace set
     */
    private <T extends ContextValue> DurableMetadata captureOne(
            DurableContextMetadataEncoder<T> encoder, DurableEncodeContext ctx, DurableMetadata accumulator) {
        Optional<T> current = holder.current(encoder.type());
        if (current.isEmpty()) {
            return accumulator;
        }
        DurableMetadata single = encoder.encode(current.get(), ctx);
        if (single.isEmpty()) {
            // Encoder contract: an empty document signals "nothing legitimate to encode for the
            // currently bound value" — skip merging this namespace rather than treating it as an
            // FR-CTX-120 violation.
            return accumulator;
        }
        Set<String> actualNamespaces = single.namespaces();
        Set<String> expectedNamespaces = Set.of(encoder.namespace());
        if (!actualNamespaces.equals(expectedNamespaces)) {
            throw new IllegalStateException(
                    "DurableContextMetadataEncoder '%s' returned a document with namespaces %s but declared namespace '%s' — encoder must return exactly one namespace matching namespace() (FR-CTX-120)"
                            .formatted(encoder.getClass().getName(), actualNamespaces, encoder.namespace()));
        }
        return accumulator.merge(single, MergePolicy.FAIL_ON_CONFLICT);
    }

    /**
     * Decodes a single decoder's namespace from the full metadata document and adds a successful
     * result to the bindings map. Failures are logged at WARN and do not affect other decoders
     * (FR-CTX-156).
     *
     * @param decoder  the decoder to invoke
     * @param metadata the full durable metadata document
     * @param ctx      the decode context
     * @param bindings the binding map to populate with a successful decode
     * @param <T>      the context value type
     */
    private <T extends ContextValue> void decodeOne(
            DurableContextMetadataDecoder<T> decoder,
            DurableMetadata metadata,
            DurableDecodeContext ctx,
            Map<Class<?>, Object> bindings) {
        ContextDecodeResult<T> result;
        try {
            result = decoder.decode(metadata, ctx);
        } catch (Exception e) {
            // FR-CTX-132: throttle WARN per (boundary, decoder class, reason). A misconfigured
            // decoder would otherwise flood logs on every record.
            warningThrottle.once(
                    "throw|" + ctx.boundary() + "|" + decoder.getClass().getName(),
                    k -> log.warn(
                            "Durable context decoder {} threw an exception for boundary '{}'; type {} will not be bound (further occurrences suppressed)",
                            decoder.getClass().getName(),
                            ctx.boundary(),
                            decoder.type().getName(),
                            e));
            return;
        }
        if (result == null) {
            warningThrottle.once(
                    "null|" + ctx.boundary() + "|" + decoder.getClass().getName(),
                    k -> log.warn(
                            "Durable context decoder {} returned null for boundary '{}' — null is an SPI contract violation; type {} will not be bound (further occurrences suppressed)",
                            decoder.getClass().getName(),
                            ctx.boundary(),
                            decoder.type().getName()));
            return;
        }
        if (!result.warnings().isEmpty()) {
            for (ContextDecodeWarning warning : result.warnings()) {
                warningThrottle.once(
                        "warn|" + ctx.boundary() + "|" + decoder.getClass().getName() + "|" + warning.reason(),
                        k -> log.warn(
                                "Durable context decoder {} warning for boundary '{}' — key='{}', value='{}', reason='{}' (further occurrences with same reason suppressed)",
                                decoder.getClass().getName(),
                                ctx.boundary(),
                                warning.key(),
                                warning.value(),
                                warning.reason()));
            }
        }
        result.value().ifPresent(value -> bindings.put(decoder.type(), value));
    }

    /**
     * Finds the encoder whose declared namespace matches the given namespace.
     *
     * @param namespace the namespace to look up
     * @return the owning encoder, or {@code null} if not found
     */
    private DurableContextMetadataEncoder<?> findEncoderForNamespace(String namespace) {
        for (DurableContextMetadataEncoder<?> encoder : registry.encoders()) {
            if (encoder.namespace().equals(namespace)) {
                return encoder;
            }
        }
        return null;
    }
}
