// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.rest.core.router.HttpVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-002: proves the edge {@link RateLimitEdgeMiddleware} mounts and runs strictly before {@code
 * BodyHandler}/any {@code RouterMount} — the exact {@code HttpVerticle.start} ordering
 * {@code plan.md} finding 2 confirms live — with no {@code vertique-rest-core} source change.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitEdgeMiddlewareOrderingIT {

    private static final String GLOBAL_POLICY = "edge-global-ordering-test";

    private WebClient client;

    // Verticles deployed by the tests below are automatically undeployed when VertxExtension
    // closes the injected Vertx instance after each test; only the directly-created WebClient
    // needs an explicit close.
    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldMountEdgeMiddlewareBeforeBodyHandlerAndAnyRouterMount(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean resourceInvoked = new AtomicBoolean(false);
        RateLimitPolicy policy =
                RateLimitEdgeTestFixture.tokenBucketPolicy(GLOBAL_POLICY, 1L, RateLimitFailureMode.CLOSED);
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(vertx, policy);

        // TokenBucketRateLimit requires capacity >= 1, so "capacity 0 (every request denied)" is
        // faithfully substituted by capacity=1 pre-exhausted via one direct acquire() before the
        // HTTP call under test — the middleware's own acquire() then always observes
        // QUOTA_EXCEEDED (0 remaining), so every subsequent request is denied exactly as a literal
        // capacity-0 rule would deny it.
        rateLimiters
                .limiter(GLOBAL_POLICY)
                .acquire(RateLimitKey.global())
                .compose(ignored -> deployVerticle(vertx, rateLimiters, resourceInvoked))
                .onComplete(ctx.succeeding(port -> {
                    client = WebClient.create(vertx);
                    client.post(port, "127.0.0.1", "/api/echo")
                            .sendBuffer(Buffer.buffer("payload"))
                            .onComplete(ctx.succeeding(response -> {
                                assertEquals(429, response.statusCode(), "edge denial must precede the resource");
                                assertFalse(
                                        resourceInvoked.get(),
                                        "resource method — and therefore BodyHandler — must never run");
                                ctx.completeNow();
                            }));
                }));
    }

    @Test
    void sensitivity_shouldFlipToResourceResponseWhenCapacityIsRaisedAboveZero(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean resourceInvoked = new AtomicBoolean(false);
        // No pre-consumption this time, and capacity raised above zero (2): the same GLOBAL rule
        // must now admit the request, proving the 429 above is sensitive to the rule's actual
        // admission decision, not a fixed stub.
        RateLimitPolicy policy =
                RateLimitEdgeTestFixture.tokenBucketPolicy(GLOBAL_POLICY, 2L, RateLimitFailureMode.CLOSED);
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(vertx, policy);

        deployVerticle(vertx, rateLimiters, resourceInvoked).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            client.post(port, "127.0.0.1", "/api/echo")
                    .sendBuffer(Buffer.buffer("payload"))
                    .onComplete(ctx.succeeding(response -> {
                        assertEquals(200, response.statusCode());
                        assertTrue(resourceInvoked.get(), "resource must run once admitted");
                        ctx.completeNow();
                    }));
        }));
    }

    private Future<Integer> deployVerticle(Vertx vertx, RateLimiters rateLimiters, AtomicBoolean resourceInvoked) {
        RateLimitEdgeRule globalRule = new RateLimitEdgeRule(
                GLOBAL_POLICY,
                List.of(RateLimitEdgeKeyDimension.GLOBAL),
                Optional.empty(),
                OptionalLong.empty(),
                MissingDimensionPolicy.SHARED_BUCKET,
                OptionalInt.empty());
        RateLimitEdgeConfig config = new RateLimitEdgeConfig(true, List.of(globalRule), "/*");
        RateLimitEdgeMiddleware middleware = new RateLimitEdgeMiddleware(
                config, rateLimiters, false, RateLimitEdgeTestFixture.defaultExceptionMappers());
        HttpVerticle verticle = new HttpVerticle(
                RateLimitEdgeTestFixture.localhostOptions(),
                Set.of(),
                Set.of(middleware),
                Set.of(RateLimitEdgeTestFixture.echoBodyMount(resourceInvoked)),
                Set.of());
        return RateLimitEdgeTestFixture.deploy(vertx, verticle);
    }
}
