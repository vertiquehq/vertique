// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.Cacheable;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;

/** Handler-pattern implementation used to prove identity-scoped cache behavior. */
public class CacheProbeServiceHandler implements ServiceHandler<CacheProbeService> {

    private final AtomicInteger invocationCount = new AtomicInteger();

    /** Creates a cache probe handler. */
    @Inject
    public CacheProbeServiceHandler() {}

    /**
     * Returns the propagated actor and increments the count only on a concrete invocation.
     *
     * @param securityContext the caller context injected by service dispatch; may be {@code null}
     * @return a future containing the propagated actor and invocation count
     */
    @Cacheable(
            name = "cache-probe",
            key = "probe",
            identity = CacheIdentity.ACTOR,
            anonymous = AnonymousCachePolicy.BYPASS)
    public Future<CacheProbeResult> probe(SecurityContext securityContext) {
        String actor = securityContext == null
                ? "anonymous"
                : securityContext.identity().actor().id();
        return Future.succeededFuture(new CacheProbeResult(actor, invocationCount.incrementAndGet()));
    }
}
