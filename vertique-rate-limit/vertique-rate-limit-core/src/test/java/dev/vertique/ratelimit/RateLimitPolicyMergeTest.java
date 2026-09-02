// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-002 (review repair Carried-2): root configuration replaces a same-name Dagger {@code
 * @IntoSet}-contributed (programmatic) policy <strong>wholesale</strong> — fields never merge
 * across tiers (contracts/rate-limit-runtime.md, "Policy model"). {@code
 * RateLimitPolicy#mergeConfigOverProgrammatic} was internalized into {@code RateLimitCoreModule}
 * (no longer public API), so this merge is proven end-to-end through a real, minimal Dagger graph
 * over {@code RateLimitCoreModule} rather than by calling the (now module-private) resolution
 * step directly.
 */
@ExtendWith(VertxExtension.class)
class RateLimitPolicyMergeTest {

    private static final String POLICY_NAME = "quota-a";

    /** Programmatic contribution's capacity/defaultCost — must not survive into the resolved policy. */
    private static final long PROGRAMMATIC_CAPACITY = 10L;

    /** Root-configuration's capacity/defaultCost — must be exactly what the resolved policy uses. */
    private static final long CONFIG_CAPACITY = 50L;

    private static final long CONFIG_DEFAULT_COST = 2L;

    @Test
    void shouldReplaceSameNameProgrammaticPolicyWhollyNotMerge(Vertx vertx) {
        JsonObject config = new JsonObject()
                .put(
                        "rateLimit",
                        new JsonObject().put("policies", new JsonObject().put(POLICY_NAME, configPolicyJson())));
        TestComponent component =
                DaggerRateLimitPolicyMergeTest_TestComponent.factory().create(vertx, new ConfigFixtureModule(config));
        RateLimiters rateLimiters = component.rateLimiters();

        RateLimitDecision decision = rateLimiters
                .limiter(POLICY_NAME)
                .acquire(RateLimitKey.of("row-key"))
                .result();

        assertThat(decision.outcome())
                .as("resolved policy must still admit (proves it actually wired through)")
                .isEqualTo(RateLimitOutcome.PERMITTED);
        assertThat(decision.capacity())
                .as("resolved policy's capacity matches the root-configuration definition, not the programmatic one")
                .isEqualTo(CONFIG_CAPACITY);
        assertThat(decision.remaining())
                .as("resolved policy's defaultCost (also config-only) was consumed — proves the FULL config row won, "
                        + "not merely one field merged from each tier")
                .isEqualTo(java.util.OptionalLong.of(CONFIG_CAPACITY - CONFIG_DEFAULT_COST));
    }

    /** A Dagger {@code @IntoSet}-style programmatic contribution, built the way a @Provides factory would. */
    private static RateLimitPolicy programmaticPolicy() {
        return new RateLimitPolicy(
                POLICY_NAME,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(
                        PROGRAMMATIC_CAPACITY,
                        new GreedyRateLimitRefill(PROGRAMMATIC_CAPACITY, Duration.ofMillis(1_000L))));
    }

    /** A root-configuration policy row with its own complete field set, parsed through the same config-binding path production uses. */
    private static JsonObject configPolicyJson() {
        return new JsonObject()
                .put("name", POLICY_NAME)
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "CLOSED")
                .put("revision", "r2")
                .put("defaultCost", CONFIG_DEFAULT_COST)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", CONFIG_CAPACITY)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "INTERVAL")
                                                .put("tokens", CONFIG_CAPACITY)
                                                .put("periodMs", 2_000)));
    }

    // --- Fixture: a real, minimal Dagger graph over RateLimitCoreModule alone ---

    @Module
    static final class PolicyModule {

        @Provides
        @IntoSet
        RateLimitPolicy contributedPolicy() {
            return programmaticPolicy();
        }
    }

    /** Supplies the caller-given raw {@code rateLimit.*} config plus a real config parser. */
    @Module
    static final class ConfigFixtureModule {
        private final JsonObject config;

        ConfigFixtureModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    @Singleton
    @Component(modules = {RateLimitCoreModule.class, PolicyModule.class, ConfigFixtureModule.class})
    interface TestComponent {

        RateLimiters rateLimiters();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, ConfigFixtureModule configFixtureModule);
        }
    }
}
