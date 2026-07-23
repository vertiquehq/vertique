// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.security.DelegationGrant;
import dev.vertique.security.DelegationGrantDecision;
import dev.vertique.security.DelegationGrantValidator;
import dev.vertique.security.DelegationReasonCodes;
import dev.vertique.security.PrincipalRef;
import io.vertx.core.Future;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Framework-shipped, in-memory {@link DelegationGrantValidator} default (PRD identity-002
 * FR-ID-DG-003).
 *
 * <p>Holds a fixed, immutable set of {@link DelegationGrant}s supplied at construction time — the same
 * dependency-clean-default shape as {@code InMemoryPolicyDefinitionSource} /
 * {@code InMemoryRolePolicyResolver} — and ships the expiry and scope checks the FR requires of the
 * framework default. Durable storage and revocation lookup are consumer-owned implementations of the
 * same {@link DelegationGrantValidator} seam.
 *
 * <p>{@link #validate(PrincipalRef, PrincipalRef, String, String, String)} maps {@code actor} to the
 * grant's {@link DelegationGrant#grantee()} — the principal exercising the grant — and {@code subject}
 * to the grant's {@link DelegationGrant#grantor()} — the principal on whose behalf the action is taken.
 * This is the same actor/subject direction {@code IdentityReconstruction.deferredExecution} and
 * FR-ID-DG-004 use (actor = the one acting, subject = the one on whose behalf). Principal matching
 * compares only {@code (type, id)}, never {@code attributes} (FR-ID-CA-012 — attributes are
 * non-authoritative, request-scoped provenance).
 *
 * <p><strong>Fail-closed:</strong> any exception raised while evaluating a request (a malformed input,
 * an unexpected internal failure, or a backing-store lookup failure) is caught and mapped to a deny
 * with reason {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED} — {@link #validate} never returns a
 * failed {@link Future}.
 */
public final class InMemoryDelegationGrantValidator implements DelegationGrantValidator {

    private final Map<String, DelegationGrant> grantsById;
    private final Clock clock;

    /**
     * Creates a validator over the given grants, using the system UTC clock for expiry checks.
     *
     * @param grants the grants this validator evaluates against; must not be {@code null} or contain
     *               {@code null} entries
     */
    public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants) {
        this(grants, Clock.systemUTC());
    }

    /**
     * Creates a validator over the given grants, using the given clock for expiry checks.
     *
     * @param grants the grants this validator evaluates against; must not be {@code null} or contain
     *               {@code null} entries
     * @param clock  the clock supplying "now" for expiry checks; injected so expiry evaluation is
     *               deterministic under test; must not be {@code null}
     */
    public InMemoryDelegationGrantValidator(Collection<DelegationGrant> grants, Clock clock) {
        Objects.requireNonNull(grants, "grants");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grantsById =
                grants.stream().collect(Collectors.toUnmodifiableMap(DelegationGrant::grantId, grant -> grant));
    }

    /**
     * Visible for testing: injects the backing map directly (e.g. a stub that throws on lookup) so the
     * fail-closed {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED} path can be exercised without a
     * public API for it. (The project has no {@code @VisibleForTesting} annotation; this note records
     * the intent.)
     *
     * @param grantsById the backing map, used as-is (not defensively copied); must not be {@code null}
     * @param clock      the clock supplying "now" for expiry checks; must not be {@code null}
     */
    InMemoryDelegationGrantValidator(Map<String, DelegationGrant> grantsById, Clock clock) {
        this.grantsById = Objects.requireNonNull(grantsById, "grantsById");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Future<DelegationGrantDecision> validate(
            PrincipalRef actor, PrincipalRef subject, String scopeKind, String scopeRef, String grantId) {
        try {
            return Future.succeededFuture(evaluate(actor, subject, scopeKind, scopeRef, grantId));
        } catch (RuntimeException e) {
            return Future.succeededFuture(lookupFailed(grantId));
        }
    }

    /**
     * Looks up the grant and checks actor/subject direction, scope, and expiry, in that order.
     *
     * @param actor     the principal exercising the grant
     * @param subject   the principal the action is on behalf of
     * @param scopeKind the namespaced kind of the scope being evaluated
     * @param scopeRef  the scope value within {@code scopeKind}
     * @param grantId   the grant id to evaluate
     * @return the resulting decision; never {@code null}
     */
    private DelegationGrantDecision evaluate(
            PrincipalRef actor, PrincipalRef subject, String scopeKind, String scopeRef, String grantId) {
        DelegationGrant grant = grantsById.get(grantId);
        if (grant == null) {
            return new DelegationGrantDecision(false, DelegationReasonCodes.GRANT_NOT_FOUND, grantId, Optional.empty());
        }
        boolean directionMatches = samePrincipal(actor, grant.grantee()) && samePrincipal(subject, grant.grantor());
        boolean scopeMatches =
                grant.scopeKind().equals(scopeKind) && grant.scopeRef().equals(scopeRef);
        if (!directionMatches || !scopeMatches) {
            return new DelegationGrantDecision(
                    false, DelegationReasonCodes.GRANT_OUT_OF_SCOPE, grantId, Optional.of(grant.expiresAt()));
        }
        if (!clock.instant().isBefore(grant.expiresAt())) {
            return new DelegationGrantDecision(
                    false, DelegationReasonCodes.GRANT_EXPIRED, grantId, Optional.of(grant.expiresAt()));
        }
        return new DelegationGrantDecision(
                true, DelegationReasonCodes.GRANT_VALID, grantId, Optional.of(grant.expiresAt()));
    }

    /**
     * Builds the fail-closed {@code GRANT_LOOKUP_FAILED} decision for an internal evaluation failure.
     *
     * @param grantId the grant id that was being evaluated when the failure occurred; may be
     *                {@code null} (an invalid caller input is itself a fail-closed case)
     * @return the fail-closed decision
     */
    private static DelegationGrantDecision lookupFailed(String grantId) {
        return new DelegationGrantDecision(
                false, DelegationReasonCodes.GRANT_LOOKUP_FAILED, grantId == null ? "" : grantId, Optional.empty());
    }

    /**
     * Compares two principal refs by their durable identity tuple {@code (type, id)} only, never
     * consulting {@code attributes} (FR-ID-CA-012).
     *
     * @param a the first principal ref
     * @param b the second principal ref
     * @return {@code true} iff both refs share the same {@code (type, id)} tuple
     */
    private static boolean samePrincipal(PrincipalRef a, PrincipalRef b) {
        return a.type() == b.type() && a.id().equals(b.id());
    }
}
