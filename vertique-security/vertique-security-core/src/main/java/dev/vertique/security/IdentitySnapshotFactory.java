// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Captures the credential-free {@link IdentitySnapshotContent} of a live {@link SecurityContext}'s
 * full identity dimension (actor, subject, delegation summary, client, authentication method kind,
 * assurance, and typed authorization claims), suitable for carrying across a durability boundary
 * (e.g. a scheduled job, an outbox relay, or a workflow resume).
 *
 * <p>This type exists as its own interface because capture was split off {@link SecurityContexts}'s
 * general-context assembly (PRD identity-002 amendment A2) — not because it is a supported
 * swap-in extension point. For V1 it is a fixed, framework-wired implementation: {@code
 * IdentitySnapshotCarriageModule} installs {@code DefaultIdentitySnapshotFactory} as this type's
 * sole implementation via an unconditional {@code @Provides} binding, with no
 * {@code @BindsOptionalOf} seam. An application {@code @Provides} of this type is therefore not an
 * override — Dagger rejects it at compile time as a duplicate binding.
 *
 * <p>"Full identity dimension" describes the captured <em>structure</em>, not every field on
 * {@code PrincipalRef}/{@code ClientRef}/{@code AuthorityClaim} verbatim. Free-form attribute
 * maps on those types are projected to a framework-owned allowlist at capture rather than carried
 * through, so captured content stays credential-free and bounded regardless of what an
 * upstream resolver placed in those maps (PRD identity-002 FR-ID-CA-003/A7). Because
 * {@code IdentitySnapshotCodec} performs no independent content re-validation of a snapshot's
 * attribute maps at encode time — it only signs the envelope it assembles — any substitute
 * implementation hand-wired outside {@code IdentitySnapshotCarriageModule} is solely responsible
 * for upholding this same credential-free projection.
 *
 * <p>This interface returns <strong>content only</strong>, not a signed {@link IdentitySnapshot}:
 * capture cannot legitimately produce a carrier binding, temporal bounds, or an authoritative
 * signature — those belong to the durable envelope. The durable encoder (e.g.
 * {@code IdentitySnapshotDurableEncoder} in {@code vertique-security-runtime}) assembles the
 * {@link IdentitySnapshot} envelope around this content and the codec computes the authoritative
 * integrity tag at encode time, not this capture step. Returning content directly removes the
 * former "to-be-signed sentinel with placeholder integrity" invalid state.
 *
 * <p>Provided only where snapshot capture is wired (e.g. by
 * {@code IdentitySnapshotCarriageModule}), separately from the general-assembly
 * {@link SecurityContexts} statics — capture is a distinct concern with its own Dagger placement.
 *
 * @see SecurityContexts
 * @see IdentitySnapshotContent
 * @see IdentitySnapshot
 */
public interface IdentitySnapshotFactory {

    /**
     * Captures the credential-free {@link IdentitySnapshotContent} of the given live context's full
     * identity dimension.
     *
     * <p>Named {@code capture} rather than {@code snapshot} to avoid colliding with
     * {@link SecurityContext#snapshot()}, which produces the distinct, non-serializable, in-memory
     * {@link SecurityContextSnapshot}.
     *
     * @param live the live security context to capture; must not be {@code null}
     * @return a new {@link IdentitySnapshotContent} capturing {@code live}'s identity dimension
     */
    IdentitySnapshotContent capture(SecurityContext live);
}
