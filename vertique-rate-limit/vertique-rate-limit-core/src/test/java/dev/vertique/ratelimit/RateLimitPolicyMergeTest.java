// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TP-002: root configuration replaces a same-name Dagger {@code @IntoSet}-contributed
 * (programmatic) policy <strong>wholesale</strong> — fields never merge across tiers
 * (contracts/rate-limit-runtime.md, "Policy model").
 */
class RateLimitPolicyMergeTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());
    private static final String NAME = "quota-a";

    @Test
    void shouldReplaceSameNameProgrammaticPolicyWhollyNotMerge() {
        RateLimitPolicy programmatic = programmaticPolicy(10L);
        RateLimitPolicy config = configPolicy(50L);

        Set<RateLimitPolicy> merged = RateLimitPolicy.mergeConfigOverProgrammatic(Set.of(config), Set.of(programmatic));

        assertThat(merged).hasSize(1);
        RateLimitPolicy resolved = merged.iterator().next();
        assertThat(resolved)
                .as("resolved policy matches the root-configuration definition's full field set exactly")
                .isEqualTo(config);
        assertThat(((TokenBucketRateLimit) resolved.algorithm()).capacity())
                .as("no field from the programmatic contribution survives into the resolved policy")
                .isEqualTo(50L);
    }

    /** A Dagger {@code @IntoSet}-style programmatic contribution, built the way a @Provides factory would. */
    private static RateLimitPolicy programmaticPolicy(long capacity) {
        return new RateLimitPolicy(
                NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(capacity, Duration.ofMillis(1_000L))));
    }

    /** A root-configuration policy row with its own complete field set, parsed through the same config-binding path production uses. */
    private static RateLimitPolicy configPolicy(long capacity) {
        JsonObject json = new JsonObject()
                .put("name", NAME)
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "CLOSED")
                .put("revision", "r2")
                .put("defaultCost", 2)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", capacity)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "INTERVAL")
                                                .put("tokens", capacity)
                                                .put("periodMs", 2_000)));
        return PARSER.parse(json, RateLimitPolicy.class);
    }
}
