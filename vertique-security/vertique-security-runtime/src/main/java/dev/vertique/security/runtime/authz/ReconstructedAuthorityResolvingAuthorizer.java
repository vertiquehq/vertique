// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link Authorizer} decorator implementing Mode 2 (and the Mode-3 passthrough) of
 * reconstructed-context authorization: it re-resolves a verified reconstruction's
 * <strong>current</strong> authority live, at authorization time, rather than trusting the durable
 * snapshot's captured claims — <strong>except</strong> when the reconstruction's own
 * {@link ReconstructionMarker#mode()} is {@link ReconstructedAuthorityMode#CAPTURED}, in which case
 * the captured claims already carried by the context ARE current authority and are evaluated as-is
 * (PRD identity-002 §14.3 Phase-2 Appendix).
 *
 * <p>This is the <strong>outermost</strong> authorizer in the wired chain:
 * {@code ReconstructedAuthorityResolvingAuthorizer} &rarr; {@link NarrowingAuthorizer} &rarr;
 * {@code DefaultAuthorizer}. Its behavior turns entirely on
 * {@link SecurityContext#reconstruction()} — the typed, framework-mediated verified-reconstruction
 * signal (see {@link ReconstructionMarker}'s javadoc for the trust model it is and is not a
 * guarantee of) — <strong>never</strong> the descriptive {@code identity.reconstructed=true} string
 * attribute on {@link dev.vertique.security.AuthenticationState#safeAttributes()}:
 *
 * <ul>
 *   <li><strong>{@code reconstruction()} empty (a normal, live-authored context).</strong> The
 *       request passes through to the wrapped {@link #inner} unchanged; the
 *       {@link PrincipalAuthorityResolver} is never consulted.</li>
 *   <li><strong>{@code reconstruction()} present with mode {@code CAPTURED}.</strong> The captured
 *       claims already carried by the context's {@link SecurityContext#authorization()} are
 *       trusted as current authority; the resolver is never consulted and the context is never
 *       rebuilt. {@link #inner} evaluates the request against the original context unchanged.</li>
 *   <li><strong>{@code reconstruction()} present with mode {@code ATTRIBUTION_ONLY}.</strong> The
 *       context's own {@link SecurityContext#identity()}{@code .}{@link
 *       dev.vertique.security.SecurityIdentity#actor() actor()} — <strong>never</strong> {@link
 *       dev.vertique.security.SecurityIdentity#subject() subject()} — is resolved to a durable
 *       {@link PrincipalKey} and re-resolved <em>exactly once</em> per
 *       {@link #authorize(AuthorizationRequest)} call via {@link PrincipalAuthorityResolver} —
 *       never cached across dispatches. Per FR-ID-DG-006, v1 delegation resolves only the acting
 *       principal's own authority (intersected with any grant scope by a downstream narrower);
 *       subject-authority evaluation (impersonation) is explicitly out of v1 scope, so the
 *       subject-of-record is never the resolved principal here. The resolved claims replace the
 *       context's authorization dimension in an <strong>evaluation-only</strong> rebuild (built via
 *       {@link SecurityContexts#assembleReconstructed}, so the rebuilt context remains a
 *       reconstructed-typed context, carrying the SAME marker, for any downstream assurance
 *       check); the original context and its holder are never mutated. {@link #inner} then
 *       evaluates the request against that rebuilt context — including any
 *       {@link AuthorizationNarrower} folded into {@link NarrowingAuthorizer}, which can still
 *       only <em>remove</em> authority the live resolution granted, never add to it.</li>
 * </ul>
 *
 * <p><strong>Fails closed.</strong> A resolver failure, timeout, or ambiguous result — reported as
 * a failed {@link Future} from {@link PrincipalAuthorityResolver#resolve(PrincipalKey)} — denies
 * with reason {@link AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED} rather than falling back to the
 * snapshot's captured claims or propagating the failure. The frozen snapshot's own
 * {@link SecurityContext#authorization()} is never consulted as a trust source in the live
 * re-resolution path; only the resolver's live answer governs there.
 *
 * <p>Every decision this class produces for a reconstructed context — live-resolved permit,
 * resolver-failure deny, or captured passthrough — is stamped with {@link #AUTHORITY_MODE_ATTRIBUTE}
 * (an alias of {@link ReconstructedAuthorityMode#DECISION_ATTRIBUTE}) carrying the
 * {@link ReconstructedAuthorityMode} name that produced it, in
 * {@link AuthorizationDecision#safeAttributes()}, so downstream audit projections (the
 * {@code AuthorizationDecisionEvent}, which embeds the request and decision) can distinguish which
 * strategy produced a given decision — a decision with no {@link #AUTHORITY_MODE_ATTRIBUTE} entry
 * is attribution-only by absence. Non-reconstructed passthrough decisions are never stamped.
 */
@Slf4j
public final class ReconstructedAuthorityResolvingAuthorizer implements Authorizer {

    /**
     * The {@link AuthorizationDecision#safeAttributes()} key this decorator stamps with the
     * {@link ReconstructedAuthorityMode} name that produced the decision. Public alias of
     * {@link ReconstructedAuthorityMode#DECISION_ATTRIBUTE} — the canonical constant now lives on
     * the enum so {@code AuthorizationDecisionEvent} can reference it without depending on this
     * runtime-module decorator.
     */
    public static final String AUTHORITY_MODE_ATTRIBUTE = ReconstructedAuthorityMode.DECISION_ATTRIBUTE;

    private final Authorizer inner;
    private final PrincipalAuthorityResolver resolver;

    /**
     * Creates the decorator from the wrapped inner authorizer and the live authority resolver.
     *
     * @param inner    the wrapped inner {@link Authorizer} (typically a {@link NarrowingAuthorizer}
     *                 wrapping the default engine); must not be {@code null}
     * @param resolver the {@link PrincipalAuthorityResolver} used to re-resolve a reconstructed
     *                 principal's current authority; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ReconstructedAuthorityResolvingAuthorizer(Authorizer inner, PrincipalAuthorityResolver resolver) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        SecurityContext ctx = request.securityContext();
        Optional<ReconstructionMarker> marker = ctx.reconstruction();
        if (marker.isEmpty()) {
            // Not a verified reconstruction: passthrough unchanged, resolver never consulted.
            return inner.authorize(request);
        }
        if (marker.get().mode() == ReconstructedAuthorityMode.CAPTURED) {
            // Mode 3: the context's own captured claims ARE current authority — never thaw a
            // CAPTURED context into a live re-resolve, and never consult the resolver.
            return evaluateCaptured(request);
        }
        // ATTRIBUTION_ONLY (the only other sanctioned marker value): live-resolve the CONTEXT'S OWN
        // ACTOR — never the subject-of-record. Per FR-ID-DG-006, subject-authority evaluation
        // (impersonation) is out of v1 scope; both resumeAsPrincipal (actor == the resumed
        // principal) and deferredExecution (actor == the executing service) resolve the acting
        // principal's own current authority.
        PrincipalRef actor = ctx.identity().actor();
        PrincipalKey principal = new PrincipalKey(actor.type(), actor.id());
        // Wrapped in an initial succeededFuture().compose(...) so a synchronous throw from the
        // resolver fails the returned Future rather than escaping this method synchronously.
        return Future.succeededFuture()
                .compose(v -> resolver.resolve(principal))
                .compose(liveClaims -> evaluateWithLiveClaims(request, marker.get(), liveClaims))
                .recover(t -> {
                    // WARN carries only the principal TYPE and the stable reason code — never the
                    // resolver's raw failure message, which a store-backed PrincipalAuthorityResolver
                    // implementation could populate with connection strings, credentials, or a
                    // principal identifier (CWE-532). The full throwable (and its message) is
                    // available at DEBUG only, for local diagnosis.
                    log.warn(
                            "PrincipalAuthorityResolver failed to re-resolve current authority for a "
                                    + "reconstructed principal (type={}); denying with {}",
                            principal.type(),
                            AuthzReasonCodes.AUTHORITY_RESOLUTION_FAILED);
                    log.debug("PrincipalAuthorityResolver failure detail for principal (type={})", principal.type(), t);
                    return Future.succeededFuture(withAuthorityMode(
                            AuthorizationDecision.deny(AuthzReasonCodes.AUTHORITY_RESOLUTION_FAILED),
                            ReconstructedAuthorityMode.LIVE_RESOLVED));
                });
    }

    @Override
    public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        if (ctx == null) {
            // Mirrors NarrowingAuthorizer: a null context fails closed before any
            // AuthorizationRequest can be built, so the Mode-2 path never participates in this
            // outcome.
            return Future.succeededFuture().compose(v -> inner.authorize(null, action, resource));
        }
        return authorize(new AuthorizationRequest(ctx, action.value(), resource, Map.of()));
    }

    /**
     * Rebuilds an evaluation-only reconstructed context carrying the resolver's live claims, then
     * delegates to {@link #inner} and stamps {@link ReconstructedAuthorityMode#LIVE_RESOLVED} onto
     * the resulting decision.
     *
     * @param request    the original request (its {@code securityContext} is never mutated)
     * @param marker     the reconstruction marker carried by the original context, re-attached to
     *                   the evaluation-only rebuild
     * @param liveClaims the resolver's live authority answer for the reconstructed principal
     * @return a future carrying the inner authorizer's decision, stamped with the live-resolved
     *         authority mode
     */
    private Future<AuthorizationDecision> evaluateWithLiveClaims(
            AuthorizationRequest request, ReconstructionMarker marker, AuthorizationClaims liveClaims) {
        SecurityContext original = request.securityContext();
        SecurityContext evalCtx = SecurityContexts.assembleReconstructed(
                original.identity(), original.authentication(), liveClaims, original.origin(), marker);
        AuthorizationRequest evalRequest = new AuthorizationRequest(
                evalCtx, request.action(), request.resource(), request.origin(), request.context());
        return inner.authorize(evalRequest)
                .map(decision -> withAuthorityMode(decision, ReconstructedAuthorityMode.LIVE_RESOLVED));
    }

    /**
     * Evaluates a Mode-3 {@link ReconstructedAuthorityMode#CAPTURED} context as-is through
     * {@link #inner} — the resolver is never consulted and the context is never rebuilt, since its
     * {@link SecurityContext#authorization()} already carries the trusted captured claims — then
     * stamps {@link ReconstructedAuthorityMode#CAPTURED} onto the resulting decision.
     *
     * @param request the original request, evaluated unchanged
     * @return a future carrying the inner authorizer's decision, stamped with the captured
     *         authority mode
     */
    private Future<AuthorizationDecision> evaluateCaptured(AuthorizationRequest request) {
        return inner.authorize(request)
                .map(decision -> withAuthorityMode(decision, ReconstructedAuthorityMode.CAPTURED));
    }

    /**
     * Returns a copy of {@code decision} with {@link #AUTHORITY_MODE_ATTRIBUTE} added to its
     * {@link AuthorizationDecision#safeAttributes()}, preserving every other field.
     *
     * @param decision the decision to stamp
     * @param mode     the authority mode that produced {@code decision}
     * @return a new {@link AuthorizationDecision} identical to {@code decision} except for the
     *         added mode attribute
     */
    private static AuthorizationDecision withAuthorityMode(
            AuthorizationDecision decision, ReconstructedAuthorityMode mode) {
        Map<String, Object> stamped = new LinkedHashMap<>(decision.safeAttributes());
        stamped.put(AUTHORITY_MODE_ATTRIBUTE, mode.name());
        return new AuthorizationDecision(
                decision.permitted(), decision.reasonCode(), decision.policyId(), decision.policyVersion(), stamped);
    }
}
