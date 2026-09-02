// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only construction helpers hiding a real {@link HttpVerticle} composition wired with a
 * {@link RateLimitEdgeMiddleware} under test (TP-002, {@code RateLimitEdgeMiddlewareOrderingIT}).
 *
 * <p>Uses a real, non-mocked LOCAL {@link RateLimiters} (constructed directly via its public
 * constructor — no Dagger graph needed) so the middleware's {@code acquire(...)} calls exercise
 * genuine token-bucket admission, not a stub.
 */
final class RateLimitEdgeTestFixture {

    private RateLimitEdgeTestFixture() {}

    /**
     * Builds a single-policy {@link RateLimitPolicy} backed by a token bucket refilled once per
     * day (effectively static for the duration of a test).
     *
     * @param name the policy name
     * @param capacity the token-bucket capacity; {@code TokenBucketRateLimit} requires {@code >= 1}
     * @param failureMode this policy's backend-failure behavior
     * @return the constructed policy
     */
    static RateLimitPolicy tokenBucketPolicy(String name, long capacity, RateLimitFailureMode failureMode) {
        return new RateLimitPolicy(
                name,
                true,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(1, Duration.ofDays(1))));
    }

    /**
     * Builds a real LOCAL-engine {@link RateLimiters} runtime over {@code policies}, with no
     * observers and the root kill switch enabled.
     *
     * @param vertx the test's Vert.x instance
     * @param policies the declared policies
     * @return the constructed runtime
     */
    static RateLimiters rateLimiters(Vertx vertx, RateLimitPolicy... policies) {
        Map<RateLimitMode, RateLimitBackend> backends =
                Map.of(RateLimitMode.LOCAL, LocalRateLimitBackendFactory.local(policyName -> 100_000L, 60_000L));
        return new RateLimiters(Set.of(policies), backends, null, vertx, Set.of(), java.util.Optional::empty, true);
    }

    /**
     * Builds a {@link RouterMount} at {@code /api/*} that installs a {@link BodyHandler} then a
     * {@code POST /api/echo} handler flipping {@code invoked} and responding {@code 200}.
     *
     * @param invoked flipped {@code true} only if the resource handler actually runs
     * @return the mount
     */
    static RouterMount echoBodyMount(AtomicBoolean invoked) {
        return new RouterMount() {
            @Override
            public String mountPath() {
                return "/api/*";
            }

            @Override
            public Future<Router> createRouter(Vertx vertx) {
                Router router = Router.router(vertx);
                router.route().handler(BodyHandler.create());
                router.post("/echo").handler(ctx -> {
                    invoked.set(true);
                    ctx.response().setStatusCode(200).end("ok");
                });
                return Future.succeededFuture(router);
            }
        };
    }

    /**
     * @return HTTP server options bound to an ephemeral port on the loopback interface
     */
    static HttpServerOptions localhostOptions() {
        return new HttpServerOptions().setPort(0).setHost("127.0.0.1");
    }

    /**
     * Deploys {@code verticle} and resolves the bound port from the shared-data entry {@link
     * HttpVerticle} itself publishes.
     *
     * @param vertx the test's Vert.x instance
     * @param verticle the constructed verticle
     * @return a future resolving to the bound port
     */
    static Future<Integer> deploy(Vertx vertx, HttpVerticle verticle) {
        return vertx.deployVerticle(verticle).map(deploymentId ->
                (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port"));
    }
}
