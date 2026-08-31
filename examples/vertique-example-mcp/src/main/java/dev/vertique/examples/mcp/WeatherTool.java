// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.mcp.annotation.McpTool;
import dev.vertique.mcp.annotation.McpToolParam;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.validation.Valid;

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
}
