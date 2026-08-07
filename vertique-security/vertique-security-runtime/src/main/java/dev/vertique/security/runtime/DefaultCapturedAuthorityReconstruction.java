// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.CapturedAuthorityReconstruction;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentityReconstructionException;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotCarrierBinding;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link CapturedAuthorityReconstruction} implementation — the Mode-3 counterpart of
 * {@link DefaultIdentityReconstruction}, provided only by the opt-in {@code
 * CapturedAuthorityReconstructionModule} (PRD identity-002 §14.3 Phase-2 Appendix, §14.6 P2.S4).
 *
 * <p>Both entry points reuse the exact trust-boundary checks {@link DefaultIdentityReconstruction}
 * applies — re-verifying the snapshot's integrity envelope <em>and</em> its current freshness via
 * {@link IdentitySnapshotCodec#verifyForUse(IdentitySnapshot)} and confirming the F5 carrier binding
 * against the caller-supplied {@code expectedCarrier} — duplicated here (rather than shared via a
 * common helper) so this privileged, opt-in type has no compile-time coupling to the
 * general-reconstruction class. After those checks pass, an additional per-target-kind allowlist
 * gate runs: the snapshot's carrier {@link SnapshotCarrierBinding#target()}'s {@code kind} must be
 * a member of the {@link #allowedTargetKinds} this instance was constructed with, or reconstruction
 * fails closed before any {@link SecurityContext} is built.
 *
 * <p>Unlike {@link DefaultIdentityReconstruction}, both entry points here present the snapshot's
 * {@link IdentitySnapshotContent#authorizationClaims()} as the reconstructed context's
 * <strong>current</strong> {@link SecurityContext#authorization()} — the frozen, captured claims
 * ARE current authority (Mode 3) — and the returned {@link ReconstructionMarker#mode()} is always
 * {@link ReconstructedAuthorityMode#CAPTURED}.
 */
final class DefaultCapturedAuthorityReconstruction implements CapturedAuthorityReconstruction {

    /**
     * Stable framework authority-id for a deferred reconstruction's scheduling
     * {@link DelegationContext}. Mirrors {@link DefaultIdentityReconstruction}'s constant.
     */
    private static final String DEFERRED_EXECUTION_AUTHORITY_ID = "deferred-execution";

    /**
     * Self-describing fallback substituted in {@link #toDelegationContext(DelegationSummary)} when a
     * captured {@link DelegationSummary} carries no grant identifier, so a reconstructed
     * {@link DelegationContext} always has a non-blank {@link DelegationContext#authorityId()}
     * (which {@link DelegationSummary#authorityId()}, being {@link Optional}, does not guarantee).
     *
     * <p>Deliberately <strong>not</strong> {@link #DEFERRED_EXECUTION_AUTHORITY_ID}: reusing that
     * literal would leave a resumed grant-backed delegation (say {@code kind="psd2-pis"}) whose
     * captured summary had no grant id indistinguishable, to an audit consumer of the activation
     * event, from a real grant whose id genuinely is {@code "deferred-execution"}. Mirrors
     * {@link DefaultIdentityReconstruction}'s constant.
     */
    private static final String ABSENT_AUTHORITY_ID_MARKER = "no-captured-authority-id";

    private final IdentitySnapshotCodec codec;
    private final Set<String> allowedTargetKinds;

    /**
     * Constructs a {@code DefaultCapturedAuthorityReconstruction} backed by the given codec and
     * per-target-kind allowlist.
     *
     * @param codec              the snapshot codec whose {@link IdentitySnapshotCodec#verifyForUse}
     *                           this service reuses to re-verify a snapshot's integrity and current
     *                           freshness before reconstruction; must not be {@code null}
     * @param allowedTargetKinds the durable-target kinds this instance permits captured-authority
     *                           reconstruction against; defensively copied; must not be {@code null}
     */
    DefaultCapturedAuthorityReconstruction(IdentitySnapshotCodec codec, Set<String> allowedTargetKinds) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.allowedTargetKinds = Set.copyOf(Objects.requireNonNull(allowedTargetKinds, "allowedTargetKinds"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The returned context's typed {@link ReconstructionMarker} carries no principal of its own
     * — mirroring {@link DefaultIdentityReconstruction#resumeAsPrincipal} — with authority mode
     * {@link ReconstructedAuthorityMode#CAPTURED}; the captured claims already carried by the
     * returned context ARE current authority, so no live resolution ever consults the returned
     * context's {@link SecurityIdentity#actor()} for this mode.
     */
    @Override
    public SecurityContext resumeWithCapturedAuthority(
            IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
        verifySnapshot(snapshot);
        verifyCarrier(snapshot, expectedCarrier);
        verifyAllowedTarget(snapshot);
        IdentitySnapshotContent content = snapshot.content();

        SecurityIdentity identity = new SecurityIdentity(
                content.actor(),
                content.subject(),
                content.delegation().map(DefaultCapturedAuthorityReconstruction::toDelegationContext),
                content.client());

        return SecurityContexts.assembleReconstructed(
                identity,
                capturedAuthentication(content, "CAPTURED-RESUME"),
                capturedClaims(content),
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The reconstructed identity carries a framework-mediated {@link DelegationContext} whose
     * {@code kind} is {@link DelegationContext#DEFERRED_EXECUTION_KIND} — a scheduling
     * relationship, not a grant-backed delegation; {@code DelegationEnforcementNarrower} recognizes
     * this {@code kind} and passes the evaluation through unchanged rather than validating it as a
     * grant.
     *
     * <p>The returned context's typed {@link ReconstructionMarker} carries no principal of its own
     * — mirroring {@link DefaultIdentityReconstruction#deferredExecution} — with authority mode
     * {@link ReconstructedAuthorityMode#CAPTURED}; the captured claims already carried by the
     * returned context ARE current authority. The subject-of-record — the snapshot's
     * {@link IdentitySnapshotContent#subject() subject} when present, else its
     * {@link IdentitySnapshotContent#actor() actor} — is carried on
     * {@link SecurityIdentity#subject()} as attribution only.
     */
    @Override
    public SecurityContext deferredExecutionWithCapturedAuthority(
            SecurityIdentity executingServiceIdentity,
            IdentitySnapshot snapshot,
            DurableCarrierDescriptor expectedCarrier) {
        Objects.requireNonNull(executingServiceIdentity, "executingServiceIdentity");
        requireExecutingServiceActor(executingServiceIdentity);
        verifySnapshot(snapshot);
        verifyCarrier(snapshot, expectedCarrier);
        verifyAllowedTarget(snapshot);
        IdentitySnapshotContent content = snapshot.content();

        PrincipalRef subjectOfRecord = content.subject().orElse(content.actor());
        DelegationContext delegation = new DelegationContext(
                DelegationContext.DEFERRED_EXECUTION_KIND,
                DEFERRED_EXECUTION_AUTHORITY_ID,
                Optional.empty(),
                capturedDelegationAttributes(content));

        SecurityIdentity identity = new SecurityIdentity(
                executingServiceIdentity.actor(),
                Optional.of(subjectOfRecord),
                Optional.of(delegation),
                content.client());

        return SecurityContexts.assembleReconstructed(
                identity,
                capturedAuthentication(content, "CAPTURED-DEFERRED"),
                capturedClaims(content),
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED));
    }

    /**
     * Re-verifies the given snapshot's integrity envelope and current freshness, failing closed with a
     * typed {@link IdentityReconstructionException} for a {@code null} snapshot or any verification
     * failure the codec reports. Mirrors {@code DefaultIdentityReconstruction.verifySnapshot}.
     *
     * @param snapshot the snapshot to verify; may be {@code null}
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null} or fails {@link
     *                                          IdentitySnapshotCodec#verifyForUse}
     */
    private void verifySnapshot(IdentitySnapshot snapshot) {
        if (snapshot == null) {
            throw new IdentityReconstructionException("cannot reconstruct a null identity snapshot");
        }
        try {
            codec.verifyForUse(snapshot);
        } catch (IdentitySnapshotCodecException e) {
            throw new IdentityReconstructionException("identity snapshot failed integrity verification", e, e.reason());
        }
    }

    /**
     * Enforces the F5 carrier-binding check: the snapshot's signed {@link IdentitySnapshot#carrier()}
     * must match the trusted receive-side {@code expectedCarrier} on both {@code carrierId} and
     * {@code target}. Mirrors {@code DefaultIdentityReconstruction.verifyCarrier}.
     *
     * @param snapshot        the integrity-verified snapshot whose signed carrier is being checked
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for; must
     *                        not be {@code null}
     * @throws IdentityReconstructionException if the snapshot's carrier does not match {@code
     *                                          expectedCarrier}
     */
    private static void verifyCarrier(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
        Objects.requireNonNull(expectedCarrier, "expectedCarrier");
        SnapshotCarrierBinding signed = snapshot.carrier();
        if (!signed.carrierId().equals(expectedCarrier.carrierId())
                || !signed.target().equals(expectedCarrier.target())) {
            throw new IdentityReconstructionException(
                    "identity snapshot carrier does not match the expected carrier for this dispatch");
        }
    }

    /**
     * Enforces the Mode-3 per-target-kind allowlist: the carrier-verified snapshot's target kind
     * must be a member of {@link #allowedTargetKinds}, or reconstruction fails closed before any
     * context is minted. The failure message names only the disallowed kind — never any content of
     * the snapshot.
     *
     * @param snapshot the integrity- and carrier-verified snapshot being checked
     * @throws IdentityReconstructionException if the snapshot's carrier target kind is not
     *                                          allowlisted
     */
    private void verifyAllowedTarget(IdentitySnapshot snapshot) {
        String kind = snapshot.carrier().target().kind();
        if (!allowedTargetKinds.contains(kind)) {
            throw new IdentityReconstructionException(
                    "captured-authority reconstruction is not allowlisted for " + "durable target kind '" + kind + "'");
        }
    }

    /**
     * Builds the reconstructed {@link AuthenticationState} common to both entry points, mirroring
     * {@code DefaultIdentityReconstruction.reconstructedAuthentication} except for the mode marker
     * values, which are the Mode-3-specific {@code "CAPTURED-RESUME"}/{@code "CAPTURED-DEFERRED"}.
     *
     * @param content the captured identity content being reconstructed from
     * @param mode    the reconstruction mode marker value ({@code "CAPTURED-RESUME"} or {@code
     *                "CAPTURED-DEFERRED"})
     * @return the reconstructed {@link AuthenticationState}
     */
    private static AuthenticationState capturedAuthentication(IdentitySnapshotContent content, String mode) {
        Map<String, Object> markerAttributes = Map.of(
                "identity.reconstructed",
                "true",
                "identity.reconstructed.mode",
                mode,
                "identity.reconstructed.capturedAt",
                content.capturedAt().toString(),
                "identity.reconstructed.authenticatedAt",
                content.authenticatedAt().toString());
        return new AuthenticationState(
                DefaultAuthMethod.custom(content.authenticationMethodKind()),
                List.of(),
                content.assurance(),
                Optional.empty(),
                markerAttributes);
    }

    /**
     * Builds the reconstructed context's current authority from the snapshot's captured claims —
     * the Mode-3 inversion of {@link DefaultIdentityReconstruction}'s always-empty authorization.
     *
     * @param content the captured identity content whose {@link
     *                IdentitySnapshotContent#authorizationClaims()} become current authority
     * @return an {@link AuthorizationClaims} carrying exactly the snapshot's captured claims, with
     *         no attributes
     */
    private static AuthorizationClaims capturedClaims(IdentitySnapshotContent content) {
        return new AuthorizationClaims(Set.copyOf(content.authorizationClaims()), Map.of());
    }

    /**
     * Guards that the caller-supplied executing-service identity acts as a system or service
     * principal. Mirrors {@code DefaultIdentityReconstruction.requireExecutingServiceActor}.
     *
     * @param executingService the executing-service identity to check
     * @throws IllegalArgumentException if its actor is neither {@link PrincipalType#SYSTEM} nor
     *                                  {@link PrincipalType#SERVICE}
     */
    private static void requireExecutingServiceActor(SecurityIdentity executingService) {
        PrincipalType actorType = executingService.actor().type();
        if (actorType != PrincipalType.SYSTEM && actorType != PrincipalType.SERVICE) {
            throw new IllegalArgumentException("deferredExecutionWithCapturedAuthority requires a SYSTEM or SERVICE "
                    + "executing service actor, got: " + actorType);
        }
    }

    /**
     * Builds the bounded, audit-safe attribute map carrying the snapshot's captured delegation
     * lineage onto a deferred reconstruction's framework-mediated {@link DelegationContext}. Mirrors
     * {@code DefaultIdentityReconstruction.capturedDelegationAttributes}.
     *
     * @param content the captured identity content being reconstructed from
     * @return the captured-lineage attributes, or {@link Map#of()} when the content carried no
     *         delegation summary
     */
    private static Map<String, Object> capturedDelegationAttributes(IdentitySnapshotContent content) {
        Optional<DelegationSummary> captured = content.delegation();
        if (captured.isEmpty()) {
            return Map.of();
        }
        DelegationSummary summary = captured.orElseThrow();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("delegation.captured.kind", summary.kind());
        summary.authorityId().ifPresent(id -> attributes.put("delegation.captured.authorityId", id));
        return Map.copyOf(attributes);
    }

    /**
     * Converts a snapshot's {@link DelegationSummary} back into a full {@link DelegationContext},
     * with no reason and no attributes. A summary carrying no grant identifier yields
     * {@link #ABSENT_AUTHORITY_ID_MARKER}, which states the absence rather than fabricating an id a
     * consumer of the activation event could mistake for a real grant. Mirrors
     * {@code DefaultIdentityReconstruction.toDelegationContext}.
     *
     * @param summary the delegation summary to expand
     * @return the reconstructed {@link DelegationContext}
     */
    private static DelegationContext toDelegationContext(DelegationSummary summary) {
        String authorityId = summary.authorityId().orElse(ABSENT_AUTHORITY_ID_MARKER);
        return new DelegationContext(summary.kind(), authorityId, Optional.empty(), Map.of());
    }
}
