// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.DelegationContext;
import dev.vertique.security.DelegationGrantDecision;
import dev.vertique.security.DelegationGrantValidator;
import dev.vertique.security.DelegationReasonCodes;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequirementDescriptor;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AuthorizationNarrower} that bounds a delegated evaluation to the intersection of the
 * actor's base authority and the delegation grant's scope (PRD identity-002 FR-ID-DG-006).
 *
 * <p><strong>Non-delegated contexts pass through unchanged.</strong> {@link SecurityIdentity#subject()}
 * and {@link SecurityIdentity#delegation()} must both be present for a context to count as delegated.
 * When either is absent, {@link #narrow} returns {@code base} untouched and {@link #requirementFor}
 * reports {@link Optional#empty()} — this narrower only acts on on-behalf-of evaluations.
 *
 * <p><strong>Framework-scheduled deferred work is not grant-backed delegation.</strong> A
 * {@link DelegationContext} whose {@link DelegationContext#kind()} is
 * {@link DelegationContext#DEFERRED_EXECUTION_KIND} records a framework-mediated scheduling
 * relationship (see {@code IdentityReconstruction#deferredExecution}) — not a grant a principal
 * requested. Its authority is the executing service's own, already resolved live by Mode 2
 * ({@code ReconstructedAuthorityResolvingAuthorizer}); there is no grant to intersect against, so
 * {@link #narrow} passes {@code base} through unchanged without ever consulting
 * {@link #validator} — validating the synthetic scheduling id as a grant would deny every
 * reconstructed deferred action (FR-ID-DG-006 excludes this path from grant-scope intersection).
 *
 * <p><strong>The pass-through is gated on the TYPED reconstruction signal, never the string
 * {@code kind} alone.</strong> {@link DelegationContext} is a public core type: a live
 * (non-reconstructed), ordinary context could carry a {@code kind} equal to
 * {@link DelegationContext#DEFERRED_EXECUTION_KIND} — either through misconfiguration or a forged
 * value — and keying the pass-through on {@code kind} alone would fail-open, skipping actor &cap;
 * grant validation and keeping the base context's authority unearned. Per
 * {@link dev.vertique.security.SecurityContext#reconstruction()}'s own contract, the pass-through
 * therefore requires <strong>both</strong> {@code kind == DEFERRED_EXECUTION_KIND} <strong>and</strong>
 * {@code request.securityContext().reconstruction().isPresent()} — i.e. the context must be a
 * framework-verified reconstruction, never a live-authored context regardless of what {@code kind}
 * it carries. A live context with this {@code kind} is validated as an ordinary grant-backed
 * delegation instead (and typically fails closed, since the synthetic scheduling id is not a real
 * grant) — never silently passed through with the base decision's authority.
 *
 * <p><strong>Delegated contexts enforce intersection.</strong> For a genuinely grant-backed
 * delegated context, the evaluation the base engine already permitted must also be covered by the
 * delegation grant named by {@link DelegationContext#authorityId()}:
 *
 * <ul>
 *   <li>base PERMIT + grant PERMIT &rarr; the permit survives (intersection satisfied).
 *   <li>base PERMIT + grant DENY (not found, expired, or out of scope) &rarr; narrowed to DENY, with
 *       the grant's own reason code (e.g. {@code GRANT_EXPIRED}) surfaced as this decision's
 *       {@link AuthorizationDecision#reasonCode()} — the grant simply does not cover this request.
 *   <li>base DENY (regardless of the grant) &rarr; the base deny survives unchanged and no grant
 *       lookup is even attempted; this narrower never adds authority the actor lacks, so the grant's
 *       outcome cannot change a deny into a permit (the {@code NarrowingAuthorizer} no-widen guard
 *       backstops this, but this narrower does not rely on it).
 * </ul>
 *
 * <p><strong>Grant-id correlation and lookup failure.</strong> A permit from {@link #validator} is
 * trusted only when its {@link DelegationGrantDecision#grantId()} equals the requested
 * {@link DelegationContext#authorityId()} — a miscorrelating validator that permits a different
 * grant than the one requested fails closed to {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED}
 * rather than silently stamping the requested authorityId onto an unrelated permit. Likewise, a
 * validator that violates its own "never a failed future" contract (a synchronous throw or a
 * genuinely failed {@link Future}) is defended against: any such exceptional path also fails closed
 * to {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED}, preserving this narrower's own contract of
 * always returning an already-succeeded {@link Future} (see {@link #narrow}).
 *
 * <p><strong>Scope mapping (frozen for this narrower).</strong> A
 * {@code dev.vertique.security.DelegationGrant}'s {@code (scopeKind, scopeRef)} is derived from the
 * {@link AuthorizationRequest} as:
 *
 * <ul>
 *   <li>{@code scopeKind} &mdash; the requested action's {@code "<subsystem>.<resource>"} segments
 *       (the {@link ActionRef#subsystem()} and {@link ActionRef#resource()} parsed from
 *       {@link AuthorizationRequest#action()}), matching the {@code "cms.content"}-style example in
 *       {@code DelegationGrant#scopeKind()}'s own javadoc.
 *   <li>{@code scopeRef} &mdash; {@link AuthorizationRequest#resource()}'s
 *       {@code dev.vertique.security.authz.ResourceRef#id()}, matching that field's own "specific
 *       resource instance identifier" semantics.
 * </ul>
 *
 * An action string that does not parse as a canonical {@link ActionRef} fails closed to
 * {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR} rather than validating against an undefined scope.
 * In practice this branch is unreachable through {@code DefaultAuthorizer}, which already fails
 * closed before a malformed action string can reach a PERMIT, but this narrower does not assume a
 * specific base {@code Authorizer} implementation.
 *
 * <p><strong>Audit visibility.</strong> Every decision this narrower produces for a delegated
 * context &mdash; permit or deny &mdash; carries two bounded, audit-safe entries in
 * {@link AuthorizationDecision#safeAttributes()}: {@value #SUBJECT_ATTRIBUTE} (the delegated subject
 * as {@code "<PrincipalType>:<id>"}; never the subject's {@link PrincipalRef#attributes()}, which are
 * non-authoritative, request-scoped provenance, not audit evidence) and
 * {@value #AUTHORITY_ID_ATTRIBUTE} (the grant id evaluated, i.e.
 * {@link DelegationContext#authorityId()}; never the grant's evidence reference). This lets audit
 * output always answer "who was this for, under what grant" for a delegated action.
 *
 * <p><strong>Ordering.</strong> Runs at {@link #priority()} {@value #PRIORITY} ("delegation") within
 * the default {@code dev.vertique.core.extension.ExtensionPhase#APPLICATION} phase &mdash; ahead of
 * the identity-002 assurance narrower (priority 200), so grant-scope enforcement composes before
 * assurance-level narrowing.
 *
 * <p><strong>Introspection is annotate-only.</strong> {@link #requirementFor} never calls
 * {@link DelegationGrantValidator#validate}; it is a synchronous, resource-independent surface that
 * reports every action as gated by the active grant for a genuinely grant-backed delegated actor,
 * letting {@code NarrowingIntrospector} annotate capabilities without a concrete
 * {@code dev.vertique.security.authz.ResourceRef} to evaluate against. A framework-scheduled
 * deferred-execution context — {@code kind == DEFERRED_EXECUTION_KIND} <strong>and</strong>
 * {@link dev.vertique.security.SecurityContext#reconstruction()} present, the same two-part test
 * {@link #narrow} applies — reports {@link Optional#empty()} here too, matching {@link #narrow}'s
 * pass-through. A live context that merely carries that {@code kind} (reconstruction absent) reports
 * the ordinary delegation requirement instead, since {@link #narrow} validates it as a genuine
 * grant for such a context — the narrow-deny and requirement-present outcomes must agree for every
 * context this narrower observes.
 */
public final class DelegationEnforcementNarrower implements AuthorizationNarrower {

    /** Requirement kind reported for actions gated by an active delegation grant. */
    private static final String REQUIREMENT_KIND = "delegation";

    /**
     * {@link AuthorizationDecision#safeAttributes()} key carrying the delegated subject as
     * {@code "<PrincipalType>:<id>"}. Package-private so {@code DelegationEnforcementNarrowerTest} can
     * assert on it directly.
     */
    static final String SUBJECT_ATTRIBUTE = "delegation.subject";

    /**
     * {@link AuthorizationDecision#safeAttributes()} key carrying the grant id evaluated
     * ({@link DelegationContext#authorityId()}). Package-private so
     * {@code DelegationEnforcementNarrowerTest} can assert on it directly.
     */
    static final String AUTHORITY_ID_ATTRIBUTE = "delegation.authorityId";

    /**
     * Fixed {@link #priority()} for this narrower ("delegation"): runs ahead of the identity-002
     * assurance narrower (priority 200) within the default
     * {@code dev.vertique.core.extension.ExtensionPhase#APPLICATION} phase.
     */
    private static final int PRIORITY = 100;

    private final DelegationGrantValidator validator;

    /**
     * Creates the narrower over the given grant validator.
     *
     * @param validator the seam used to evaluate whether a delegation grant currently authorizes the
     *                  evaluated (actor, subject, scope) triple; must not be {@code null}
     */
    @Inject
    public DelegationEnforcementNarrower(DelegationGrantValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(base, "base");
        SecurityIdentity identity = request.securityContext().identity();
        Optional<PrincipalRef> subjectOpt = identity.subject();
        Optional<DelegationContext> delegationOpt = identity.delegation();
        if (subjectOpt.isEmpty() || delegationOpt.isEmpty()) {
            return Future.succeededFuture(base);
        }
        PrincipalRef subject = subjectOpt.get();
        DelegationContext delegation = delegationOpt.get();

        if (isFrameworkScheduledDeferredExecution(delegation, request.securityContext())) {
            // Framework-scheduled deferred work — not a grant to validate; the actor's own
            // (already live-resolved) authority governs. Never deny-all by treating the synthetic
            // scheduling id as a missing/invalid grant. Gated on BOTH the kind and the typed
            // reconstruction signal (see class javadoc) — a live context merely carrying this kind
            // falls through to ordinary grant-backed validation below, never a silent pass-through.
            return Future.succeededFuture(base);
        }

        if (!base.permitted()) {
            // The narrower never adds authority: the base deny survives unmodified (only annotated)
            // and no grant lookup is needed to preserve it.
            return Future.succeededFuture(withDelegationAttributes(base, subject, delegation.authorityId()));
        }

        Optional<ScopeCoordinates> scope = deriveScope(request);
        if (scope.isEmpty()) {
            return Future.succeededFuture(withDelegationAttributes(
                    AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR),
                    subject,
                    delegation.authorityId()));
        }

        PrincipalRef actor = identity.actor();
        ScopeCoordinates coordinates = scope.get();
        String authorityId = delegation.authorityId();
        // Wrapped in an initial succeededFuture().compose(...) so a synchronous throw from a
        // contract-violating DelegationGrantValidator escapes into the .recover() below rather than
        // propagating out of this method synchronously — mirrors NarrowingAuthorizer's composition
        // guard over external SPI implementations. The validator's own contract promises "never a
        // failed future" (a lookup failure already folds into a GRANT_LOOKUP_FAILED
        // DelegationGrantDecision), but .recover() defends against a contract-violating
        // implementation anyway: any exceptional path here — synchronous throw or a genuinely
        // failed Future — fails closed to the same GRANT_LOOKUP_FAILED deny, carrying the same
        // subject/authorityId audit attributes as every other deny path, so this narrower's own
        // contract of always returning an already-succeeded Future (see the class javadoc) holds
        // even against a misbehaving validator.
        return Future.succeededFuture()
                .compose(v -> validator.validate(actor, subject, coordinates.kind(), coordinates.ref(), authorityId))
                .map(grantDecision -> toNarrowedDecision(base, subject, authorityId, grantDecision))
                .recover(t -> Future.succeededFuture(withDelegationAttributes(
                        AuthorizationDecision.deny(DelegationReasonCodes.GRANT_LOOKUP_FAILED), subject, authorityId)));
    }

    /**
     * Converts the grant validator's {@link DelegationGrantDecision} into the narrowed decision,
     * enforcing that a permit's {@link DelegationGrantDecision#grantId()} matches the requested
     * {@code authorityId}. A validator that permits a <strong>different</strong> grant than the one
     * requested (a miscorrelating custom implementation) must not silently stamp the requested
     * {@code authorityId} onto an unrelated permit — this narrower fails closed to
     * {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED} on any such mismatch.
     *
     * @param base          the base decision the delegated evaluation already permitted
     * @param subject       the delegated subject, carried into the annotated decision's
     *                      safeAttributes
     * @param authorityId   the grant id requested ({@link DelegationContext#authorityId()})
     * @param grantDecision the validator's decision for the requested grant
     * @return the narrowed, annotated decision
     */
    private static AuthorizationDecision toNarrowedDecision(
            AuthorizationDecision base,
            PrincipalRef subject,
            String authorityId,
            DelegationGrantDecision grantDecision) {
        if (grantDecision.permitted() && !authorityId.equals(grantDecision.grantId())) {
            return withDelegationAttributes(
                    AuthorizationDecision.deny(DelegationReasonCodes.GRANT_LOOKUP_FAILED), subject, authorityId);
        }
        return grantDecision.permitted()
                ? withDelegationAttributes(base, subject, authorityId)
                : withDelegationAttributes(
                        AuthorizationDecision.deny(grantDecision.reasonCode()), subject, authorityId);
    }

    @Override
    public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(action, "action");
        SecurityIdentity identity = ctx.identity();
        if (identity.subject().isEmpty() || identity.delegation().isEmpty()) {
            return Optional.empty();
        }
        DelegationContext delegation = identity.delegation().get();
        if (isFrameworkScheduledDeferredExecution(delegation, ctx)) {
            // Framework-scheduled deferred work: narrow() passes it through unchanged (see the class
            // javadoc's "Framework-scheduled deferred work" section) — reporting a synthetic
            // "delegation" requirement here would violate the narrow-deny <-> requirement-present
            // Agreement invariant that AgreementInvariantConformanceTest pins.
            return Optional.empty();
        }
        return Optional.of(new RequirementDescriptor(REQUIREMENT_KIND, delegation.authorityId()));
    }

    /**
     * Reports whether {@code delegation} represents genuinely framework-scheduled deferred work — the
     * only case {@link #narrow} passes through and {@link #requirementFor} reports no requirement for.
     *
     * <p>Both conditions are required: {@code delegation.kind()} equal to
     * {@link DelegationContext#DEFERRED_EXECUTION_KIND} <strong>and</strong> {@code ctx}'s
     * {@link SecurityContext#reconstruction()} present. {@link DelegationContext} is a public core
     * type, so a live (non-reconstructed) context could carry this {@code kind} through
     * misconfiguration or forgery; keying off {@code kind} alone would let such a context skip actor
     * &cap; grant validation and keep the base decision's authority unearned (fail-open). Requiring
     * the typed {@link SecurityContext#reconstruction()} signal too — per that accessor's own
     * contract, the sanctioned way to detect a framework-verified reconstruction — closes that gap: a
     * live context with this {@code kind} falls through to ordinary grant-backed validation instead.
     *
     * @param delegation the delegation context to classify; must not be {@code null}
     * @param ctx        the security context {@code delegation} was read from; must not be {@code null}
     * @return {@code true} iff {@code delegation} is the deferred-execution kind AND {@code ctx} is a
     *     verified reconstruction
     */
    private static boolean isFrameworkScheduledDeferredExecution(DelegationContext delegation, SecurityContext ctx) {
        return DelegationContext.DEFERRED_EXECUTION_KIND.equals(delegation.kind())
                && ctx.reconstruction().isPresent();
    }

    /**
     * Derives the {@code (scopeKind, scopeRef)} coordinates a delegation grant is checked against,
     * from the requested action and resource. See the class javadoc's "Scope mapping" section.
     *
     * @param request the request being evaluated
     * @return the derived scope coordinates, or {@link Optional#empty()} if
     *     {@link AuthorizationRequest#action()} does not parse as a canonical {@link ActionRef}
     */
    private static Optional<ScopeCoordinates> deriveScope(AuthorizationRequest request) {
        try {
            ActionRef actionRef = ActionRef.parse(request.action());
            return Optional.of(new ScopeCoordinates(
                    actionRef.subsystem() + "." + actionRef.resource(),
                    request.resource().id()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns a copy of {@code decision} with the delegation audit attributes
     * ({@value #SUBJECT_ATTRIBUTE}, {@value #AUTHORITY_ID_ATTRIBUTE}) merged into its
     * {@link AuthorizationDecision#safeAttributes()}, preserving every other field.
     *
     * @param decision    the decision to annotate
     * @param subject     the delegated subject
     * @param authorityId the grant id evaluated
     * @return the annotated decision; never {@code null}
     */
    private static AuthorizationDecision withDelegationAttributes(
            AuthorizationDecision decision, PrincipalRef subject, String authorityId) {
        Map<String, Object> attributes = new HashMap<>(decision.safeAttributes());
        attributes.put(SUBJECT_ATTRIBUTE, subject.type() + ":" + subject.id());
        attributes.put(AUTHORITY_ID_ATTRIBUTE, authorityId);
        return new AuthorizationDecision(
                decision.permitted(), decision.reasonCode(), decision.policyId(), decision.policyVersion(), attributes);
    }

    /**
     * The {@code (scopeKind, scopeRef)} pair a delegation grant is validated against, derived from an
     * {@link AuthorizationRequest} per the class javadoc's "Scope mapping" section.
     *
     * @param kind the namespaced scope kind (e.g. {@code "cms.content"})
     * @param ref  the scope value within {@code kind} (e.g. a resource id)
     */
    private record ScopeCoordinates(String kind, String ref) {}
}
