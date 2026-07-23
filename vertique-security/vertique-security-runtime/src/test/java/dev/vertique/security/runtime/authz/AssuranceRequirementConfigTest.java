// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.authz.ActionRef;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AssuranceRequirementConfig} — the FR-ID-AR-003 action-PATTERN authoring
 * contract (PRD identity-002).
 *
 * <p>Pins: a wildcard pattern key gates every action it matches (the C1 spec-conformance fix — a
 * previous exact-string-keyed implementation silently matched nothing for a wildcard key), an exact
 * pattern still gates only its exact action, a non-matching action reports no requirement, two
 * overlapping patterns combine into the strictest requirement, and a malformed pattern key fails
 * fast at config-construction time rather than silently ungating.
 */
class AssuranceRequirementConfigTest {

    @Nested
    @DisplayName("wildcardPatternGatesMatchingActions")
    class WildcardPatternGatesMatchingActions {

        @Test
        @DisplayName("a wildcard action-pattern key gates every action it matches")
        void wildcardPatternGatesMatchingActions() {
            AssuranceRequirement requirement = new AssuranceRequirement(2, Duration.ofMinutes(5));
            AssuranceRequirementConfig config =
                    AssuranceRequirementConfig.fromJson(Map.of("payments.refund.*", requirement));

            Optional<AssuranceRequirement> result =
                    config.requirementFor(ActionRef.of("payments", "refund", "approve"));

            assertTrue(result.isPresent(), "a wildcard pattern must gate an action it matches");
            assertEquals(requirement, result.get());
        }
    }

    @Nested
    @DisplayName("exactPatternStillGates")
    class ExactPatternStillGates {

        @Test
        @DisplayName("a fully-specified action pattern still gates its exact action")
        void exactPatternStillGates() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            AssuranceRequirementConfig config =
                    AssuranceRequirementConfig.fromJson(Map.of("cms.content.delete", requirement));

            Optional<AssuranceRequirement> result = config.requirementFor(ActionRef.of("cms", "content", "delete"));

            assertTrue(result.isPresent());
            assertEquals(requirement, result.get());
        }
    }

    @Nested
    @DisplayName("nonMatchingActionUngated")
    class NonMatchingActionUngated {

        @Test
        @DisplayName("an action matching no configured pattern reports no requirement")
        void nonMatchingActionUngated() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            AssuranceRequirementConfig config =
                    AssuranceRequirementConfig.fromJson(Map.of("cms.content.delete", requirement));

            Optional<AssuranceRequirement> result =
                    config.requirementFor(ActionRef.of("payments", "refund", "approve"));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("overlappingPatternsCombineStrictest")
    class OverlappingPatternsCombineStrictest {

        @Test
        @DisplayName("a wildcard rule and a more-specific rule that both match combine into the strictest "
                + "requirement (highest minProviderLevel, shortest maxAge)")
        void overlappingPatternsCombineStrictest() {
            AssuranceRequirement wildcard = new AssuranceRequirement(2, Duration.ofMinutes(10));
            AssuranceRequirement specific = new AssuranceRequirement(3, Duration.ofMinutes(5));
            AssuranceRequirementConfig config = AssuranceRequirementConfig.fromJson(
                    Map.of("payments.refund.*", wildcard, "payments.refund.approve", specific));

            Optional<AssuranceRequirement> result =
                    config.requirementFor(ActionRef.of("payments", "refund", "approve"));

            assertTrue(result.isPresent());
            assertEquals(3, result.get().minProviderLevel(), "the strictest (highest) minProviderLevel must win");
            assertEquals(Duration.ofMinutes(5), result.get().maxAge(), "the strictest (shortest) maxAge must win");
        }
    }

    @Nested
    @DisplayName("malformedPatternKeyFailsStartup")
    class MalformedPatternKeyFailsStartup {

        @Test
        @DisplayName("an unparseable pattern key throws ConfigurationException at config construction, never "
                + "silently ungating")
        void malformedPatternKeyFailsStartup() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            Map<String, AssuranceRequirement> actions = Map.of("*", requirement);

            assertThrows(ConfigurationException.class, () -> AssuranceRequirementConfig.fromJson(actions));
        }
    }

    @Nested
    @DisplayName("neverMatchingPatternKeyFailsStartup")
    class NeverMatchingPatternKeyFailsStartup {

        @Test
        @DisplayName("a segment-count-mismatched pattern key (e.g. a two-segment exact pattern) that could never "
                + "match any 3-segment ActionRef throws ConfigurationException at config construction, never "
                + "silently ungating the action (C1 residual fix, ActionPattern's own construction-time rejection)")
        void neverMatchingPatternKeyFailsStartup() {
            AssuranceRequirement requirement = new AssuranceRequirement(1, Duration.ofMinutes(5));
            Map<String, AssuranceRequirement> actions = Map.of("payments.refund", requirement);

            assertThrows(ConfigurationException.class, () -> AssuranceRequirementConfig.fromJson(actions));
        }
    }
}
