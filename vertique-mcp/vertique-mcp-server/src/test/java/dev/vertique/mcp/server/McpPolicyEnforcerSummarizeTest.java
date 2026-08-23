// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.vertique.mcp.lifecycle.McpAuthorizationSummary;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthzReasonCodes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R05 (issue #431) — {@link McpPolicyEnforcer#summarize} bounds the {@link McpAuthorizationSummary}
 * it produces so it can never fail to construct, regardless of the {@link AuthorizationDecision}
 * handed to it.
 *
 * <p><strong>Why this is the decisive seam.</strong> {@link McpAuthorizationSummary}'s own compact
 * constructor already enforces its bounds (throws on violation) — the record cannot silently grow
 * unbounded. What it does <em>not</em> do is protect a caller that blindly forwards an {@link
 * AuthorizationDecision#reasonCode()}: the shipped vocabulary ({@link AuthzReasonCodes}) is
 * upper-case ({@code "PERMITTED"}, {@code "ROLE_MISSING"}), which the summary's lower-case grammar
 * rejects outright, and {@link AuthzReasonCodes}'s own javadoc records that the set is a deliberate
 * <em>superset</em> an application-supplied {@link dev.vertique.security.authz.Authorizer} is not
 * bound to. A blind pass-through would crash the request the moment any decision (shipped or
 * custom) failed to satisfy the summary's exact grammar. {@link McpPolicyEnforcer#summarize} is the
 * one seam that must survive an arbitrary decision without ever throwing.
 */
class McpPolicyEnforcerSummarizeTest {

    private static final String SHIPPED_ROLE_CODE = "shouldLowercaseEveryShippedReasonCode";
    private static final String OVERSIZED_ROLE_CODE = "shouldSubstituteAnOversizedReasonCode";
    private static final String ILLEGAL_CHAR_ROW = "shouldSubstituteAReasonCodeWithAnIllegalCharacter";
    private static final String OVERSIZED_POLICY_ID_ROW = "shouldDropAnOversizedPolicyId";
    private static final String CONTROL_CHAR_POLICY_VERSION_ROW = "shouldDropAPolicyVersionWithAControlCharacter";
    private static final String NEVER_THROWS_ROW = "shouldNeverThrowRegardlessOfDecisionShape";

    private static Stream<String> rows() {
        return Stream.of(
                SHIPPED_ROLE_CODE,
                OVERSIZED_ROLE_CODE,
                ILLEGAL_CHAR_ROW,
                OVERSIZED_POLICY_ID_ROW,
                CONTROL_CHAR_POLICY_VERSION_ROW,
                NEVER_THROWS_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("summarize never lets an AuthorizationDecision's shape reach McpAuthorizationSummary unbounded")
    void shouldEnforceSummarizeBounds(String row) {
        switch (row) {
            case SHIPPED_ROLE_CODE -> shouldLowercaseEveryShippedReasonCode();
            case OVERSIZED_ROLE_CODE -> shouldSubstituteAnOversizedReasonCode();
            case ILLEGAL_CHAR_ROW -> shouldSubstituteAReasonCodeWithAnIllegalCharacter();
            case OVERSIZED_POLICY_ID_ROW -> shouldDropAnOversizedPolicyId();
            case CONTROL_CHAR_POLICY_VERSION_ROW -> shouldDropAPolicyVersionWithAControlCharacter();
            case NEVER_THROWS_ROW -> shouldNeverThrowRegardlessOfDecisionShape();
            default -> throw new IllegalArgumentException("unknown row: " + row);
        }
    }

    // --- Every shipped reason code round-trips, lowercased ---

    private void shouldLowercaseEveryShippedReasonCode() {
        for (String shipped : List.of(
                AuthzReasonCodes.PERMITTED,
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                AuthzReasonCodes.ROLE_MISSING,
                AuthzReasonCodes.DENY_ALL,
                AuthzReasonCodes.INTERNAL_AUTHZ_ERROR)) {
            McpAuthorizationSummary summary = McpPolicyEnforcer.summarize(AuthorizationDecision.deny(shipped));
            assertThat(summary.reasonCode())
                    .as("DECISIVE: %s must round-trip as its own lower-cased value, not the generic fallback", shipped)
                    .isEqualTo(shipped.toLowerCase(Locale.ROOT));
        }
    }

    // --- A hypothetical rogue Authorizer's oversized reason code degrades safely ---

    private void shouldSubstituteAnOversizedReasonCode() {
        AuthorizationDecision decision = AuthorizationDecision.deny("a".repeat(65));

        McpAuthorizationSummary summary = McpPolicyEnforcer.summarize(decision);

        assertThat(summary.reasonCode())
                .as("DECISIVE: an oversized reason code from a non-shipped Authorizer must never reach "
                        + "McpAuthorizationSummary's constructor unbounded — it is dropped to the bounded fallback")
                .isEqualTo("unmapped");
    }

    // --- An illegal character degrades safely ---

    private void shouldSubstituteAReasonCodeWithAnIllegalCharacter() {
        AuthorizationDecision decision = AuthorizationDecision.deny("policy denied!");

        McpAuthorizationSummary summary = McpPolicyEnforcer.summarize(decision);

        assertThat(summary.reasonCode()).isEqualTo("unmapped");
    }

    // --- An oversized policyId is dropped, not truncated or propagated ---

    private void shouldDropAnOversizedPolicyId() {
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.of("p".repeat(257)), Optional.empty(), Map.of());

        McpAuthorizationSummary summary = McpPolicyEnforcer.summarize(decision);

        assertThat(summary.policyId())
                .as("DECISIVE: an oversized policyId must never reach the summary — construction would "
                        + "otherwise throw IllegalArgumentException for a real decision")
                .isNull();
    }

    // --- A policyVersion with a control character is dropped ---

    private void shouldDropAPolicyVersionWithAControlCharacter() {
        AuthorizationDecision decision =
                new AuthorizationDecision(true, "PERMITTED", Optional.empty(), Optional.of("v1"), Map.of());

        McpAuthorizationSummary summary = McpPolicyEnforcer.summarize(decision);

        assertThat(summary.policyVersion()).isNull();
    }

    // --- The seam itself never throws, for any combination above ---

    private void shouldNeverThrowRegardlessOfDecisionShape() {
        AuthorizationDecision worstCase = new AuthorizationDecision(
                false, "Not-A-Valid_Code!" + "x".repeat(80), Optional.of(""), Optional.of("v"), Map.of());

        assertThatCode(() -> McpPolicyEnforcer.summarize(worstCase))
                .as("DECISIVE: summarize must never throw, regardless of how malformed the decision is")
                .doesNotThrowAnyException();
    }
}
