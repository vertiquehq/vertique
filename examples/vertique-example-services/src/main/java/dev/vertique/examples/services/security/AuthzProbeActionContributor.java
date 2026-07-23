// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.security;

import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;

/**
 * Declares the {@code svc.probe.run} action used by the example-services
 * {@code @RequiresAction} proof.
 *
 * <p>The {@code ServiceAuthorizationInterceptor} validates every {@code @RequiresAction} declaration
 * against the {@code ActionRegistry} at startup and fails fast if the action is not registered, so
 * this contributor is mandatory for the guarded
 * {@link dev.vertique.examples.services.service.AuthzProbeService#run(String)} operation to deploy.
 */
@Singleton
public final class AuthzProbeActionContributor implements ActionContributor {

    /** Canonical action guarding {@code AuthzProbeService.run}. */
    public static final ActionRef PROBE_RUN = ActionRef.parse("svc.probe.run");

    /** Creates the contributor. */
    @Inject
    public AuthzProbeActionContributor() {}

    /**
     * Returns the single {@link #PROBE_RUN} action this contributor declares.
     *
     * @return an immutable singleton collection containing the {@code svc.probe.run} action
     */
    @Override
    public Collection<ActionDefinition> actions() {
        return List.of(new ActionDefinition(PROBE_RUN));
    }
}
