// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.context.DurableCarrierDescriptor;

/**
 * Privileged service that reconstructs a {@link SecurityContext} from a credential-free
 * {@link IdentitySnapshot} captured across a durability boundary (a scheduled job, an outbox
 * relay, or a workflow resume).
 *
 * <p>This is the trust boundary named by FR-ID-CA-008: both entry points re-verify the
 * snapshot's {@link SnapshotIntegrity} envelope before minting any context, reusing the
 * runtime codec's canonical verification rather than re-implementing it, and confirm the
 * snapshot's signed {@link IdentitySnapshot#carrier()} matches the {@code expectedCarrier} the
 * trusted receive-side infrastructure supplies (the F5 replay defense — a snapshot signed for one
 * durable row cannot be transplanted onto another). Reconstruction is fail-closed — a {@code null}
 * snapshot, a snapshot that fails integrity verification, or a snapshot whose carrier does not match
 * the expected carrier throws {@link IdentityReconstructionException} before any
 * {@link SecurityContext} is built; there is never a partial or unbound result.
 *
 * <p>Every reconstructed context's {@link SecurityContext#authentication()} carries:
 * <ul>
 *   <li>{@code primaryMethod} — {@link DefaultAuthMethod#custom(String)} built from the
 *       content's {@link IdentitySnapshotContent#authenticationMethodKind()}</li>
 *   <li>{@code evidence} — always empty; the snapshot is credential-free by construction</li>
 *   <li>{@code assurance} — the content's {@link IdentitySnapshotContent#assurance()}, carried
 *       as-is</li>
 *   <li>{@code safeAttributes} — the reconstruction marker: {@code identity.reconstructed=true},
 *       {@code identity.reconstructed.mode} = {@code RESUME} or {@code DEFERRED},
 *       {@code identity.reconstructed.capturedAt} = the content's
 *       {@link IdentitySnapshotContent#capturedAt()}, and
 *       {@code identity.reconstructed.authenticatedAt} = the content's
 *       {@link IdentitySnapshotContent#authenticatedAt()} (so a chained re-capture preserves the
 *       original authentication instant), each rendered via {@link java.time.Instant#toString()}</li>
 * </ul>
 *
 * <p>A reconstructed context's {@link SecurityContext#authorization()} is empty: the content's
 * {@link IdentitySnapshotContent#authorizationClaims()} are attribution only (audit lineage on the
 * snapshot), never presented as current authority. Current authority is resolved by mode per
 * FR-ID-CA-010.
 *
 * <p><strong>Privileged boundary.</strong> This service is provided only by a dedicated
 * privileged Dagger module installed on framework infrastructure components (job execution,
 * inbox/outbox, workflow resume) — never by the general security runtime module — so that
 * accidental application-level injection is visible in a component's module list at review time.
 */
public interface IdentityReconstruction {

    /**
     * Restores the full identity structure carried by {@code snapshot} as-is: the snapshot's
     * actor becomes the reconstructed context's actor, and the snapshot's subject, delegation,
     * and client are carried over unchanged.
     *
     * <p>Used when the original principal itself is being resumed (e.g. a workflow resuming
     * exactly as the identity that scheduled it).
     *
     * @param snapshot        the snapshot to reconstruct from; must not be {@code null}
     * @param expectedCarrier the durable row-carrier the receiving dispatch was written for; the
     *                        snapshot's signed {@link IdentitySnapshot#carrier()} must match it (same
     *                        {@code carrierId} and {@code target}); must not be {@code null}
     * @return a reconstructed {@link SecurityContext} carrying the snapshot's full identity
     *         structure
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null}, fails integrity
     *                                          verification, or its carrier does not match
     *                                          {@code expectedCarrier}
     */
    SecurityContext resumeAsPrincipal(IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);

    /**
     * Reconstructs a context for deferred execution: the acting principal is the executing
     * service identity, and the subject-of-record is the snapshot's subject when present, else
     * the snapshot's actor.
     *
     * <p>Used when a system component (a scheduled job, an outbox relay handler) executes work
     * on behalf of the identity that originally scheduled it — the executing service is always
     * the actor, never impersonated as the original principal. The {@code executingService}'s actor
     * must therefore be a {@link PrincipalType#SYSTEM} or {@link PrincipalType#SERVICE} principal; a
     * {@code USER} or {@code ANONYMOUS} actor is rejected.
     *
     * @param executingService the identity of the system component executing the deferred work;
     *                          must not be {@code null}, and its actor must be
     *                          {@link PrincipalType#SYSTEM} or {@link PrincipalType#SERVICE}
     * @param snapshot          the snapshot describing the identity that scheduled the work;
     *                          must not be {@code null}
     * @param expectedCarrier   the durable row-carrier the receiving dispatch was written for; the
     *                          snapshot's signed {@link IdentitySnapshot#carrier()} must match it
     *                          (same {@code carrierId} and {@code target}); must not be {@code null}
     * @return a reconstructed {@link SecurityContext} with {@code executingService} as actor and
     *         the snapshot's subject-of-record as subject
     * @throws IllegalArgumentException       if {@code executingService}'s actor is neither
     *                                        {@link PrincipalType#SYSTEM} nor
     *                                        {@link PrincipalType#SERVICE}
     * @throws IdentityReconstructionException if {@code snapshot} is {@code null}, fails integrity
     *                                          verification, or its carrier does not match
     *                                          {@code expectedCarrier}
     */
    SecurityContext deferredExecution(
            SecurityIdentity executingService, IdentitySnapshot snapshot, DurableCarrierDescriptor expectedCarrier);

    /**
     * Reports whether {@code ctx} carries the reconstruction marker.
     *
     * <p>Reads strictly and only the {@code "identity.reconstructed"} entry of
     * {@link SecurityContext#authentication()}'s {@link AuthenticationState#safeAttributes()
     * safeAttributes} — never {@link SecurityContext#authorization()} or
     * {@link SecurityContext#origin()}. Setting the marker never grants or removes authorization
     * standing by itself; it is purely descriptive of how the context's authentication state was
     * produced.
     *
     * @param ctx the context to inspect; must not be {@code null}
     * @return {@code true} iff {@code ctx.authentication().safeAttributes()} contains
     *         {@code "identity.reconstructed"} mapped to the string {@code "true"}
     */
    static boolean isReconstructed(SecurityContext ctx) {
        return "true".equals(ctx.authentication().safeAttributes().get("identity.reconstructed"));
    }
}
