// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the additive shape of the trailing {@code origin} component: every factory and the
 * constructor that predate it still link and report no origin, and the overloads that accept an
 * origin carry it through unchanged.
 */
class McpRequestTerminalEventOriginCompatibilityTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(1);
    private static final String TOOL = "weather.current";
    private static final RequestOrigin ORIGIN = new RequestOrigin(
            "203.0.113.9", 4433, List.of(), 0, false, "203.0.113.9", "https", "mcp.test", Optional.empty());

    @Test
    void shouldReportNoOriginForEveryFactoryThatPredatesTheComponent() {
        assertThat(McpRequestTerminalEvent.success(
                                STARTED_AT, TERMINAL_AT, McpMethod.TOOLS_CALL, TOOL, 200, null, null, null, null)
                        .origin())
                .isNull();
        assertThat(McpRequestTerminalEvent.toolError(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.HANDLER,
                                200,
                                null,
                                null,
                                null,
                                null)
                        .origin())
                .isNull();
        assertThat(McpRequestTerminalEvent.rejected(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.AUTHORIZATION,
                                403,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .origin())
                .isNull();
        assertThat(McpRequestTerminalEvent.failed(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.INTERNAL,
                                500,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .origin())
                .isNull();
        assertThat(McpRequestTerminalEvent.cancelled(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.TRANSPORT,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .origin())
                .isNull();
    }

    @Test
    void shouldReportNoOriginForTheConstructorThatPredatesTheComponent() {
        McpRequestTerminalEvent event = new McpRequestTerminalEvent(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                TOOL,
                McpOutcome.SUCCESS,
                McpErrorType.NONE,
                McpResultType.COMPLETE,
                200,
                null,
                null,
                null,
                null,
                null);

        assertThat(event.origin()).isNull();
    }

    @Test
    void shouldCarryTheCapturedOriginThroughEveryOverloadThatAcceptsOne() {
        assertThat(McpRequestTerminalEvent.success(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                200,
                                null,
                                null,
                                null,
                                null,
                                ORIGIN)
                        .origin())
                .isSameAs(ORIGIN);
        assertThat(McpRequestTerminalEvent.toolError(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.HANDLER,
                                200,
                                null,
                                null,
                                null,
                                null,
                                ORIGIN)
                        .origin())
                .isSameAs(ORIGIN);
        assertThat(McpRequestTerminalEvent.rejected(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.RATE_LIMIT,
                                429,
                                null,
                                null,
                                null,
                                null,
                                null,
                                ORIGIN)
                        .origin())
                .isSameAs(ORIGIN);
        assertThat(McpRequestTerminalEvent.failed(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.RATE_LIMIT,
                                503,
                                null,
                                null,
                                null,
                                null,
                                null,
                                ORIGIN)
                        .origin())
                .isSameAs(ORIGIN);
        assertThat(McpRequestTerminalEvent.cancelled(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                TOOL,
                                McpErrorType.TRANSPORT,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                ORIGIN)
                        .origin())
                .isSameAs(ORIGIN);
    }
}
