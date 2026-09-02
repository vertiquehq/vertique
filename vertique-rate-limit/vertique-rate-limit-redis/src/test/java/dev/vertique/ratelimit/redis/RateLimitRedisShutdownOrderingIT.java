// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.RedisAPI;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TP-006: application teardown runs {@link ApplicationShutdownStep}s in the <em>reverse</em> of
 * {@link LifecycleOrdered#comparator()} startup order (the same ordering {@code
 * dev.vertique.application.VertiqueApplicationHandle#teardown()} applies) — so {@code
 * vertique-rate-limit-redis}'s {@link RedisRateLimitFenceLifecycle} ({@code INFRA}, priority
 * {@code RedisClientShutdownStep.SHUTDOWN_PRIORITY + 1}) must close the CLUSTERED rate-limit
 * runtime <em>before</em> {@code vertique-redis-core}'s own {@code RedisClientShutdownStep}
 * closes the shared client, and {@code vertique-rate-limit-core}'s {@code CONFIGURE}-phase
 * shutdown step (which sorts ahead of {@code INFRA} in startup order and therefore runs
 * <em>after</em> it once reversed for teardown) must observe an already-closed, no-op runtime
 * (contracts/rate-limit-runtime.md, "Redis integration contract"; §11.5).
 *
 * <p>This proof composes the real Dagger graph (no test double stands between it and the shared
 * client whose close ordering is under proof) and exercises the framework's own {@link
 * LifecycleOrdered#comparator()} — reversed, exactly as the orchestrator applies it — over the
 * component's real, merged {@code Set<ApplicationShutdownStep>} multibinding, then drives each
 * step's real {@code stop()} sequentially in that order (mirroring {@code
 * VertiqueApplicationHandle#teardown()}'s own compose-chain), rather than hand-sequencing the two
 * steps in an assumed order.
 */
@Testcontainers
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisShutdownOrderingIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
                    DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE))
            .withExposedPorts(6379)
            // DEBUG SLEEP is disallowed by default in Redis 7.2 unless explicitly enabled.
            .withCommand("redis-server", "--enable-debug-command", "yes");

    @Test
    void shouldFenceInFlightConsumeBeforeSharedRedisClientCloses() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            // Given: a real Dagger graph composing RateLimitCoreModule + RateLimitRedisModule
            // (which in turn installs RedisConnectionModule), and one consume(...) call held
            // in flight (blocked before its Redis round-trip completes, via a forced
            // server-side DEBUG SLEEP) at the moment application shutdown begins.
            JsonObject configuration = RateLimitRedisTestFixture.configuration(
                    REDIS.getHost(),
                    REDIS.getMappedPort(6379),
                    5,
                    5,
                    60_000,
                    "OPEN",
                    5_000,
                    1_000,
                    RateLimitRedisTestFixture.NAMESPACE);
            ClusteredComponent component = DaggerRateLimitRedisShutdownOrderingIT_ClusteredComponent.builder()
                    .vertxModule(new VertxModule(vertx, configuration))
                    .build();
            RateLimiters rateLimiters = component.rateLimiters();
            RedisClientRegistry registry = component.redisClientRegistry();
            RedisAPI rawCommands = RedisAPI.api(registry.client(RateLimitRedisTestFixture.CONNECTION));

            // Given: the REAL reversed-teardown order, computed the way the orchestrator computes
            // it — LifecycleOrdered.comparator().reversed() over the component's own merged
            // shutdown-step set, never a hand-picked order.
            List<ApplicationShutdownStep> reverseTeardownOrder = component.shutdownSteps().stream()
                    .sorted(LifecycleOrdered.comparator().reversed())
                    .toList();
            assertThat(reverseTeardownOrder)
                    .as("real reversed-teardown order: the redis-rate-limit fence step (INFRA, "
                            + "priority above the shared client) precedes the shared Redis client "
                            + "close (INFRA), which precedes rate-limit-core's now-redundant "
                            + "CONFIGURE-phase close")
                    .extracting(step -> step.getClass().getSimpleName())
                    .containsExactly(
                            "RedisRateLimitFenceLifecycle", "RedisClientShutdownStep", "RateLimitersShutdownStep");

            rawCommands.debug(List.of("SLEEP", "0.5"));
            Future<RateLimitDecision> inFlight =
                    rateLimiters.limiter(RateLimitRedisTestFixture.POLICY_NAME).acquire(RateLimitKey.of("caller-1"));

            // When: teardown runs — every step's real stop(), driven sequentially in the real
            // reversed-comparator order above (mirroring VertiqueApplicationHandle#teardown()'s
            // own compose-chain) — while the in-flight consume(...) is still held.
            List<String> completionOrder = new ArrayList<>();
            Future<Void> teardown = Future.succeededFuture();
            for (ApplicationShutdownStep step : reverseTeardownOrder) {
                teardown = teardown.compose(ignored -> step.stop()
                        .onSuccess(v -> completionOrder.add(step.getClass().getSimpleName())));
            }
            RateLimitRedisTestFixture.await(teardown, 10);

            // Then: the fence actually completed, in real execution order, before the shared
            // Redis client closed.
            assertThat(completionOrder)
                    .as("real step execution order: fence completes before the shared client closes")
                    .containsExactly(
                            "RedisRateLimitFenceLifecycle", "RedisClientShutdownStep", "RateLimitersShutdownStep");

            // Then: the in-flight consume(...) settles — never hangs — as a normalized decision
            // (PERMITTED, using the still-open client, or BACKEND_FAILURE_OPEN under this
            // OPEN-failure-mode policy), never an unhandled/raw client-closed failure leaking
            // out of acquire()'s own decision-first contract.
            RateLimitDecision decision = RateLimitRedisTestFixture.await(inFlight, 10);
            assertThat(decision).isNotNull();
            assertThat(decision.permitted())
                    .as("in-flight consume settles fenced/normalized (PERMITTED or "
                            + "BACKEND_FAILURE_OPEN), never a raw client-closed leak")
                    .isTrue();
        } finally {
            RateLimitRedisTestFixture.await(vertx.close(), 10);
        }
    }

    @Singleton
    @Component(modules = {RateLimitRedisModule.class, ConfigParsingModule.class, VertxModule.class})
    interface ClusteredComponent {
        RateLimiters rateLimiters();

        RedisClientRegistry redisClientRegistry();

        Set<ApplicationShutdownStep> shutdownSteps();

        @Component.Builder
        interface Builder {
            Builder vertxModule(VertxModule module);

            ClusteredComponent build();
        }
    }
}
