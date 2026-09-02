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
import dev.vertique.ratelimit.RateLimiters;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.RedisAPI;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TP-003: the {@code CLUSTERED} backend's full-graph, first end-to-end wiring — a real Dagger
 * component composing {@code RateLimitCoreModule} and {@code RateLimitRedisModule} (not test
 * doubles for either), driven through {@code RateLimiters.limiter(name).acquire(...)} against a
 * real Testcontainers Redis, asserting both the decision and the physical Redis key format (§6.1)
 * (contracts/rate-limit-runtime.md, "Redis integration contract").
 */
@Testcontainers
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisEndToEndIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE)).withExposedPorts(6379);

    private Vertx vertx;
    private RateLimiters rateLimiters;
    private RedisAPI rawCommands;

    @AfterEach
    void tearDown() throws Exception {
        if (rateLimiters != null) {
            RateLimitRedisTestFixture.await(rateLimiters.close());
        }
        if (vertx != null) {
            RateLimitRedisTestFixture.await(vertx.close());
        }
    }

    @Test
    void shouldDispatchClusteredEndToEndThroughRealDaggerGraphWithCorrectPhysicalKey() throws Exception {
        // Given: a real Dagger component composing RateLimitCoreModule and RateLimitRedisModule,
        // with one enabled CLUSTERED policy declared through the same config-tree binding path an
        // application would use, and a known keyDerivation.secret.
        buildGraph(RateLimitRedisTestFixture.NAMESPACE);

        // When: resolve rateLimiters.limiter(policyName).acquire(RateLimitKey.of("caller-1")) once
        // against the real graph.
        RateLimitDecision decision = RateLimitRedisTestFixture.await(
                rateLimiters.limiter(RateLimitRedisTestFixture.POLICY_NAME).acquire(RateLimitKey.of("caller-1")));

        // Then: the decision is PERMITTED with the expected remaining, and the physical Redis key
        // actually written matches <namespace>:v1:<keyFingerprint>:<hmac> exactly, both segments
        // independently recomputed from JDK primitives.
        assertThat(decision.outcome()).isEqualTo(RateLimitOutcome.PERMITTED);
        assertThat(decision.remaining()).hasValue(4L);

        String canonicalInput =
                RateLimitRedisTestFixture.POLICY_NAME + ':' + RateLimitRedisTestFixture.POLICY_REVISION + ":Scaller-1";
        String expectedPhysicalKey = IndependentRedisReference.physicalKey(
                RateLimitRedisTestFixture.NAMESPACE, RateLimitRedisTestFixture.SECRET, canonicalInput);
        var ttl = RateLimitRedisTestFixture.await(rawCommands.pttl(expectedPhysicalKey));
        assertThat(ttl)
                .as("the exact physical key must exist in Redis with a finite TTL")
                .isNotNull();
        assertThat(ttl.toLong()).isGreaterThan(0L);
    }

    /**
     * Sensitivity proof: a different {@code namespace} changes the observed physical key's
     * namespace segment while the {@code keyFingerprint}/{@code hmac} segments stay identical.
     */
    @Test
    void shouldChangePhysicalKeyNamespaceSegmentWhenNamespaceConfigChanges() throws Exception {
        buildGraph("rl-alt-ns");

        RateLimitDecision decision = RateLimitRedisTestFixture.await(
                rateLimiters.limiter(RateLimitRedisTestFixture.POLICY_NAME).acquire(RateLimitKey.of("caller-1")));
        assertThat(decision.outcome()).isEqualTo(RateLimitOutcome.PERMITTED);

        String canonicalInput =
                RateLimitRedisTestFixture.POLICY_NAME + ':' + RateLimitRedisTestFixture.POLICY_REVISION + ":Scaller-1";
        String expectedPhysicalKey =
                IndependentRedisReference.physicalKey("rl-alt-ns", RateLimitRedisTestFixture.SECRET, canonicalInput);
        var ttl = RateLimitRedisTestFixture.await(rawCommands.pttl(expectedPhysicalKey));
        assertThat(ttl)
                .as("the namespace segment must change while fingerprint/hmac stay identical")
                .isNotNull();
        assertThat(ttl.toLong()).isGreaterThan(0L);
    }

    private void buildGraph(String namespace) {
        vertx = Vertx.vertx();
        JsonObject configuration = RateLimitRedisTestFixture.configuration(
                REDIS.getHost(), REDIS.getMappedPort(6379), 5, 5, 60_000, "OPEN", 2_000, 1_000, namespace);
        ClusteredComponent component = DaggerRateLimitRedisEndToEndIT_ClusteredComponent.builder()
                .vertxModule(new VertxModule(vertx, configuration))
                .build();
        rateLimiters = component.rateLimiters();
        rawCommands = RedisAPI.api(component.redisClientRegistry().client(RateLimitRedisTestFixture.CONNECTION));
    }

    @Singleton
    @Component(modules = {RateLimitRedisModule.class, ConfigParsingModule.class, VertxModule.class})
    interface ClusteredComponent {
        RateLimiters rateLimiters();

        dev.vertique.redis.RedisClientRegistry redisClientRegistry();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            ClusteredComponent build();
        }
    }
}
