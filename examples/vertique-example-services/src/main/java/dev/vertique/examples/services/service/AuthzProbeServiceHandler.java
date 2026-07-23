// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.examples.services.security.AuthzEventCollector;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler-pattern implementation of {@link AuthzProbeService} for the {@code @RequiresAction} proof.
 *
 * <p>The handler records each invocation into the shared {@link AuthzEventCollector} so an
 * integration test can prove that the guarded operation runs <em>only</em> when the
 * {@code ServiceAuthorizationInterceptor} permits the dispatch — and never runs on a deny or a
 * fail-closed (missing identity) outcome, because the interceptor short-circuits dispatch before the
 * handler is invoked.
 *
 * <p>The {@link SecurityContext} parameter is auto-injected by the framework from the dispatch
 * context during dispatch; it is not part of the {@link AuthzProbeService} client contract.
 *
 * @see ServiceHandler
 * @see AuthzProbeService
 */
@Slf4j
public class AuthzProbeServiceHandler implements ServiceHandler<AuthzProbeService> {

    private final AuthzEventCollector collector;

    /**
     * Creates the handler.
     *
     * @param collector the shared collector that records guarded-handler invocations; must not be
     *                  {@code null}
     */
    @Inject
    AuthzProbeServiceHandler(AuthzEventCollector collector) {
        this.collector = collector;
    }

    /**
     * Echoes the payload after the {@code svc.probe.run} gate has permitted the dispatch, recording
     * the invocation so a test can assert the handler executed.
     *
     * @param payload the input payload to echo; may be {@code null}
     * @param sc      the auto-injected caller security context; may be {@code null}
     * @return a future containing {@code "probe:" + payload}
     */
    public Future<String> run(String payload, SecurityContext sc) {
        collector.recordHandlerInvocation();
        if (sc != null) {
            log.debug(
                    "AuthzProbeService.run permitted for actor {}",
                    sc.identity().actor().id());
        }
        return Future.succeededFuture("probe:" + payload);
    }
}
