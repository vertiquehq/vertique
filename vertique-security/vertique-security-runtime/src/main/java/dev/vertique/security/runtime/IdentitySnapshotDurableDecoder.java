// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.SnapshotDegradationReason;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable metadata decoder for {@link IdentitySnapshotContext} (PRD-ID-002 §14.3 "Durable
 * carriage", §14.6 amendment A9 — schema v2 verification + carrier binding).
 *
 * <p>Pairs with {@link IdentitySnapshotDurableEncoder}: reads the base64-encoded, signed snapshot
 * bytes from the {@value IdentitySnapshotDurableEncoder#NAMESPACE} namespace, delegates to the injected
 * {@link IdentitySnapshotCodec} to decode and verify the HMAC integrity tag <em>and</em> the freshness
 * envelope, then confirms the snapshot's signed {@link SnapshotCarrierBinding} matches the expected
 * carrier for the receiving dispatch (from {@link DurableDecodeContext#carrier()}, or the
 * pre-boundary-wiring {@code "unbound"} sentinel when the boundary supplies none). Decoding is
 * fail-closed and never throws, but it distinguishes <em>absent</em> from
 * <em>present-but-unverifiable</em> so a tampered, stale, or transplanted snapshot cannot silently
 * bypass the receive-side degradation gate:
 *
 * <ul>
 *   <li><strong>Absent</strong> — the namespace body is missing, or its {@code present} companion
 *       flag is not {@code true}: {@link ContextDecodeResult#empty()}. Nothing is bound and the
 *       reconstruction initializer falls to its no-snapshot path.
 *   <li><strong>Verified</strong> — the codec decodes and verifies the snapshot and the carrier
 *       matches: a {@link ContextDecodeResult} carrying a verified {@link IdentitySnapshotContext#verified}.
 *   <li><strong>Present-but-unverifiable</strong> — the {@code present} flag is {@code true} but the
 *       payload is a malformed base64/JSON body, fails the codec's integrity/freshness check
 *       ({@link IdentitySnapshotCodecException}), its signed carrier does not match the expected
 *       carrier, or its signed carrier is the pre-boundary-wiring
 *       {@value IdentitySnapshotDurableEncoder#UNBOUND_CARRIER_ID} sentinel (F3a fail-closed
 *       backstop, below): a {@link ContextDecodeResult} carrying an
 *       {@link IdentitySnapshotContext#unverifiable(SnapshotDegradationReason)} <em>bound value</em>
 *       (mapped from the codec's typed reason) <em>plus</em> a diagnostic {@link ContextDecodeWarning}.
 *       Binding the value — rather than dropping it as a value-less failure — is what routes the
 *       degradation to {@link IdentitySnapshotReconstructionInitializer}'s marker path so the
 *       non-droppable degradation event and the operator {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY}
 *       policy are enforced.
 * </ul>
 *
 * <p><strong>F3a fail-closed sentinel backstop.</strong> A boundary that has not yet been wired with a
 * real per-row {@link DurableCarrierDescriptor} (see {@link IdentitySnapshotDurableEncoder}'s
 * pre-boundary-wiring default carrier) signs the fixed {@code "unbound"} sentinel into every snapshot
 * it emits. Because the paired decoder builds the identical sentinel as its expected carrier when the
 * boundary supplies none, the ordinary carrier-match check ({@link #carrierMatches}) alone is a no-op
 * for such a boundary — every sentinel-signed snapshot "matches" every other sentinel-signed snapshot,
 * so a snapshot captured for one app-writable durable row could be copied onto another row of the same
 * unwired boundary and still reconstruct the original subject. This decoder therefore treats a signed
 * carrier that equals the sentinel as unconditionally unverifiable, regardless of what the expected
 * carrier resolves to: a sentinel-signed snapshot carries no real per-row binding, so it must never be
 * reconstructable. This makes every not-yet-wired boundary (outbox today; kafka and workflow-branch
 * until their own carrier-wiring lands) fail closed as a group, while a boundary that supplies a real
 * carrier continues to verify and reconstruct exactly as before.
 */
@Singleton
public final class IdentitySnapshotDurableDecoder implements DurableContextMetadataDecoder<IdentitySnapshotContext> {

    private final IdentitySnapshotCodec codec;

    /**
     * Constructs a decoder backed by the given codec.
     *
     * @param codec the codec used to decode and verify the wrapped snapshot; must not be
     *              {@code null}
     */
    @Inject
    public IdentitySnapshotDurableDecoder(IdentitySnapshotCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Class<IdentitySnapshotContext> type() {
        return IdentitySnapshotContext.class;
    }

    @Override
    public String namespace() {
        return IdentitySnapshotDurableEncoder.NAMESPACE;
    }

    @Override
    public ContextDecodeResult<IdentitySnapshotContext> decode(DurableMetadata metadata, DurableDecodeContext context) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(context, "context");
        DurableCarrierDescriptor expectedCarrier = expectedCarrier(context);
        return metadata.body(IdentitySnapshotDurableEncoder.NAMESPACE)
                .map(body -> decodeBody(body, expectedCarrier))
                .orElseGet(ContextDecodeResult::empty);
    }

    /**
     * Decodes, verifies, and carrier-checks the {@code identity-snapshot} namespace body, converting
     * any decode, verification, freshness, or carrier-binding failure into a <em>bound</em>
     * {@link IdentitySnapshotContext#unverifiable(SnapshotDegradationReason)} value (so the
     * degradation is routed to the receive-side gate) rather than propagating an exception or dropping
     * the value.
     *
     * @param body            the namespace body previously written by {@link IdentitySnapshotDurableEncoder}
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for
     * @return the decode result: a verified bound value on success, a present-but-unverifiable bound
     *         value (plus a diagnostic warning) on any failure, or {@link ContextDecodeResult#empty()}
     *         when the body carries no present snapshot
     */
    private ContextDecodeResult<IdentitySnapshotContext> decodeBody(
            JsonObject body, DurableCarrierDescriptor expectedCarrier) {
        // A body without present=true carries no snapshot to reconstruct — the true no-snapshot path;
        // bind nothing. Read the raw value (not getBoolean) so an adversarial non-boolean present value
        // is treated as "not present" rather than throwing.
        if (!Boolean.TRUE.equals(body.getValue("present"))) {
            return ContextDecodeResult.empty();
        }

        String base64 = body.getString("snapshot");
        if (base64 == null) {
            return unverifiable(
                    SnapshotDegradationReason.DECODE_FAILED,
                    "identity-snapshot metadata is missing the 'snapshot' property");
        }

        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return unverifiable(
                    SnapshotDegradationReason.DECODE_FAILED,
                    "identity-snapshot 'snapshot' property is not valid base64: " + e.getMessage());
        }

        IdentitySnapshot decoded;
        try {
            decoded = codec.decode(encoded);
        } catch (IdentitySnapshotCodecException e) {
            return unverifiable(e.reason(), "identity-snapshot failed to decode/verify: " + e.getMessage());
        }

        // F3a fail-closed sentinel backstop: a snapshot signed with the pre-boundary-wiring "unbound"
        // sentinel carries no real per-row binding, so the ordinary carrier-match check below is a
        // no-op for it (the decoder's expected carrier defaults to the identical sentinel whenever the
        // boundary supplies none — see expectedCarrier). Bind unverifiable unconditionally so a
        // sentinel-signed snapshot can never be transplanted between rows on a not-yet-wired boundary,
        // regardless of what the expected carrier resolves to.
        if (isSentinelCarrier(decoded.carrier())) {
            return unverifiable(
                    SnapshotDegradationReason.DECODE_FAILED,
                    "identity-snapshot was signed with no real per-row carrier binding (pre-boundary-wiring "
                            + "sentinel) and cannot be verified");
        }

        // F5 carrier binding: the snapshot must be bound to the carrier the receiving dispatch was
        // written for. A mismatch is a replay/transplant onto a different row — bind nothing usable.
        if (!carrierMatches(decoded.carrier(), expectedCarrier)) {
            return unverifiable(
                    SnapshotDegradationReason.DECODE_FAILED,
                    "identity-snapshot carrier does not match the receiving dispatch's carrier");
        }
        return ContextDecodeResult.of(IdentitySnapshotContext.verified(decoded));
    }

    /**
     * Resolves the expected carrier for the receiving dispatch: the real
     * {@link DurableCarrierDescriptor} the boundary supplied on the decode context when present,
     * otherwise the fixed, boundary-independent {@code "unbound"} sentinel — matching the sentinel
     * {@link IdentitySnapshotDurableEncoder} signs (which is deliberately boundary-independent so a
     * producer/relay boundary-label asymmetry does not spuriously mismatch).
     *
     * @param context the decode context carrying the optional boundary-supplied carrier
     * @return the expected carrier to check the decoded snapshot against
     */
    private static DurableCarrierDescriptor expectedCarrier(DurableDecodeContext context) {
        return context.carrier()
                .orElseGet(() -> new DurableCarrierDescriptor(
                        IdentitySnapshotDurableEncoder.UNBOUND_CARRIER_ID,
                        IdentitySnapshotDurableEncoder.UNBOUND_TARGET));
    }

    /**
     * Returns whether the snapshot's signed carrier matches the expected carrier on both
     * {@code carrierId} and {@code target}.
     *
     * @param signed          the carrier binding signed into the snapshot
     * @param expectedCarrier the carrier the receiving dispatch was written for
     * @return {@code true} when both {@code carrierId} and {@code target} match
     */
    private static boolean carrierMatches(SnapshotCarrierBinding signed, DurableCarrierDescriptor expectedCarrier) {
        return signed.carrierId().equals(expectedCarrier.carrierId())
                && signed.target().equals(expectedCarrier.target());
    }

    /**
     * Returns whether {@code signed} is the fixed, boundary-independent {@code "unbound"} sentinel
     * {@link IdentitySnapshotDurableEncoder} signs when no real {@link DurableCarrierDescriptor} was
     * supplied at encode time (F3a fail-closed sentinel backstop, see class javadoc).
     *
     * @param signed the carrier binding signed into the decoded snapshot
     * @return {@code true} when {@code signed} equals the fixed sentinel carrier
     */
    private static boolean isSentinelCarrier(SnapshotCarrierBinding signed) {
        return signed.carrierId().equals(IdentitySnapshotDurableEncoder.UNBOUND_CARRIER_ID)
                && signed.target().equals(IdentitySnapshotDurableEncoder.UNBOUND_TARGET);
    }

    /**
     * Builds a present-but-unverifiable decode result: a <em>bound</em>
     * {@link IdentitySnapshotContext#unverifiable(SnapshotDegradationReason)} value carrying the
     * typed reason, plus one diagnostic {@link ContextDecodeWarning}. The bound value is what routes
     * the degradation to {@link IdentitySnapshotReconstructionInitializer}'s marker path.
     *
     * @param reason the typed reason the snapshot could not be verified
     * @param detail the human-readable failure detail for the diagnostic warning
     * @return a decode result carrying an unverifiable bound value and one warning
     */
    private static ContextDecodeResult<IdentitySnapshotContext> unverifiable(
            SnapshotDegradationReason reason, String detail) {
        return new ContextDecodeResult<>(
                Optional.of(IdentitySnapshotContext.unverifiable(reason)),
                List.of(new ContextDecodeWarning(IdentitySnapshotDurableEncoder.NAMESPACE, null, detail)));
    }
}
