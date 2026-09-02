// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-005: {@link RateLimiters#close()} is idempotent under concurrent shutdown and fences any
 * in-flight {@code execute(...)}, mirroring {@code CanonicalResilienceModuleTest}'s {@code
 * firstStop == secondStop} proof for {@code ResilienceModule}. The component is a real,
 * Dagger-composed {@link RateLimitCoreModule} graph — the shutdown step under test is the module's
 * own {@code @IntoSet ApplicationShutdownStep} contribution, not a hand-constructed test double.
 */
@ExtendWith(VertxExtension.class)
class RateLimitersLifecycleIT {

    private static final String POLICY_NAME = "lifecycle-quota";

    @Test
    @DisplayName("close() is idempotent and fences an in-flight execute() under concurrent shutdown")
    void shouldCloseIdempotentlyAndFenceInFlightExecuteUnderConcurrentShutdown(Vertx vertx) {
        TestComponent component =
                DaggerRateLimitersLifecycleIT_TestComponent.factory().create(vertx);
        RateLimiters rateLimiters = component.rateLimiters();
        Set<ApplicationShutdownStep> shutdownSteps = component.shutdownSteps();
        assertThat(shutdownSteps)
                .as("RateLimitCoreModule must contribute exactly one shutdown step")
                .hasSize(1);
        ApplicationShutdownStep shutdownStep = shutdownSteps.iterator().next();
        assertThat(shutdownStep.phase()).as("shutdown step phase").isEqualTo(LifecyclePhase.CONFIGURE);

        RateLimiter limiter = rateLimiters.limiter(POLICY_NAME);
        Promise<String> heldOpen = Promise.promise();
        Future<String> inFlight = limiter.execute(RateLimitKey.of("in-flight-caller"), heldOpen::future);
        assertThat(inFlight.isComplete())
                .as("in-flight execute not yet complete before shutdown")
                .isFalse();

        Future<Void> firstClose = rateLimiters.close();
        Future<Void> secondClose = rateLimiters.close();
        Future<Void> stepStop = shutdownStep.stop();

        assertThat(firstClose)
                .as("repeated close() calls return the same future")
                .isSameAs(secondClose);
        assertThat(firstClose)
                .as("the shutdown step's stop() observes the same close future")
                .isSameAs(stepStop);
        assertThat(firstClose.succeeded())
                .as("close() completes without needing to await anything")
                .isTrue();

        assertThat(inFlight.isComplete())
                .as("in-flight execute fenced by close()")
                .isTrue();
        assertThat(inFlight.cause())
                .as("in-flight execute's fenced failure type")
                .isInstanceOf(RateLimitUnavailableException.class);

        assertThatCode(() -> heldOpen.complete("late-result"))
                .as("completing the held-open action after close must not throw")
                .doesNotThrowAnyException();
        assertThat(inFlight.cause())
                .as("the action's late completion never overwrites the already-fenced failure")
                .isInstanceOf(RateLimitUnavailableException.class);

        Future<RateLimitDecision> afterClose = limiter.acquire(RateLimitKey.of("after-close-caller"));
        assertThat(afterClose.failed())
                .as("acquire issued after close fails immediately")
                .isTrue();
        assertThat(afterClose.cause())
                .as("acquire-after-close failure type")
                .isInstanceOf(RateLimitUnavailableException.class);
    }

    // --- Fixture: a real, minimal Dagger graph over RateLimitCoreModule alone ---

    @Module
    static final class PolicyModule {

        @Provides
        @IntoSet
        RateLimitPolicy lifecyclePolicy() {
            return new RateLimitPolicy(
                    POLICY_NAME,
                    true,
                    RateLimitMode.LOCAL,
                    RateLimitFailureMode.OPEN,
                    "r1",
                    1L,
                    new TokenBucketRateLimit(2L, new GreedyRateLimitRefill(2L, Duration.ofSeconds(60))));
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

    /** Declares the host lifecycle set so the real module's shutdown-step contribution composes in isolation. */
    @Module
    abstract static class TestLifecycleModule {

        @Multibinds
        abstract Set<ApplicationShutdownStep> shutdownSteps();
    }

    @Singleton
    @Component(
            modules = {
                RateLimitCoreModule.class,
                PolicyModule.class,
                ConfigFixtureModule.class,
                TestLifecycleModule.class
            })
    interface TestComponent {

        RateLimiters rateLimiters();

        Set<ApplicationShutdownStep> shutdownSteps();

        @Component.Factory
        interface Factory {

            TestComponent create(@BindsInstance Vertx vertx);
        }
    }
}
