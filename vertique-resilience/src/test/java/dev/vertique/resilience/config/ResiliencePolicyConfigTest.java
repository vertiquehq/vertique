// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.annotation.Bulkhead;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** TP-002 proof for named policy bounds, required fields, empty-policy rejection, and config parsing. */
@DisplayName("ResiliencePolicyConfig")
class ResiliencePolicyConfigTest {

    private static final String POLICY_NAME = "payments";
    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicyRows")
    @DisplayName("rejects every out-of-bound value with a path-qualified message")
    void rejectsEveryOutOfBoundValueWithAPathQualifiedMessage(
            String caseName, Executable invalidConfiguration, String expectedMessagePrefix) {
        ConfigurationException failure = assertThrows(ConfigurationException.class, invalidConfiguration, caseName);

        assertEquals(true, failure.getMessage().startsWith(expectedMessagePrefix), caseName);
    }

    @Test
    @DisplayName("parses a complete keyed policy through the canonical config parser")
    void parsesACompleteKeyedPolicyThroughTheCanonicalConfigParser() {
        JsonObject root = new JsonObject()
                .put(
                        "resilience",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        POLICY_NAME,
                                                        new JsonObject()
                                                                .put("timeout", new JsonObject().put("valueMs", 1_500L))
                                                                .put(
                                                                        "retry",
                                                                        new JsonObject()
                                                                                .put("maxRetries", 5)
                                                                                .put("delayMs", 100L)
                                                                                .put("backoffMultiplier", 2.0d)
                                                                                .put("maxDelayMs", 30_000L))
                                                                .put(
                                                                        "circuitBreaker",
                                                                        new JsonObject()
                                                                                .put("maxFailures", 3)
                                                                                .put("resetTimeoutMs", 10_000L))
                                                                .put(
                                                                        "bulkhead",
                                                                        new JsonObject()
                                                                                .put("maxConcurrentCalls", 4)
                                                                                .put("mode", "QUEUE")
                                                                                .put("maxQueueSize", 8)
                                                                                .put("queueTimeoutMs", 250L)))));

        List<ResiliencePolicyConfig> parsed = PARSER.parseKeyedObject(
                JsonConfigPaths.navigateObject(root, "resilience", "policies"), "name", ResiliencePolicyConfig.class);

        ResiliencePolicyConfig policy = parsed.get(0);
        assertEquals(POLICY_NAME, policy.name());
        assertEquals(1_500L, policy.timeout().valueMs());
        assertEquals(5, policy.retry().maxRetries());
        assertEquals(100L, policy.retry().delayMs());
        assertEquals(2.0d, policy.retry().backoffMultiplier());
        assertEquals(30_000L, policy.retry().maxDelayMs());
        assertEquals(3, policy.circuitBreaker().maxFailures());
        assertEquals(10_000L, policy.circuitBreaker().resetTimeoutMs());
        assertEquals(4, policy.bulkhead().maxConcurrentCalls());
        assertEquals(Bulkhead.Mode.QUEUE, policy.bulkhead().mode());
        assertEquals(8, policy.bulkhead().maxQueueSize());
        assertEquals(250L, policy.bulkhead().queueTimeoutMs());
    }

    private static Stream<Arguments> invalidPolicyRows() {
        return Stream.of(
                Arguments.of(
                        "retry maxRetries above the upper bound",
                        (Executable) () -> retryPolicy(101, null, null, null),
                        "resilience.policies.payments.retry.maxRetries must"),
                Arguments.of(
                        "retry delayMs below the lower bound",
                        (Executable) () -> retryPolicy(5, -1L, null, null),
                        "resilience.policies.payments.retry.delayMs must"),
                Arguments.of(
                        "retry backoffMultiplier below the lower bound",
                        (Executable) () -> retryPolicy(5, null, 0.5d, null),
                        "resilience.policies.payments.retry.backoffMultiplier must"),
                Arguments.of(
                        "timeout valueMs at zero",
                        (Executable) () ->
                                new ResiliencePolicyConfig(POLICY_NAME, new TimeoutPolicyConfig(0L), null, null, null),
                        "resilience.policies.payments.timeout.valueMs must"),
                Arguments.of(
                        "circuit breaker maxFailures at zero",
                        (Executable) () -> breakerPolicy(0, 1_000L),
                        "resilience.policies.payments.circuitBreaker.maxFailures must"),
                Arguments.of(
                        "circuit breaker resetTimeoutMs at zero",
                        (Executable) () -> breakerPolicy(1, 0L),
                        "resilience.policies.payments.circuitBreaker.resetTimeoutMs must"),
                Arguments.of(
                        "reject bulkhead with a configured queue",
                        (Executable) () -> bulkheadPolicy(4, Bulkhead.Mode.REJECT, 1, null),
                        "resilience.policies.payments.bulkhead.maxQueueSize"),
                Arguments.of(
                        "queue bulkhead maxQueueSize above the upper bound",
                        (Executable) () -> bulkheadPolicy(4, Bulkhead.Mode.QUEUE, 1_025, 1_000L),
                        "resilience.policies.payments.bulkhead.maxQueueSize must"),
                Arguments.of(
                        "queue bulkhead queueTimeoutMs above the upper bound",
                        (Executable) () -> bulkheadPolicy(4, Bulkhead.Mode.QUEUE, 1, 60_001L),
                        "resilience.policies.payments.bulkhead.queueTimeoutMs must"),
                Arguments.of(
                        "policy name outside the identifier grammar",
                        (Executable) () -> new ResiliencePolicyConfig(
                                "payments/bad", new TimeoutPolicyConfig(1_000L), null, null, null),
                        "resilience.policies[].name must match [A-Za-z0-9._~-]{1,128}"),
                Arguments.of(
                        "retry maxRetries is required",
                        (Executable) () -> retryPolicy(null, 100L, 2.0d, 1_000L),
                        "resilience.policies.payments.retry.maxRetries is required"),
                Arguments.of(
                        "bulkhead maxConcurrentCalls is required",
                        (Executable) () -> bulkheadPolicy(null, Bulkhead.Mode.QUEUE, 1, 1_000L),
                        "resilience.policies.payments.bulkhead.maxConcurrentCalls is required"),
                Arguments.of(
                        "empty policy configures no concern",
                        (Executable) () -> new ResiliencePolicyConfig(POLICY_NAME, null, null, null, null),
                        "resilience.policies.payments must configure at least one of timeout, retry, circuitBreaker, bulkhead"));
    }

    private static ResiliencePolicyConfig retryPolicy(
            Integer maxRetries, Long delayMs, Double backoffMultiplier, Long maxDelayMs) {
        return new ResiliencePolicyConfig(
                POLICY_NAME,
                null,
                new RetryPolicyConfig(maxRetries, delayMs, backoffMultiplier, maxDelayMs),
                null,
                null);
    }

    private static ResiliencePolicyConfig breakerPolicy(Integer maxFailures, Long resetTimeoutMs) {
        return new ResiliencePolicyConfig(
                POLICY_NAME, null, null, new CircuitBreakerPolicyConfig(maxFailures, resetTimeoutMs), null);
    }

    private static ResiliencePolicyConfig bulkheadPolicy(
            Integer maxConcurrentCalls, Bulkhead.Mode mode, Integer maxQueueSize, Long queueTimeoutMs) {
        return new ResiliencePolicyConfig(
                POLICY_NAME,
                null,
                null,
                null,
                new BulkheadPolicyConfig(maxConcurrentCalls, mode, maxQueueSize, queueTimeoutMs));
    }
}
