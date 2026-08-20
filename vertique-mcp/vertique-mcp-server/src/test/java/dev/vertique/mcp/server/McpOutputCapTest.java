// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.vertique.mcp.server.McpRequestDispatcher.CappedOutputStream;
import dev.vertique.mcp.server.McpRequestDispatcher.OutputCapExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the response write is bounded at {@code mcp.output.maxBytes} <em>as bytes are produced</em>:
 * the cap aborts serialization the instant the running byte count would exceed it, so the full
 * over-cap byte array is never materialized (the T003-review output-cap obligation on T004).
 */
class McpOutputCapTest {

    @Test
    @DisplayName("a write that stays at or below the cap is accumulated in full")
    void shouldAccumulateUpToTheCap() {
        CappedOutputStream out = new CappedOutputStream(4);
        out.write(new byte[] {1, 2, 3}, 0, 3);
        out.write(4);

        assertThat(out.toByteArray()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("a single-byte write past the cap aborts before the byte is buffered")
    void shouldAbortOnSingleByteOverCap() {
        CappedOutputStream out = new CappedOutputStream(2);
        out.write(1);
        out.write(2);

        assertThatExceptionOfType(OutputCapExceededException.class).isThrownBy(() -> out.write(3));
        // The over-cap byte was never buffered: what was accumulated is still exactly the cap.
        assertThat(out.toByteArray()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a bulk write that would exceed the cap aborts before any of its bytes are buffered")
    void shouldAbortBulkWriteBeforeMaterializingOverCap() {
        CappedOutputStream out = new CappedOutputStream(3);
        out.write(new byte[] {1, 2}, 0, 2);

        assertThatExceptionOfType(OutputCapExceededException.class)
                .isThrownBy(() -> out.write(new byte[] {3, 4, 5}, 0, 3));
        // The whole over-cap chunk is rejected atomically — none of 3,4,5 is materialized.
        assertThat(out.toByteArray()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("the failure message never carries a payload or serialized value")
    void shouldNotLeakPayloadInFailure() {
        OutputCapExceededException failure = new OutputCapExceededException();
        assertThat(failure.getMessage()).isEqualTo("MCP response exceeded mcp.output.maxBytes");
    }
}
