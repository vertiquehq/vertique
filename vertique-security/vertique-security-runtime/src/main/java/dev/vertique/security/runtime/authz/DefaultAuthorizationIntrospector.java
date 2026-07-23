// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionCapability;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationIntrospector;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.RolePolicyResolver;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default in-memory, synchronous {@link AuthorizationIntrospector}: the set-valued inverse of
 * {@link DefaultAuthorizer}.
 *
 * <p>It walks the {@link ActionRegistry} and keeps each action whose verdict permits, evaluating every
 * action through the <strong>same shared {@link AuthzResolution} core</strong> that {@link DefaultAuthorizer}
 * uses. This is what makes the agreement invariant hold by construction rather than by parallel logic:
 * for a registered action the authorizer applies the registration check and then {@code decide}, while
 * the introspector calls {@code decide} for that same already-registered action — so an action is in the
 * returned set <em>iff</em> {@link Authorizer#authorize(SecurityContext, ActionRef, dev.vertique.security.authz.ResourceRef)}
 * would permit it.
 *
 * <p>The decision is a pure function of the actor's roles and the static policy catalogue, so the
 * computation is fully in-memory and returns directly (no {@link io.vertx.core.Future}).
 */
public final class DefaultAuthorizationIntrospector implements AuthorizationIntrospector {

    private final ActionRegistry actionRegistry;
    private final AuthzResolution resolution;

    /**
     * Creates the introspector from the action registry, the policy source, and the role→policy resolver.
     *
     * <p>The policy catalogue is materialised once at construction (inside {@link AuthzResolution}) into
     * an immutable name→policy map, identical to the one {@link DefaultAuthorizer} builds from the same
     * inputs.
     *
     * @param actionRegistry         the registry of known actions; must not be {@code null}
     * @param policyDefinitionSource the source of policy definitions; must not be {@code null}
     * @param rolePolicyResolver     the resolver mapping roles to policy names; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultAuthorizationIntrospector(
            ActionRegistry actionRegistry,
            PolicyDefinitionSource policyDefinitionSource,
            RolePolicyResolver rolePolicyResolver) {
        this(actionRegistry, new AuthzResolution(policyDefinitionSource, rolePolicyResolver));
    }

    /**
     * Creates the introspector from the action registry and a shared, pre-built {@link AuthzResolution}.
     *
     * <p>This is the wiring-layer constructor: {@code SecurityAuthzModule} builds the merged,
     * validated {@link AuthzResolution} once (as a {@code @Singleton}) and hands the same instance to
     * both this introspector and the {@link DefaultAuthorizer}, so the set-valued introspection and the
     * single-action verdict can never diverge.
     *
     * @param actionRegistry the registry of known actions; must not be {@code null}
     * @param resolution     the shared role→policy→action resolution core; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public DefaultAuthorizationIntrospector(ActionRegistry actionRegistry, AuthzResolution resolution) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.resolution = Objects.requireNonNull(resolution, "resolution");
    }

    @Override
    public Set<ActionRef> allowedActions(SecurityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return actionRegistry.actions().stream()
                .map(ActionDefinition::ref)
                .filter(action -> resolution.decide(ctx, action).permitted())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The base engine installs no {@link dev.vertique.security.authz.AuthorizationNarrower}s, so
     * every returned {@link ActionCapability} carries an un-annotated (empty) requirement set.
     * Narrower-contributed annotations are applied by the wrapping {@code NarrowingIntrospector}.
     */
    @Override
    public Set<ActionCapability> capabilities(SecurityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return allowedActions(ctx).stream()
                .map(action -> new ActionCapability(action, Set.of()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
