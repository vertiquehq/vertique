// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.ComposeValidator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Fails composition when the configured MCP JSON profile is not registered. */
@Singleton
final class McpJsonProfileDefaultValidator implements ComposeValidator {
    /** Resolves the configured profile even when MCP is disabled or otherwise inert. */
    @Inject
    McpJsonProfileDefaultValidator(McpServerConfig config, JsonMapperProfileRegistry registry) {
        registry.validateConfigured(config.jsonProfile());
    }
}
