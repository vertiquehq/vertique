// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.security.RouteAuthHandler;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Validates the startup-safe, bounded configuration accepted by the MCP server. */
final class McpServerConfigValidator {
    private static final int MAX_INSTRUCTIONS_CHARS = 16_384;

    /** Validates {@code config}, throwing one stable key-named configuration error on failure. */
    void validate(McpServerConfig config) {
        require(config != null, "mcp");
        validateMountPath(config.mountPath());
        if (config.enabled()) {
            requireNonBlank(config.serverName(), "mcp.serverName");
            requireNonBlank(config.serverVersion(), "mcp.serverVersion");
        }
        require(
                config.instructions() == null || config.instructions().length() <= MAX_INSTRUCTIONS_CHARS,
                "mcp.instructions");
        require(
                config.authenticationScheme() == null
                        || !config.authenticationScheme().isBlank(),
                "mcp.authenticationScheme");
        requireRange(config.outputMaxBytes(), 1_024, 16_777_216, "mcp.output.maxBytes");
        requireRange(config.toolsPageSize(), 1, 500, "mcp.tools.pageSize");
        requireRange(config.toolsTtlMs(), 0, 3_600_000, "mcp.tools.ttlMs");
        validateOrigins(config.allowedOrigins());
    }

    /** Validates the selected optional-authentication capability before the MCP mount is created. */
    void validate(McpServerConfig config, Set<RouteAuthHandler> routeAuthHandlers) {
        validate(config);
        require(routeAuthHandlers != null, "mcp.authenticationScheme");
        if (!config.enabled() || config.authenticationScheme() == null) {
            return;
        }
        List<RouteAuthHandler> matches = routeAuthHandlers.stream()
                .filter(handler -> config.authenticationScheme().equals(handler.schemeName()))
                .toList();
        require(matches.size() == 1, "mcp.authenticationScheme");
        Optional<Handler<RoutingContext>> optionalHandler = matches.getFirst().createOptionalHandler();
        require(optionalHandler != null && optionalHandler.isPresent(), "mcp.authenticationScheme");
    }

    /**
     * Validates that {@code mountPath} is one absolute, normalized, literal Router mount ending
     * {@code /*}: a leading {@code /}, no path parameters ({@code :} segments), no wildcard other
     * than the terminal {@code /*}, no query or fragment, no duplicate separators, no {@code .} or
     * {@code ..} segments, and no whitespace or control characters. The bare {@code /*} root mount
     * satisfies every one of those constraints and is therefore accepted.
     */
    private static void validateMountPath(String mountPath) {
        require(mountPath != null, "mcp.mountPath");
        require(mountPath.startsWith("/"), "mcp.mountPath");
        require(mountPath.endsWith("/*"), "mcp.mountPath");
        require(mountPath.indexOf('*') == mountPath.length() - 1, "mcp.mountPath");
        require(mountPath.indexOf(':') < 0, "mcp.mountPath");
        require(mountPath.indexOf('?') < 0 && mountPath.indexOf('#') < 0, "mcp.mountPath");
        require(!mountPath.contains("//"), "mcp.mountPath");
        require(
                mountPath
                        .chars()
                        .noneMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character)),
                "mcp.mountPath");
        String literalPrefix = mountPath.substring(0, mountPath.length() - 1);
        require(
                Stream.of(literalPrefix.split("/", -1))
                        .noneMatch(segment -> ".".equals(segment) || "..".equals(segment)),
                "mcp.mountPath");
    }

    private static void validateOrigins(Set<String> origins) {
        require(origins != null, "mcp.allowedOrigins");
        require(origins.stream().allMatch(origin -> origin != null && !origin.isBlank()), "mcp.allowedOrigins");
    }

    private static void requireNonBlank(String value, String key) {
        require(value != null && !value.isBlank(), key);
    }

    private static void requireRange(long value, long minimum, long maximum, String key) {
        require(value >= minimum && value <= maximum, key);
    }

    private static void require(boolean condition, String key) {
        if (!condition) {
            throw new ConfigurationException("Invalid configuration: " + key);
        }
    }
}
