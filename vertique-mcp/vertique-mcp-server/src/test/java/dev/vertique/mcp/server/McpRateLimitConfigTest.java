// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** T002 TP-001 — config-only policy precedence for generated MCP tool names. */
class McpRateLimitConfigTest {

    private static final String DEFAULT_POLICY = "mcp-default";
    private static final String PER_TOOL_POLICY = "mcp-write";

    @ParameterizedTest(name = "{0}")
    @MethodSource("policyResolutionCases")
    @DisplayName("shouldResolveEffectivePolicyByPrecedenceNeverReadingTheAnnotation")
    void shouldResolveEffectivePolicyByPrecedenceNeverReadingTheAnnotation(
            String ignoredCaseName, McpRateLimitConfig config, String toolName, Optional<String> expectedPolicy) {
        assertThat(McpToolAdmission.resolvePolicy(config, toolName)).isEqualTo(expectedPolicy);
    }

    @Test
    void shouldFallBackToDefaultWhenThePerToolEntryIsRemoved() {
        McpRateLimitConfig config = new McpRateLimitConfig(
                DEFAULT_POLICY,
                RateLimitSubject.EFFECTIVE_PRINCIPAL,
                AnonymousRateLimitPolicy.SHARED_BUCKET,
                List.of());

        assertThat(McpToolAdmission.resolvePolicy(config, "orders.create")).contains(DEFAULT_POLICY);
    }

    private static Stream<Arguments> policyResolutionCases() {
        McpRateLimitConfig configured = new McpRateLimitConfig(
                DEFAULT_POLICY,
                RateLimitSubject.EFFECTIVE_PRINCIPAL,
                AnonymousRateLimitPolicy.SHARED_BUCKET,
                List.of(tool("orders.create", PER_TOOL_POLICY)));
        return Stream.of(
                Arguments.of(
                        "shouldPreferPerToolPolicyOverDefault",
                        configured,
                        "orders.create",
                        Optional.of(PER_TOOL_POLICY)),
                Arguments.of(
                        "shouldFallBackToDefaultPolicyWhenNoToolEntryMatches",
                        configured,
                        "orders.search",
                        Optional.of(DEFAULT_POLICY)),
                Arguments.of(
                        "shouldResolveNoAdmissionWhenNeitherIsConfigured",
                        McpRateLimitConfig.defaults(),
                        "orders.search",
                        Optional.empty()),
                Arguments.of(
                        "shouldIgnoreRateLimitedAnnotationEntirely",
                        McpRateLimitConfig.defaults(),
                        "annotated.tool",
                        Optional.empty()));
    }

    private static McpToolRateLimitConfig tool(String name, String policy) {
        return new McpToolRateLimitConfig(name, policy, null, null, 1L);
    }
}
