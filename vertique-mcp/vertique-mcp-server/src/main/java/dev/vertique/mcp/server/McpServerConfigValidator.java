// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.security.authz.Authorizer;
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
        requireRange(config.outputMaxBytes(), 1_024, 16_777_216, "mcp.outputMaxBytes");
        requireTokenBudget(config.ingressMaxTokens(), "mcp.ingressMaxTokens");
        requireTokenBudget(config.outputMaxTokens(), "mcp.outputMaxTokens");
        requireRange(config.toolsPageSize(), 1, 500, "mcp.toolsPageSize");
        requireRange(config.toolsTtlMs(), 0, 3_600_000, "mcp.toolsTtlMs");
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
     * Validates the selected optional-authentication capability exactly as {@link #validate(McpServerConfig,
     * Set)} does, and additionally enforces the registry-visibility rule (§4.5): when the server is
     * enabled with no configured scheme, every tool the registry publishes must be reachable without
     * authentication — {@link McpAccessMode#PERMIT_ALL} (public) or {@link McpAccessMode#DENY_ALL}
     * (unreachable) — because an anonymous-only endpoint can never satisfy a
     * {@link McpAccessMode#RESTRICTED} tool's requirement. This is the registry-visibility seam the
     * composition validator exposes to callers that own a built {@link McpToolRegistry}; it discharges
     * the T004 red-slice deferral recorded as {@code shouldAllowUnconfiguredPublicOrDenyAllRegistryAndRejectUnconfiguredRestrictedRegistry}.
     *
     * @param config the bounded configuration to validate
     * @param routeAuthHandlers every registered optional-authentication-capable handler
     * @param registry the immutable tool registry built before this validation runs
     * @throws ConfigurationException if the scheme selection is invalid, or if no scheme is configured
     *     while the registry publishes a restricted tool
     */
    void validate(McpServerConfig config, Set<RouteAuthHandler> routeAuthHandlers, McpToolRegistry registry) {
        validate(config, routeAuthHandlers);
        require(registry != null, "mcp.tools");
        if (!config.enabled() || config.authenticationScheme() != null) {
            // A disabled endpoint is inert, and a configured scheme was already fully validated above
            // (including its optional-handler capability); registry visibility is only decisive for the
            // canonical-anonymous, no-scheme case.
            return;
        }
        boolean everyToolReachableWithoutAuthentication = registry.descriptorsByName().values().stream()
                .map(McpToolDescriptor::access)
                .allMatch(access -> access.mode() != McpAccessMode.RESTRICTED);
        require(everyToolReachableWithoutAuthentication, "mcp.authenticationScheme");
    }

    /**
     * Validates exactly as {@link #validate(McpServerConfig, Set, McpToolRegistry)} does, and
     * additionally refuses to start an enabled MCP mount when the shared HTTP layer arms no liveness
     * timeout at all.
     *
     * <p>MCP arms no whole-request deadline of its own (the T007 amendment removed {@code
     * mcp.request.timeoutMs}): the only thing that can ever reclaim a hanging {@code
     * McpRequestInterceptor}, a hanging {@code McpToolInterceptor}, a hanging tool handler, or a
     * client that stops reading mid-response is the shared {@link HttpConfig} idle/read timeout
     * behavior. Both {@link HttpConfig#idleTimeoutSeconds()} and {@link
     * HttpConfig#readIdleTimeoutSeconds()} default to {@code 0} ("disabled"), so a default deployment
     * provides no deadline at all — an unauthenticated caller can strand a {@code @PermitAll} tool's
     * connection, coordinator, and every open observation indefinitely. This is the one place that
     * closes that gap: an enabled mount refuses to start unless at least one of the two is armed.
     * {@link HttpConfig#writeIdleTimeoutSeconds()} alone does not qualify (repair task R33 defect 4):
     * it fires only while a write is actually in flight, so it cannot reclaim a connection that opens
     * and then never reads or writes again.
     *
     * @param config the bounded configuration to validate
     * @param routeAuthHandlers every registered optional-authentication-capable handler
     * @param registry the immutable tool registry built before this validation runs
     * @param httpConfig the shared HTTP configuration whose liveness timeouts are checked
     * @throws ConfigurationException if any check {@link #validate(McpServerConfig, Set,
     *     McpToolRegistry)} performs fails, or if the mount is enabled and both {@link
     *     HttpConfig#idleTimeoutSeconds()} and {@link HttpConfig#readIdleTimeoutSeconds()} are zero
     */
    void validate(
            McpServerConfig config,
            Set<RouteAuthHandler> routeAuthHandlers,
            McpToolRegistry registry,
            HttpConfig httpConfig) {
        validate(config, routeAuthHandlers, registry);
        require(httpConfig != null, "http");
        if (!config.enabled()) {
            return;
        }
        boolean anyLivenessTimeoutArmed =
                httpConfig.idleTimeoutSeconds() > 0 || httpConfig.readIdleTimeoutSeconds() > 0;
        require(
                anyLivenessTimeoutArmed,
                "http.idleTimeoutSeconds or http.readIdleTimeoutSeconds must be greater than zero while "
                        + "mcp is enabled, so a hanging interceptor, tool handler, or non-reading client "
                        + "cannot strand a connection indefinitely (http.writeIdleTimeoutSeconds alone does "
                        + "not satisfy this gate: a write timer cannot reclaim a hung handler that never "
                        + "writes)");
    }

    /**
     * Validates exactly as {@link #validate(McpServerConfig, Set, McpToolRegistry)} does, and
     * additionally refuses to start an enabled MCP mount when the registry publishes an {@code
     * @RequiresAction} tool but no core {@link Authorizer} is installed (issue #421).
     *
     * <p>Without this gate, {@code SecurityPolicyEnforcer.decide} NPEs on its {@code null} authorizer
     * field at the first request for such a tool, and the surrounding fail-closed catch converts that
     * into a deny — safe, but accidental: the tool is unreachable for a reason no operator ever sees
     * until traffic hits it. REST already rejects the equivalent shape at startup ({@code
     * JaxRsRouteRegistrar#resolveRequiredAction}'s "No Authorizer" case); this is the MCP mount-time
     * equivalent, naming every affected tool in one bounded configuration error rather than deferring
     * to a per-request NPE.
     *
     * @param config the bounded configuration to validate
     * @param routeAuthHandlers every registered optional-authentication-capable handler
     * @param registry the immutable tool registry built before this validation runs
     * @param authorizer the optional core {@link Authorizer}; empty when the authorization engine is
     *     not installed
     * @throws ConfigurationException if any check {@link #validate(McpServerConfig, Set,
     *     McpToolRegistry)} performs fails, or if the mount is enabled, {@code authorizer} is absent,
     *     and the registry publishes at least one {@code @RequiresAction} tool
     */
    void validate(
            McpServerConfig config,
            Set<RouteAuthHandler> routeAuthHandlers,
            McpToolRegistry registry,
            Optional<Authorizer> authorizer) {
        validate(config, routeAuthHandlers, registry);
        requireAuthorizerForActionTools(config, registry, authorizer);
    }

    /**
     * Validates exactly as {@link #validate(McpServerConfig, Set, McpToolRegistry, HttpConfig)} does,
     * and additionally applies the no-authorizer-for-{@code @RequiresAction} gate documented on
     * {@link #validate(McpServerConfig, Set, McpToolRegistry, Optional)} (issue #421). This is the
     * overload {@link McpRouterMount}'s constructor — the one real production mount point — calls, so
     * every check this validator performs runs there together.
     *
     * @param config the bounded configuration to validate
     * @param routeAuthHandlers every registered optional-authentication-capable handler
     * @param registry the immutable tool registry built before this validation runs
     * @param httpConfig the shared HTTP configuration whose liveness timeouts are checked
     * @param authorizer the optional core {@link Authorizer}; empty when the authorization engine is
     *     not installed
     * @throws ConfigurationException if any check {@link #validate(McpServerConfig, Set,
     *     McpToolRegistry, HttpConfig)} or {@link #validate(McpServerConfig, Set, McpToolRegistry,
     *     Optional)} performs fails
     */
    void validate(
            McpServerConfig config,
            Set<RouteAuthHandler> routeAuthHandlers,
            McpToolRegistry registry,
            HttpConfig httpConfig,
            Optional<Authorizer> authorizer) {
        validate(config, routeAuthHandlers, registry, httpConfig);
        requireAuthorizerForActionTools(config, registry, authorizer);
    }

    /**
     * Rejects, with one bounded configuration error naming every affected tool, an enabled mount whose
     * registry publishes at least one {@code @RequiresAction} tool while {@code authorizer} is absent
     * (issue #421). A disabled mount is inert and never checked; an installed authorizer always passes
     * regardless of the registry's contents.
     */
    private static void requireAuthorizerForActionTools(
            McpServerConfig config, McpToolRegistry registry, Optional<Authorizer> authorizer) {
        require(authorizer != null, "mcp.tools");
        if (!config.enabled() || authorizer.isPresent()) {
            return;
        }
        List<String> unenforceableActionTools = registry.descriptorsByName().values().stream()
                .filter(descriptor -> descriptor.access().action() != null)
                .map(McpToolDescriptor::name)
                .sorted()
                .toList();
        require(
                unenforceableActionTools.isEmpty(),
                "mcp.tools " + unenforceableActionTools + " declare @RequiresAction but no Authorizer is "
                        + "installed to enforce it. Include SecurityAuthzModule in your Dagger component to "
                        + "enable the authorization engine.");
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

    private static void requireTokenBudget(int value, String key) {
        if (value < 1_024 || value > 262_144) {
            throw new ConfigurationException(
                    "Invalid configuration: " + key + " must be between 1024 and 262144 inclusive");
        }
    }

    private static void require(boolean condition, String key) {
        if (!condition) {
            throw new ConfigurationException("Invalid configuration: " + key);
        }
    }
}
