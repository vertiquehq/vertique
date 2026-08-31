// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.mcp.annotation.McpTool;
import dev.vertique.mcp.annotation.McpToolParam;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpContent;
import dev.vertique.mcp.tool.McpToolResult;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.List;

/** Dependency-injected tool published by the MCP annotation processor. */
public final class WeatherTool {
    @Inject
    public WeatherTool() {}

    @McpTool(
            name = "weather.current",
            description = "Returns the current weather for the requested location.",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false)
    @JsonProfile(ExampleJsonProfile.ID)
    public Future<WeatherResult> current(
            @Valid @McpToolParam(name = "request", description = "The location to look up.") WeatherRequest request) {
        return Future.succeededFuture(new WeatherResult(request.locationName(), 18, "clear"));
    }

    @McpTool(
            name = "weather.report",
            description = "Returns a report using standard MCP content blocks.",
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false)
    public Future<McpToolResult<Void>> report(
            @Valid @McpToolParam(name = "request", description = "The location to report on.") WeatherRequest request,
            McpCancellationSignal cancellation) {
        return cancellation
                .progressReporter()
                .report(1, 1.0, "Preparing the weather report")
                .map(ignored -> McpToolResult.content(List.of(
                        new McpContent.Text("Weather report for " + request.locationName()),
                        new McpContent.Image("aGVsbG8=", "image/png"),
                        new McpContent.Audio("aGVsbG8=", "audio/wav"),
                        new McpContent.ResourceLink("https://example.test/weather", "weather-source"),
                        new McpContent.EmbeddedResource(
                                new McpContent.TextResource("urn:vertique:weather", "text/plain", "clear")))));
    }
}
