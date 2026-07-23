// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Static, transport-neutral factory for assembling {@link SecurityContext} instances outside any
 * REST middleware.
 *
 * <p>This is the general-assembly API for jobs, cron triggers, message handlers, and any other
 * framework or application code that needs a real {@link SecurityContext} without going through
 * the REST identity-resolution pipeline:
 *
 * <ul>
 *   <li>{@link #system(SecurityIdentity)} — a system-identity preset for cron/scheduled/internal
 *       work executing under a {@link SystemIdentities} identity, with no subject;
 *   <li>{@link #unauthenticated(SecurityIdentity)} — the canonical assembly for non-HTTP ingress
 *       whose principal was asserted but for which no credential was verified;
 *   <li>{@link #assemble(SecurityIdentity, AuthenticationState, AuthorizationClaims, Optional)} —
 *       general-purpose assembly from already-known identity, authentication, and authorization
 *       facts;
 *   <li>{@link #assembleReconstructed(SecurityIdentity, AuthenticationState, AuthorizationClaims,
 *       Optional, ReconstructionMarker)} — framework-mediated assembly for a verified
 *       reconstruction, carrying the typed {@link ReconstructionMarker} that
 *       {@link SecurityContext#reconstruction()} returns.
 * </ul>
 *
 * <p>Mirrors {@link SystemIdentities}: a plain static factory, not a Dagger-provided singleton —
 * transport-neutral context assembly needs no injected collaborator. None of these methods emit
 * {@link dev.vertique.security.events.SecurityEventObserver} events; a static method cannot invoke
 * an injected emitter by construction (CA-007), so consuming flows decide whether and how to audit.
 *
 * <p>Snapshot capture — a credential-free, to-be-signed capture of a live context's identity
 * dimension for durability-boundary carriage — is deliberately <strong>not</strong> on this class.
 * It lives on the separate {@link IdentitySnapshotFactory} capture interface (fixed framework
 * implementation in V1, provided only where snapshot capture is wired), so the general-assembly
 * API and the snapshot-capture seam remain different types with different Dagger placement.
 *
 * @see SystemIdentities
 * @see IdentitySnapshotFactory
 */
public final class SecurityContexts {

    private SecurityContexts() {
        /* utility class — no instances */
    }

    /**
     * Creates a {@link SecurityContext} carrying the given service identity as-is, for jobs, cron
     * triggers, and message handlers that execute with no request-scoped identity of their own.
     *
     * <p>The identity's {@link SecurityIdentity#actor() actor} MUST be a
     * {@link PrincipalType#SYSTEM} principal — the only sanctioned path to one is
     * {@link SystemIdentities}. Stamping a {@code custom("system")} authentication method on a
     * {@code USER}/{@code SERVICE}/{@code ANONYMOUS} actor would misattribute an asserted principal
     * as system-acting (ADR-0163), so a non-{@code SYSTEM} actor is rejected.
     *
     * <p>The identity must also be <strong>actor-only</strong>: {@link SecurityIdentity#subject()},
     * {@link SecurityIdentity#delegation()}, and {@link SecurityIdentity#client()} must all be
     * {@link Optional#empty()}. {@code system(...)} mints framework-scheduled work with no external
     * trigger and no asserted external principal, so a subject, delegation, or client — each of
     * which implies an asserted external principal or authority grant — is rejected.
     *
     * @param serviceIdentity the system identity to carry; must be produced by
     *                        {@link SystemIdentities} (i.e. carry a {@link PrincipalType#SYSTEM}
     *                        actor and no subject, delegation, or client); must not be {@code null}
     * @return a new {@link SecurityContext} whose {@link SecurityContext#identity()} is
     *         {@code serviceIdentity}
     * @throws IllegalArgumentException if {@code serviceIdentity}'s actor is not
     *                                  {@link PrincipalType#SYSTEM}, or if it carries a subject,
     *                                  delegation, or client
     */
    public static SecurityContext system(SecurityIdentity serviceIdentity) {
        Objects.requireNonNull(serviceIdentity, "serviceIdentity");
        PrincipalType actorType = serviceIdentity.actor().type();
        if (actorType != PrincipalType.SYSTEM) {
            throw new IllegalArgumentException(
                    "SecurityContexts.system(...) requires a SYSTEM actor (use SystemIdentities), got: " + actorType);
        }
        if (serviceIdentity.subject().isPresent()
                || serviceIdentity.delegation().isPresent()
                || serviceIdentity.client().isPresent()) {
            throw new IllegalArgumentException("SecurityContexts.system(...) requires an actor-only identity "
                    + "(no subject, delegation, or client)");
        }
        AuthenticationState systemAuth = new AuthenticationState(
                DefaultAuthMethod.custom("system"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new DefaultSecurityContext(serviceIdentity, systemAuth, AuthorizationClaims.empty(), Optional.empty());
    }

    /**
     * Creates a {@link SecurityContext} for a principal that was asserted (by the framework default
     * or by the application) but for which the framework verified <strong>no credential</strong> —
     * the canonical assembly for non-HTTP ingress such as file/queue drops. The authentication
     * dimension is {@link DefaultAuthMethod#none()} with empty evidence, no assurance, and no tokens.
     *
     * <p>Distinct from {@link #system(SecurityIdentity)}: {@code system(...)} is for
     * framework-scheduled work with no external trigger and no asserted external principal (cron,
     * delayed jobs) and carries a {@code custom("system")} method. Use {@code unauthenticated(...)}
     * when there IS an external trigger and an asserted principal — including an
     * application-asserted user — so the authentication dimension stays honest (kind {@code NONE}).
     *
     * @param identity the asserted principal to carry; must not be {@code null}
     * @return a new {@link SecurityContext} whose {@link SecurityContext#identity()} is
     *         {@code identity}, with {@code none()} authentication, empty authorization claims, and
     *         no origin
     */
    public static SecurityContext unauthenticated(SecurityIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        AuthenticationState noAuth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new DefaultSecurityContext(identity, noAuth, AuthorizationClaims.empty(), Optional.empty());
    }

    /**
     * Assembles a {@link SecurityContext} from already-known identity, authentication, and
     * authorization facts, for use outside any REST middleware.
     *
     * @param identity the identity aggregate to carry; must not be {@code null}
     * @param auth     the authentication state to carry; must not be {@code null}
     * @param claims   the authorization claims to carry; must not be {@code null}
     * @param origin   the optional network-envelope origin to carry; must not be {@code null} as
     *                 an {@code Optional}
     * @return a new {@link SecurityContext} exposing exactly the given identity, authentication,
     *         authorization, and origin
     */
    public static SecurityContext assemble(
            SecurityIdentity identity,
            AuthenticationState auth,
            AuthorizationClaims claims,
            Optional<RequestOrigin> origin) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(origin, "origin");
        return new DefaultSecurityContext(identity, auth, claims, origin);
    }

    /**
     * Assembles a framework-mediated {@link SecurityContext} for a verified reconstruction,
     * carrying the typed {@link ReconstructionMarker} that {@link SecurityContext#reconstruction()}
     * returns.
     *
     * <p>The <strong>only</strong> sanctioned producers of this method's result are the framework's
     * verified-reconstruction path ({@code DefaultIdentityReconstruction}) and the Mode-2
     * authorizer's own evaluation-context rebuild — never application code assembling an ordinary,
     * live-authored context (use {@link #assemble} for that). This method carries no stronger trust
     * guarantee than {@link #assemble} — the returned context's {@link ReconstructionMarker} is a
     * typed signal, not a cryptographic proof; see {@link ReconstructionMarker}'s own javadoc for the
     * trust model it is (and is not) a guarantee of.
     *
     * @param identity the identity aggregate to carry; must not be {@code null}
     * @param auth     the authentication state to carry; must not be {@code null}
     * @param claims   the authorization claims to carry; must not be {@code null}
     * @param origin   the optional network-envelope origin to carry; must not be {@code null} as
     *                 an {@code Optional}
     * @param marker   the typed verified-reconstruction signal to carry; must not be {@code null}
     * @return a new {@link SecurityContext} exposing exactly the given facts, whose
     *         {@link SecurityContext#reconstruction()} returns {@code Optional.of(marker)}
     */
    public static SecurityContext assembleReconstructed(
            SecurityIdentity identity,
            AuthenticationState auth,
            AuthorizationClaims claims,
            Optional<RequestOrigin> origin,
            ReconstructionMarker marker) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(marker, "marker");
        return new ReconstructedSecurityContext(identity, auth, claims, origin, marker);
    }
}
