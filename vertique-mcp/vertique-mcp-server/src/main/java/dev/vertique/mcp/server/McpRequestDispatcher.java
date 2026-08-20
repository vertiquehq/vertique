// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;

/** Dispatches the bounded T001 discovery endpoint without exposing tool registration or invocation. */
final class McpRequestDispatcher {
    private static final String DISCOVER_METHOD = "server/discover";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /** The official schema treats an absent {@code resultType} as this completed-result value. */
    private static final String COMPLETE_RESULT_TYPE = "complete";

    /** Discovery results are never shared across authorization contexts. */
    private static final String PRIVATE_CACHE_SCOPE = "private";

    private static final String SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo";
    private static final String COMPLETION_COORDINATOR_KEY =
            McpRequestDispatcher.class.getName() + ".completionCoordinator";
    private final McpServerConfig config;
    private final SecurityRuntime securityRuntime;
    private final Set<McpRequestLifecycleObserver> lifecycleObservers;
    private final Set<McpRequestCompletedListener> completedListeners;

    @Inject
    McpRequestDispatcher(
            McpServerConfig config,
            SecurityRuntime securityRuntime,
            Set<McpRequestLifecycleObserver> lifecycleObservers,
            Set<McpRequestCompletedListener> completedListeners) {
        this.config = config;
        this.securityRuntime = securityRuntime;
        this.lifecycleObservers = Set.copyOf(lifecycleObservers);
        this.completedListeners = Set.copyOf(completedListeners);
    }

    /** Opens neutral lifecycle observation before authentication and identity establishment. */
    void begin(RoutingContext context) {
        Instant startedAt = Instant.now();
        context.put(
                COMPLETION_COORDINATOR_KEY,
                new McpCompletionCoordinator(
                        context.vertx().getOrCreateContext(), lifecycleObservers, completedListeners, startedAt));
        context.put(McpRequestDispatcher.class.getName() + ".startedAt", startedAt);
        if (context.request().method() != HttpMethod.POST) {
            // Terminal before identity establishment: no security facts exist yet.
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 405, null);
            return;
        }
        context.next();
    }

    /**
     * Handles one admitted MCP HTTP request.
     *
     * <p>Runs after identity establishment, so every terminal event it creates carries the
     * established {@link SecurityContextSnapshot} — including the canonical anonymous one.
     */
    void dispatch(RoutingContext context) {
        SecurityContextSnapshot security = establishedSecurity();
        JsonObject request;
        try {
            request = context.body().asJsonObject();
        } catch (RuntimeException exception) {
            reject(context, McpMethod.OTHER, McpErrorType.PROTOCOL, 400, security);
            return;
        }
        if (request == null) {
            // An absent, empty, or literal-null body decodes to no envelope at all: a protocol
            // failure, not an internal one.
            reject(context, McpMethod.OTHER, McpErrorType.PROTOCOL, 400, security);
            return;
        }
        if (!DISCOVER_METHOD.equals(request.getString("method"))) {
            reject(context, McpMethod.OTHER, McpErrorType.PROTOCOL, 404, security);
            return;
        }
        JsonObject serverInfo =
                new JsonObject().put("name", config.serverName()).put("version", config.serverVersion());
        // The official DiscoverResult requires resultType, supportedVersions, capabilities, ttlMs,
        // and cacheScope; the configured server identity is stamped into result _meta.
        JsonObject result = new JsonObject()
                .put("resultType", COMPLETE_RESULT_TYPE)
                .put("supportedVersions", new JsonArray().add(PROTOCOL_VERSION))
                .put("capabilities", new JsonObject())
                .put("ttlMs", config.toolsTtlMs())
                .put("cacheScope", PRIVATE_CACHE_SCOPE)
                .put("_meta", new JsonObject().put(SERVER_INFO_META_KEY, serverInfo));
        JsonObject response =
                new JsonObject().put("jsonrpc", "2.0").put("result", result).put("id", request.getValue("id"));
        context.response().putHeader("content-type", "application/json");
        write(
                context,
                200,
                response.encode(),
                McpRequestTerminalEvent.success(
                        startedAt(context),
                        Instant.now(),
                        McpMethod.SERVER_DISCOVER,
                        McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                        200,
                        null,
                        security,
                        null));
    }

    static void completeAuthenticationRejection(RoutingContext context) {
        int status = context.statusCode();
        // An authentication rejection terminates before identity establishment, and the event
        // contract forbids it from carrying security facts.
        reject(context, McpMethod.OTHER, McpErrorType.AUTHENTICATION, status >= 400 ? status : 401, null);
    }

    /** Completes failures from optional authentication and identity establishment without leakage. */
    void handleFailure(RoutingContext context) {
        if (context.response().ended()) {
            return;
        }
        int status = context.statusCode();
        if (status < 400) {
            status = 500;
        }
        if (status == 401 || status == 403) {
            reject(context, McpMethod.OTHER, McpErrorType.AUTHENTICATION, status, null);
            return;
        }
        // An internal failure can occur on either side of identity establishment; the snapshot is
        // null exactly when no context was established before the failure.
        reject(context, McpMethod.OTHER, McpErrorType.INTERNAL, status, establishedSecurity());
    }

    /**
     * Snapshots the security context identity establishment bound for this request.
     *
     * @return the established snapshot, or {@code null} when no context is bound — i.e. the request
     *         terminated before identity establishment completed
     */
    private @Nullable SecurityContextSnapshot establishedSecurity() {
        SecurityContext current = securityRuntime.current();
        return current == null ? null : SecurityContextSnapshot.from(current);
    }

    private static void reject(
            RoutingContext context,
            McpMethod method,
            McpErrorType errorType,
            int status,
            @Nullable SecurityContextSnapshot security) {
        write(
                context,
                status,
                null,
                McpRequestTerminalEvent.rejected(
                        startedAt(context),
                        Instant.now(),
                        method,
                        McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                        errorType,
                        status,
                        null,
                        null,
                        security,
                        null));
    }

    private static Instant startedAt(RoutingContext context) {
        Instant startedAt = context.get(McpRequestDispatcher.class.getName() + ".startedAt");
        return startedAt == null ? Instant.now() : startedAt;
    }

    private static void write(RoutingContext context, int status, String body, McpRequestTerminalEvent terminal) {
        context.response().setStatusCode(status);
        if (body == null) {
            context.response().end().onComplete(result -> complete(context, terminal, result.succeeded()));
            return;
        }
        // end(body) sets Content-Length, so the response is framed by length instead of relying on
        // connection-close framing the way a separate write() + end() pair does.
        context.response()
                .end(Buffer.buffer(body))
                .onComplete(result -> complete(context, terminal, result.succeeded()));
    }

    private static void complete(RoutingContext context, McpRequestTerminalEvent terminal, boolean written) {
        McpCompletionCoordinator coordinator = context.get(COMPLETION_COORDINATOR_KEY);
        if (coordinator != null) {
            coordinator.complete(
                    terminal,
                    written ? McpTransportOutcome.WRITTEN : McpTransportOutcome.WRITE_FAILED,
                    written,
                    Instant.now());
        }
    }
}
