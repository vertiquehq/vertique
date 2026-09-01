// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.ratelimit.dagger.RateLimitCoreModule;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitSubjectResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
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
 *
 * <p>M5 (review repair): {@code rateLimit.local.maxTrackedKeys}/{@code cleanupIntervalMs}
 * (default and per-policy override) are eagerly validated too — that validation lives inside
 * {@code RateLimitCoreModule}'s LOCAL backend provider (a Dagger dependency of {@link
 * RateLimiters} itself), so those rows are proven through a real, minimal Dagger graph rather
 * than direct construction.
 */
class RateLimitersEagerValidationTest {

    private static final Vertx VERTX = Vertx.vertx();
    private static final RateLimitSubjectResolver ANONYMOUS_SUBJECT_RESOLVER = Optional::empty;

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
                        "shouldFailForEnabledClusteredPolicyWithUnresolvedPlaceholderSecret",
                        RateLimitersEagerValidationTest
                                ::shouldFailForEnabledClusteredPolicyWithUnresolvedPlaceholderSecret),
                new MatrixRow(
                        "shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent",
                        RateLimitersEagerValidationTest
                                ::shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent),
                new MatrixRow(
                        "shouldFailForLocalMaxTrackedKeysBelowOneAtGlobalDefault",
                        RateLimitersEagerValidationTest::shouldFailForLocalMaxTrackedKeysBelowOneAtGlobalDefault),
                new MatrixRow(
                        "shouldFailForLocalCleanupIntervalMsBelowOneAtGlobalDefault",
                        RateLimitersEagerValidationTest::shouldFailForLocalCleanupIntervalMsBelowOneAtGlobalDefault),
                new MatrixRow(
                        "shouldFailForPerPolicyLocalMaxTrackedKeysOverrideBelowOne",
                        RateLimitersEagerValidationTest::shouldFailForPerPolicyLocalMaxTrackedKeysOverrideBelowOne),
                new MatrixRow(
                        "shouldFailForUnrecognizedDefaultMode",
                        RateLimitersEagerValidationTest::shouldFailForUnrecognizedDefaultMode));
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

        assertThatThrownBy(() -> newRateLimiters(policies, backends, null))
                .as("two policies named 'dup', one from config, one from @IntoSet")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    // --- Row 2: enabled policy whose mode has no bound backend ---

    private static void shouldFailForEnabledPolicyWithUnboundBackendMode() {
        RateLimitPolicy clustered = policy("clustered-quota", RateLimitMode.CLUSTERED, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(clustered);
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, NOOP_BACKEND);

        assertThatThrownBy(() -> newRateLimiters(policies, backends, null))
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

        assertThatThrownBy(() -> newRateLimiters(policies, backends, null))
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

        assertThatThrownBy(() -> newRateLimiters(policies, backends, secret31Bytes))
                .as("resolved keyDerivation.secret shorter than 32 bytes UTF-8")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    // --- Row 4b: enabled CLUSTERED policy, resolved secret looks like an unresolved placeholder ---

    private static void shouldFailForEnabledClusteredPolicyWithUnresolvedPlaceholderSecret() {
        RateLimitPolicy clustered = policy("clustered-quota", RateLimitMode.CLUSTERED, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(clustered);
        Map<RateLimitMode, RateLimitBackend> backends =
                Map.of(RateLimitMode.LOCAL, NOOP_BACKEND, RateLimitMode.CLUSTERED, NOOP_BACKEND);
        // Deliberately >= 32 bytes so this row is decisive against the placeholder check alone,
        // not merely re-triggering the shorter-than-32-bytes row above.
        String unresolvedPlaceholder = "${RATE_LIMIT_KEY_DERIVATION_SECRET_ENV_VAR}";
        assertThat(unresolvedPlaceholder.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(32);

        assertThatThrownBy(() -> newRateLimiters(policies, backends, unresolvedPlaceholder))
                .as("resolved keyDerivation.secret still literally '${...}' — an unresolved config placeholder")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    // --- Row 5 (control): one enabled LOCAL policy, no CLUSTERED policy present ---

    private static void shouldSucceedForOneEnabledLocalPolicyWithNoClusteredPolicyPresent() {
        RateLimitPolicy local = policy("local-quota", RateLimitMode.LOCAL, true, "r1");
        Set<RateLimitPolicy> policies = Set.of(local);
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, NOOP_BACKEND);

        assertThatCode(() -> newRateLimiters(policies, backends, null))
                .as("one enabled LOCAL policy, no secret configured, no CLUSTERED policy present")
                .doesNotThrowAnyException();
    }

    // --- Row 6: rateLimit.local.maxTrackedKeys below 1 at the global default, via real Dagger wiring ---

    private static void shouldFailForLocalMaxTrackedKeysBelowOneAtGlobalDefault() {
        JsonObject config = new JsonObject()
                .put("rateLimit", new JsonObject().put("local", new JsonObject().put("maxTrackedKeys", 0)));

        assertThatThrownBy(() -> buildViaDagger(config).rateLimiters())
                .as("rateLimit.local.maxTrackedKeys=0 (global default)")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("maxTrackedKeys");
    }

    // --- Row 7: rateLimit.local.cleanupIntervalMs below 1 at the global default, via real Dagger wiring ---

    private static void shouldFailForLocalCleanupIntervalMsBelowOneAtGlobalDefault() {
        JsonObject config = new JsonObject()
                .put("rateLimit", new JsonObject().put("local", new JsonObject().put("cleanupIntervalMs", 0)));

        assertThatThrownBy(() -> buildViaDagger(config).rateLimiters())
                .as("rateLimit.local.cleanupIntervalMs=0 (global default)")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cleanupIntervalMs");
    }

    // --- Row 8: a per-policy rateLimit.policies.<name>.local.maxTrackedKeys override below 1 ---

    private static void shouldFailForPerPolicyLocalMaxTrackedKeysOverrideBelowOne() {
        JsonObject config = new JsonObject()
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        "quota-x",
                                                        new JsonObject()
                                                                .put(
                                                                        "local",
                                                                        new JsonObject().put("maxTrackedKeys", 0)))));

        assertThatThrownBy(() -> buildViaDagger(config).rateLimiters())
                .as("rateLimit.policies.quota-x.local.maxTrackedKeys=0 (per-policy override)")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("quota-x")
                .hasMessageContaining("maxTrackedKeys");
    }

    // --- Row 9 (m4): an unrecognized rateLimit.defaultMode wraps into ConfigurationException ---

    private static void shouldFailForUnrecognizedDefaultMode() {
        JsonObject config = new JsonObject().put("rateLimit", new JsonObject().put("defaultMode", "NOT_A_MODE"));

        assertThatThrownBy(() -> buildViaDagger(config).rateLimiters())
                .as("rateLimit.defaultMode='NOT_A_MODE'")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("defaultMode");
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

    /** Direct construction via the one (seven-argument) {@link RateLimiters} constructor. */
    private static RateLimiters newRateLimiters(
            Set<RateLimitPolicy> policies, Map<RateLimitMode, RateLimitBackend> backends, String keyDerivationSecret) {
        return new RateLimiters(
                policies, backends, keyDerivationSecret, VERTX, Set.of(), ANONYMOUS_SUBJECT_RESOLVER, true);
    }

    /** Builds a real, minimal Dagger graph over {@link RateLimitCoreModule} alone, for the given raw config. */
    private static TestComponent buildViaDagger(JsonObject config) {
        return DaggerRateLimitersEagerValidationTest_TestComponent.factory()
                .create(VERTX, new ConfigFixtureModule(config));
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }

    // --- Fixture: a real, minimal Dagger graph over RateLimitCoreModule alone, config-parameterized ---

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
    @Component(modules = {RateLimitCoreModule.class, ConfigFixtureModule.class})
    interface TestComponent {

        RateLimiters rateLimiters();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, ConfigFixtureModule configFixtureModule);
        }
    }
}
