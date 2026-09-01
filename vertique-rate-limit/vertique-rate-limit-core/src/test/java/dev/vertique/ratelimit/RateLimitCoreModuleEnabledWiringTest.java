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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.inject.Singleton;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * M2 (review repair): {@code rateLimit.enabled=false}, read from configuration through a real,
 * minimal Dagger graph over {@link RateLimitCoreModule}, yields {@code DISABLED} decisions on
 * every handle this runtime resolves — not merely when the (7-argument) {@link RateLimiters}
 * constructor's {@code rateLimitEnabled} flag is poked directly.
 */
@ExtendWith(VertxExtension.class)
class RateLimitCoreModuleEnabledWiringTest {

    private static final String POLICY_NAME = "kill-switch-quota";

    @Test
    @DisplayName("rateLimit.enabled=false in config yields a DISABLED decision, wired end-to-end through the module")
    void shouldYieldDisabledDecisionWhenRateLimitEnabledIsFalseInConfig(Vertx vertx) {
        JsonObject config = new JsonObject().put("rateLimit", new JsonObject().put("enabled", false));
        TestComponent component = DaggerRateLimitCoreModuleEnabledWiringTest_TestComponent.factory()
                .create(vertx, new ConfigFixtureModule(config));
        RateLimiters rateLimiters = component.rateLimiters();

        Future<RateLimitDecision> future = rateLimiters.limiter(POLICY_NAME).acquire(RateLimitKey.of("row-key"));

        assertThat(future.succeeded())
                .as("acquire() future succeeds (decision-first)")
                .isTrue();
        assertThat(future.result().outcome())
                .as("outcome is DISABLED when rateLimit.enabled=false in config")
                .isEqualTo(RateLimitOutcome.DISABLED);
    }

    // --- Fixture: a real, minimal Dagger graph over RateLimitCoreModule alone ---

    @Module
    static final class PolicyModule {

        @Provides
        @IntoSet
        RateLimitPolicy killSwitchPolicy() {
            return new RateLimitPolicy(
                    POLICY_NAME,
                    true,
                    RateLimitMode.LOCAL,
                    RateLimitFailureMode.OPEN,
                    "r1",
                    1L,
                    new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
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
