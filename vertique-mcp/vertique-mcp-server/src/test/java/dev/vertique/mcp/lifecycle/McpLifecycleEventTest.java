// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Pins the legal terminal and completion state combinations used by the MCP server. */
class McpLifecycleEventTest {
    @Test
    void shouldRejectEveryImpossibleTerminalAndCompletionCombination() {
        Instant startedAt = Instant.parse("2026-08-20T00:00:00Z");
        McpRequestTerminalEvent success = McpRequestTerminalEvent.success(
                startedAt,
                startedAt.plusMillis(1),
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                null,
                null);

        assertThat(McpRequestCompletedEvent.written(success, startedAt.plusMillis(2))
                        .responseCommitted())
                .isTrue();
        assertThatThrownBy(() -> new McpRequestTerminalEvent(
                        startedAt,
                        startedAt.plusMillis(1),
                        McpMethod.SERVER_DISCOVER,
                        McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                        McpOutcome.SUCCESS,
                        McpErrorType.HANDLER,
                        McpResultType.COMPLETE,
                        200,
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpRequestCompletedEvent.written(success, startedAt.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRequestCompletedEvent(
                        success, startedAt.plusMillis(2), McpTransportOutcome.WRITTEN, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
