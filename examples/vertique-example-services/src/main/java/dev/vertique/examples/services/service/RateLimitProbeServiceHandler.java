// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

import dev.vertique.ratelimit.aop.RateLimited;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;

/**
 * Handler-pattern implementation of {@link RateLimitProbeService}. The generated {@code
 * $AopProxy} intercepts {@link #probe()} exactly like {@code @Cacheable} intercepts {@code
 * CacheProbeServiceHandler#probe} (the mirrored idiom this class follows) — the interceptor chain
 * is resolved once at proxy-construction time, and this handler is reached only through the
 * Dagger-provided {@code Provider.get()} the generated services module uses to dispatch.
 *
 * <p>{@code subject = NONE} means no identity dimension is resolved (the resolver is never
 * consulted, contracts/rate-limit-aop.md), so the derived key is the explicit zero-component
 * {@code RateLimitKey.global()} — the exact same key {@link
 * dev.vertique.examples.services.resource.RateLimitProbeResource}'s programmatic path acquires
 * against, so both paths compete for the same token bucket (TP-002).
 */
public class RateLimitProbeServiceHandler implements ServiceHandler<RateLimitProbeService> {

    /** Creates a rate-limit probe handler. */
    @Inject
    public RateLimitProbeServiceHandler() {}

    /**
     * Runs only once the runtime has admitted the call; a denial fails this method's returned
     * future with {@code RateLimitExceededException} before this body ever executes.
     *
     * @return a future completing with a fixed {@code "ADMITTED"} outcome
     */
    @RateLimited(policy = RateLimitProbeService.POLICY_NAME, subject = RateLimitSubject.NONE)
    public Future<RateLimitProbeResult> probe() {
        return Future.succeededFuture(new RateLimitProbeResult("ADMITTED"));
    }
}
