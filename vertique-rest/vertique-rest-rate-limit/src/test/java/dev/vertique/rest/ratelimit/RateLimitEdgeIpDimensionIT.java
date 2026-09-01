// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.rest.security.OriginCaptureMiddleware;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-004: the {@code IP} dimension derives its key from {@link OriginCaptureMiddleware}'s
 * published {@code RequestOrigin} only — never from a directly-parsed forwarding header or the raw
 * socket {@code remoteAddress()} (contracts/rest-adapter.md, "Rule composition semantics" — IP
 * mechanism).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RateLimitEdgeIpDimensionIT {

    private WebClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldSeparateBucketsByOriginResolvedClientIp(Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-ip-separate";
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        OriginCaptureMiddleware origin = RateLimitEdgeIpTestFixture.originCaptureMiddleware(Set.of("127.0.0.1/32"));
        RateLimitEdgeMiddleware edge = RateLimitEdgeIpTestFixture.ipEdgeMiddleware(
                rateLimiters, policy, RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS);
        AtomicBoolean invoked = new AtomicBoolean(false);

        RateLimitEdgeIpTestFixture.deploy(vertx, invoked, origin, edge).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            // Capacity is 1 per bucket: two distinct trusted-proxy-resolved client IPs
            // must each get their own bucket, so both requests are independently admitted.
            postWithForwardedFor(port, "203.0.113.10")
                    .compose(first -> {
                        assertEquals(200, first.statusCode(), "first distinct-IP request must be admitted");
                        return postWithForwardedFor(port, "203.0.113.20");
                    })
                    .onComplete(ctx.succeeding(second -> {
                        assertEquals(200, second.statusCode(), "second distinct-IP request must be admitted");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    void sensitivity_shouldShareOneBucketWhenBothRequestsReuseTheSameOriginResolvedIp(
            Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-ip-sensitivity";
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        OriginCaptureMiddleware origin = RateLimitEdgeIpTestFixture.originCaptureMiddleware(Set.of("127.0.0.1/32"));
        RateLimitEdgeMiddleware edge = RateLimitEdgeIpTestFixture.ipEdgeMiddleware(
                rateLimiters, policy, RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS);
        AtomicBoolean invoked = new AtomicBoolean(false);

        // Sensitivity proof for the row above: reusing the SAME origin-resolved client IP for both
        // requests must flip the two-separate-buckets outcome to one-shared-bucket (second denied).
        RateLimitEdgeIpTestFixture.deploy(vertx, invoked, origin, edge).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            postWithForwardedFor(port, "203.0.113.10")
                    .compose(first -> {
                        assertEquals(200, first.statusCode());
                        return postWithForwardedFor(port, "203.0.113.10");
                    })
                    .onComplete(ctx.succeeding(second -> {
                        assertEquals(429, second.statusCode(), "same client IP must share one bucket");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    void shouldNotSplitBucketsOnSpoofedForwardedForFromAnUntrustedPeer(Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-ip-untrusted";
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        // No trusted proxies: the direct peer (127.0.0.1, the test client itself) is untrusted, so
        // X-Forwarded-For is never honored regardless of its value.
        OriginCaptureMiddleware origin = RateLimitEdgeIpTestFixture.originCaptureMiddleware(Set.of());
        RateLimitEdgeMiddleware edge = RateLimitEdgeIpTestFixture.ipEdgeMiddleware(
                rateLimiters, policy, RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS);
        AtomicBoolean invoked = new AtomicBoolean(false);

        RateLimitEdgeIpTestFixture.deploy(vertx, invoked, origin, edge).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            postWithForwardedFor(port, "203.0.113.10")
                    .compose(first -> {
                        assertEquals(200, first.statusCode());
                        // Different spoofed XFF value from the same untrusted peer — origin
                        // capture already refused to trust it, so this must resolve to the
                        // same clientIp (remoteIp) and share the already-exhausted bucket.
                        return postWithForwardedFor(port, "203.0.113.20");
                    })
                    .onComplete(ctx.succeeding(second -> {
                        assertEquals(
                                429,
                                second.statusCode(),
                                "spoofed X-Forwarded-For from an untrusted peer must never split buckets");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    void shouldClassifyPerFailureModeWhenRequestOriginIsAbsentAtRuntime_open(Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-ip-absent-open";
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policy, 1L, RateLimitFailureMode.OPEN));
        // No OriginCaptureMiddleware registered at all: RequestOrigin is absent from the routing
        // context at request time despite the edge middleware having been constructed with
        // originCaptureBound=true (simulating the startup check having already passed).
        RateLimitEdgeMiddleware edge = RateLimitEdgeIpTestFixture.ipEdgeMiddleware(
                rateLimiters, policy, RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS);
        AtomicBoolean invoked = new AtomicBoolean(false);

        RateLimitEdgeIpTestFixture.deploy(vertx, invoked, edge).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            // Capacity is 1: if the middleware ever fell back to remoteAddress() instead of
            // classifying by failureMode, it would derive a real (shared, remoteIp-keyed)
            // key and the second call would consume the single token, denying the request.
            // Both succeeding proves no key was ever derived and no acquire() call was made.
            postWithForwardedFor(port, "203.0.113.10")
                    .compose(first -> {
                        assertEquals(200, first.statusCode());
                        return postWithForwardedFor(port, "203.0.113.10");
                    })
                    .onComplete(ctx.succeeding(second -> {
                        assertEquals(
                                200,
                                second.statusCode(),
                                "OPEN must continue on absent RequestOrigin, never falling back to remoteAddress()");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    void shouldClassifyPerFailureModeWhenRequestOriginIsAbsentAtRuntime_closed(Vertx vertx, VertxTestContext ctx) {
        String policy = "edge-ip-absent-closed";
        RateLimiters rateLimiters = RateLimitEdgeTestFixture.rateLimiters(
                vertx, RateLimitEdgeTestFixture.tokenBucketPolicy(policy, 1L, RateLimitFailureMode.CLOSED));
        RateLimitEdgeMiddleware edge = RateLimitEdgeIpTestFixture.ipEdgeMiddleware(
                rateLimiters, policy, RateLimitEdgeRule.DEFAULT_IPV6_PREFIX_BITS);
        AtomicBoolean invoked = new AtomicBoolean(false);

        RateLimitEdgeIpTestFixture.deploy(vertx, invoked, edge).onComplete(ctx.succeeding(port -> {
            client = WebClient.create(vertx);
            postWithForwardedFor(port, "203.0.113.10").onComplete(ctx.succeeding(response -> {
                // A remoteAddress() fallback would have derived a real key against a
                // fresh capacity-1 bucket and PERMITTED this first call; 503 is only
                // reachable via the failureMode classification path.
                assertEquals(
                        503,
                        response.statusCode(),
                        "CLOSED must deny on absent RequestOrigin, never falling back to remoteAddress()");
                ctx.completeNow();
            }));
        }));
    }

    @Test
    void shouldRunAfterOriginCaptureMiddleware() {
        assertEquals(
                OriginCaptureMiddleware.ORDER + 20,
                RateLimitEdgeMiddleware.ORDER,
                "edge middleware must be registered exactly OriginCaptureMiddleware.ORDER + 20");
        assertTrue(RateLimitEdgeMiddleware.ORDER > OriginCaptureMiddleware.ORDER);
    }

    private Future<HttpResponse<Buffer>> postWithForwardedFor(int port, String forwardedFor) {
        return client.post(port, "127.0.0.1", "/api/echo")
                .putHeader("X-Forwarded-For", forwardedFor)
                .sendBuffer(Buffer.buffer("payload"));
    }
}
