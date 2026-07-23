// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.services.security.AuthzEventCollector;
import dev.vertique.examples.services.security.AuthzProbeActionContributor;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyDefinitionSource;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.authz.InMemoryPolicyDefinitionSource;
import dev.vertique.security.runtime.authz.InMemoryRolePolicyResolver;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Programmatic authorization wiring for the example-services {@code @RequiresAction} proof.
 *
 * <p>This module contributes, via Dagger {@code @IntoSet} multibinding consumed by
 * {@code SecurityAuthzModule}:
 * <ul>
 *   <li>the {@link AuthzProbeActionContributor} that registers the {@code svc.probe.run} action;</li>
 *   <li>a {@link PolicyDefinitionSource} ({@code probe-policy}) allowing {@code svc.probe.run};</li>
 *   <li>a {@link RolePolicyResolver} granting {@code probe-policy} to the {@code prober} role (and to
 *       no other role, so {@code viewer} is denied).</li>
 * </ul>
 *
 * <p>It also contributes a capturing {@link SecurityEventObserver} that records every emitted
 * {@link AuthorizationDecisionEvent} into the shared {@link AuthzEventCollector}, so an integration
 * test can assert the enforcement point emitted exactly one decision event per gated dispatch.
 *
 * <p>This is example/proof wiring; a real application would source policies and role mappings from
 * configuration (the {@code vertique-config-core} config-backed sources) rather than hard-coding
 * them here.
 */
@Module
public class AuthzModule {

    /** Policy name granting the {@code svc.probe.run} action. */
    private static final String PROBE_POLICY = "probe-policy";

    /** Role that the proof grants {@link #PROBE_POLICY} to. */
    private static final String PROBER_ROLE = "prober";

    /**
     * Contributes the {@code svc.probe.run} action declaration so the interceptor's startup
     * validation passes and the guarded operation can deploy.
     *
     * @param contributor the singleton action contributor
     * @return the contributor, added to the action-contributor multibinding set
     */
    @Provides
    @IntoSet
    static ActionContributor probeActionContributor(AuthzProbeActionContributor contributor) {
        return contributor;
    }

    /**
     * Contributes a programmatic policy source allowing the {@code svc.probe.run} action.
     *
     * @return a {@link PolicyDefinitionSource} whose single {@code probe-policy} allows
     *     {@code svc.probe.run}
     */
    @Provides
    @IntoSet
    static PolicyDefinitionSource probePolicySource() {
        PolicyDefinition policy = new PolicyDefinition(
                PROBE_POLICY,
                List.of(new PolicyStatement(
                        Effect.ALLOW, Set.of(new ActionPattern(AuthzProbeActionContributor.PROBE_RUN.value())))));
        return new InMemoryPolicyDefinitionSource(List.of(policy));
    }

    /**
     * Contributes a programmatic role-to-policy resolver granting {@link #PROBE_POLICY} to the
     * {@code prober} role only.
     *
     * @return a {@link RolePolicyResolver} mapping {@code prober -> [probe-policy]}
     */
    @Provides
    @IntoSet
    static RolePolicyResolver probeRoleResolver() {
        return new InMemoryRolePolicyResolver(Map.of(PROBER_ROLE, List.of(PROBE_POLICY)));
    }

    /**
     * Contributes a {@link SecurityEventObserver} that records every authorization decision event
     * into the shared {@link AuthzEventCollector}.
     *
     * @param collector the shared event/invocation collector; must not be {@code null}
     * @return an observer that appends each {@link AuthorizationDecisionEvent} to the collector
     */
    @Provides
    @IntoSet
    static SecurityEventObserver capturingAuthzObserver(AuthzEventCollector collector) {
        return new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                collector.recordEvent(event);
                return Future.succeededFuture();
            }
        };
    }
}
