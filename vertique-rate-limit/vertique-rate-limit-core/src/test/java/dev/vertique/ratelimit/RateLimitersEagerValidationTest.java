// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-003: {@link RateLimiters}'s constructor eagerly walks every declared policy against the
 * bound backend map and fails application startup, at construction time and before any handle is
 * requested (contracts/rate-limit-runtime.md, "Startup validation"). Proven here by direct
 * construction, independent of the Dagger shutdown-step machinery that later forces this
 * constructor to run eagerly at bootstrap (T004).
 */
class RateLimitersEagerValidationTest {

    private static final Vertx VERTX = Vertx.vertx();

    /** Never actually invoked — these rows prove construction-time validation only. */
    private static final RateLimitBackend NOOP_BACKEND = request -> Future.succeededFuture(
            new RateLimitBackendResult(true, 0L, Optional.empty(), Optional.empty(), Optional.empty()));

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    static Stream<MatrixRow> t003ValidationMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldFailForDuplicatePolicyNamesAcrossSources",
                        RateLimitersEagerValidationTest::shouldFailForDuplicatePolicyNamesAcrossSources),
                new MatrixRow(
                        "shouldFailForEnabledPolicyWithUnboundBackendMode",
                        RateLimitersEagerValidationTest::shouldFailForEnabledPolicyWithUnboundBackendMode),
                new MatrixRow(
                        "shouldFailForEnabledClusteredPolicyWithNoConfiguredSecret",
                        RateLimitersEagerValidationTest::shouldFailForEnabledClusteredPolicyWithNoConfiguredSecret),
                new MatrixRow(
                        "shouldFailForEnabledClusteredPolicyWithSecretShorterThan32Bytes",
                        RateLimitersEagerValidationTest
                                ::shouldFailForEnabledClusteredPolicyWithSecretShorterThan32Bytes),
                new MatrixRow(
                        "shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent",
                        RateLimitersEagerValidationTest
                                ::shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t003ValidationMatrix")
    @DisplayName("enforces the T003 startup-validation matrix")
    void shouldEnforceT003StartupValidationMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: duplicate policy name, across sources ---

    private static void shouldFailForDuplicatePolicyNamesAcrossSources() {
        RateLimitPolicy fromConfig = policy("dup", RateLimitMode.LOCAL, true, "r1");
        RateLimitPolicy fromIntoSet = policy("dup", RateLimitMode.LOCAL, true, "r2");
        Set<RateLimitPolicy> policies = Set.of(fromConfig, fromIntoSet);
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, NOOP_BACKEND);

        assertThatThrownBy(() -> new RateLimiters(policies, backends, null, VERTX, Set.of()))
                .as("two policies named 'dup', one from config, one from @IntoSet")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    // --- Row 2: enabled policy whose mode has no bound backend ---

    private static void shouldFailForEnabledPolicyWithUnboundBackendMode() {
        RateLimitPolicy clustered = policy("clustered-quota", RateLimitMode.CLUSTERED, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(clustered);
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, NOOP_BACKEND);

        assertThatThrownBy(() -> new RateLimiters(policies, backends, null, VERTX, Set.of()))
                .as("enabled CLUSTERED policy with only a LOCAL backend bound")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLUSTERED");
    }

    // --- Row 3: enabled CLUSTERED policy, both backends bound, no configured secret ---

    private static void shouldFailForEnabledClusteredPolicyWithNoConfiguredSecret() {
        RateLimitPolicy clustered = policy("clustered-quota", RateLimitMode.CLUSTERED, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(clustered);
        Map<RateLimitMode, RateLimitBackend> backends =
                Map.of(RateLimitMode.LOCAL, NOOP_BACKEND, RateLimitMode.CLUSTERED, NOOP_BACKEND);

        assertThatThrownBy(() -> new RateLimiters(policies, backends, null, VERTX, Set.of()))
                .as("enabled CLUSTERED policy with no keyDerivation.secret configured")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret");
    }

    // --- Row 4: enabled CLUSTERED policy, resolved secret exactly 31 bytes UTF-8 ---

    private static void shouldFailForEnabledClusteredPolicyWithSecretShorterThan32Bytes() {
        RateLimitPolicy clustered = policy("clustered-quota", RateLimitMode.CLUSTERED, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(clustered);
        Map<RateLimitMode, RateLimitBackend> backends =
                Map.of(RateLimitMode.LOCAL, NOOP_BACKEND, RateLimitMode.CLUSTERED, NOOP_BACKEND);
        String secret31Bytes = "a".repeat(31);
        assertThat(secret31Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(31);

        assertThatThrownBy(() -> new RateLimiters(policies, backends, secret31Bytes, VERTX, Set.of()))
                .as("resolved keyDerivation.secret shorter than 32 bytes UTF-8")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    // --- Row 5 (control): one enabled LOCAL policy, no CLUSTERED policy present ---

    private static void shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent() {
        RateLimitPolicy local = policy("local-quota", RateLimitMode.LOCAL, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(local);
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, NOOP_BACKEND);

        assertThatCode(() -> new RateLimiters(policies, backends, null, VERTX, Set.of()))
                .as("one enabled LOCAL policy, no secret configured, no CLUSTERED policy present")
                .doesNotThrowAnyException();
    }

    private static RateLimitPolicy policy(String name, RateLimitMode mode, boolean enabled, String revision) {
        return new RateLimitPolicy(
                name,
                enabled,
                mode,
                RateLimitFailureMode.OPEN,
                revision,
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofMillis(1_000L))));
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }
}
