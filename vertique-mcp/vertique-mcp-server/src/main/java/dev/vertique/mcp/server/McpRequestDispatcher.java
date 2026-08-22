// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
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
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.Validator;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches the bounded discovery endpoint over the hardened stateless HTTP contract (§4.7).
 *
 * <p>The dispatcher wires the framework-owned strict codec onto the live request path, enforces the
 * method/origin/content-type/accept admission checks, registers the disconnect/reset settlement seam,
 * and bounds the response write at {@code mcp.output.maxBytes}. MCP arms no whole-request deadline of
 * its own: transport liveness comes from the shared {@link HttpConfig} idle/read/write timeouts, so an
 * idle or slow connection is closed by the shared HTTP layer and reaches this dispatcher through the
 * ordinary disconnect/reset settlement path (T007).
 *
 * <p>{@code tools/call} (T012) resolves the requested name through the immutable {@link
 * McpToolRegistry}, reauthorizes the resolved descriptor through {@link McpPolicyEnforcer#decide}
 * exactly like {@code tools/list}, and maps an unknown name or a denied decision to the same
 * {@code -32602} JSON response {@link #writeUnknownOrUnauthorized} already produces — no tool is ever
 * invoked on that path. Only once a call is known and authorized does the dispatcher select
 * request-scoped SSE ({@code text/event-stream}, {@code X-Accel-Buffering: no}) — unconditionally,
 * before {@link McpToolInvoker#prepare} runs, so the response is committed to SSE framing regardless
 * of how invocation later resolves and never falls back to JSON afterward — and only then runs stage 1
 * of the fixed request-time input pipeline (T015, contract §4.7): the precompiled input schema
 * validator T009 compiled at composition. A schema rejection settles as the bounded text-only {@code
 * isError=true} tool-error outcome without ever calling {@code prepare()}. Only once schema validation
 * passes does the dispatcher invoke the generated invoker directly (no reflection); {@code prepare()}
 * itself then owns stages 2–4 (INP-001 canonicalization/sanitization, materialization, Bean
 * Validation), signalling a rejection there through {@link McpInputRejectionException}. Rich structured
 * output is a later slice.
 */
final class McpRequestDispatcher {
    private static final String DISCOVER_METHOD = "server/discover";
    private static final String TOOLS_LIST_METHOD = "tools/list";
    private static final String TOOLS_CALL_METHOD = "tools/call";
    private static final String PROTOCOL_VERSION = McpCursorCodec.PROTOCOL_VERSION;

    /** The official schema treats an absent {@code resultType} as this completed-result value. */
    private static final String COMPLETE_RESULT_TYPE = "complete";

    /** Discovery and listing results are never shared across authorization contexts. */
    private static final String PRIVATE_CACHE_SCOPE = "private";

    private static final String SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String EVENT_STREAM_CONTENT_TYPE = "text/event-stream";
    private static final String APPLICATION_WILDCARD_RANGE = "application/*";
    private static final String WILDCARD_RANGE = "*/*";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;

    /**
     * The bounded, non-leaking text returned as the sole content item of a stage-1 (schema) rejection
     * — §4.7's "bounded invalid-arguments/tool-error outcome". Deliberately generic rather than
     * echoing the failing keyword or property: the compiled validator's {@code OutputUnit} detail is
     * diagnostic-only and never reaches the wire, matching {@link
     * McpPolicyEnforcer#unknownOrUnauthorizedError()}'s established non-leaking-message convention for
     * a different stage.
     */
    private static final String SCHEMA_REJECTION_MESSAGE = "Invalid tool arguments: schema validation failed";

    /**
     * The hard per-page examination cap (§4.7): a page examines at most this many multiples of
     * {@code mcp.tools.pageSize} candidates, bounding the authorization fan-out an unauthenticated
     * {@code tools/list} scan can trigger (issue #416).
     */
    private static final int EXAMINATION_BUDGET_MULTIPLIER = 4;

    private static final String KEY_PREFIX = McpRequestDispatcher.class.getName();
    private static final String COMPLETION_COORDINATOR_KEY = KEY_PREFIX + ".completionCoordinator";
    private static final String STARTED_AT_KEY = KEY_PREFIX + ".startedAt";

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
    private final McpToolRegistry toolRegistry;
    private final McpPolicyEnforcer policyEnforcer;
    private final McpCursorCodec cursorCodec;

    @Inject
    McpRequestDispatcher(
            McpServerConfig config,
            SecurityRuntime securityRuntime,
            Set<McpRequestLifecycleObserver> lifecycleObservers,
            Set<McpRequestCompletedListener> completedListeners,
            HttpConfig httpConfig,
            McpToolRegistry toolRegistry,
            McpPolicyEnforcer policyEnforcer) {
        this.config = config;
        this.securityRuntime = securityRuntime;
        this.lifecycleObservers = Set.copyOf(lifecycleObservers);
        this.completedListeners = Set.copyOf(completedListeners);
        this.codec = new McpProtocolCodec(httpConfig);
        this.toolRegistry = toolRegistry;
        this.policyEnforcer = policyEnforcer;
        this.cursorCodec = new McpCursorCodec();
    }

    /**
     * Applies the cheap HTTP admission checks (§4.7 stage 1) before opening any lifecycle observation
     * and before authentication and identity establishment.
     *
     * <p>All admission checks run <em>before</em> the completion coordinator is constructed, so — like
     * the body-limit rejection — a request that fails admission produces no lifecycle observation. Only
     * POST is accepted; GET/DELETE and any other method are HTTP 405. A present {@code Origin} outside a
     * non-empty {@code mcp.allowedOrigins} allowlist is HTTP 403; an empty allowlist imposes no origin
     * restriction. A present {@code Content-Type} whose media type is not {@code application/json} is
     * HTTP 415, and a present {@code Accept} that admits none of {@code application/json},
     * {@code text/event-stream}, {@code application/*}, or {@code *&#47;*} is HTTP 406; an absent header
     * imposes no restriction (present-only, mirroring Origin). Only once every check passes does the
     * dispatcher construct the coordinator, register the disconnect and reset settlement hooks (§4.7
     * stage 2), and continue.
     */
    void begin(RoutingContext context) {
        Instant startedAt = Instant.now();
        context.put(STARTED_AT_KEY, startedAt);
        // Every admission check runs before the coordinator exists, so a rejection here opens no
        // lifecycle observation — the reject path null-guards the (absent) coordinator and timer.
        if (context.request().method() != HttpMethod.POST) {
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
        if (!contentTypeAdmitted(context)) {
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 415, null);
            return;
        }
        if (!acceptAdmitted(context)) {
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 406, null);
            return;
        }
        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context.vertx().getOrCreateContext(), lifecycleObservers, completedListeners, startedAt);
        context.put(COMPLETION_COORDINATOR_KEY, coordinator);
        registerSettlementHooks(context, coordinator, startedAt);
        context.next();
    }

    /**
     * Enforces the present-only {@code Content-Type} admission check: an absent header is admitted, and
     * a present one is admitted only when its media type (parameters such as {@code ; charset=utf-8}
     * stripped, case-insensitive) is {@code application/json}.
     *
     * @param context the request whose {@code Content-Type} header is inspected
     * @return {@code true} when the request may proceed, {@code false} when it is HTTP 415
     */
    private static boolean contentTypeAdmitted(RoutingContext context) {
        String contentType = context.request().getHeader("Content-Type");
        if (contentType == null) {
            return true;
        }
        return JSON_CONTENT_TYPE.equalsIgnoreCase(mediaTypeOf(contentType));
    }

    /**
     * Enforces the present-only {@code Accept} admission check: an absent header is admitted, and a
     * present one is admitted only when at least one comma-separated media range matches
     * {@code application/json}, {@code text/event-stream}, {@code application/*}, or {@code *&#47;*}
     * (case-insensitive) <em>and</em> is not explicitly rejected with {@code q=0}. Per RFC 7231 a
     * media-range with a quality value of zero is not acceptable, so such a range does not admit its
     * media type; if every matching range carries {@code q=0} and no other range admits, the request
     * is HTTP 406. This implements only the {@code q=0} exclusion, not full q-value preference ranking.
     *
     * @param context the request whose {@code Accept} header is inspected
     * @return {@code true} when the request may proceed, {@code false} when it is HTTP 406
     */
    private static boolean acceptAdmitted(RoutingContext context) {
        String accept = context.request().getHeader("Accept");
        if (accept == null) {
            return true;
        }
        for (String range : accept.split(",")) {
            String mediaRange = mediaTypeOf(range);
            if ((mediaRange.equalsIgnoreCase(JSON_CONTENT_TYPE)
                            || mediaRange.equalsIgnoreCase(EVENT_STREAM_CONTENT_TYPE)
                            || mediaRange.equalsIgnoreCase(APPLICATION_WILDCARD_RANGE)
                            || mediaRange.equals(WILDCARD_RANGE))
                    && !hasZeroQuality(range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether an {@code Accept} range explicitly rejects its media type with a zero quality
     * value ({@code q=0}, {@code q=0.0}, {@code q=0.000}, …). The {@code q} parameter name is
     * matched case-insensitively and surrounding whitespace is tolerated. A range with no {@code q}
     * parameter, or a {@code q} greater than zero, is not zero-quality; a {@code q} token that cannot
     * be parsed as a number is left permissive (treated as non-zero) rather than over-engineered into
     * full q-value handling.
     *
     * @param range one comma-separated {@code Accept} range, possibly carrying parameters
     * @return {@code true} when the range carries a numerically-zero {@code q} parameter
     */
    private static boolean hasZeroQuality(String range) {
        int semicolon = range.indexOf(';');
        if (semicolon < 0) {
            return false;
        }
        for (String parameter : range.substring(semicolon + 1).split(";")) {
            int equals = parameter.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String name = parameter.substring(0, equals).trim();
            if (!"q".equalsIgnoreCase(name)) {
                continue;
            }
            String value = parameter.substring(equals + 1).trim();
            try {
                return Double.parseDouble(value) == 0.0;
            } catch (NumberFormatException unparseable) {
                // An unparseable q token is left permissive rather than over-engineering full RFC
                // q-value handling; only a cleanly numerically-zero q rejects the media type.
                return false;
            }
        }
        return false;
    }

    /**
     * Extracts the bare media type from a header value by dropping any {@code ;}-delimited parameters
     * (charset, q-value) and surrounding whitespace.
     *
     * @param headerValue one media type or range, possibly carrying parameters
     * @return the trimmed media type with parameters removed
     */
    private static String mediaTypeOf(String headerValue) {
        int semicolon = headerValue.indexOf(';');
        String mediaType = semicolon < 0 ? headerValue : headerValue.substring(0, semicolon);
        return mediaType.trim();
    }

    /**
     * Handles one admitted MCP HTTP request.
     *
     * <p>Runs after identity establishment, so every terminal event it creates carries the
     * established {@link SecurityContextSnapshot} — including the canonical anonymous one. The request
     * body is decoded exactly once through the strict codec, the single envelope authority: a
     * validated {@code server/discover} frame is served, and every other outcome — a malformed frame,
     * invalid envelope (including a {@code server/discover} that omits the schema-required
     * {@code params}, which carries the protocol version and client capabilities), or unknown method
     * — is classified to its final-spec JSON-RPC code and bounded HTTP status. Discovery is routed
     * through the same codec decode as every other method, so it requires {@code params} exactly like
     * the rest of the supported set.
     */
    void dispatch(RoutingContext context) {
        SecurityContextSnapshot security = establishedSecurity();
        byte[] body = bodyBytes(context);
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(body);
        if (!decoded.isError()) {
            String method = decoded.envelope().get("method").asText();
            if (DISCOVER_METHOD.equals(method)) {
                writeDiscovery(context, decoded.envelope(), security);
                return;
            }
            if (TOOLS_LIST_METHOD.equals(method)) {
                writeToolsList(context, decoded.envelope(), security);
                return;
            }
            if (TOOLS_CALL_METHOD.equals(method)) {
                writeToolsCall(context, decoded.envelope(), security);
                return;
            }
        }
        emitProtocolError(context, decoded, body, security);
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
            if (fallback.length > config.outputMaxBytes()) {
                // Even the id-bearing internal-error can exceed the cap when the request id is itself
                // large; degrade to the minimal id-less internal error, which is always under cap.
                fallback = codec.internalFallback(null, overCap);
            }
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

    // --- tools/list ---

    /**
     * Serves one bounded, authorized page of the immutable tool registry (§4.7).
     *
     * <p>Scans the registry in global name order starting from the request's cursor anchor (or the
     * beginning, when absent), reauthorizing every candidate examined through {@link
     * McpPolicyEnforcer#decide} — never {@link McpPolicyEnforcer#isVisible}, so exactly one decision
     * (and at most one {@code AuthorizationDecisionEvent}) is produced per candidate. Examination stops
     * at the first of: the page reaching {@code mcp.tools.pageSize} visible tools, examining {@code
     * 4 * mcp.tools.pageSize} candidates (the fan-out bound, issue #416), or the registry being
     * exhausted. A present, malformed, or otherwise invalid cursor is rejected with the same
     * indistinguishable {@code -32602} response {@link McpPolicyEnforcer#unknownOrUnauthorizedError()}
     * produces for a denied or unknown tool (issue #420), never a distinct code or status.
     *
     * @param context the request context
     * @param envelope the validated {@code tools/list} request envelope
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void writeToolsList(RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        JsonNode cursorNode = envelope.get("params").get("cursor");
        if (cursorNode != null && !cursorNode.isTextual()) {
            writeUnknownOrUnauthorized(
                    context, envelope, security, McpMethod.TOOLS_LIST, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
            return;
        }
        List<String> names = List.copyOf(toolRegistry.descriptorsByName().keySet());
        int startIndex;
        if (cursorNode == null) {
            startIndex = 0;
        } else {
            McpCursorCodec.Decoded decoded = cursorCodec.decode(
                    cursorNode.asText(),
                    toolRegistry.digest(),
                    toolRegistry.descriptorsByName().keySet());
            if (decoded.isInvalid()) {
                writeUnknownOrUnauthorized(
                        context, envelope, security, McpMethod.TOOLS_LIST, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
                return;
            }
            startIndex = names.indexOf(decoded.anchor()) + 1;
        }
        int pageSize = config.toolsPageSize();
        int budget = pageSize * EXAMINATION_BUDGET_MULTIPLIER;
        SecurityContext caller = securityRuntime.current();
        scan(names, startIndex, pageSize, budget, 0, List.of(), null, caller).onComplete(ar -> {
            if (ar.failed()) {
                // McpPolicyEnforcer#decide never fails per its own contract; defended here so a
                // contract-violating extension cannot escape as an unhandled exception.
                writeToolsListFallback(context, envelope, security, ar.cause());
                return;
            }
            writeToolsListResult(context, envelope, security, ar.result());
        });
    }

    /**
     * Scans candidates {@code names[index..)} for one bounded page, reauthorizing every candidate it
     * examines exactly once, and stopping at the first of: the page reaching {@code pageSize} visible
     * tools, {@code examined} reaching {@code budget}, or the candidate list being exhausted.
     */
    private Future<ScanResult> scan(
            List<String> names,
            int index,
            int pageSize,
            int budget,
            int examined,
            List<McpToolDescriptor> visible,
            @Nullable String lastExaminedName,
            SecurityContext caller) {
        if (visible.size() >= pageSize || examined >= budget || index >= names.size()) {
            boolean candidatesRemain = index < names.size();
            return Future.succeededFuture(
                    new ScanResult(visible, candidatesRemain ? lastExaminedName : null, examined));
        }
        String name = names.get(index);
        McpToolDescriptor descriptor = toolRegistry.descriptorsByName().get(name);
        return policyEnforcer.decide(descriptor, caller).compose(decision -> {
            List<McpToolDescriptor> updated = visible;
            if (decision.permitted()) {
                updated = new ArrayList<>(visible);
                updated.add(descriptor);
            }
            return scan(names, index + 1, pageSize, budget, examined + 1, updated, name, caller);
        });
    }

    /** One bounded page's outcome: the visible tools, the next-page anchor, and the examined count. */
    private record ScanResult(
            List<McpToolDescriptor> visible, @Nullable String nextAnchor, int examined) {}

    /** Settles a {@link McpPolicyEnforcer#decide} contract violation through the internal fallback. */
    private void writeToolsListFallback(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security, Throwable cause) {
        byte[] fallback = codec.internalFallback(envelope.get("id"), cause);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_LIST,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.INTERNAL,
                500,
                INTERNAL_ERROR,
                null,
                security,
                null);
        write(context, 500, fallback, terminal);
    }

    /**
     * Writes the bounded {@code tools/list} result, bounding serialization at {@code
     * mcp.output.maxBytes} exactly like discovery.
     */
    private void writeToolsListResult(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security, ScanResult result) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        byte[] payload;
        try {
            payload = encodeCapped(toolsListResponse(envelope, result));
        } catch (OutputCapExceededException overCap) {
            byte[] fallback = codec.internalFallback(envelope.get("id"), overCap);
            if (fallback.length > config.outputMaxBytes()) {
                fallback = codec.internalFallback(null, overCap);
            }
            McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    McpMethod.TOOLS_LIST,
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
                McpMethod.TOOLS_LIST,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                null,
                security,
                null);
        write(context, 200, payload, terminal);
    }

    /**
     * Builds the canonical {@code ListToolsResult} response node: the visible tools in scanned order,
     * an opaque {@code nextCursor} when unexamined candidates remain, and the mandatory {@code ttlMs}
     * and {@code cacheScope=private} cache hints.
     */
    private ObjectNode toolsListResponse(JsonNode envelope, ScanResult result) {
        ArrayNode tools = OUTPUT_ENCODER.createArrayNode();
        for (McpToolDescriptor descriptor : result.visible()) {
            tools.add(toolNode(descriptor));
        }
        ObjectNode serverInfo = OUTPUT_ENCODER.createObjectNode();
        serverInfo.put("name", config.serverName());
        serverInfo.put("version", config.serverVersion());
        ObjectNode meta = OUTPUT_ENCODER.createObjectNode();
        meta.set(SERVER_INFO_META_KEY, serverInfo);
        ObjectNode result0 = OUTPUT_ENCODER.createObjectNode();
        result0.put("resultType", COMPLETE_RESULT_TYPE);
        result0.set("tools", tools);
        if (result.nextAnchor() != null) {
            result0.put("nextCursor", cursorCodec.encode(result.nextAnchor(), toolRegistry.digest()));
        }
        result0.put("ttlMs", config.toolsTtlMs());
        result0.put("cacheScope", PRIVATE_CACHE_SCOPE);
        result0.set("_meta", meta);
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", result0);
        JsonNode id = envelope.get("id");
        response.set("id", id != null ? id : NullNode.getInstance());
        return response;
    }

    /** Builds one {@code Tool} node from a visible descriptor. */
    private static JsonNode toolNode(McpToolDescriptor descriptor) {
        ObjectNode node = OUTPUT_ENCODER.createObjectNode();
        node.put("name", descriptor.name());
        if (descriptor.title() != null) {
            node.put("title", descriptor.title());
        }
        node.put("description", descriptor.description());
        node.set("inputSchema", parseSchema(descriptor.inputSchema()));
        if (descriptor.outputSchema() != null) {
            node.set("outputSchema", parseSchema(descriptor.outputSchema()));
        }
        node.set("annotations", annotationsNode(descriptor.annotations()));
        return node;
    }

    /** Builds the {@code ToolAnnotations} node from the descriptor's published behavior hints. */
    private static JsonNode annotationsNode(McpToolAnnotations annotations) {
        ObjectNode node = OUTPUT_ENCODER.createObjectNode();
        node.put("readOnlyHint", annotations.readOnlyHint());
        node.put("destructiveHint", annotations.destructiveHint());
        node.put("idempotentHint", annotations.idempotentHint());
        node.put("openWorldHint", annotations.openWorldHint());
        return node;
    }

    /** Parses an already-validated canonical schema JSON string into a node for embedding. */
    private static JsonNode parseSchema(String json) {
        try {
            return OUTPUT_ENCODER.readTree(json);
        } catch (IOException impossible) {
            // The schema strings embedded here were already compiled by McpSchemaRegistry at startup;
            // a failure here is a programming error, not a wire condition.
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * Writes the same externally indistinguishable {@code -32602} response {@link
     * McpPolicyEnforcer#unknownOrUnauthorizedError()} defines, so an invalid cursor, a denied tool, and
     * an unknown tool all yield byte-identical bodies and the same HTTP status (issue #420) — the
     * status comes from the shared {@link #httpStatusFor} mapping, never a bespoke one. Always
     * {@code application/json}: an unknown or denied {@code tools/call} never reaches SSE selection
     * (§4.7 — "unknown tool[s] and authorization denial return JSON").
     *
     * @param toolName the requested tool name recorded on the internal terminal event for telemetry
     *     only — never serialized to the wire, so it has no bearing on the indistinguishability
     *     guarantee; callers with no tool identity (an invalid {@code tools/list} cursor, or a
     *     structurally invalid {@code tools/call} name) pass {@link
     *     McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}
     */
    private void writeUnknownOrUnauthorized(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            McpMethod method,
            String toolName) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        McpProtocolCodec.CodecError error = McpPolicyEnforcer.unknownOrUnauthorizedError();
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        JsonNode id = envelope.get("id");
        response.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode errorNode = OUTPUT_ENCODER.createObjectNode();
        errorNode.put("code", error.code());
        errorNode.put("message", error.message());
        response.set("error", errorNode);
        byte[] body = codec.encode(response);
        int status = httpStatusFor(error.code());
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.rejected(
                startedAt(context),
                Instant.now(),
                method,
                method == McpMethod.TOOLS_CALL ? toolName : McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.AUTHORIZATION,
                status,
                error.code(),
                null,
                security,
                null);
        write(context, status, body, terminal);
    }

    // --- tools/call ---

    /**
     * Resolves, reauthorizes, and dispatches one zero-argument {@code tools/call} request (T012).
     *
     * <p>A structurally missing/blank name, an unresolved name, or a denied decision all settle
     * through {@link #writeUnknownOrUnauthorized} — the exact same JSON response {@code tools/list}
     * produces for an invalid cursor — before any invocation is attempted. Only a known, authorized
     * call reaches {@link #invokeAndRespond}.
     *
     * @param context the request context
     * @param envelope the validated {@code tools/call} request envelope
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void writeToolsCall(RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        JsonNode nameNode = envelope.get("params").get("name");
        if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
            writeUnknownOrUnauthorized(
                    context, envelope, security, McpMethod.TOOLS_CALL, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
            return;
        }
        String toolName = nameNode.asText();
        McpToolInvoker invoker = toolRegistry.invokersByName().get(toolName);
        if (invoker == null) {
            writeUnknownOrUnauthorized(context, envelope, security, McpMethod.TOOLS_CALL, toolName);
            return;
        }
        SecurityContext caller = securityRuntime.current();
        policyEnforcer.decide(invoker.descriptor(), caller).onComplete(ar -> {
            if (ar.failed()) {
                // McpPolicyEnforcer#decide never fails per its own contract; defended here so a
                // contract-violating extension cannot escape as an unhandled exception. No invocation
                // was ever attempted, so this stays a JSON (never SSE) response.
                writeUnknownOrUnauthorized(context, envelope, security, McpMethod.TOOLS_CALL, toolName);
                return;
            }
            if (!ar.result().permitted()) {
                writeUnknownOrUnauthorized(context, envelope, security, McpMethod.TOOLS_CALL, toolName);
                return;
            }
            invokeAndRespond(context, envelope, security, toolName, invoker);
        });
    }

    /**
     * Selects request-scoped SSE, runs stage 1 of the fixed request-time input pipeline (contract
     * §4.7), then invokes the resolved tool directly.
     *
     * <p>{@link #selectSse} runs unconditionally as the first statement here — strictly before {@link
     * McpToolInvoker#prepare} is ever called — so the response is committed to SSE framing before
     * invocation begins and independently of how invocation later resolves: a synchronous {@code
     * prepare}/{@code invoke} throw and a failed invocation future both settle through {@link
     * #writeSseFallback}, never a JSON response. {@code arguments} is the bounded empty map for an
     * absent or non-object {@code arguments} member.
     *
     * <p>Stage 1 — the precompiled schema validator T009 compiled at composition — runs next, on
     * exactly this {@code arguments} tree, before {@code prepare()} is ever called: a schema rejection
     * settles through {@link #writeToolResult} as the bounded text-only {@code isError=true} outcome
     * and never invokes {@code prepare()}. Stages 2–4 (INP-001 canonicalization/sanitization at
     * {@code InputLocation.PAYLOAD}, materialization through the effective mapper, and Bean Validation)
     * are the generated fixed input boundary's own responsibility inside {@code prepare()}; a stage
     * 2–4 rejection is signalled by {@link McpInputRejectionException} and settles exactly like a
     * stage-1 rejection, while any other {@code RuntimeException} from {@code prepare()}/{@code
     * invoke()} stays the pre-existing internal-error fallback.
     */
    private void invokeAndRespond(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            McpToolInvoker invoker) {
        selectSse(context);
        Map<String, Object> arguments = argumentsOf(envelope);
        if (!schemaValid(toolName, arguments)) {
            writeToolResult(context, envelope, security, toolName, McpToolResult.error(SCHEMA_REJECTION_MESSAGE));
            return;
        }
        McpCompletionCoordinator coordinator = context.get(COMPLETION_COORDINATOR_KEY);
        // T013: the coordinator owns the request's cancellation signal, fired exactly once when the
        // request settles as a disconnect, a stream reset, or a failed write. Every admitted request
        // has a coordinator by the time invocation is reached (begin() always constructs one before
        // dispatch); the fallback exists only as the same defensive null-guard the write path below
        // already uses.
        McpCancellationSignal cancellation =
                coordinator != null ? coordinator.cancellation() : NoOpCancellationSignal.INSTANCE;
        McpPreparedToolCall prepared;
        try {
            prepared = invoker.prepare(arguments, cancellation);
        } catch (McpInputRejectionException rejected) {
            // Stages 2-4 (generated fixed input boundary) rejected before any application handler ran;
            // this is the same bounded tool-error outcome stage 1 produces above, never the internal
            // fallback a genuine bug in prepare()/invoke() produces.
            writeToolResult(context, envelope, security, toolName, McpToolResult.error(rejected.getMessage()));
            return;
        } catch (RuntimeException prepareFailure) {
            writeSseFallback(context, envelope, security, toolName, prepareFailure);
            return;
        }
        Future<McpToolResult<?>> result;
        try {
            result = prepared.invoke();
        } catch (RuntimeException invokeFailure) {
            writeSseFallback(context, envelope, security, toolName, invokeFailure);
            return;
        }
        result.onComplete(ar -> {
            if (ar.failed() || ar.result() == null) {
                writeSseFallback(
                        context,
                        envelope,
                        security,
                        toolName,
                        ar.failed() ? ar.cause() : new NullPointerException("tool result"));
                return;
            }
            writeToolResult(context, envelope, security, toolName, ar.result());
        });
    }

    /**
     * Selects request-scoped SSE for this response: mutates the buffered response headers only — no
     * byte reaches the wire from this call, since Vert.x defers sending headers until the first
     * {@code write}/{@code end} — so this may run freely before invocation without violating "no byte
     * until a terminal message is ready" (§4.7).
     */
    private static void selectSse(RoutingContext context) {
        context.response().putHeader("content-type", EVENT_STREAM_CONTENT_TYPE);
        context.response().putHeader("X-Accel-Buffering", "no");
    }

    /**
     * Runs stage 1 of the fixed request-time input pipeline (contract §4.7): validates {@code
     * arguments} against {@code toolName}'s precompiled input schema.
     *
     * <p>Never compiles a schema or a validator here — {@link McpSchemaRegistry} compiled every
     * validator exactly once, at composition, from {@link McpToolRegistry}'s descriptor set (T009);
     * this call only invokes the already-compiled instance. {@code toolName} is guaranteed present in
     * {@link McpToolRegistry#schemaRegistry()} because {@link #writeToolsCall} only reaches this method
     * once a {@link McpToolInvoker} for {@code toolName} was already resolved from the same registry
     * the schema registry was compiled from.
     *
     * @param toolName the resolved tool's name
     * @param arguments the {@code tools/call} argument tree, as {@link #argumentsOf} normalizes it
     * @return {@code true} when {@code arguments} satisfies the tool's input schema
     */
    private boolean schemaValid(String toolName, Map<String, Object> arguments) {
        Validator inputValidator = toolRegistry.schemaRegistry().inputValidator(toolName);
        return inputValidator.validate(new JsonObject(arguments)).getValid();
    }

    /**
     * Normalizes the {@code tools/call} {@code arguments} member to a bounded, non-null map: absent,
     * explicit {@code null}, or non-object all normalize to the same immutable empty map as {@code {}}
     * (§4.7). A present object is shallow-converted to {@code Map<String, Object>}; deeper structure is
     * preserved as nested {@code Map}/{@code List}/scalar values exactly as Jackson's generic
     * conversion produces them.
     */
    private static Map<String, Object> argumentsOf(JsonNode envelope) {
        JsonNode arguments = envelope.get("params").get("arguments");
        if (arguments == null || !arguments.isObject()) {
            return Map.of();
        }
        return OUTPUT_ENCODER.convertValue(arguments, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Writes a completed {@code CallToolResult}, bounding serialization at {@code mcp.output.maxBytes}
     * exactly like discovery and {@code tools/list}, and always through {@link #writeSse} — SSE was
     * already selected in {@link #invokeAndRespond} before this method is ever reached.
     */
    private void writeToolResult(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            McpToolResult<?> result) {
        byte[] payload;
        try {
            payload = encodeCapped(toolCallResponse(envelope, result));
        } catch (OutputCapExceededException overCap) {
            writeSseFallback(context, envelope, security, toolName, overCap);
            return;
        }
        McpRequestTerminalEvent terminal = result.isError()
                ? McpRequestTerminalEvent.toolError(
                        startedAt(context),
                        Instant.now(),
                        McpMethod.TOOLS_CALL,
                        toolName,
                        McpErrorType.HANDLER,
                        200,
                        null,
                        security,
                        null)
                : McpRequestTerminalEvent.success(
                        startedAt(context), Instant.now(), McpMethod.TOOLS_CALL, toolName, 200, null, security, null);
        writeSse(context, 200, payload, terminal);
    }

    /**
     * Builds the canonical {@code CallToolResult} response node: the text content items, the optional
     * structured content, the {@code isError} flag, and the mandatory server-identity {@code _meta}.
     */
    private ObjectNode toolCallResponse(JsonNode envelope, McpToolResult<?> result) {
        ArrayNode content = OUTPUT_ENCODER.createArrayNode();
        for (String text : result.textContent()) {
            ObjectNode item = OUTPUT_ENCODER.createObjectNode();
            item.put("type", "text");
            item.put("text", text);
            content.add(item);
        }
        ObjectNode serverInfo = OUTPUT_ENCODER.createObjectNode();
        serverInfo.put("name", config.serverName());
        serverInfo.put("version", config.serverVersion());
        ObjectNode meta = OUTPUT_ENCODER.createObjectNode();
        meta.set(SERVER_INFO_META_KEY, serverInfo);
        ObjectNode toolResult = OUTPUT_ENCODER.createObjectNode();
        toolResult.set("content", content);
        toolResult.put("isError", result.isError());
        if (result.structuredContent() != null) {
            toolResult.set("structuredContent", OUTPUT_ENCODER.valueToTree(result.structuredContent()));
        }
        toolResult.set("_meta", meta);
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", toolResult);
        JsonNode id = envelope.get("id");
        response.set("id", id != null ? id : NullNode.getInstance());
        return response;
    }

    /**
     * Settles an invocation-path failure — a synchronous {@code prepare}/{@code invoke} throw, a
     * failed invocation future, or a null direct result — through the bounded pre-encoded
     * internal-error fallback, framed as SSE: SSE was already selected before invocation began, and
     * the response never falls back to JSON after that point (§4.7).
     */
    private void writeSseFallback(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            Throwable cause) {
        byte[] fallback = codec.internalFallback(envelope.get("id"), cause);
        if (fallback.length > config.outputMaxBytes()) {
            // Degrade to the id-less internal error so the hard cap holds, exactly like the discovery
            // and tools/list fallback paths.
            fallback = codec.internalFallback(null, cause);
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                McpErrorType.INTERNAL,
                500,
                INTERNAL_ERROR,
                null,
                security,
                null);
        writeSse(context, 500, fallback, terminal);
    }

    /**
     * Writes one complete JSON-RPC message SSE-framed as a single {@code event: message} / {@code
     * data:} block, through the same {@link #write} settlement path every other response uses — so the
     * two-phase logical-settlement-before-byte-write ordering, the coordinator's first-observed-wins
     * guard, and the completion accounting are identical to the JSON write paths. Framing and writing
     * happen in the same call, so no byte of this SSE message reaches the wire before it is complete.
     */
    private static void writeSse(
            RoutingContext context, int status, byte[] jsonPayload, McpRequestTerminalEvent terminal) {
        write(context, status, sseFrame(jsonPayload), terminal);
    }

    /** Frames one complete JSON-RPC message as a single {@code event: message} / {@code data:} SSE block. */
    private static byte[] sseFrame(byte[] jsonPayload) {
        byte[] prefix = "event: message\ndata: ".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[prefix.length + jsonPayload.length + suffix.length];
        System.arraycopy(prefix, 0, framed, 0, prefix.length);
        System.arraycopy(jsonPayload, 0, framed, prefix.length, jsonPayload.length);
        System.arraycopy(suffix, 0, framed, prefix.length + jsonPayload.length, suffix.length);
        return framed;
    }

    /**
     * A cancellation signal that is never cancelled, used only as the defensive fallback for a null
     * completion coordinator ({@link #invokeAndRespond} above) — the same null-guard every other
     * coordinator use in this class already applies. Every ordinarily admitted request instead
     * receives {@link McpCompletionCoordinator#cancellation()} (T013), which fires on disconnect,
     * reset, or a failed write. {@link #cancelled()} returns a future backed by a {@link Promise} that
     * is deliberately never completed, matching the interface's "never completed otherwise" contract.
     */
    private static final class NoOpCancellationSignal implements McpCancellationSignal {
        static final NoOpCancellationSignal INSTANCE = new NoOpCancellationSignal();

        private final Future<Void> neverCompletes = Promise.<Void>promise().future();

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Future<Void> cancelled() {
            return neverCompletes;
        }
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
            // The response already settled; nothing further to do.
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
     * Registers the disconnect and reset settlement hooks for one request.
     *
     * <p>The response close handler settles a premature client disconnect and the response exception
     * handler settles a stream reset. Each drives the coordinator's first-observed-wins guard, so a
     * hook that fires after a normal write is suppressed. MCP arms no whole-request timer of its own:
     * transport liveness is shared {@link HttpConfig} idle/read/write timeout behavior, so an idle or
     * slow connection is closed by the shared HTTP layer and reaches these same hooks through the
     * ordinary close/exception path (T007), classified as transport cancellation rather than a
     * distinct timeout.
     */
    private void registerSettlementHooks(
            RoutingContext context, McpCompletionCoordinator coordinator, Instant startedAt) {
        context.response()
                .closeHandler(ignored -> coordinator.settleDisconnected(
                        settlementTerminal(context, startedAt, McpErrorType.TRANSPORT),
                        context.response().headWritten()));
        context.response()
                .exceptionHandler(ignored -> coordinator.settleReset(
                        settlementTerminal(context, startedAt, McpErrorType.TRANSPORT),
                        context.response().headWritten()));
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
     * @param decoded the already-decoded envelope this dispatch produced, reused so the body is
     *     decoded only once on the dispatch path
     * @param body the raw request bytes the codec re-analyzes for the error id and code
     * @param security the established security snapshot, or {@code null}
     */
    private void emitProtocolError(
            RoutingContext context,
            McpProtocolCodec.Decoded decoded,
            byte[] body,
            @Nullable SecurityContextSnapshot security) {
        int code = decoded.isError() ? decoded.error().code() : INTERNAL_ERROR;
        int status = httpStatusFor(code);
        McpErrorType errorType = code == INTERNAL_ERROR ? McpErrorType.INTERNAL : McpErrorType.PROTOCOL;
        byte[] errorBytes = codec.errorResponse(body);
        if (errorBytes.length > config.outputMaxBytes()) {
            // The classified error echoes the request id, whose only unbounded element can push the
            // response past mcp.output.maxBytes (a string id is bounded by the envelope codec's frozen
            // maxStringLength, 20,000,000 chars, far above the minimum cap). Degrade to a bounded
            // id-less internal error so the hard cap holds — the emitted status, terminal, and body
            // stay consistent as a 500 internal error.
            errorBytes = codec.internalFallback(null, new OutputCapExceededException());
            code = INTERNAL_ERROR;
            status = httpStatusFor(INTERNAL_ERROR);
            errorType = McpErrorType.INTERNAL;
        }
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
        write(context, status, errorBytes, terminal);
    }

    /**
     * The shared JSON-RPC-code-to-HTTP-status mapping. {@code INVALID_PARAMS} ({@code -32602}) maps to
     * {@code 400} here — once, in this one shared factory — so a denied tool, an unknown tool, and an
     * invalid cursor all resolve the same status alongside the same response body (issue #420);
     * splitting the mapping per call site would reinstate an existence oracle.
     */
    private static int httpStatusFor(int protocolCode) {
        return switch (protocolCode) {
            case METHOD_NOT_FOUND -> 404;
            case PARSE_ERROR, INVALID_REQUEST -> 400;
            case McpPolicyEnforcer.UNKNOWN_OR_UNAUTHORIZED_CODE -> 400;
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

    // T013 TP-002: package-private (not private) so McpWritePhaseSettlementTest can drive this exact
    // write path directly, stubbing HttpServerResponse#end(...) to return a Promise-backed future the
    // test owns and completes explicitly — no socket, no timing dependency. This is the only visibility
    // change; the write orchestration itself (beginWrite before the byte write, finishWrite after) is
    // unchanged from the T004/P03 behavior.
    static void write(RoutingContext context, int status, @Nullable byte[] body, McpRequestTerminalEvent terminal) {
        McpCompletionCoordinator coordinator = context.get(COMPLETION_COORDINATOR_KEY);
        // Logical settlement precedes the byte write: beginWrite publishes the terminal and claims
        // the shared first-observed latch. If a settlement (disconnect or reset) already won, the
        // client-visible write is superseded and must be suppressed — otherwise a slow handler's late
        // write would reach a client the shared HttpConfig liveness timeout already abandoned.
        // The admission-rejection path (W4) has no coordinator and writes directly with no
        // terminal/observation. A slow client (e.g. a stopped TCP receive window) can leave this
        // end(buffer) future pending indefinitely, so the write phase CAN stall. That is not left
        // unbounded: the disconnect/exception settlement hooks registered in registerSettlementHooks
        // reach the coordinator on the same request-owning context, and McpCompletionCoordinator's
        // completeOnContext drives finishWrite from there when it finds settlement already claimed by
        // this beginWrite — so a stalled end() cannot strand the request past the shared HttpConfig
        // liveness bound that eventually closes the connection.
        if (coordinator != null && !coordinator.beginWrite(terminal)) {
            return;
        }
        context.response().setStatusCode(status);
        Handler<AsyncResult<Void>> onEnd = result -> {
            if (coordinator != null) {
                coordinator.finishWrite(
                        result.succeeded() ? McpTransportOutcome.WRITTEN : McpTransportOutcome.WRITE_FAILED,
                        // The completion records the response's actual commit state, not the end()
                        // success flag: a write can fail after the head was already committed.
                        context.response().headWritten(),
                        Instant.now());
            }
        };
        if (body == null) {
            context.response().end().onComplete(onEnd);
            return;
        }
        // end(body) sets Content-Length, so the response is framed by length instead of relying on
        // connection-close framing the way a separate write() + end() pair does.
        context.response().end(Buffer.buffer(body)).onComplete(onEnd);
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
