// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.context.DurableCarrierDescriptor;

/**
 * Privileged service that reconstructs a {@link SecurityContext} from a credential-free
 * {@link IdentitySnapshot}, presenting the snapshot's <strong>captured</strong> authorization
 * claims as the reconstructed context's <strong>current</strong> authority — Mode 3 of PRD
 * identity-002 §14.3 Phase-2 Appendix (FR-ID-CA-010).
 *
 * <p>This is a <strong>distinct privileged type</strong>, deliberately not a third method on
 * {@link IdentityReconstruction}: {@link IdentityReconstruction}'s default reconstruction (Mode
 * 1) always mints a {@link SecurityContext#authorization()} of {@link
 * dev.vertique.security.authz.AuthorizationClaims#empty()} — the snapshot's claims are audit
 * lineage only. Mode 3 deliberately inverts that guarantee, so it lives on its own interface,
 * wired only by its own opt-in Dagger module ({@code CapturedAuthorityReconstructionModule}) —
 * <strong>never</strong> by {@code PrivilegedIdentityModule} and never with a fallback binding.
 * An application enables Mode 3 by explicitly installing that module, which is visible at code
 * review.
 *
 * <p>Both entry points share {@link IdentityReconstruction}'s trust boundary (FR-ID-CA-008): they
 * re-verify the snapshot's integrity envelope, confirm the snapshot's signed {@link
 * IdentitySnapshot#carrier()} matches the {@code expectedCarrier} the trusted receive-side
 * infrastructure supplies (the F5 replay defense), and additionally enforce a <strong>per-target-kind
 * allowlist</strong>: the snapshot's carrier {@link SnapshotCarrierBinding#target()}'s {@link
 * dev.vertique.core.context.DurableTarget#kind() kind} must be one of the implementation's
 * configured allowed kinds, or reconstruction fails closed. Every allowed kind is required (by
 * startup validation in the wiring module) to carry a {@link CarriageRequirement#REQUIRED}
 * carriage requirement — a captured-authority target whose carriage is merely {@link
 * CarriageRequirement#OPTIONAL}/{@link CarriageRequirement#FORBIDDEN} would let an attacker
 * suppress the snapshot and dispatch un-authorized, or would be a standing bearer-credential hole
 * on a target that may never actually carry one. Reconstruction is fail-closed throughout: a
 * {@code null} snapshot, a snapshot that fails integrity or carrier verification, or a snapshot
 * whose carrier target kind is not allowlisted throws {@link IdentityReconstructionException}
 * before any {@link SecurityContext} is built — there is never a partial or unbound result.
 *
 * <p><strong>Event-silent.</strong> Per FR-ID-CA-007, reconstruction itself never emits any
 * {@link dev.vertique.security.events.SecurityEventObserver} event — a plain interface method
 * cannot invoke an injected emitter by construction, and this contract preserves that discipline
 * deliberately. The consuming infrastructure that invokes {@code CapturedAuthorityReconstruction}
 * is responsible for emitting the dedicated activation-audit event once Mode-3 authority is
 * actually put into effect (a later slice; not implemented by this interface).
 *
 * <p>{@link #deferredExecutionWithCapturedAuthority} preserves the actor/subject split used by
 * {@link IdentityReconstruction#deferredExecution}: the executing service is always the acting
 * principal, and the snapshot's subject-of-record is carried as the delegated subject — never
 * impersonated as the acting principal. Jobs and other deferred-execution infrastructure MUST use
 * this method, <strong>never</strong> {@link #resumeWithCapturedAuthority}, which restores the
 * snapshot's full identity structure as-is (including its actor) and is intended only for
 * resuming exactly as the original principal (e.g. a workflow resuming as the identity that
 * scheduled it).
 */
public interface CapturedAuthorityReconstruction {

    /**
     * Restores the full identity structure carried by {@code snapshot} as-is — mirroring {@link
     * IdentityReconstruction#resumeAsPrincipal} — but presents the snapshot's captured {@link
     * IdentitySnapshotContent#authorizationClaims()} as the reconstructed context's
     * <strong>current</strong> {@link SecurityContext#authorization()}, rather than {@link
     * dev.vertique.security.authz.AuthorizationClaims#empty()}.
     *
     * <p>Used when the original principal itself is being resumed with its captured authority
     * trusted as-is (e.g. a workflow resuming exactly as the identity that scheduled it, under an
     * operator-configured Mode-3 opt-in).
     *
     * @param snapshot        the snapshot to reconstruct from; must not be {@code null}
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for; the
     *                        snapshot's signed {@link IdentitySnapshot#carrier()} must match it
     *                        (same {@code carrierId} and {@code target}); must not be {@code null}
     * @return a reconstructed {@link SecurityContext} carrying the snapshot's full identity
     *         structure and its captured claims as current authority
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null}, fails integrity
     *                                          or carrier verification, or its carrier target kind
     *                                          is not allowlisted for captured-authority
     *                                          reconstruction
     */
    SecurityContext resumeWithCapturedAuthority(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);

    /**
     * Reconstructs a context for deferred execution — mirroring {@link
     * IdentityReconstruction#deferredExecution} — but presents the snapshot's captured {@link
     * IdentitySnapshotContent#authorizationClaims()} as the reconstructed context's
     * <strong>current</strong> {@link SecurityContext#authorization()}, rather than {@link
     * dev.vertique.security.authz.AuthorizationClaims#empty()}.
     *
     * <p>The acting principal is always {@code executingServiceIdentity}'s actor; the
     * subject-of-record is the snapshot's subject when present, else its actor — the executing
     * service is never impersonated as the original principal. Jobs and other deferred-execution
     * infrastructure MUST call this method rather than {@link #resumeWithCapturedAuthority}.
     *
     * @param executingServiceIdentity the identity of the system component executing the deferred
     *                                 work; must not be {@code null}, and its actor must be
     *                                 {@link PrincipalType#SYSTEM} or {@link PrincipalType#SERVICE}
     * @param snapshot                 the snapshot describing the identity that scheduled the
     *                                 work; must not be {@code null}
     * @param expectedCarrier          the durable row-carrier the receiving dispatch was written
     *                                 for; the snapshot's signed {@link IdentitySnapshot#carrier()}
     *                                 must match it (same {@code carrierId} and {@code target});
     *                                 must not be {@code null}
     * @return a reconstructed {@link SecurityContext} with {@code executingServiceIdentity} as
     *         actor, the snapshot's subject-of-record as subject, and its captured claims as
     *         current authority
     * @throws IllegalArgumentException        if {@code executingServiceIdentity}'s actor is
     *                                          neither {@link PrincipalType#SYSTEM} nor {@link
     *                                          PrincipalType#SERVICE}
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null}, fails integrity
     *                                          or carrier verification, or its carrier target kind
     *                                          is not allowlisted for captured-authority
     *                                          reconstruction
     */
    SecurityContext deferredExecutionWithCapturedAuthority(
            SecurityIdentity executingServiceIdentity,
            IdentitySnapshot snapshot,
            DurableCarrierDescriptor expectedCarrier);
}
