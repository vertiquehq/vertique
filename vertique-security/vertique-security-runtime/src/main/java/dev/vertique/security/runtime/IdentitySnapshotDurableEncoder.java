// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.exception.DurableEncodeRejectedException;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotIntegrity;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Durable metadata encoder for {@link IdentitySnapshotContext} (PRD-ID-002 §14.3 "Durable
 * carriage", §14.6 amendment A9 — schema v2 content/envelope split + carrier binding).
 *
 * <p>Resolves the {@link IdentitySnapshotContent} to sign — see {@link #resolveContent(IdentitySnapshotContext)}
 * — assembles the schema-v2 {@link IdentitySnapshot} envelope around it (the {@link SnapshotCarrierBinding}
 * from {@link DurableEncodeContext#carrier()}, {@code issuedAt} from the codec's clock, and
 * {@code expiresAt} from the codec's capturedAt-anchored snapshot-lifetime budget), signs it via the
 * injected {@link IdentitySnapshotCodec} (which computes the integrity tag under the codec's active
 * signing key), and writes the resulting bytes, base64-encoded, under the {@value #NAMESPACE} namespace
 * alongside the {@code present} companion flag consumers can check without fully decoding the snapshot.
 *
 * <p><strong>Re-encode across a durable boundary (F1).</strong> This encoder is invoked for
 * {@link IdentitySnapshotContext} in whichever of its three mutually-exclusive states is currently
 * bound — not only the producer-side captured-content state. A durable-timer or workflow-recovery
 * re-enqueue binds a <em>receive-side</em> context (a {@link IdentitySnapshotContext#verified verified}
 * snapshot decoded off the original row, or a present-but-{@link IdentitySnapshotContext#unverifiable
 * unverifiable} degradation marker) and then performs an ordinary enqueue, which captures whatever is
 * currently bound and re-encodes it for the new row's carrier:
 * <ul>
 *   <li><strong>Verified</strong> — the content of the bound {@link IdentitySnapshot} is re-signed into
 *       a fresh envelope for the new carrier ({@link DurableEncodeContext#carrier()}), with a fresh
 *       {@code issuedAt} but the <em>same, immutable</em> {@link IdentitySnapshotContent#capturedAt()} —
 *       so a chained re-encode cannot renew authority past what the original capture earned (this is
 *       also why {@link IdentitySnapshotCodec#envelopeExpiry} anchors on {@code capturedAt}, not
 *       {@code issuedAt}; F6).</li>
 *   <li><strong>Present-but-unverifiable</strong> — there is no legitimate content to carry forward, so
 *       this encoder emits <em>no</em> {@value #NAMESPACE} namespace at all: {@link #encode} returns
 *       {@link DurableMetadata#empty()}, which {@code DurableContextPropagator}'s capture path treats as
 *       "skip this namespace" rather than a contract violation or a thrown exception.</li>
 * </ul>
 *
 * <p><strong>Pre-boundary-wiring default carrier.</strong> Until the job/outbox/workflow boundaries
 * supply a real {@link DurableCarrierDescriptor} on the encode context, {@link DurableEncodeContext#carrier()}
 * is empty and this encoder signs a fixed, boundary-independent {@code "unbound"} sentinel carrier. The
 * sentinel is deliberately <em>not</em> keyed on the boundary string: a producer and its relay can carry
 * different {@link dev.vertique.core.context.DispatchBoundary} labels (e.g. {@code OUTBOX} on merge,
 * {@code OUTBOX_SERVICE} on decode), so a boundary-keyed sentinel would spuriously mismatch. The paired
 * decoder builds the identical sentinel, so the F5 carrier check is a no-op until real per-row carriers
 * arrive with the boundary-wiring commits, and enforced once they flow.
 *
 * <p><strong>Doomed-expiry detection (F5, PRD-ID-002 §14.6 A9).</strong> When
 * {@link DurableEncodeContext#fireTime()} is present — populated only by the delayed-job schedule path
 * today — and the freshly-computed signed {@code expiresAt} would not be after that fire time, the
 * envelope this encoder is about to sign would already be expired by the time the durable row fires. The
 * configured {@link #policy} decides how {@link #encode} responds instead of silently persisting a
 * doomed row: {@link IdentitySnapshotDegradationPolicy#FAIL} throws
 * {@link DurableEncodeRejectedException}, which propagates synchronously out of the enclosing
 * {@code DurableContextPropagator#mergeCaptured} call so a producer (e.g. {@code DelayedJobService})
 * surfaces it as a rejected enqueue; {@link IdentitySnapshotDegradationPolicy#CONTINUE_WITHOUT_IDENTITY} logs a
 * WARN and returns {@link DurableMetadata#empty()} — the same "nothing legitimate to encode" signal the
 * present-but-unverifiable re-encode path (F1) already uses — so the row persists without the
 * {@value #NAMESPACE} namespace rather than with a snapshot guaranteed to fail closed at first decode.
 * When {@link DurableEncodeContext#fireTime()} is absent (cron, outbox, Kafka, and any boundary that
 * predates this check), no doomed-window check runs at all — current behavior is preserved.
 */
@Slf4j
@Singleton
public final class IdentitySnapshotDurableEncoder implements DurableContextMetadataEncoder<IdentitySnapshotContext> {

    /** The single namespace this encoder writes and the paired decoder reads. */
    static final String NAMESPACE = "identity-snapshot";

    /** Carrier id used for the pre-boundary-wiring sentinel binding (see class javadoc). */
    static final String UNBOUND_CARRIER_ID = "unbound";

    /**
     * Fixed, boundary-independent sentinel target signed (and expected) when no real carrier is
     * supplied — see class javadoc for why it must not depend on the boundary string.
     */
    static final DurableTarget UNBOUND_TARGET = new DurableTarget("unbound", "unbound", Optional.empty());

    /** Default MAC algorithm named on the to-be-signed envelope's integrity placeholder; the codec re-signs. */
    private static final String DEFAULT_ALGORITHM = "HmacSHA256";

    /** Placeholder key id on the to-be-signed envelope; the codec substitutes the active key id at sign time. */
    private static final String PENDING_KEY_ID = "pending";

    /** Sentinel integrity tag on the to-be-signed envelope; the codec substitutes the authoritative tag. */
    private static final String UNSIGNED_TAG = "unsigned";

    /** JSON property carrying the base64-encoded, signed snapshot bytes. */
    private static final String SNAPSHOT_PROPERTY = "snapshot";

    /** JSON property flagging that a snapshot is present, checkable without decoding it. */
    private static final String PRESENT_PROPERTY = "present";

    private final IdentitySnapshotCodec codec;
    private final IdentitySnapshotDegradationPolicy policy;

    /**
     * Constructs an encoder backed by the given codec, applying the given policy to the F5
     * doomed-expiry degrade path (see class javadoc).
     *
     * @param codec  the codec used to sign and serialize the assembled snapshot; must not be
     *               {@code null}
     * @param policy the {@code identity.snapshot.onDegradation} policy applied when the signed
     *               envelope would already be expired by {@link DurableEncodeContext#fireTime()};
     *               must not be {@code null}
     */
    @Inject
    public IdentitySnapshotDurableEncoder(IdentitySnapshotCodec codec, IdentitySnapshotDegradationPolicy policy) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Convenience constructor defaulting {@link #policy} to
     * {@link IdentitySnapshotDegradationPolicy#FAIL} — mirrors {@link IdentitySnapshotConfig}'s own
     * fail-closed default when {@code onDegradation} is omitted from config. The production Dagger
     * wiring uses the two-arg constructor with the config-resolved policy; this keeps unit fixtures
     * that do not exercise the F5 doomed-expiry degrade path concise.
     *
     * @param codec the codec used to sign and serialize the assembled snapshot; must not be
     *              {@code null}
     */
    public IdentitySnapshotDurableEncoder(IdentitySnapshotCodec codec) {
        this(codec, IdentitySnapshotDegradationPolicy.FAIL);
    }

    @Override
    public Class<IdentitySnapshotContext> type() {
        return IdentitySnapshotContext.class;
    }

    @Override
    public String namespace() {
        return NAMESPACE;
    }

    @Override
    public DurableMetadata encode(IdentitySnapshotContext value, DurableEncodeContext context) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(context, "context");
        Optional<IdentitySnapshotContent> content = resolveContent(value);
        if (content.isEmpty()) {
            // Present-but-unverifiable receive-side context: nothing legitimate to carry forward.
            // Emit no identity-snapshot namespace at all rather than signing an empty/garbage
            // snapshot or throwing — DurableContextPropagator's capture path treats an empty
            // document as "skip this namespace" (F1).
            return DurableMetadata.empty();
        }

        Instant expiresAt = codec.envelopeExpiry(content.get().capturedAt());
        Optional<DurableMetadata> doomedResult = degradeIfDoomed(expiresAt, context);
        if (doomedResult.isPresent()) {
            return doomedResult.orElseThrow();
        }

        SnapshotCarrierBinding carrier = resolveCarrier(context);
        Instant issuedAt = codec.now();
        IdentitySnapshot envelope = new IdentitySnapshot(
                IdentitySnapshotCodec.SUPPORTED_SCHEMA_VERSION,
                content.get(),
                carrier,
                issuedAt,
                expiresAt,
                new SnapshotIntegrity(DEFAULT_ALGORITHM, PENDING_KEY_ID, UNSIGNED_TAG));

        byte[] signed = codec.encode(envelope);
        String base64 = Base64.getEncoder().encodeToString(signed);
        JsonObject body = new JsonObject().put(SNAPSHOT_PROPERTY, base64).put(PRESENT_PROPERTY, true);
        return DurableMetadata.of(NAMESPACE, body);
    }

    /**
     * Detects the F5 doomed-expiry window and applies the configured {@link #policy} when it is hit
     * (see class javadoc): when {@link DurableEncodeContext#fireTime()} is present and {@code expiresAt}
     * would not be after it, the envelope this encoder is about to sign would already be expired by
     * the time the durable row fires.
     *
     * @param expiresAt the signed expiry this encoder is about to sign into the envelope
     * @param context   the encode context carrying the optional fire time
     * @return {@link Optional#empty()} when there is no doomed window (no fire time supplied, or the
     *         envelope survives past it) — the caller proceeds to sign normally; otherwise, under
     *         {@link IdentitySnapshotDegradationPolicy#CONTINUE_WITHOUT_IDENTITY}, a present
     *         {@link DurableMetadata#empty()} the caller returns directly instead of signing
     * @throws DurableEncodeRejectedException if the window is doomed and {@link #policy} is
     *                                         {@link IdentitySnapshotDegradationPolicy#FAIL}
     */
    private Optional<DurableMetadata> degradeIfDoomed(Instant expiresAt, DurableEncodeContext context) {
        Optional<Instant> fireTime = context.fireTime();
        if (fireTime.isEmpty() || expiresAt.isAfter(fireTime.orElseThrow())) {
            return Optional.empty();
        }
        Instant fire = fireTime.orElseThrow();
        String detail = "identity snapshot signed expiry " + expiresAt + " is not after the scheduled fire time " + fire
                + " — this snapshot would already be expired when the durable row fires";
        if (policy == IdentitySnapshotDegradationPolicy.FAIL) {
            throw new DurableEncodeRejectedException(detail + " (onDegradation=FAIL, enqueue rejected)");
        }
        log.warn(
                "{} — proceeding without the '{}' namespace per onDegradation=CONTINUE_WITHOUT_IDENTITY (F5 doomed-expiry"
                        + " detection)",
                detail,
                NAMESPACE);
        return Optional.of(DurableMetadata.empty());
    }

    /**
     * Resolves the content to sign into the re-assembled envelope: the producer-side captured
     * content when {@code value} is in its captured-content state, or — for a receive-side
     * {@link IdentitySnapshotContext#verified verified} context — the content of the already-bound
     * snapshot, so a re-encode re-signs the <em>same</em> content for the new carrier, preserving its
     * immutable {@link IdentitySnapshotContent#capturedAt() capturedAt} (F1). A present-but-
     * {@link IdentitySnapshotContext#unverifiable unverifiable} receive-side context carries no
     * legitimate content to forward and resolves to {@link Optional#empty()}.
     *
     * @param value the currently bound context, in exactly one of its three mutually-exclusive states
     * @return the content to sign, or empty when {@code value} has nothing legitimate to encode
     */
    private static Optional<IdentitySnapshotContent> resolveContent(IdentitySnapshotContext value) {
        return value.content().or(() -> value.snapshot().map(IdentitySnapshot::content));
    }

    /**
     * Resolves the carrier binding to sign: the real {@link DurableCarrierDescriptor} the boundary
     * supplied on the encode context when present, otherwise the fixed, boundary-independent
     * {@code "unbound"} sentinel (see class javadoc).
     *
     * @param context the encode context carrying the optional boundary-supplied carrier
     * @return the carrier binding to sign into the envelope
     */
    private static SnapshotCarrierBinding resolveCarrier(DurableEncodeContext context) {
        return context.carrier()
                .map(descriptor -> new SnapshotCarrierBinding(descriptor.carrierId(), descriptor.target()))
                .orElseGet(() -> new SnapshotCarrierBinding(UNBOUND_CARRIER_ID, UNBOUND_TARGET));
    }
}
