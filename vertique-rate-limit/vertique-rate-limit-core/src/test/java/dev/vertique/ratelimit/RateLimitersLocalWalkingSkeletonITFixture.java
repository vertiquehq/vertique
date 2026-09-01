// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

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
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Builds a real Dagger graph installing {@link RateLimitCoreModule} alone, contributing one
 * enabled LOCAL TOKEN_BUCKET policy named {@code walking-skeleton} via the Dagger {@code
 * @IntoSet RateLimitPolicy} source (contracts/rate-limit-runtime.md, "Policy model"), plus an
 * empty {@code rateLimit} config section (so {@link RateLimitCoreModule}'s config-tree binding
 * resolves to no config policies, leaving this fixture's programmatic policy untouched).
 */
final class RateLimitersLocalWalkingSkeletonITFixture {

    private static final String POLICY_NAME = "walking-skeleton";
    private static final String POLICY_REVISION = "r1";
    private static final long REFILL_TOKENS = 1L;
    private static final long REFILL_PERIOD_MS = 60_000L;

    private RateLimitersLocalWalkingSkeletonITFixture() {}

    static RateLimiters create(Vertx vertx, long capacity) {
        return DaggerRateLimitersLocalWalkingSkeletonITFixture_FixtureGraph.builder()
                .vertx(vertx)
                .policyModule(new PolicyModule(capacity))
                .build()
                .rateLimiters();
    }

    @Module
    static final class PolicyModule {
        private final long capacity;

        PolicyModule(long capacity) {
            this.capacity = capacity;
        }

        @Provides
        @IntoSet
        RateLimitPolicy walkingSkeletonPolicy() {
            return new RateLimitPolicy(
                    POLICY_NAME,
                    true,
                    RateLimitMode.LOCAL,
                    RateLimitFailureMode.OPEN,
                    POLICY_REVISION,
                    1L,
                    new TokenBucketRateLimit(
                            capacity, new GreedyRateLimitRefill(REFILL_TOKENS, Duration.ofMillis(REFILL_PERIOD_MS))));
        }
    }

    /** Supplies an empty config section and a real config parser, so no config policies resolve. */
    @Module
    static final class ConfigFixtureModule {

        @Provides
        @VertxConfig
        static JsonObject vertxConfig() {
            return new JsonObject();
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    @Singleton
    @Component(modules = {RateLimitCoreModule.class, PolicyModule.class, ConfigFixtureModule.class})
    interface FixtureGraph {

        RateLimiters rateLimiters();

        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder vertx(Vertx vertx);

            Builder policyModule(PolicyModule policyModule);

            FixtureGraph build();
        }
    }
}
