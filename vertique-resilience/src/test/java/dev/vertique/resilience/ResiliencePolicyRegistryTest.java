// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.annotation.RetryDeclaration;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.config.TimeoutPolicyConfig;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-001 proof for named resilience-tier lookup, per-concern completion, and precedence layering.
 *
 * <p>The fixtures keep the registry and resolver in memory so each assertion isolates the
 * observable policy selected by the named tier and its caller-provided overrides.
 */
@DisplayName("ResiliencePolicyRegistry")
class ResiliencePolicyRegistryTest {

    private static final String PAYMENTS_POLICY = "payments";

    @Test
    @DisplayName("layers named tier per concern against declarations and fails on unknown names")
    void layersNamedTierPerConcernAgainstDeclarationsAndFailsOnUnknownNames() {
        ResiliencePolicyRegistry paymentsRegistry = ResiliencePolicyRegistry.of(List.of(paymentsPolicy()));
        ResiliencePolicyResolver resolver = new ResiliencePolicyResolver();

        assertAll(
                "named resilience policy rows",
                () -> {
                    ResolvedResiliencePolicy resolved = resolve(
                            resolver, paymentsRegistry, anchorOnly(PAYMENTS_POLICY), ResiliencePolicyOverrides.none());

                    RetryConfig retry = resolved.retry().orElseThrow();
                    assertEquals(5, retry.maxRetries());
                    RetryBackoff.Exponential backoff =
                            assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
                    assertEquals(500L, backoff.initialDelayMs());
                    assertEquals(2.0d, backoff.multiplier());
                    assertEquals(30_000L, backoff.maxDelayMs());
                    assertEquals(1_000L, backoff.maxJitterMs());
                },
                () -> {
                    RetryDeclaration declaration = retryDeclaration(2, 1L);
                    ResolvedResiliencePolicy resolved = resolve(
                            resolver,
                            paymentsRegistry,
                            withRetryDeclaration(PAYMENTS_POLICY, declaration),
                            retryOverrides(retryWithBackoffDelay(100L)));

                    RetryConfig retry = resolved.retry().orElseThrow();
                    assertEquals(5, retry.maxRetries());
                    RetryBackoff.Exponential backoff =
                            assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
                    assertEquals(100L, backoff.initialDelayMs());
                },
                () -> {
                    ResolvedResiliencePolicy resolved = resolve(
                            resolver,
                            paymentsRegistry,
                            withRetryDeclaration(PAYMENTS_POLICY, retryDeclaration(2, 100L)),
                            ResiliencePolicyOverrides.none());

                    RetryConfig retry = resolved.retry().orElseThrow();
                    assertEquals(5, retry.maxRetries());
                    RetryBackoff.Exponential backoff =
                            assertInstanceOf(RetryBackoff.Exponential.class, retry.backoff());
                    assertEquals(100L, backoff.initialDelayMs());
                },
                () -> {
                    ResiliencePolicyRegistry timeoutOnlyRegistry =
                            ResiliencePolicyRegistry.of(List.of(new ResiliencePolicyConfig(
                                    "timeout-only", new TimeoutPolicyConfig(2_000L), null, null, null)));
                    ResolvedResiliencePolicy resolved = resolve(
                            resolver,
                            timeoutOnlyRegistry,
                            anchorOnly("timeout-only"),
                            ResiliencePolicyOverrides.none());

                    assertEquals(2_000L, resolved.timeout().orElseThrow().timeoutMs());
                    assertTrue(resolved.retry().isEmpty());
                    assertTrue(resolved.circuitBreaker().isEmpty());
                },
                () -> {
                    ResolvedResiliencePolicy resolved = resolve(
                            resolver, paymentsRegistry, anchorOnly(PAYMENTS_POLICY), retryOverrides(disabledRetry()));

                    assertTrue(resolved.retry().isEmpty());
                },
                () -> {
                    BackoffStrategy transportStrategy = retryCount -> 17L;
                    BackoffOverride transportBackoff = new BackoffOverride(
                            Optional.of(transportStrategy),
                            OptionalLong.empty(),
                            Optional.empty(),
                            OptionalLong.empty(),
                            OptionalLong.empty());
                    RetryOverride transportRetry = new RetryOverride(
                            Optional.empty(),
                            OptionalInt.empty(),
                            Optional.of(transportBackoff),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty());
                    ResiliencePolicyRegistry completeTierRegistry =
                            ResiliencePolicyRegistry.of(List.of(new ResiliencePolicyConfig(
                                    PAYMENTS_POLICY, null, new RetryPolicyConfig(5, 200L, 1.5d, 5_000L), null, null)));

                    ResolvedResiliencePolicy resolved = resolve(
                            resolver,
                            completeTierRegistry,
                            anchorOnly(PAYMENTS_POLICY),
                            retryOverrides(transportRetry));

                    RetryConfig retry = resolved.retry().orElseThrow();
                    assertEquals(5, retry.maxRetries());
                    RetryBackoff.Custom backoff = assertInstanceOf(RetryBackoff.Custom.class, retry.backoff());
                    assertSame(transportStrategy, backoff.delegate());
                },
                () -> {
                    ConfigurationException failure =
                            assertThrows(ConfigurationException.class, () -> paymentsRegistry.require("missing"));
                    assertEquals("resilience.policies.missing is not defined", failure.getMessage());
                },
                () -> {
                    ConfigurationException failure =
                            assertThrows(ConfigurationException.class, () -> ResiliencePolicyRegistry.empty()
                                    .require(PAYMENTS_POLICY));
                    assertEquals("resilience.policies.payments is not defined", failure.getMessage());
                });
    }

    private static ResolvedResiliencePolicy resolve(
            ResiliencePolicyResolver resolver,
            ResiliencePolicyRegistry registry,
            ResilienceAnnotations annotations,
            ResiliencePolicyOverrides transportOverrides) {
        ResiliencePolicyOverrides layered = registry.layer(annotations, transportOverrides);
        return resolver.resolve(annotations, layered, ResilienceDefaults.none());
    }

    private static ResiliencePolicyConfig paymentsPolicy() {
        return new ResiliencePolicyConfig(
                PAYMENTS_POLICY, null, new RetryPolicyConfig(5, null, null, null), null, null);
    }

    private static ResilienceAnnotations anchorOnly(String policy) {
        return new ResilienceAnnotations(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(policy));
    }

    private static ResilienceAnnotations withRetryDeclaration(String policy, RetryDeclaration declaration) {
        return new ResilienceAnnotations(
                Optional.empty(), Optional.empty(), Optional.of(declaration), Optional.empty(), Optional.of(policy));
    }

    private static RetryDeclaration retryDeclaration(int maxRetries, long delayMs) {
        return new RetryDeclaration(
                maxRetries, delayMs, 2.0d, 30_000L, BackoffStrategy.Default.class, List.of(), List.of());
    }

    private static RetryOverride retryWithBackoffDelay(long delayMs) {
        return new RetryOverride(
                Optional.empty(),
                OptionalInt.empty(),
                Optional.of(new BackoffOverride(
                        Optional.empty(),
                        OptionalLong.of(delayMs),
                        Optional.empty(),
                        OptionalLong.empty(),
                        OptionalLong.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static RetryOverride disabledRetry() {
        return new RetryOverride(
                Optional.of(false),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ResiliencePolicyOverrides retryOverrides(RetryOverride retry) {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }
}
