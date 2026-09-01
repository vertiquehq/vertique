// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Public cross-package test fixture: builds a real Dagger graph installing {@link
 * RateLimitCoreModule} alone (the same "local-engine composition seam" {@code
 * RateLimitersLocalWalkingSkeletonITFixture} exercises), contributing exactly one caller-supplied
 * {@link RateLimitPolicy} and one caller-supplied bound {@link RateLimitObserver} set. Used by
 * tests outside the {@code dev.vertique.ratelimit} package (e.g. {@code
 * dev.vertique.ratelimit.spi}) that need a real, non-mocked LOCAL engine plus a varying observer
 * composition.
 */
public final class RateLimitCoreTestFixtures {

    private RateLimitCoreTestFixtures() {}

    /**
     * Builds a {@link RateLimiters} composed for exactly one enabled policy and one observer
     * composition, via a real Dagger graph over the LOCAL Bucket4j engine.
     *
     * @param vertx application Vert.x instance
     * @param policy the single policy to contribute
     * @param observers the observer set to bind (zero, one, or many)
     * @return the composed runtime
     */
    public static RateLimiters singlePolicy(Vertx vertx, RateLimitPolicy policy, Set<RateLimitObserver> observers) {
        return DaggerRateLimitCoreTestFixtures_FixtureGraph.builder()
                .vertx(vertx)
                .policyModule(new PolicyModule(policy))
                .observerModule(new ObserverModule(observers))
                .build()
                .rateLimiters();
    }

    @Module
    static final class PolicyModule {
        private final RateLimitPolicy policy;

        PolicyModule(RateLimitPolicy policy) {
            this.policy = policy;
        }

        @Provides
        @IntoSet
        RateLimitPolicy contributedPolicy() {
            return policy;
        }
    }

    @Module
    static final class ObserverModule {
        private final Set<RateLimitObserver> observers;

        ObserverModule(Set<RateLimitObserver> observers) {
            this.observers = observers;
        }

        @Provides
        @ElementsIntoSet
        Set<RateLimitObserver> contributedObservers() {
            return observers;
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
    @Component(
            modules = {RateLimitCoreModule.class, PolicyModule.class, ObserverModule.class, ConfigFixtureModule.class})
    interface FixtureGraph {

        RateLimiters rateLimiters();

        @Component.Builder
        interface Builder {
            @BindsInstance
            Builder vertx(Vertx vertx);

            Builder policyModule(PolicyModule policyModule);

            Builder observerModule(ObserverModule observerModule);

            FixtureGraph build();
        }
    }
}
