// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Objects;

/**
 * Default in-memory {@link Authorizer}: a pure, synchronous decision function over the actor's roles
 * and the requested action.
 *
 * <p>The engine reads {@link AuthorityKind#ROLE} claims off the request's {@link SecurityContext},
 * maps them to policy names via the merged {@link RolePolicyResolver}, resolves those names against
 * the policy catalogue (built once from every {@link PolicyDefinitionSource}), and permits the action
 * iff some {@link Effect#ALLOW} statement's pattern matches it. Every outcome is reported as an
 * already-succeeded {@link Future} carrying an {@link AuthorizationDecision} with a stable
 * {@link AuthzReasonCodes reason code}; the engine never emits an event (ADR-0114) and never throws to
 * signal a denial.
 *
 * <p><strong>Decision order</strong> (first matching rule wins):
 * <ol>
 *   <li>action not in the {@link ActionRegistry} → deny {@link AuthzReasonCodes#ACTION_NOT_REGISTERED}</li>
 *   <li>no {@link AuthorityKind#ROLE} claims → deny {@link AuthzReasonCodes#ROLE_MISSING}</li>
 *   <li>roles map to no policy name → deny {@link AuthzReasonCodes#ROLE_POLICY_MISSING}</li>
 *   <li>a mapped policy name is absent from the catalogue → deny {@link AuthzReasonCodes#POLICY_NOT_FOUND}</li>
 *   <li>some {@link Effect#ALLOW} statement matches → permit {@link AuthzReasonCodes#PERMITTED}</li>
 *   <li>otherwise → deny {@link AuthzReasonCodes#ACTION_NOT_ALLOWED}</li>
 * </ol>
 *
 * <p>Steps 2–6 (the role→policy→action resolution for a registered action) are delegated to the shared
 * {@link AuthzResolution} core, which {@link DefaultAuthorizationIntrospector} also uses — so the
 * single-action verdict here and the set-valued introspection there can never diverge.
 *
 * <p>The whole evaluation is wrapped so any unexpected {@link RuntimeException} (e.g. a misbehaving
 * resolver) <strong>fails closed</strong> to a deny with {@link AuthzReasonCodes#INTERNAL_AUTHZ_ERROR}
 * rather than propagating. The action-only convenience overload also fails closed when handed a
 * {@code null} {@link SecurityContext}.
 */
public final class DefaultAuthorizer implements Authorizer {

    private final ActionRegistry actionRegistry;
    private final AuthzResolution resolution;

    /**
     * Creates the engine from the action registry, the policy source, and the role→policy resolver.
     *
     * <p>The policy catalogue is materialised once at construction (inside {@link AuthzResolution})
     * into an immutable name→policy map. When two sources contribute a policy with the same name the
     * last one wins; duplicate-name rejection across sources is enforced upstream at startup
     * validation (slice 7 wiring).
     *
     * @param actionRegistry     the registry of known actions; must not be {@code null}
     * @param policyDefinitionSource the source of policy definitions; must not be {@code null}
     * @param rolePolicyResolver the resolver mapping roles to policy names; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultAuthorizer(
            ActionRegistry actionRegistry,
            PolicyDefinitionSource policyDefinitionSource,
            RolePolicyResolver rolePolicyResolver) {
        this(actionRegistry, new AuthzResolution(policyDefinitionSource, rolePolicyResolver));
    }

    /**
     * Creates the engine from the action registry and a shared, pre-built {@link AuthzResolution} core.
     *
     * <p>This is the wiring-layer constructor: {@code SecurityAuthzModule} builds the merged,
     * validated {@link AuthzResolution} once (as a {@code @Singleton}) and hands the same instance to
     * both this authorizer and the {@link DefaultAuthorizationIntrospector}, so the single-action
     * verdict and the set-valued introspection can never diverge.
     *
     * @param actionRegistry the registry of known actions; must not be {@code null}
     * @param resolution     the shared role→policy→action resolution core; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultAuthorizer(ActionRegistry actionRegistry, AuthzResolution resolution) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    @Override
    public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        return Future.succeededFuture(evaluate(request.securityContext(), request.action()));
    }

    @Override
    public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        // The record's compact constructor forbids a null context; fail closed here before building it
        // so a null ctx becomes a deny rather than a thrown NullPointerException.
        if (ctx == null) {
            return Future.succeededFuture(AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR));
        }
        AuthorizationRequest request = new AuthorizationRequest(ctx, action.value(), resource, Map.of());
        return authorize(request);
    }

    /**
     * Evaluates the decision for the given context and canonical action string, failing closed on any
     * unexpected error.
     *
     * <p>This adds the action-registration check (deny {@link AuthzReasonCodes#ACTION_NOT_REGISTERED})
     * around the shared {@link AuthzResolution#decide(SecurityContext, ActionRef)} core; the parse and
     * registry-lookup wrapping is itself fail-closed so a malformed action string denies rather than
     * propagating.
     *
     * @param ctx    the security context; never {@code null} (guaranteed by the request invariant)
     * @param action the canonical action string ({@link ActionRef#value()})
     * @return the {@link AuthorizationDecision}; never {@code null}
     */
    private AuthorizationDecision evaluate(SecurityContext ctx, String action) {
        try {
            ActionRef actionRef = ActionRef.parse(action);

            // (1) action must be registered; (2–6) delegated to the shared resolution core
            if (!actionRegistry.contains(actionRef)) {
                return AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_REGISTERED);
            }
            return resolution.decide(ctx, actionRef);
        } catch (RuntimeException e) {
            // Fail closed: an unexpected error (e.g. an unparseable action) never permits.
            return AuthorizationDecision.deny(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        }
    }
}
