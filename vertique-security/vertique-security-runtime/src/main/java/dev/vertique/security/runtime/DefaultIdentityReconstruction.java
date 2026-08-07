// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentityReconstruction;
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

/**
 * Default {@link IdentityReconstruction} implementation, provided only by the privileged Dagger
 * module installed on framework infrastructure components (identity-002 §14.3).
 *
 * <p>Both entry points re-verify the snapshot's integrity envelope <em>and</em> its current freshness
 * via {@link IdentitySnapshotCodec#verifyForUse(IdentitySnapshot)} before minting any
 * {@link SecurityContext} — the single canonical verification the codec already uses for
 * {@link IdentitySnapshotCodec#decode(byte[])}, never a second copy of the domain-separation
 * logic. Re-checking freshness here (not just integrity) closes the replay window a bare integrity
 * check would leave open for a snapshot that was fresh when originally decoded but has since aged past
 * its effective expiry while retained in memory. A {@code null} snapshot or a snapshot that fails
 * verification throws {@link IdentityReconstructionException} before any context is built (fail-closed).
 *
 * <p>The content's {@link IdentitySnapshotContent#authorizationClaims()} are attribution only — audit
 * lineage recorded on the durable {@link IdentitySnapshot} capturing who/what the principal was at
 * capture time. The reconstructed context's {@link SecurityContext#authorization()} is therefore
 * always {@link AuthorizationClaims#empty()}; snapshot claims are never presented as current
 * authority. Current authority is resolved by mode per FR-ID-CA-010 (Phase-2
 * {@code PrincipalAuthorityResolver}), out of scope here.
 *
 * <p>Both entry points also mint the typed, unforgeable {@link ReconstructionMarker} signal
 * alongside the pre-existing {@code identity.reconstructed=true} string attribute on
 * {@link AuthenticationState#safeAttributes()} — the marker rides via
 * {@link SecurityContexts#assembleReconstructed}, while the string attribute is left
 * byte-for-byte unchanged for {@link IdentityReconstruction#isReconstructed} and
 * {@link DefaultIdentitySnapshotFactory}, which still read it.
 */
public final class DefaultIdentityReconstruction implements IdentityReconstruction {

    /**
     * Stable framework authority-id for a deferred reconstruction's scheduling
     * {@link DelegationContext} — the scheduling relationship is a framework-mediated grant, never
     * an origin summary, which is provenance rather than an authority-grant id.
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
     * captured summary had no grant id indistinguishable, to a consumer, from a real grant whose id
     * genuinely is {@code "deferred-execution"}. Mirrors
     * {@code DefaultCapturedAuthorityReconstruction}'s constant.
     */
    private static final String ABSENT_AUTHORITY_ID_MARKER = "no-captured-authority-id";

    private final IdentitySnapshotCodec codec;

    /**
     * Constructs a {@code DefaultIdentityReconstruction} backed by the given codec.
     *
     * @param codec the snapshot codec whose {@link IdentitySnapshotCodec#verifyForUse} this
     *              service reuses to re-verify a snapshot's integrity and current freshness before
     *              reconstruction; must not be {@code null}
     */
    public DefaultIdentityReconstruction(IdentitySnapshotCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The snapshot's {@code authorizationClaims} are attribution only (audit lineage on the
     * {@link IdentitySnapshot}); the reconstructed context's {@link SecurityContext#authorization()}
     * is {@link AuthorizationClaims#empty()} — snapshot claims are never presented as current
     * authority. Current authority is resolved by mode per FR-ID-CA-010 (Phase-2
     * {@code PrincipalAuthorityResolver}).
     *
     * <p>The returned context's typed {@link ReconstructionMarker} carries no principal of its own
     * — Mode 2 (e.g. {@code ReconstructedAuthorityResolvingAuthorizer}) resolves current authority
     * directly from the returned context's {@link SecurityIdentity#actor()}, which here is the
     * snapshot's {@link IdentitySnapshotContent#actor() actor} — the acting principal being resumed
     * — with authority mode {@link ReconstructedAuthorityMode#ATTRIBUTION_ONLY}, the default
     * reconstruction disposition.
     */
    @Override
    public SecurityContext resumeAsPrincipal(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
        verifySnapshot(snapshot);
        verifyCarrier(snapshot, expectedCarrier);
        IdentitySnapshotContent content = snapshot.content();

        SecurityIdentity identity = new SecurityIdentity(
                content.actor(),
                content.subject(),
                content.delegation().map(DefaultIdentityReconstruction::toDelegationContext),
                content.client());

        return SecurityContexts.assembleReconstructed(
                identity,
                reconstructedAuthentication(content, "RESUME"),
                AuthorizationClaims.empty(),
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The snapshot's {@code authorizationClaims} are attribution only (audit lineage on the
     * {@link IdentitySnapshot}); the reconstructed context's {@link SecurityContext#authorization()}
     * is {@link AuthorizationClaims#empty()} — snapshot claims are never presented as current
     * authority. Current authority is resolved by mode per FR-ID-CA-010 (Phase-2
     * {@code PrincipalAuthorityResolver}).
     *
     * <p>The reconstructed identity carries a framework-mediated {@link DelegationContext} whose
     * {@code kind} is {@link DelegationContext#DEFERRED_EXECUTION_KIND} and whose
     * {@code authorityId} is the stable framework marker {@code "deferred-execution"} (never the
     * snapshot's origin summary). This is a scheduling relationship, not a grant-backed delegation —
     * {@code DelegationEnforcementNarrower} recognizes this {@code kind} and passes the evaluation
     * through unchanged rather than validating it as a grant (FR-ID-DG-006 excludes framework-
     * scheduled deferred work from grant-scope intersection). When the snapshot carried a
     * {@link DelegationSummary}, that captured lineage is preserved as the bounded, audit-safe
     * attributes {@code delegation.captured.kind} and {@code delegation.captured.authorityId}
     * (FR-ID-CA-004) — so a chained deferral never loses who the work is ultimately for.
     *
     * <p>The returned context's typed {@link ReconstructionMarker} carries no principal of its own
     * — Mode 2 resolves current authority directly from the returned context's
     * {@link SecurityIdentity#actor()}, which here is {@code executingService}'s own actor (the
     * <strong>executing service</strong>, never the subject-of-record — subject-authority evaluation
     * / impersonation is explicitly out of v1 scope per FR-ID-DG-006). The subject-of-record — the
     * snapshot's {@link IdentitySnapshotContent#subject() subject} when present, else its
     * {@link IdentitySnapshotContent#actor() actor} — is carried on {@link SecurityIdentity#subject()}
     * as attribution only. The marker's authority mode is
     * {@link ReconstructedAuthorityMode#ATTRIBUTION_ONLY}, the default reconstruction disposition.
     *
     * @throws IllegalArgumentException if {@code executingService}'s actor is neither
     *                                  {@link PrincipalType#SYSTEM} nor {@link PrincipalType#SERVICE}
     */
    @Override
    public SecurityContext deferredExecution(
            SecurityIdentity executingService, IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier) {
        Objects.requireNonNull(executingService, "executingService");
        requireExecutingServiceActor(executingService);
        verifySnapshot(snapshot);
        verifyCarrier(snapshot, expectedCarrier);
        IdentitySnapshotContent content = snapshot.content();

        PrincipalRef subjectOfRecord = content.subject().orElse(content.actor());
        DelegationContext delegation = new DelegationContext(
                DelegationContext.DEFERRED_EXECUTION_KIND,
                DEFERRED_EXECUTION_AUTHORITY_ID,
                Optional.empty(),
                capturedDelegationAttributes(content));

        SecurityIdentity identity = new SecurityIdentity(
                executingService.actor(), Optional.of(subjectOfRecord), Optional.of(delegation), content.client());

        return SecurityContexts.assembleReconstructed(
                identity,
                reconstructedAuthentication(content, "DEFERRED"),
                AuthorizationClaims.empty(),
                Optional.empty(),
                new ReconstructionMarker(ReconstructedAuthorityMode.ATTRIBUTION_ONLY));
    }

    /**
     * Re-verifies the given snapshot's integrity envelope and current freshness, failing closed with a
     * typed {@link IdentityReconstructionException} for a {@code null} snapshot or any verification
     * failure the codec reports.
     *
     * @param snapshot the snapshot to verify; may be {@code null}
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null} or fails
     *                                          {@link IdentitySnapshotCodec#verifyForUse}
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
     * Enforces the F5 carrier-binding check: the snapshot's signed
     * {@link IdentitySnapshot#carrier()} must match the trusted receive-side {@code expectedCarrier}
     * on both {@code carrierId} and {@code target}. A mismatch means the snapshot was signed for a
     * different durable row (a replay/transplant), so reconstruction fails closed before any context
     * is minted.
     *
     * @param snapshot        the integrity-verified snapshot whose signed carrier is being checked
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for; must not
     *                        be {@code null}
     * @throws IdentityReconstructionException if the snapshot's carrier does not match
     *                                          {@code expectedCarrier}
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
     * Builds the reconstructed {@link AuthenticationState} common to both entry points: empty
     * evidence, the snapshot's carried assurance, and the reconstruction marker attributes.
     *
     * <p>The marker attributes carry both {@code identity.reconstructed.capturedAt} and
     * {@code identity.reconstructed.authenticatedAt} — each the corresponding
     * {@link IdentitySnapshot} instant rendered via {@link java.time.Instant#toString()} — so a
     * re-capture of an already-reconstructed context (e.g. a REST-originated chain where
     * {@link IdentitySnapshotContent#assurance()} is empty) never loses the original authentication
     * instant per hop:
     * {@link DefaultIdentitySnapshotFactory} reads {@code identity.reconstructed.authenticatedAt}
     * back as a fallback source when re-capturing such a context.
     *
     * <p>This marker mechanism carries only the original authentication instant across such a
     * re-capture — it does not round-trip the rest of the reconstructed identity dimension. On a
     * re-captured context, {@link AuthenticationState#primaryMethod()}'s normalized kind is already
     * {@link dev.vertique.security.AuthMethodKind#CUSTOM} (this method always mints reconstructed
     * authentication via {@code DefaultAuthMethod.custom(...)}), a {@code deferredExecution(...)}
     * reconstruction's delegation lineage has already narrowed to the synthetic
     * deferred-execution summary, and the reconstructed {@link SecurityContext#origin()} is
     * already empty — see {@link DefaultIdentitySnapshotFactory} for how each of those is derived
     * on re-capture. No Phase-1 production path re-captures a reconstructed context, so this
     * narrowing is presently latent; carrying the remaining fields across a chained re-capture is
     * tracked as a deferred item in the identity-002 PRD (§13).
     *
     * @param content the captured identity content being reconstructed from
     * @param mode    the reconstruction mode marker value ({@code "RESUME"} or {@code "DEFERRED"})
     * @return the reconstructed {@link AuthenticationState}
     */
    private static AuthenticationState reconstructedAuthentication(IdentitySnapshotContent content, String mode) {
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
     * Guards that the caller-supplied executing-service identity acts as a system or service
     * principal, rejecting a {@link PrincipalType#USER} or {@link PrincipalType#ANONYMOUS} actor
     * that would be misattributed as the executing service of deferred work.
     *
     * @param executingService the executing-service identity to check
     * @throws IllegalArgumentException if its actor is neither {@link PrincipalType#SYSTEM} nor
     *                                  {@link PrincipalType#SERVICE}
     */
    private static void requireExecutingServiceActor(SecurityIdentity executingService) {
        PrincipalType actorType = executingService.actor().type();
        if (actorType != PrincipalType.SYSTEM && actorType != PrincipalType.SERVICE) {
            throw new IllegalArgumentException(
                    "deferredExecution requires a SYSTEM or SERVICE executing service actor, got: " + actorType);
        }
    }

    /**
     * Builds the bounded, audit-safe attribute map carrying the snapshot's captured delegation
     * lineage onto a deferred reconstruction's framework-mediated {@link DelegationContext}. When
     * the snapshot carried a {@link DelegationSummary}, its {@code kind} (and {@code authorityId}
     * when present) are recorded under the {@code delegation.captured.*} keys so the lineage
     * survives the chained deferral (FR-ID-CA-004); when it carried none, the map is empty.
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
     * with no reason and no attributes (the summary never carried them). A summary carrying no
     * grant identifier yields {@link #ABSENT_AUTHORITY_ID_MARKER}, which states the absence rather
     * than fabricating an id a consumer could mistake for a real grant.
     *
     * @param summary the delegation summary to expand
     * @return the reconstructed {@link DelegationContext}
     */
    private static DelegationContext toDelegationContext(DelegationSummary summary) {
        String authorityId = summary.authorityId().orElse(ABSENT_AUTHORITY_ID_MARKER);
        return new DelegationContext(summary.kind(), authorityId, Optional.empty(), Map.of());
    }
}
