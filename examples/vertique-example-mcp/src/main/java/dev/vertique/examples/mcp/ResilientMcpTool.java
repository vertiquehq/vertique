// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dev.vertique.mcp.annotation.McpTool;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Timeout;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;

/** MCP example bean whose generated proxy activates the common AOP resilience pipeline. */
public class ResilientMcpTool {

    @Inject
    public ResilientMcpTool() {}

    /** A never-completing handler used to prove timeout mapping and the absence of retries. */
    @McpTool(
            name = "weather.resilient-timeout",
            description = "A timeout-only resilient MCP tool.",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false)
    @Resilient
    @Timeout(value = 100, unit = TimeUnit.MILLISECONDS)
    public Future<String> timeout() {
        McpResilienceProbe probe = McpResilienceProbe.shared();
        probe.recordTimeoutInvocation();
        return probe.timeoutFuture();
    }

    /** A pending handler used to prove cooperative cancellation and late-result fencing. */
    @McpTool(
            name = "weather.resilient-disconnect",
            description = "A disconnect-sensitive resilient MCP tool.",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false)
    @Resilient
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public Future<String> disconnect(McpCancellationSignal cancellation) {
        McpResilienceProbe probe = McpResilienceProbe.shared();
        probe.recordDisconnectInvocation();
        cancellation.cancelled().onSuccess(ignored -> probe.observeCancellation());
        return probe.disconnectFuture();
    }
}
