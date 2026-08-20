// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Set;

/**
 * Dispatches the bounded discovery endpoint over the hardened stateless HTTP contract (§4.7).
 *
 * <p>The dispatcher wires the framework-owned strict codec onto the live request path, enforces the
 * method/origin admission checks, registers the disconnect/reset/timeout settlement seam, and bounds
 * the response write at {@code mcp.output.maxBytes}. Tool registration and invocation, and the
 * tool-level {@code -32602} classification, are owned by later slices and are deliberately absent.
 */
final class McpRequestDispatcher {
    private static final String DISCOVER_METHOD = "server/discover";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /** The official schema treats an absent {@code resultType} as this completed-result value. */
    private static final String COMPLETE_RESULT_TYPE = "complete";

    /** Discovery results are never shared across authorization contexts. */
    private static final String PRIVATE_CACHE_SCOPE = "private";

    private static final String SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;

    private static final String KEY_PREFIX = McpRequestDispatcher.class.getName();
    private static final String COMPLETION_COORDINATOR_KEY = KEY_PREFIX + ".completionCoordinator";
    private static final String STARTED_AT_KEY = KEY_PREFIX + ".startedAt";
    private static final String TIMER_ID_KEY = KEY_PREFIX + ".timerId";

    /**
     * Compact, insertion-order-preserving success encoder, canonicalized identically to the codec's
     * encoder ({@code WRITE_BIGDECIMAL_AS_PLAIN}). The response is streamed through a byte-bounded
     * {@link CappedOutputStream} so serialization stops at {@code mcp.output.maxBytes} as bytes are
     * produced, rather than materializing a full buffer that the cap then rejects.
     */
    private static final ObjectMapper OUTPUT_ENCODER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final McpServerConfig config;
    private final SecurityRuntime securityRuntime;
    private final Set<McpRequestLifecycleObserver> lifecycleObservers;
    private final Set<McpRequestCompletedListener> completedListeners;
    private final McpProtocolCodec codec;
    private final McpStrictJsonReader reader;

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
        this.codec = new McpProtocolCodec(config);
        this.reader = new McpStrictJsonReader(config);
    }

    /**
     * Opens neutral lifecycle observation and applies the cheap HTTP admission checks (§4.7 stage 1)
     * before authentication and identity establishment.
     *
     * <p>Only POST is accepted; GET/DELETE and any other method are HTTP 405. A present {@code Origin}
     * outside a non-empty {@code mcp.allowedOrigins} allowlist is HTTP 403 before dispatch; an empty
     * allowlist imposes no origin restriction. Admitted requests then register the disconnect, reset,
     * and whole-request timeout settlement hooks (§4.7 stage 2) before continuing.
     */
    void begin(RoutingContext context) {
        Instant startedAt = Instant.now();
        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context.vertx().getOrCreateContext(), lifecycleObservers, completedListeners, startedAt);
        context.put(COMPLETION_COORDINATOR_KEY, coordinator);
        context.put(STARTED_AT_KEY, startedAt);
        if (context.request().method() != HttpMethod.POST) {
            // Terminal before identity establishment: no security facts exist yet.
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 405, null);
            return;
        }
        String origin = context.request().getHeader("Origin");
        if (origin != null
                && !config.allowedOrigins().isEmpty()
                && !config.allowedOrigins().contains(origin)) {
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 403, null);
            return;
        }
        registerSettlementHooks(context, coordinator, startedAt);
        context.next();
    }

    /**
     * Handles one admitted MCP HTTP request.
     *
     * <p>Runs after identity establishment, so every terminal event it creates carries the
     * established {@link SecurityContextSnapshot} — including the canonical anonymous one. The request
     * body is decoded through the strict codec: a malformed frame, invalid envelope, or unknown method
     * is classified to its final-spec JSON-RPC code and bounded HTTP status. The walking-skeleton
     * {@code server/discover} still serves a frame that carries no {@code params}; params-content
     * validation is a later slice, and the frozen discovery proofs send none.
     */
    void dispatch(RoutingContext context) {
        SecurityContextSnapshot security = establishedSecurity();
        byte[] body = bodyBytes(context);
        // The walking skeleton serves server/discover whether or not it carries params — the frozen
        // discovery proofs send none, and params-content validation is a later slice. Detect exactly
        // that shape through the framework strict reader; every other frame is classified by the codec
        // so the emitted HTTP status and JSON-RPC body code always agree.
        JsonNode discovery = paramlessDiscoveryEnvelope(body);
        if (discovery != null) {
            writeDiscovery(context, discovery, security);
            return;
        }
        emitProtocolError(context, body, security);
    }

    /**
     * Detects an otherwise-valid {@code server/discover} envelope, with or without params. Returns the
     * node when the frame is a strict-decodable object with {@code jsonrpc} {@code "2.0"}, a usable
     * string/integer id, the discovery method, and (when present) an object params, and {@code null}
     * for every other shape — so the codec's classification is preserved for a genuine failure.
     *
     * @param body the raw UTF-8 request bytes
     * @return the discovery envelope node, or {@code null} when the frame is not a discovery frame
     */
    @Nullable
    private JsonNode paramlessDiscoveryEnvelope(byte[] body) {
        McpStrictJsonReader.Result parsed = reader.read(body);
        if (parsed.isRejected()) {
            return null;
        }
        JsonNode node = parsed.value();
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode version = node.get("jsonrpc");
        if (version == null || !version.isTextual() || !"2.0".equals(version.asText())) {
            return null;
        }
        JsonNode id = node.get("id");
        if (id == null || !(id.isTextual() || id.isIntegralNumber())) {
            return null;
        }
        JsonNode method = node.get("method");
        if (method == null || !method.isTextual() || !DISCOVER_METHOD.equals(method.asText())) {
            return null;
        }
        JsonNode params = node.get("params");
        if (params != null && !params.isObject()) {
            return null;
        }
        return node;
    }

    /**
     * Writes the discovery result, bounding serialization at {@code mcp.output.maxBytes} as bytes are
     * produced. An over-cap response is classified as a bounded internal error and never emitted.
     */
    private void writeDiscovery(RoutingContext context, JsonNode envelope, SecurityContextSnapshot security) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        byte[] payload;
        try {
            payload = encodeCapped(discoveryResponse(envelope));
        } catch (OutputCapExceededException overCap) {
            byte[] fallback = codec.internalFallback(envelope.get("id"), overCap);
            McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    McpMethod.SERVER_DISCOVER,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    null,
                    security,
                    null);
            write(context, 500, fallback, terminal);
            return;
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.success(
                startedAt(context),
                Instant.now(),
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                security,
                null);
        write(context, 200, payload, terminal);
    }

    /**
     * Builds the canonical {@code DiscoverResult} response node, echoing the request id and stamping
     * the configured server identity into result {@code _meta}.
     *
     * @param envelope the validated request envelope whose id is echoed
     * @return the JSON-RPC response node
     */
    private ObjectNode discoveryResponse(JsonNode envelope) {
        ObjectNode serverInfo = OUTPUT_ENCODER.createObjectNode();
        serverInfo.put("name", config.serverName());
        serverInfo.put("version", config.serverVersion());
        ObjectNode meta = OUTPUT_ENCODER.createObjectNode();
        meta.set(SERVER_INFO_META_KEY, serverInfo);
        ArrayNode supportedVersions = OUTPUT_ENCODER.createArrayNode();
        supportedVersions.add(PROTOCOL_VERSION);
        ObjectNode result = OUTPUT_ENCODER.createObjectNode();
        // The official DiscoverResult requires resultType, supportedVersions, capabilities, ttlMs,
        // and cacheScope; the configured server identity is stamped into result _meta.
        result.put("resultType", COMPLETE_RESULT_TYPE);
        result.set("supportedVersions", supportedVersions);
        result.set("capabilities", OUTPUT_ENCODER.createObjectNode());
        result.put("ttlMs", config.toolsTtlMs());
        result.put("cacheScope", PRIVATE_CACHE_SCOPE);
        result.set("_meta", meta);
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", result);
        JsonNode id = envelope.get("id");
        response.set("id", id != null ? id : NullNode.getInstance());
        return response;
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

    // --- Settlement seam wiring ---

    /**
     * Registers the disconnect, reset, and whole-request timeout settlement hooks for one request.
     *
     * <p>The response close handler settles a premature client disconnect, the response exception
     * handler settles a stream reset, and a {@code mcp.request.timeoutMs} timer settles a
     * whole-request timeout. Each drives the coordinator's first-observed-wins guard, so a hook that
     * fires after a normal write is suppressed. The timer is cancelled on every settlement path to
     * avoid leaking an event-loop timer past the request.
     */
    private void registerSettlementHooks(
            RoutingContext context, McpCompletionCoordinator coordinator, Instant startedAt) {
        long timerId = context.vertx()
                .setTimer(
                        config.requestTimeoutMs(),
                        ignored -> coordinator.settleTimeout(
                                settlementTerminal(context, startedAt, McpErrorType.TIMEOUT)));
        context.put(TIMER_ID_KEY, timerId);
        context.response().closeHandler(ignored -> {
            cancelTimer(context);
            coordinator.settleDisconnected(
                    settlementTerminal(context, startedAt, McpErrorType.TRANSPORT),
                    context.response().headWritten());
        });
        context.response().exceptionHandler(ignored -> {
            cancelTimer(context);
            coordinator.settleReset(
                    settlementTerminal(context, startedAt, McpErrorType.TRANSPORT),
                    context.response().headWritten());
        });
    }

    /**
     * Synthesizes the cancelled terminal facts for a disconnect, reset, or timeout settlement from the
     * captured request facts.
     *
     * @param context the request context whose established security snapshot is captured
     * @param startedAt the instant the request began
     * @param errorType the transport or timeout error classification
     * @return the synthesized cancelled terminal event
     */
    private McpRequestTerminalEvent settlementTerminal(
            RoutingContext context, Instant startedAt, McpErrorType errorType) {
        return McpRequestTerminalEvent.cancelled(
                startedAt,
                Instant.now(),
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                errorType,
                0,
                null,
                null,
                establishedSecurity(),
                null);
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

    /**
     * Emits a bounded protocol-failure response for a non-discovery frame.
     *
     * <p>The codec classifies the frame — malformed to {@code -32700}, invalid envelope to
     * {@code -32600}, unknown method to {@code -32601} — and produces the canonical, non-leaking error
     * bytes. The emitted HTTP status is derived from that same code, so status and body always agree.
     * A structurally valid but not-yet-exposed supported method (tools/list, tools/call) has no
     * classified codec error and settles through the bounded internal-error response; the tool surface
     * arrives in T006/T007.
     *
     * @param context the request context
     * @param body the raw request bytes the codec classifies for the error id and code
     * @param security the established security snapshot, or {@code null}
     */
    private void emitProtocolError(RoutingContext context, byte[] body, @Nullable SecurityContextSnapshot security) {
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(body);
        int code = decoded.isError() ? decoded.error().code() : INTERNAL_ERROR;
        int status = httpStatusFor(code);
        McpErrorType errorType = code == INTERNAL_ERROR ? McpErrorType.INTERNAL : McpErrorType.PROTOCOL;
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.rejected(
                startedAt(context),
                Instant.now(),
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                errorType,
                status,
                code,
                null,
                security,
                null);
        write(context, status, codec.errorResponse(body), terminal);
    }

    private static int httpStatusFor(int protocolCode) {
        return switch (protocolCode) {
            case METHOD_NOT_FOUND -> 404;
            case PARSE_ERROR, INVALID_REQUEST -> 400;
            default -> 500;
        };
    }

    private static byte[] bodyBytes(RoutingContext context) {
        Buffer buffer = context.body() == null ? null : context.body().buffer();
        return buffer == null ? new byte[0] : buffer.getBytes();
    }

    private static Instant startedAt(RoutingContext context) {
        Instant startedAt = context.get(STARTED_AT_KEY);
        return startedAt == null ? Instant.now() : startedAt;
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

    private static void write(
            RoutingContext context, int status, @Nullable byte[] body, McpRequestTerminalEvent terminal) {
        cancelTimer(context);
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
        cancelTimer(context);
        McpCompletionCoordinator coordinator = context.get(COMPLETION_COORDINATOR_KEY);
        if (coordinator != null) {
            coordinator.complete(
                    terminal,
                    written ? McpTransportOutcome.WRITTEN : McpTransportOutcome.WRITE_FAILED,
                    written,
                    Instant.now());
        }
    }

    private static void cancelTimer(RoutingContext context) {
        Long timerId = context.get(TIMER_ID_KEY);
        if (timerId != null) {
            context.remove(TIMER_ID_KEY);
            context.vertx().cancelTimer(timerId);
        }
    }

    /**
     * Encodes a JSON node to canonical UTF-8 bytes, bounding the output at {@code mcp.output.maxBytes}
     * as bytes are produced.
     *
     * @param value the response node to encode
     * @return the canonical UTF-8 bytes, at most {@code mcp.output.maxBytes} long
     * @throws OutputCapExceededException when serialization would exceed the configured cap
     */
    private byte[] encodeCapped(JsonNode value) {
        CappedOutputStream out = new CappedOutputStream(config.outputMaxBytes());
        try {
            OUTPUT_ENCODER.writeValue(out, value);
        } catch (OutputCapExceededException overCap) {
            throw overCap;
        } catch (IOException encodeFailure) {
            // Writing a fully in-memory node tree to a byte sink cannot fail on I/O; a failure here is
            // a programming error, not a wire condition.
            throw new UncheckedIOException(encodeFailure);
        }
        return out.toByteArray();
    }

    /**
     * A byte sink that accumulates canonical output and aborts serialization the moment the running
     * byte count would exceed the configured cap, so an over-cap response is classified before its
     * full byte array is ever materialized.
     */
    static final class CappedOutputStream extends OutputStream {
        private final int cap;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        CappedOutputStream(int cap) {
            this.cap = cap;
        }

        @Override
        public void write(int b) {
            if (buffer.size() + 1 > cap) {
                throw new OutputCapExceededException();
            }
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if ((long) buffer.size() + len > cap) {
                throw new OutputCapExceededException();
            }
            buffer.write(b, off, len);
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }

    /** Signals that a response exceeded {@code mcp.output.maxBytes} while being serialized. */
    static final class OutputCapExceededException extends RuntimeException {
        OutputCapExceededException() {
            super("MCP response exceeded mcp.output.maxBytes");
        }
    }
}
