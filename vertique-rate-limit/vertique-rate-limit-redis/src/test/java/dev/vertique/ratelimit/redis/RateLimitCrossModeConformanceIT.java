// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TP-004: the same shared conformance vector set ({@code spec.md} §11.3) runs once against LOCAL
 * and once against CLUSTERED, proving both modes agree on {@code outcome}, {@code remaining}, and
 * the presence/absence of {@code retryAfter}/{@code resetAfter} — not merely that each mode passes
 * its own separate suite. No injectable clock exists for CLUSTERED in Bucket4j 8.19.0's Vert.x
 * builder, so every vector here only exercises full-initial-state-to-exhaustion sequences within
 * one refill period — never mid-period refill timing — so wall-clock jitter cannot flake the
 * agreement assertion (contracts/rate-limit-runtime.md, "Redis integration contract";
 * decisions/D011-bucket4j-redis-cas-and-shared-lifecycle.md).
 */
@Testcontainers
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitCrossModeConformanceIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE)).withExposedPorts(6379);

    private Vertx vertx;
    private RateLimiters rateLimiters;

    @AfterEach
    void tearDown() throws Exception {
        if (rateLimiters != null) {
            RateLimitRedisTestFixture.await(rateLimiters.close());
        }
        if (vertx != null) {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    /** One conformance vector: an algorithm shape driven through an acquisition sequence. */
    private record Vector(
            String policyBaseName,
            String refillType,
            long capacity,
            long tokens,
            long periodMs,
            long cost,
            int acquisitions) {
        String localPolicy() {
            return policyBaseName + "-local";
        }

        String clusteredPolicy() {
            return policyBaseName + "-clustered";
        }
    }

    private static final List<Vector> VECTORS = List.of(
            new Vector("full-to-exhausted-greedy", "GREEDY", 3, 3, 60_000, 1, 4),
            new Vector("full-to-exhausted-interval", "INTERVAL", 3, 3, 60_000, 1, 4),
            new Vector("weighted-cost", "GREEDY", 10, 10, 60_000, 3, 4),
            new Vector("single-capacity-key-isolation", "GREEDY", 1, 1, 60_000, 1, 2));

    @Test
    void shouldAgreeWithLocalModeOnTheSameSharedConformanceVectorSet() throws Exception {
        vertx = Vertx.vertx();
        JsonObject configuration = vectorConfiguration();
        ClusteredComponent component = DaggerRateLimitCrossModeConformanceIT_ClusteredComponent.builder()
                .vertxModule(new VertxModule(vertx, configuration))
                .build();
        rateLimiters = component.rateLimiters();

        for (Vector vector : VECTORS) {
            RateLimiter local = rateLimiters.limiter(vector.localPolicy());
            RateLimiter clustered = rateLimiters.limiter(vector.clusteredPolicy());
            RateLimitKey key = RateLimitKey.of("caller-1");

            for (int i = 1; i <= vector.acquisitions(); i++) {
                RateLimitDecision localDecision = RateLimitRedisTestFixture.await(local.acquire(key, vector.cost()));
                RateLimitDecision clusteredDecision =
                        RateLimitRedisTestFixture.await(clustered.acquire(key, vector.cost()));

                String step = vector.policyBaseName() + " step " + i;
                assertThat(clusteredDecision.outcome()).as(step + " outcome").isEqualTo(localDecision.outcome());
                assertThat(clusteredDecision.remaining())
                        .as(step + " remaining")
                        .isEqualTo(localDecision.remaining());
                assertThat(clusteredDecision.retryAfter().isPresent())
                        .as(step + " retryAfter presence")
                        .isEqualTo(localDecision.retryAfter().isPresent());
                assertThat(clusteredDecision.resetAfter().isPresent())
                        .as(step + " resetAfter presence")
                        .isEqualTo(localDecision.resetAfter().isPresent());

                // Sanity: the vector must actually exercise a capacity boundary, not trivially
                // agree because both are always PERMITTED.
                boolean expectedExhausted = i * vector.cost() > vector.capacity();
                RateLimitOutcome expected =
                        expectedExhausted ? RateLimitOutcome.QUOTA_EXCEEDED : RateLimitOutcome.PERMITTED;
                assertThat(localDecision.outcome())
                        .as(step + " expected outcome")
                        .isEqualTo(expected);
            }
        }

        // Key isolation: a second, distinct key under the same policy pair starts fresh on both modes.
        Vector isolation = VECTORS.get(VECTORS.size() - 1);
        RateLimiter local = rateLimiters.limiter(isolation.localPolicy());
        RateLimiter clustered = rateLimiters.limiter(isolation.clusteredPolicy());
        RateLimitKey otherKey = RateLimitKey.of("caller-2");
        RateLimitDecision localOther = RateLimitRedisTestFixture.await(local.acquire(otherKey, isolation.cost()));
        RateLimitDecision clusteredOther =
                RateLimitRedisTestFixture.await(clustered.acquire(otherKey, isolation.cost()));
        assertThat(localOther.outcome()).isEqualTo(RateLimitOutcome.PERMITTED);
        assertThat(clusteredOther.outcome()).isEqualTo(RateLimitOutcome.PERMITTED);
    }

    private JsonObject vectorConfiguration() {
        JsonObject policies = new JsonObject();
        for (Vector vector : VECTORS) {
            policies.put(vector.localPolicy(), policyJson(vector, "LOCAL"));
            policies.put(vector.clusteredPolicy(), policyJson(vector, "CLUSTERED"));
        }
        return new JsonObject()
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put("enabled", true)
                                .put("keyDerivation", new JsonObject().put("secret", RateLimitRedisTestFixture.SECRET))
                                .put("policies", policies)
                                .put(
                                        "redis",
                                        new JsonObject()
                                                .put("connection", RateLimitRedisTestFixture.CONNECTION)
                                                .put("namespace", RateLimitRedisTestFixture.NAMESPACE)
                                                .put("operationTimeoutMs", 2_000)
                                                .put("expirationSlackMs", 1_000)))
                .put(
                        "redis",
                        new JsonObject()
                                .put(
                                        "connections",
                                        new JsonObject()
                                                .put(
                                                        RateLimitRedisTestFixture.CONNECTION,
                                                        new JsonObject()
                                                                .put(
                                                                        "endpoints",
                                                                        List.of("redis://" + REDIS.getHost() + ":"
                                                                                + REDIS.getMappedPort(6379)))
                                                                .put("connectTimeoutMs", 2_000)
                                                                .put("maxPoolSize", 8)
                                                                .put("maxPoolWaiting", 100))));
    }

    private static JsonObject policyJson(Vector vector, String mode) {
        return new JsonObject()
                .put("enabled", true)
                .put("mode", mode)
                .put("failureMode", "OPEN")
                .put("revision", "r1")
                .put("defaultCost", vector.cost())
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", vector.capacity())
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", vector.refillType())
                                                .put("tokens", vector.tokens())
                                                .put("periodMs", vector.periodMs())));
    }

    @Singleton
    @Component(modules = {RateLimitRedisModule.class, ConfigParsingModule.class, VertxModule.class})
    interface ClusteredComponent {
        RateLimiters rateLimiters();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            ClusteredComponent build();
        }
    }
}
