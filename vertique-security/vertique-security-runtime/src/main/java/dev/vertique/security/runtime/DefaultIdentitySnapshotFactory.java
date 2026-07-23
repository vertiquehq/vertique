// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.AuthenticationAssurance;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DelegationSummary;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.IdentitySnapshotFactory;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.InvocationOrigin;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link IdentitySnapshotFactory} implementation providing credential-free identity
 * snapshot capture outside any REST middleware (identity-002 §14.3).
 *
 * <p>{@link #capture(SecurityContext)} returns {@link IdentitySnapshotContent} only — the captured
 * identity dimension, with no carrier binding, no temporal bounds, and no integrity envelope. The
 * durable encoder assembles the surrounding {@link dev.vertique.security.IdentitySnapshot} and
 * {@link IdentitySnapshotCodec} is the single HMAC signer — it computes the authoritative tag when
 * the envelope is encoded. Returning content directly removes the former "to-be-signed sentinel with
 * placeholder integrity" invalid state.
 *
 * <p>{@link IdentitySnapshotContent#authenticatedAt()} is derived in decisive-source order: the IdP's
 * reported {@link AuthenticationAssurance#authTime()} when present; otherwise the first (decisive)
 * {@link dev.vertique.security.AuthenticationEvidence#verifiedAt()} entry in
 * {@link AuthenticationState#evidence()} when evidence is non-empty; otherwise the live context's
 * carried {@code identity.reconstructed.authenticatedAt} safe attribute — the marker
 * {@link DefaultIdentityReconstruction} stamps on every resumed/deferred context — when present and
 * parseable as an {@link Instant}, so a re-capture of an already-reconstructed context (e.g. a
 * REST-originated chain where {@link AuthenticationState#assurance()} is empty) never loses the
 * original authentication instant per hop; otherwise the capture instant as the terminal fallback
 * for system/unauthenticated/anonymous synthetic contexts that carry no real authentication event.
 *
 * <p>This marker preserves only {@code authenticatedAt} across such a re-capture — it is not a
 * general reconstructed-context round-trip. On the reconstructed context being re-captured,
 * {@link AuthenticationState#primaryMethod()}'s normalized kind has already degraded to
 * {@link dev.vertique.security.AuthMethodKind#CUSTOM} ({@link DefaultIdentityReconstruction} always
 * mints reconstructed authentication via {@code DefaultAuthMethod.custom(...)}); the delegation
 * summary this factory derives narrows to the synthetic deferred-execution summary that
 * {@code deferredExecution(...)} reconstruction stamps on the identity (a
 * {@code resumeAsPrincipal(...)} reconstruction round-trips the captured delegation summary
 * unchanged, so it is unaffected); and {@link #originSummary()} resolves to {@code "unknown"}
 * because no {@link InvocationOrigin} is ambient on the reconstruction/dispatch boundary — only the
 * REST ingress boundary ({@code IdentityResolutionMiddleware}) installs one as of identity-002
 * P2.S5b-i; installing an ambient {@code InvocationOrigin} on the services/camel/deferred-dispatch
 * boundaries is a separate, later slice. No Phase-1 production path re-captures a reconstructed
 * context — the sole production {@link #capture(SecurityContext)} caller is
 * {@code IdentityResolutionMiddleware} on REST ingress — so this narrowing is presently latent;
 * carrying the remaining fields across a chained re-capture is tracked as a deferred item in the
 * identity-002 PRD (§13).
 *
 * <p>Capture is credential-free <strong>by enforcement</strong>, not merely by the snapshot's
 * record shape: the free-form {@code attributes} map on the actor {@link PrincipalRef}, the
 * optional subject {@link PrincipalRef}, the optional {@link ClientRef}, and every
 * {@link AuthorityClaim} in the captured claims list is application-controlled and the snapshot
 * persists to an unencrypted durable store, so {@link #capture(SecurityContext)} projects each of
 * those attribute maps down to {@link #SYSTEM_REASON_ATTRIBUTE_KEY} before returning the snapshot
 * (PRD-ID-002 FR-ID-CA-003 / NFR-ID2-003, amendment A7). The V1 allowlist is dimension-aware and
 * narrow: it retains the {@code system.reason} key — the literal
 * {@link dev.vertique.security.SystemIdentities} sets on {@code SYSTEM} actors — only on the
 * captured <strong>actor</strong>, only when that actor's {@link PrincipalRef#type()} is
 * {@link dev.vertique.security.PrincipalType#SYSTEM}, and only when the value is a {@link String}
 * of length at most 256. A non-{@code SYSTEM} actor, the subject {@link PrincipalRef}, the
 * {@link ClientRef}, and every {@link AuthorityClaim} always project to an empty attributes map —
 * a subject, client, or claim is never a legitimate {@code system.reason} carrier. The
 * {@code SYSTEM}-type-plus-bounded-{@link String} gate is a trusted-in-process assumption, not an
 * unforgeable boundary: {@link dev.vertique.security.SystemIdentities} is the sanctioned source of
 * a {@code SYSTEM}-typed actor's {@code system.reason}, but in-process code that hand-assembles a
 * {@link PrincipalRef} of type {@code SYSTEM} is already inside the trust boundary this projection
 * step does not police — the gate only bounds what such code can place under the key to at most
 * 256 characters of {@link String} data, so the channel cannot carry bulk data; it does not stop a
 * trusted caller from populating it directly.
 */
public final class DefaultIdentitySnapshotFactory implements IdentitySnapshotFactory {

    private final ContextHolder contextHolder;

    /**
     * Constructs a factory that sources {@link #originSummary()} from the ambient
     * {@link InvocationOrigin} bound on the given {@link ContextHolder} at capture time
     * (identity-002 P2.S5b-i).
     *
     * @param contextHolder the context holder read for the ambient {@link InvocationOrigin} at
     *                      capture time; must not be {@code null}
     */
    public DefaultIdentitySnapshotFactory(ContextHolder contextHolder) {
        this.contextHolder = Objects.requireNonNull(contextHolder, "contextHolder");
    }

    /**
     * The only attribute key a captured snapshot may retain, and only on the actor dimension under
     * the type/value gate enforced by {@link #projectActorAttributes(PrincipalRef)}. Every other
     * key — on the actor or on any other dimension — is dropped at capture time, because those maps
     * are application-controlled and the snapshot persists to an unencrypted durable store
     * (FR-ID-CA-003 / NFR-ID2-003).
     *
     * <p>This is the literal key {@link dev.vertique.security.SystemIdentities} sets on a
     * {@code SYSTEM} actor's attributes (private to that class, so duplicated here as a literal
     * rather than referenced).
     */
    private static final String SYSTEM_REASON_ATTRIBUTE_KEY = "system.reason";

    /**
     * Maximum length of a retained {@link #SYSTEM_REASON_ATTRIBUTE_KEY} value. A value longer than
     * this is dropped rather than retained, bounding the durable snapshot store against an
     * oversized value placed on a {@code SYSTEM} actor's attributes.
     */
    private static final int MAX_SYSTEM_REASON_LENGTH = 256;

    /**
     * Safe-attribute key {@link DefaultIdentityReconstruction} stamps on every resumed/deferred
     * {@link AuthenticationState}, carrying the content's
     * {@link IdentitySnapshotContent#authenticatedAt()} rendered via {@link Instant#toString()}. Read
     * back here as the third-precedence source for
     * {@link #resolveAuthenticatedAt(AuthenticationState, Instant)} so a chained deferral (a
     * re-capture of an already-reconstructed context) never loses the original authentication
     * instant per hop. Duplicated here as a literal, matching the {@code "system.reason"} literal
     * above — {@link DefaultIdentityReconstruction} does not expose it as a public constant.
     */
    private static final String RECONSTRUCTED_AUTHENTICATED_AT_ATTRIBUTE = "identity.reconstructed.authenticatedAt";

    @Override
    public IdentitySnapshotContent capture(SecurityContext live) {
        Objects.requireNonNull(live, "live");
        SecurityIdentity identity = live.identity();
        AuthenticationState auth = live.authentication();

        Optional<DelegationSummary> delegationSummary = identity.delegation()
                .map(delegation -> new DelegationSummary(delegation.kind(), Optional.of(delegation.authorityId())));

        Instant capturedAt = Instant.now();
        Instant authenticatedAt = resolveAuthenticatedAt(auth, capturedAt);

        return new IdentitySnapshotContent(
                projectActor(identity.actor()),
                identity.subject().map(DefaultIdentitySnapshotFactory::projectSubject),
                delegationSummary,
                identity.client().map(DefaultIdentitySnapshotFactory::projectClient),
                auth.primaryMethod().normalizedKind().name(),
                authenticatedAt,
                auth.assurance(),
                live.authorization().claims().stream()
                        .map(DefaultIdentitySnapshotFactory::projectClaim)
                        .toList(),
                originSummary(),
                capturedAt);
    }

    // --- authenticatedAt derivation ---

    /**
     * Resolves {@code authenticatedAt} in decisive-source order: the IdP's reported
     * {@link AuthenticationAssurance#authTime()} when present; otherwise the first (decisive)
     * {@link dev.vertique.security.AuthenticationEvidence#verifiedAt()} entry when
     * {@link AuthenticationState#evidence()} is non-empty; otherwise the live context's carried
     * {@link #RECONSTRUCTED_AUTHENTICATED_AT_ATTRIBUTE} safe attribute when present and parseable
     * (see {@link #reconstructedAuthenticatedAt(AuthenticationState)}); otherwise {@code capturedAt}
     * as the terminal fallback.
     *
     * @param auth       the live context's authentication state to derive from
     * @param capturedAt the capture instant, used as the terminal fallback
     * @return the resolved {@code authenticatedAt} instant
     */
    private static Instant resolveAuthenticatedAt(AuthenticationState auth, Instant capturedAt) {
        Optional<Instant> assuranceAuthTime = auth.assurance().flatMap(AuthenticationAssurance::authTime);
        if (assuranceAuthTime.isPresent()) {
            return assuranceAuthTime.get();
        }
        if (!auth.evidence().isEmpty()) {
            return auth.evidence().get(0).verifiedAt();
        }
        return reconstructedAuthenticatedAt(auth).orElse(capturedAt);
    }

    /**
     * Reads the {@link #RECONSTRUCTED_AUTHENTICATED_AT_ATTRIBUTE} safe attribute carried by a live
     * context that is itself a reconstructed context (a chained deferral re-capturing an
     * already-resumed/deferred context). Defensive: an attribute of an unexpected type, or a value
     * that does not parse as an {@link Instant}, is ignored rather than thrown, so an unexpected
     * shape on this application-readable safe attribute falls through to the terminal
     * {@code capturedAt} fallback instead of failing capture.
     *
     * @param auth the live context's authentication state to read the marker from
     * @return the parsed {@link Instant} when the marker is present and parseable; otherwise empty
     */
    private static Optional<Instant> reconstructedAuthenticatedAt(AuthenticationState auth) {
        Object marker = auth.safeAttributes().get(RECONSTRUCTED_AUTHENTICATED_AT_ATTRIBUTE);
        if (!(marker instanceof String value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Derives a short, policy-bounded summary of the ingress kind from the ambient
     * {@link InvocationOrigin} bound on {@link #contextHolder} at capture time (identity-002
     * P2.S5b-i) — e.g. {@code "rest"} when the REST ingress boundary installed one, {@code "camel"}
     * when a Camel-scoped boundary did, or {@code "unknown"} when no {@link InvocationOrigin} is
     * ambient at all.
     *
     * @return a short origin summary string, never {@code null}
     */
    private String originSummary() {
        return contextHolder
                .current(InvocationOrigin.class)
                .map(InvocationOrigin::kind)
                .orElse("unknown");
    }

    // --- attribute projection (FR-ID-CA-003 / NFR-ID2-003, dimension-aware — amendment A7) ---

    /**
     * Projects a captured actor {@link PrincipalRef}'s free-form attributes down to the
     * type/value-gated {@code system.reason} allowlist enforced by
     * {@link #projectActorAttributes(PrincipalRef)} — the only dimension on which
     * {@code system.reason} may survive capture. Skips rebuilding when {@code ref} already carries
     * no attributes — the shipped {@code DefaultSecurityIdentityResolver} always produces empty
     * attribute maps, so this keeps the default path allocation-free.
     *
     * @param ref the actor principal ref to project
     * @return {@code ref} unchanged when its attributes are already empty; otherwise a new
     *         {@link PrincipalRef} with the same {@code type}/{@code id} and a projected
     *         attributes map
     */
    private static PrincipalRef projectActor(PrincipalRef ref) {
        if (ref.attributes().isEmpty()) {
            return ref;
        }
        return new PrincipalRef(ref.type(), ref.id(), projectActorAttributes(ref));
    }

    /**
     * Applies the actor-only {@code system.reason} allowlist gate: retains the
     * {@link #SYSTEM_REASON_ATTRIBUTE_KEY} entry only when {@code ref}'s
     * {@link PrincipalRef#type()} is {@link PrincipalType#SYSTEM} and the attribute value is a
     * {@link String} of length at most {@link #MAX_SYSTEM_REASON_LENGTH}. A non-{@code SYSTEM}
     * actor, a non-{@code String} value, and an over-length {@code String} value all fall through
     * to an empty result. This gate is a trusted-in-process assumption, not an unforgeable
     * boundary: {@link dev.vertique.security.SystemIdentities} is the sanctioned source of a
     * {@code SYSTEM} actor's {@code system.reason}, and in-process code that hand-assembles a
     * {@code SYSTEM}-typed {@link PrincipalRef} is already inside the trust boundary this gate
     * does not police — it only bounds the value at {@link #MAX_SYSTEM_REASON_LENGTH} characters
     * of {@link String} data so the channel cannot carry bulk data.
     *
     * @param ref the actor principal ref whose attributes are being gated
     * @return an unmodifiable map containing the retained {@code system.reason} entry, or an empty
     *         map when the gate does not pass
     */
    private static Map<String, Object> projectActorAttributes(PrincipalRef ref) {
        if (ref.type() != PrincipalType.SYSTEM) {
            return Map.of();
        }
        Object reason = ref.attributes().get(SYSTEM_REASON_ATTRIBUTE_KEY);
        if (reason instanceof String value && value.length() <= MAX_SYSTEM_REASON_LENGTH) {
            return Map.of(SYSTEM_REASON_ATTRIBUTE_KEY, value);
        }
        return Map.of();
    }

    /**
     * Projects a captured subject {@link PrincipalRef}'s free-form attributes to an empty map
     * unconditionally — a subject is never a legitimate {@code system.reason} carrier, so the
     * actor-only gate in {@link #projectActorAttributes(PrincipalRef)} does not apply here. Skips
     * rebuilding when {@code ref} already carries no attributes.
     *
     * @param ref the subject principal ref to project
     * @return {@code ref} unchanged when its attributes are already empty; otherwise a new
     *         {@link PrincipalRef} with the same {@code type}/{@code id} and an empty attributes
     *         map
     */
    private static PrincipalRef projectSubject(PrincipalRef ref) {
        if (ref.attributes().isEmpty()) {
            return ref;
        }
        return new PrincipalRef(ref.type(), ref.id(), Map.of());
    }

    /**
     * Projects a captured {@link ClientRef}'s free-form attributes to an empty map
     * unconditionally — a client is never a legitimate {@code system.reason} carrier. Skips
     * rebuilding when {@code ref} already carries no attributes.
     *
     * @param ref the client ref to project
     * @return {@code ref} unchanged when its attributes are already empty; otherwise a new
     *         {@link ClientRef} with the same {@code clientId}/{@code source} and an empty
     *         attributes map
     */
    private static ClientRef projectClient(ClientRef ref) {
        if (ref.attributes().isEmpty()) {
            return ref;
        }
        return new ClientRef(ref.clientId(), ref.source(), Map.of());
    }

    /**
     * Projects a captured {@link AuthorityClaim}'s free-form attributes to an empty map
     * unconditionally — a claim is never a legitimate {@code system.reason} carrier. Skips
     * rebuilding when {@code claim} already carries no attributes.
     *
     * @param claim the claim to project
     * @return {@code claim} unchanged when its attributes are already empty; otherwise a new
     *         {@link AuthorityClaim} with the same {@code kind}/{@code value}/{@code issuer}/
     *         {@code audience}/{@code source} and an empty attributes map
     */
    private static AuthorityClaim projectClaim(AuthorityClaim claim) {
        if (claim.attributes().isEmpty()) {
            return claim;
        }
        return new AuthorityClaim(
                claim.kind(), claim.value(), claim.issuer(), claim.audience(), claim.source(), Map.of());
    }
}
