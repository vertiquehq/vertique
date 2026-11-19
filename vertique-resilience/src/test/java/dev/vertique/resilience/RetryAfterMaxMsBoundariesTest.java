// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.config.ResiliencePolicyConfig;
import dev.vertique.resilience.config.RetryPolicyConfig;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.resilience.exception.ResiliencePolicyFailureReason;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof for the edges of the retry delay hint maximum across the override, the configuration, the
 * layering and the resolution: a negative value is refused everywhere, zero is valid everywhere, and an
 * override that sets nothing defines no retry concern.
 */
class RetryAfterMaxMsBoundariesTest {

    /** Supporting FR-032: the override refuses a negative maximum, and accepts zero, a value and none. */
    @Test
    @DisplayName("a retry override refuses a negative hint maximum and accepts zero")
    void retryOverrideRefusesANegativeHintMaximumAndAcceptsZero() {
        // When an override carries a negative maximum
        // Then it is refused
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> retryOverride(Optional.empty(), -1L));
        assertEquals("retryAfterMaxMs must be >= 0", refused.getMessage());

        // And zero, a positive value and no value are accepted
        assertEquals(0L, retryOverride(Optional.empty(), 0L).retryAfterMaxMs());
        assertEquals(1L, retryOverride(Optional.empty(), 1L).retryAfterMaxMs());
        assertNull(retryOverride(Optional.empty(), null).retryAfterMaxMs());
    }

    /** Supporting FR-032: the retry configuration refuses a negative maximum and accepts zero. */
    @Test
    @DisplayName("a retry configuration refuses a negative hint maximum and accepts zero")
    void retryConfigurationRefusesANegativeHintMaximumAndAcceptsZero() {
        // When a configuration is created directly with a negative maximum
        // Then it is refused
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> RetryConfig.from(1, RetryBackoff.fixed(1L), Set.of(), Set.of(), (failure, count) -> true, -1L));
        assertEquals("retryAfterMaxMs must be >= 0", refused.getMessage());

        // And zero is accepted
        assertEquals(
                0L,
                RetryConfig.from(1, RetryBackoff.fixed(1L), Set.of(), Set.of(), (failure, count) -> true, 0L)
                        .retryAfterMaxMs());
    }

    /** Supporting FR-032: the bound policy tier accepts a maximum of zero and refuses a negative one. */
    @Test
    @DisplayName("a policy tier accepts a hint maximum of zero and refuses a negative one")
    void policyTierAcceptsAHintMaximumOfZeroAndRefusesANegativeOne() {
        // When a tier carries a maximum of zero
        // Then it is accepted
        assertDoesNotThrow(() -> new ResiliencePolicyConfig(
                "payments", null, new RetryPolicyConfig(5, null, null, null, 0L), null, null));

        // And a negative maximum is refused with the key's path
        ConfigurationException refused = assertThrows(
                ConfigurationException.class,
                () -> new ResiliencePolicyConfig(
                        "payments", null, new RetryPolicyConfig(5, null, null, null, -1L), null, null));
        assertTrue(refused.getMessage().startsWith("resilience.policies.payments.retry.retryAfterMaxMs must"));
    }

    /**
     * Supporting FR-032: a higher override that sets nothing does not take over a lower override's
     * explicit disable, while one that sets only the maximum does.
     */
    @Test
    @DisplayName("only a higher override that sets a retry value overrides a lower disable")
    void onlyAHigherOverrideThatSetsARetryValueOverridesALowerDisable() {
        // Given a lower override that disables retry
        RetryOverride disabledLower = retryOverride(Optional.of(false), null);

        // When a higher override that sets nothing is layered over it
        RetryOverride merged = layered(retryOverride(Optional.empty(), null), disabledLower);

        // Then the lower disable stays in force
        assertEquals(Optional.of(false), merged.enabled());
        assertNull(merged.retryAfterMaxMs());

        // And a higher override that sets only the maximum is not disabled by it
        RetryOverride withValue = layered(retryOverride(Optional.empty(), 100L), disabledLower);
        assertTrue(withValue.enabled().isEmpty());
        assertEquals(100L, withValue.retryAfterMaxMs());
    }

    /**
     * Supporting FR-032: resolution treats an override that sets nothing as no retry concern, and an
     * override that sets only the maximum as a retry concern that is still incomplete.
     */
    @Test
    @DisplayName("an override that sets nothing defines no retry and one that sets only the maximum is incomplete")
    void overrideThatSetsNothingDefinesNoRetryAndOneThatSetsOnlyTheMaximumIsIncomplete() {
        // Given a resolver with no annotations and no defaults
        ResiliencePolicyResolver resolver = new ResiliencePolicyResolver();

        // When the retry override sets nothing
        ResolvedResiliencePolicy nothing = resolver.resolve(
                ResilienceAnnotations.NONE,
                overrides(retryOverride(Optional.empty(), null)),
                ResilienceDefaults.none());

        // Then no retry concern is resolved
        assertTrue(nothing.retry().isEmpty());

        // When the retry override sets only the maximum
        // Then the concern exists and lacks its retry count and backoff
        ResiliencePolicyException incomplete = assertThrows(
                ResiliencePolicyException.class,
                () -> resolver.resolve(
                        ResilienceAnnotations.NONE,
                        overrides(retryOverride(Optional.empty(), 100L)),
                        ResilienceDefaults.none()));
        assertEquals(
                new ResiliencePolicyException(ResiliencePolicyFailureReason.INCOMPLETE_CONFIGURATION).getMessage(),
                incomplete.getMessage());
    }

    private static RetryOverride retryOverride(Optional<Boolean> enabled, Long retryAfterMaxMs) {
        return new RetryOverride(
                enabled,
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                retryAfterMaxMs);
    }

    private static ResiliencePolicyOverrides overrides(RetryOverride retry) {
        return new ResiliencePolicyOverrides(Optional.empty(), Optional.of(retry), Optional.empty(), Optional.empty());
    }

    private static RetryOverride layered(RetryOverride higher, RetryOverride lower) {
        return overrides(higher).over(overrides(lower)).retry().orElseThrow();
    }
}
