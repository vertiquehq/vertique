// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;

/**
 * Builds a real Dagger graph installing {@link RateLimitCoreModule} alone, contributing one
 * enabled LOCAL TOKEN_BUCKET policy named {@code walking-skeleton} via the Dagger {@code
 * @IntoSet RateLimitPolicy} source (contracts/rate-limit-runtime.md, "Policy model" — this task's
 * minimal policy shape has no typed-config binding yet; that is a later task's artifact).
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
                    POLICY_NAME, true, RateLimitMode.LOCAL, POLICY_REVISION, capacity, REFILL_TOKENS, REFILL_PERIOD_MS);
        }
    }

    @Singleton
    @Component(modules = {RateLimitCoreModule.class, PolicyModule.class})
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
