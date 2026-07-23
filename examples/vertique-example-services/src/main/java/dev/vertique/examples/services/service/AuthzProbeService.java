// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.security.authz.RequiresAction;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract used to prove {@code @RequiresAction} enforcement through the real,
 * Dagger-wired services dispatch pipeline.
 *
 * <p>The single {@link #run(String)} operation is guarded by
 * {@link RequiresAction @RequiresAction("svc.probe.run")}. When dispatched over the event bus, the
 * {@code @IntoSet}-delivered {@code ServiceAuthorizationInterceptor} running inside the deployed
 * {@code ServiceVerticle}'s {@code ServiceMethodInvoker} evaluates the action against the propagated
 * caller {@code SecurityContext} before the handler runs: a permitted actor reaches the handler, a
 * denied actor is short-circuited, and a missing identity fails closed.
 *
 * <p>The action {@code svc.probe.run} is declared by
 * {@link dev.vertique.examples.services.security.AuthzProbeActionContributor}; without that
 * registration the interceptor's startup validation would fail fast.
 */
@ServiceContract(namespace = "authz", value = "probe")
public interface AuthzProbeService {

    /**
     * Echoes the given payload back to the caller after the {@code svc.probe.run} action gate
     * permits the dispatch.
     *
     * @param payload the input payload to echo; may be {@code null}
     * @return a future containing {@code "probe:" + payload} when authorization permits the call
     */
    @RequiresAction("svc.probe.run")
    @ServiceOperation("run")
    Future<String> run(String payload);
}
