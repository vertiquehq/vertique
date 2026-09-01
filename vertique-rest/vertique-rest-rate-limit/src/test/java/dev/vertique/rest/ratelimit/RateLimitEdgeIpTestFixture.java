// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.security.OriginCaptureMiddleware;
import dev.vertique.rest.security.RequestOriginCapturer;
import dev.vertique.rest.security.RequestOriginConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only construction helpers for TP-004 ({@code RateLimitEdgeIpDimensionIT}): a real {@link
 * HttpVerticle} composition with {@link OriginCaptureMiddleware} co-installed ahead of the {@code
 * IP}-dimension {@link RateLimitEdgeMiddleware} under test.
 */
final class RateLimitEdgeIpTestFixture {

    private RateLimitEdgeIpTestFixture() {}

    /**
     * Builds a real {@link OriginCaptureMiddleware} bound to a {@link RequestOriginCapturer}
     * configured with {@code trustedProxyCidrs} — mirrors the real Dagger wiring {@code
     * AuthModule} performs, without needing a Dagger graph.
     */
    static OriginCaptureMiddleware originCaptureMiddleware(Set<String> trustedProxyCidrs) {
        RequestOriginConfig config = new RequestOriginConfig(trustedProxyCidrs, 16, false, false);
        return new OriginCaptureMiddleware(new RequestOriginCapturer(config));
    }

    /**
     * Builds an {@code IP}-dimension edge {@link RateLimitEdgeMiddleware} over a single named
     * policy, constructed with {@code originCaptureBound=true} — simulating the startup check
     * having already passed. {@code ipFailureMode} classification is no longer a caller-supplied
     * parameter: the middleware now reads it directly off {@code rateLimiters.limiter(policyName)}
     * (via {@link dev.vertique.ratelimit.RateLimiter#failureMode()}), so the caller-declared {@code
     * policyName}'s policy in {@code rateLimiters} is the single source of truth — callers must
     * declare that policy with the {@code failureMode} they intend to observe.
     */
    static RateLimitEdgeMiddleware ipEdgeMiddleware(RateLimiters rateLimiters, String policyName, int ipv6PrefixBits) {
        RateLimitEdgeRule rule = new RateLimitEdgeRule(
                policyName,
                List.of(RateLimitEdgeKeyDimension.IP),
                Optional.empty(),
                OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                OptionalInt.of(ipv6PrefixBits));
        RateLimitEdgeConfig config = new RateLimitEdgeConfig(true, List.of(rule), "/*");
        return new RateLimitEdgeMiddleware(config, rateLimiters, true);
    }

    /**
     * Deploys an {@link HttpVerticle} with {@code middlewares} (in the given order — the caller
     * supplies {@link OriginCaptureMiddleware} then the edge middleware, matching production
     * ordering) and a POST {@code /api/echo} mount, returning the bound port.
     *
     * <p>The middleware set carries no priority conflict risk here since each fixture-built
     * middleware already declares its own real {@code ORDER} constant.
     */
    static Future<Integer> deploy(Vertx vertx, AtomicBoolean resourceInvoked, Middleware... middlewares) {
        HttpVerticle verticle = new HttpVerticle(
                RateLimitEdgeTestFixture.localhostOptions(),
                Set.of(),
                Set.of(middlewares),
                Set.of(RateLimitEdgeTestFixture.echoBodyMount(resourceInvoked)),
                Set.of());
        return RateLimitEdgeTestFixture.deploy(vertx, verticle);
    }
}
