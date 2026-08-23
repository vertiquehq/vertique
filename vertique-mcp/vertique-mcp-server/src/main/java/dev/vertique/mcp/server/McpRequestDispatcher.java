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
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches the bounded discovery endpoint over the hardened stateless HTTP contract (§4.7).
 *
 * <p>The dispatcher wires the framework-owned strict codec onto the live request path, enforces the
 * method/origin/content-type/accept admission checks, registers the disconnect/reset settlement seam,
 * and bounds the response write at {@code mcp.output.maxBytes}. MCP arms no whole-request deadline of
 * its own: transport liveness is meant to come from the shared {@link HttpConfig} idle/read/write
 * timeouts, so an idle or slow connection is closed by the shared HTTP layer and reaches this
 * dispatcher through the ordinary disconnect/reset settlement path (T007) — <strong>but only when at
 * least one of those three timeouts is actually armed</strong>. Every one of them defaults to
 * {@code 0} ("disabled"), so this bound is not automatic: {@link McpServerConfigValidator} refuses to
 * start an enabled mount unless at least one is nonzero, and that startup gate — not the default
 * configuration — is what makes this dispatcher's liveness claim true for every mount that actually
 * runs.
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
 * Validation), signalling a rejection there through {@link McpInputRejectionException}. Every complete
 * result (T020) is then normalized exactly once, bounded at {@code mcp.output.maxBytes} as bytes are
 * produced, validated against the tool's advertised output schema before exposure, offered to the
 * opt-in {@code onToolOutput} observation only once validation passes, and only then handed to the
 * single terminal writer — contract §4.7 stage 7's fixed order.
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
     * The bounded JSON-RPC server-error-range code (§4.7 "request-interceptor rejection ... return
     * JSON") a pre-dispatch {@link McpRequestInterceptor} rejection settles as (T016). Reserved
     * strictly for this stage, distinct from the protocol (-3270x/-3260x) and tool-authorization
     * (-32602) codes above.
     */
    private static final int INTERCEPTOR_REJECTED = -32001;

    /**
     * The standard, non-leaking message paired with {@link #INTERCEPTOR_REJECTED}: no interceptor
     * class name, reason, or exception text ever reaches the wire (contract §4.4 — "no exception
     * message").
     */
    private static final String INTERCEPTOR_REJECTED_MESSAGE = "Request rejected";

    /**
     * The bounded, non-leaking text returned as the sole content item of a post-validation
     * {@link McpToolInterceptor} rejection (T017, contract §4.4). SSE was already selected before
     * this stage runs (invocation always follows {@link #selectSse}), so — like a stage 1 schema
     * rejection and a stage 2–4 {@link McpInputRejectionException} — this settles as a bounded
     * text-only {@code isError=true} tool result through {@link #writeToolResult}, never a JSON-RPC
     * protocol error. Carries no interceptor class name, reason, or exception text.
     */
    private static final String TOOL_INTERCEPTOR_REJECTED_MESSAGE = "Tool call rejected";

    /**
     * The canonical anonymous {@link SecurityContext} — {@code PrincipalType.ANONYMOUS} actor,
     * {@code none} authentication method, empty claims — synthesized by {@link
     * #establishedSecurityContext()} when no context is yet bound, matching {@code
     * McpIdentityEstablisher}'s own anonymous-binding shape for an unconfigured scheme.
     */
    private static final SecurityContext CANONICAL_ANONYMOUS =
            SecurityContexts.unauthenticated(SecurityIdentity.anonymous());

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
     * The bounded, non-leaking text returned as the sole content item when {@code tools/call}'s
     * {@code arguments} member is present and non-null but not a JSON object (e.g. an array, string,
     * or number). Such a value is rejected outright rather than silently coerced to the empty map:
     * coercing it would let a zero-argument tool execute from a schema-invalid call, materializing a
     * result from an input the client never actually sent (§4.7). An absent {@code arguments} member,
     * or an explicit JSON {@code null}, is unaffected and still normalizes to {@code {}} through
     * {@link #argumentsOf} — only a present non-null non-object value is rejected here.
     */
    private static final String ARGUMENTS_TYPE_REJECTION_MESSAGE =
            "Invalid tool arguments: arguments must be an object";

    /**
     * The hard per-page examination cap (§4.7): a page examines at most this many multiples of
     * {@code mcp.tools.pageSize} candidates, bounding the authorization fan-out an unauthenticated
     * {@code tools/list} scan can trigger (issue #416).
     */
    private static final int EXAMINATION_BUDGET_MULTIPLIER = 4;

    /**
     * The synthetic {@link McpAccessMode#DENY_ALL} descriptor evaluated for an unresolved {@code
     * tools/call} name (issue #420 residual — existence-oracle-by-timing). A known-but-denied name
     * reaches {@link McpPolicyEnforcer#decide} before its response is written; short-circuiting an
     * unknown name straight to {@link #writeUnknownOrUnauthorized} without ever reaching that same
     * decision point leaves the two paths' latency measurably different (milliseconds against an
     * application-supplied async PDP), which restores the very existence oracle the shared
     * {@code -32602} response exists to close. Evaluating this placeholder for every unresolved name
     * routes both paths through the identical asynchronous decision point instead. Never registered,
     * never listed, never actually reachable — its one and only role is to be denied.
     */
    private static final McpToolDescriptor UNKNOWN_TOOL_PLACEHOLDER_DESCRIPTOR = new McpToolDescriptor(
            McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
            null,
            "Synthetic placeholder evaluated for an unresolved tools/call name; never registered.",
            new McpToolAnnotations(true, false, true, false),
            "{}",
            null,
            new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null));

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
    private final List<McpRequestInterceptor> orderedRequestInterceptors;
    private final List<McpToolInterceptor> orderedToolInterceptors;
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
            Set<McpRequestInterceptor> requestInterceptors,
            Set<McpToolInterceptor> toolInterceptors,
            HttpConfig httpConfig,
            McpToolRegistry toolRegistry,
            McpPolicyEnforcer policyEnforcer) {
        this.config = config;
        this.securityRuntime = securityRuntime;
        this.lifecycleObservers = Set.copyOf(lifecycleObservers);
        this.completedListeners = Set.copyOf(completedListeners);
        this.orderedRequestInterceptors = sortedAndValidatedRequestInterceptors(requestInterceptors);
        this.orderedToolInterceptors = sortedAndValidatedToolInterceptors(toolInterceptors);
        this.codec = new McpProtocolCodec(httpConfig);
        this.toolRegistry = toolRegistry;
        this.policyEnforcer = policyEnforcer;
        this.cursorCodec = new McpCursorCodec();
    }

    /**
     * Sorts {@code interceptors} by {@link OrderedExtension#comparator()} — phase, then priority,
     * then {@code orderKey} — and validates that no two share the same {@code (phase, priority,
     * orderKey)} triple (contract §4.4). Ordering never falls back to Dagger set iteration: this is
     * the one place that establishes it, at composition time, before any request reaches {@link
     * #runRequestInterceptors}.
     *
     * @param interceptors the contributed request-interceptor set; must not be {@code null}
     * @return an immutable, ordered list of the interceptors
     * @throws IllegalStateException if two interceptors share the same {@code (phase, priority,
     *     orderKey)} triple, naming both conflicting classes
     */
    private static List<McpRequestInterceptor> sortedAndValidatedRequestInterceptors(
            Set<McpRequestInterceptor> interceptors) {
        List<McpRequestInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(OrderedExtension.comparator());
        record OrderKey(ExtensionPhase phase, int priority, String orderKey) {}
        Map<OrderKey, McpRequestInterceptor> byOrderKey = new LinkedHashMap<>();
        for (McpRequestInterceptor interceptor : sorted) {
            OrderKey key = new OrderKey(interceptor.phase(), interceptor.priority(), interceptor.orderKey());
            McpRequestInterceptor existing = byOrderKey.putIfAbsent(key, interceptor);
            if (existing != null) {
                throw new IllegalStateException("Duplicate McpRequestInterceptor (phase="
                        + key.phase()
                        + ", priority="
                        + key.priority()
                        + ", orderKey="
                        + key.orderKey()
                        + ") between "
                        + existing.getClass().getName()
                        + " and "
                        + interceptor.getClass().getName());
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * Sorts {@code interceptors} by {@link OrderedExtension#comparator()} — phase, then priority,
     * then {@code orderKey} — and validates that no two share the same {@code (phase, priority,
     * orderKey)} triple (contract §4.4), reusing exactly the same ordering and duplicate-key
     * validation {@link #sortedAndValidatedRequestInterceptors} establishes for the sibling T016
     * stage. Ordering never falls back to Dagger set iteration: this is the one place that
     * establishes it, at composition time, before any request reaches {@link #runToolInterceptors}.
     *
     * @param interceptors the contributed tool-interceptor set; must not be {@code null}
     * @return an immutable, ordered list of the interceptors
     * @throws IllegalStateException if two interceptors share the same {@code (phase, priority,
     *     orderKey)} triple, naming both conflicting classes
     */
    private static List<McpToolInterceptor> sortedAndValidatedToolInterceptors(Set<McpToolInterceptor> interceptors) {
        List<McpToolInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(OrderedExtension.comparator());
        record OrderKey(ExtensionPhase phase, int priority, String orderKey) {}
        Map<OrderKey, McpToolInterceptor> byOrderKey = new LinkedHashMap<>();
        for (McpToolInterceptor interceptor : sorted) {
            OrderKey key = new OrderKey(interceptor.phase(), interceptor.priority(), interceptor.orderKey());
            McpToolInterceptor existing = byOrderKey.putIfAbsent(key, interceptor);
            if (existing != null) {
                throw new IllegalStateException("Duplicate McpToolInterceptor (phase="
                        + key.phase()
                        + ", priority="
                        + key.priority()
                        + ", orderKey="
                        + key.orderKey()
                        + ") between "
                        + existing.getClass().getName()
                        + " and "
                        + interceptor.getClass().getName());
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * Applies the cheap HTTP admission checks (§4.7 stage 1) — method, {@code Origin}, {@code
     * Content-Type}, {@code Accept} — before any request body is read.
     *
     * <p>Mounted by {@link McpRouterMount} strictly <em>before</em> {@code BodyHandler}: every check
     * here inspects only the request line and headers, never {@code context.body()}, so a request that
     * fails admission is rejected without the framework ever aggregating its body — up to {@code
     * mcp.output.maxBytes}/{@code httpConfig.maxBodySize()} of aggregation work a disallowed Origin,
     * method, or media type would otherwise force before its 403/405/415, inverting the cheap-
     * admission-first contract this method restores. {@link #begin}, which constructs the completion
     * coordinator and registers the disconnect/reset settlement hooks, still runs after {@code
     * BodyHandler} for every request this method admits — splitting the two never changes when
     * lifecycle observation opens for an admitted request, only when a doomed one is rejected.
     *
     * <p>Only POST is accepted; GET/DELETE and any other method are HTTP 405. A present {@code Origin}
     * is HTTP 403 unless it is literally contained in {@code mcp.allowedOrigins} — an empty (default)
     * allowlist therefore denies every present {@code Origin} rather than imposing no restriction, so a
     * locally bound, unconfigured MCP server is not reachable from an arbitrary browser page or a
     * DNS-rebound name (the MCP HTTP transport spec requires Origin validation for exactly this reason);
     * an absent {@code Origin} (every non-browser client) is unrestricted. Every admitted method is
     * POST, which this protocol always carries a required JSON body on, so {@code Content-Type} is
     * mandatory — absent or non-{@code application/json} is both HTTP 415 — closing the CORS
     * simple-request path an absent-content-type admission would otherwise reopen ({@code Blob} with an
     * empty type, {@code navigator.sendBeacon}). A present {@code Accept} that admits none of {@code
     * application/json}, {@code text/event-stream}, {@code application/*}, or {@code *&#47;*} is HTTP
     * 406; an absent {@code Accept} imposes no restriction. Only once every check passes does the
     * request continue toward {@code BodyHandler} and, eventually, {@link #begin}.
     */
    void admitCheap(RoutingContext context) {
        Instant startedAt = Instant.now();
        context.put(STARTED_AT_KEY, startedAt);
        // Every admission check runs before the coordinator exists, so a rejection here opens no
        // lifecycle observation — the reject path null-guards the (absent) coordinator and timer.
        if (context.request().method() != HttpMethod.POST) {
            reject(context, McpMethod.OTHER, McpErrorType.HTTP, 405, null);
            return;
        }
        String origin = context.request().getHeader("Origin");
        // Deny-by-default: a present Origin must be literally contained in the allowlist. An empty
        // (unconfigured) allowlist therefore rejects every present Origin instead of admitting it —
        // the previous permissive default left a locally bound anonymous MCP server reachable from any
        // page a user's browser visited.
        if (origin != null && !config.allowedOrigins().contains(origin)) {
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
        context.next();
    }

    /**
     * Constructs the request's completion coordinator and registers its disconnect/reset settlement
     * hooks (§4.7 stage 2), for a request that already passed {@link #admitCheap}'s cheap admission
     * checks and {@code BodyHandler}'s body aggregation.
     *
     * <p>Opens the request's lifecycle observation: every observer and completed-listener call for this
     * request is scoped to the coordinator constructed here, so — like the cheap-admission rejections
     * above it — nothing before this point (a disallowed method/Origin/Content-Type/Accept, or a
     * body-limit rejection) ever produces a lifecycle observation.
     */
    void begin(RoutingContext context) {
        Instant startedAt = startedAt(context);
        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context.vertx().getOrCreateContext(), lifecycleObservers, completedListeners, startedAt);
        context.put(COMPLETION_COORDINATOR_KEY, coordinator);
        registerSettlementHooks(context, coordinator, startedAt);
        context.next();
    }

    /**
     * Enforces the mandatory {@code Content-Type} admission check: every admitted request is a POST
     * carrying the protocol's required JSON-RPC body, so an absent header is HTTP 415 exactly like a
     * present one whose media type (parameters such as {@code ; charset=utf-8} stripped,
     * case-insensitive) is not {@code application/json}. Admitting an absent {@code Content-Type} would
     * reopen the CORS simple-request path — a cross-origin {@code Blob} with an empty type, or {@code
     * navigator.sendBeacon}, both send no {@code Content-Type} — which is exactly what a browser's CORS
     * preflight exists to gate.
     *
     * @param context the request whose {@code Content-Type} header is inspected
     * @return {@code true} when the request may proceed, {@code false} when it is HTTP 415
     */
    private static boolean contentTypeAdmitted(RoutingContext context) {
        String contentType = context.request().getHeader("Content-Type");
        if (contentType == null) {
            return false;
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
     *
     * <p>Only once the envelope validates does this method run the ordered, fail-closed pre-dispatch
     * request-interceptor stage (T016, contract §4.7 stage 5) — after envelope decode, before method
     * dispatch, before any tool is resolved, and before any argument is processed. A permitted request
     * continues to {@link #dispatchByMethod}; a rejection never reaches it.
     */
    void dispatch(RoutingContext context) {
        SecurityContextSnapshot security = establishedSecurity();
        byte[] body = bodyBytes(context);
        McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(body);
        if (decoded.isError()) {
            emitProtocolError(context, decoded, security);
            return;
        }
        JsonNode envelope = decoded.envelope();
        McpMethod method = classifyMethod(envelope.get("method").asText());
        McpRequestContext requestContext = new McpRequestContext(method, establishedSecurityContext(), null, null);
        runRequestInterceptors(0, requestContext).onComplete(ar -> {
            if (ar.failed()) {
                writeInterceptorRejection(context, envelope, method, security);
                return;
            }
            // dispatchByMethod's own write*() methods are the ones that actually schedule work and
            // settle the request; a RuntimeException or StackOverflowError escaping synchronously from
            // here — before any of them ever calls beginWrite — would otherwise strand the request with
            // no response, no terminal, and no completion (see the stage-7 guard in invokeAndRespond for
            // the same class of risk on the tools/call path).
            try {
                dispatchByMethod(context, envelope, method, security);
            } catch (RuntimeException | StackOverflowError dispatchFailure) {
                writeDispatchByMethodFailure(context, envelope, method, security, dispatchFailure);
            }
        });
    }

    /**
     * Resolves the caller {@link SecurityContext} for {@link McpRequestContext}, which the frozen
     * contract requires to always be non-null: a bound identity is used as-is, and the canonical
     * anonymous context ({@code PrincipalType.ANONYMOUS}, authentication method {@code none}, empty
     * claims) is synthesized when none is bound — exactly the same anonymous shape {@code
     * McpIdentityEstablisher} binds for an unconfigured scheme, so an interceptor never distinguishes
     * "no context bound yet" from "genuinely anonymous."
     *
     * @return the caller's established security context, or the canonical anonymous context; never
     *     {@code null}
     */
    private SecurityContext establishedSecurityContext() {
        SecurityContext current = securityRuntime.current();
        return current != null ? current : CANONICAL_ANONYMOUS;
    }

    /**
     * Classifies a decoded envelope's {@code method} string into its recognized {@link McpMethod}.
     *
     * <p>{@link McpProtocolCodec#decodeEnvelope} already restricts a non-error decode's {@code method}
     * to the bounded supported set, so every branch below except the defensive default is reachable
     * here; {@link McpMethod#OTHER} exists for {@link #emitProtocolError}'s classification, not this
     * one.
     *
     * @param method the decoded envelope's {@code method} string
     * @return the recognized method class
     */
    private static McpMethod classifyMethod(String method) {
        if (DISCOVER_METHOD.equals(method)) {
            return McpMethod.SERVER_DISCOVER;
        }
        if (TOOLS_LIST_METHOD.equals(method)) {
            return McpMethod.TOOLS_LIST;
        }
        if (TOOLS_CALL_METHOD.equals(method)) {
            return McpMethod.TOOLS_CALL;
        }
        return McpMethod.OTHER;
    }

    /**
     * Dispatches one interceptor-permitted, envelope-validated request to its method-specific handler.
     *
     * @param context the request context
     * @param envelope the validated request envelope
     * @param method the classified method, as {@link #classifyMethod} produced it for this same
     *     envelope
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void dispatchByMethod(
            RoutingContext context, JsonNode envelope, McpMethod method, @Nullable SecurityContextSnapshot security) {
        switch (method) {
            case SERVER_DISCOVER:
                writeDiscovery(context, envelope, security);
                return;
            case TOOLS_LIST:
                writeToolsList(context, envelope, security);
                return;
            case TOOLS_CALL:
                writeToolsCall(context, envelope, security);
                return;
            default:
                // Unreachable in practice (see classifyMethod): decodeEnvelope already restricts a
                // non-error decode's method to the three cases above. Kept as a defensive fallback,
                // through the same bounded internal-error shape every other defensive fallback in this
                // class uses, rather than an uncaught exception.
                writeDispatchFallback(context, envelope, security);
        }
    }

    /**
     * Settles the defensive, practically-unreachable {@link #dispatchByMethod} default branch through
     * the bounded internal-error fallback, exactly like {@link #writeToolsListFallback}.
     */
    private void writeDispatchFallback(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        byte[] fallback = codec.internalFallback(
                envelope.get("id"), new IllegalStateException("Unrecognized method reached dispatchByMethod"));
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.OTHER,
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
     * Settles a {@code RuntimeException} or {@code StackOverflowError} that escapes synchronously from
     * {@link #dispatchByMethod} — before any of its {@code write*} methods ever called {@code
     * beginWrite} — through the same bounded, non-leaking internal-error fallback every other defensive
     * fallback in this class uses, so the request settles instead of being permanently stranded.
     *
     * @param context the request context
     * @param envelope the validated request envelope whose id is echoed when it fits the cap
     * @param method the classified method the failure occurred while dispatching, recorded on the
     *     terminal event
     * @param security the established security snapshot, recorded on the terminal event
     * @param cause the failure; never read for its message, only its occurrence matters
     */
    private void writeDispatchByMethodFailure(
            RoutingContext context,
            JsonNode envelope,
            McpMethod method,
            @Nullable SecurityContextSnapshot security,
            Throwable cause) {
        byte[] fallback = codec.internalFallback(envelope.get("id"), cause);
        if (fallback.length > config.outputMaxBytes()) {
            fallback = codec.internalFallback(null, cause);
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                method,
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
     * Runs the ordered pre-dispatch request-interceptor chain sequentially from {@code index},
     * short-circuiting at the first rejection (contract §4.4): a synchronous
     * {@link McpRequestInterceptor#beforeRequest} throw, a {@code null} returned future, and a future
     * that resolves failed all fail closed the same way — the next interceptor is never invoked and
     * dispatch never runs. An empty ordered chain (the zero-interceptor composition) succeeds
     * immediately.
     *
     * @param index the next interceptor to run, in {@link #orderedRequestInterceptors} order
     * @param requestContext the immutable, payload-free pre-dispatch snapshot every interceptor in the
     *     chain observes
     * @return a future that succeeds once every interceptor has permitted, or fails with the first
     *     rejection's cause
     */
    private Future<Void> runRequestInterceptors(int index, McpRequestContext requestContext) {
        if (index >= orderedRequestInterceptors.size()) {
            return Future.succeededFuture();
        }
        McpRequestInterceptor interceptor = orderedRequestInterceptors.get(index);
        Future<Void> outcome;
        try {
            outcome = interceptor.beforeRequest(requestContext);
        } catch (RuntimeException thrown) {
            return Future.failedFuture(thrown);
        }
        if (outcome == null) {
            return Future.failedFuture(
                    new NullPointerException(interceptor.getClass().getName() + "#beforeRequest returned null"));
        }
        return outcome.compose(ignored -> runRequestInterceptors(index + 1, requestContext));
    }

    /**
     * Runs the ordered post-validation tool-interceptor chain sequentially from {@code index},
     * short-circuiting at the first rejection (T017, contract §4.4): a synchronous
     * {@link McpToolInterceptor#beforeInvocation} throw, a {@code null} returned future, and a future
     * that resolves failed all fail closed the same way — the next interceptor is never invoked and
     * the generated invocation never runs. An empty ordered chain (the zero-interceptor composition)
     * succeeds immediately. Mirrors {@link #runRequestInterceptors} exactly, reusing the same ordering
     * and fail-closed semantics for this second, later stage.
     *
     * @param index the next interceptor to run, in {@link #orderedToolInterceptors} order
     * @param toolContext the immutable, argument-free descriptor snapshot every interceptor in the
     *     chain observes
     * @return a future that succeeds once every interceptor has permitted, or fails with the first
     *     rejection's cause
     */
    private Future<Void> runToolInterceptors(int index, McpToolInvocationContext toolContext) {
        if (index >= orderedToolInterceptors.size()) {
            return Future.succeededFuture();
        }
        McpToolInterceptor interceptor = orderedToolInterceptors.get(index);
        Future<Void> outcome;
        try {
            outcome = interceptor.beforeInvocation(toolContext);
        } catch (RuntimeException thrown) {
            return Future.failedFuture(thrown);
        }
        if (outcome == null) {
            return Future.failedFuture(
                    new NullPointerException(interceptor.getClass().getName() + "#beforeInvocation returned null"));
        }
        return outcome.compose(ignored -> runToolInterceptors(index + 1, toolContext));
    }

    /**
     * Writes the bounded, non-leaking JSON-RPC error response for a pre-dispatch interceptor
     * rejection (contract §4.7 — "request-interceptor rejection ... return JSON"). Carries no
     * interceptor class name, reason, or exception text — only the fixed
     * {@link #INTERCEPTOR_REJECTED}/{@link #INTERCEPTOR_REJECTED_MESSAGE} pair, exactly like
     * {@link McpPolicyEnforcer#unknownOrUnauthorizedError()} does for its own stage.
     *
     * @param context the request context
     * @param envelope the validated envelope whose id is echoed
     * @param method the classified method, recorded on the terminal event
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void writeInterceptorRejection(
            RoutingContext context, JsonNode envelope, McpMethod method, @Nullable SecurityContextSnapshot security) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        JsonNode id = envelope.get("id");
        response.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode errorNode = OUTPUT_ENCODER.createObjectNode();
        errorNode.put("code", INTERCEPTOR_REJECTED);
        errorNode.put("message", INTERCEPTOR_REJECTED_MESSAGE);
        response.set("error", errorNode);
        int status = httpStatusFor(INTERCEPTOR_REJECTED);
        byte[] responseBytes;
        try {
            // The echoed id is client-controlled (up to the envelope codec's bounded maxStringLength),
            // so — exactly like every other terminal writer in this class — this response is bounded at
            // mcp.output.maxBytes through encodeCapped rather than the unbounded codec.encode, with the
            // same degrade-to-id-less fallback below when even that cannot fit.
            responseBytes = encodeCapped(response);
        } catch (OutputCapExceededException overCap) {
            byte[] fallback = codec.internalFallback(envelope.get("id"), overCap);
            if (fallback.length > config.outputMaxBytes()) {
                fallback = codec.internalFallback(null, overCap);
            }
            McpRequestTerminalEvent overCapTerminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    method,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    null,
                    security,
                    null);
            write(context, 500, fallback, overCapTerminal);
            return;
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.rejected(
                startedAt(context),
                Instant.now(),
                method,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.INTERCEPTOR,
                status,
                INTERCEPTOR_REJECTED,
                null,
                security,
                null);
        write(context, status, responseBytes, terminal);
    }

    /**
     * Writes the discovery result, bounding serialization at {@code mcp.output.maxBytes} as bytes are
     * produced. An over-cap response is classified as a bounded internal error and never emitted.
     */
    private void writeDiscovery(RoutingContext context, JsonNode envelope, SecurityContextSnapshot security) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        putIdentityFilteredCacheHeaders(context);
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
     * Adds the response headers a per-identity filtered result requires: the body itself already
     * advertises {@code cacheScope: private} with a bounded {@code ttlMs} in its JSON payload, but
     * without an HTTP-level cache directive a shared cache (a CDN, a corporate proxy, a browser's
     * disk cache) has no signal not to store or replay one caller's filtered discovery or tool listing
     * for another. {@code Cache-Control: private, no-store} forbids shared-cache storage outright, and
     * {@code Vary: Authorization} additionally tells any cache that does key on the caller that
     * authorization is part of the cache key.
     *
     * @param context the request whose response headers are set; must not have started writing yet
     */
    private static void putIdentityFilteredCacheHeaders(RoutingContext context) {
        context.response().putHeader("Cache-Control", "private, no-store");
        context.response().putHeader("Vary", "Authorization");
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
        // establishedSecurityContext() (never null) rather than the raw securityRuntime.current():
        // McpPolicyEnforcer#decide null-checks its caller and would throw for the null the raw runtime
        // value can carry.
        SecurityContext caller = establishedSecurityContext();
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
     *
     * <p>Deliberately iterative, not recursive. Vert.x 5.1.6 documents no trampolining or
     * stack-safety guarantee for {@link Future#compose} on an already-completed future, and a
     * synchronous {@link McpPolicyEnforcer#decide} decision (e.g. {@code PermitAll}) resolves exactly
     * that way — so a per-candidate recursive call chained through {@code compose} would be genuine
     * native recursion up to {@code budget} (2,000) stack frames deep. This loop instead advances
     * in-place on the current stack frame whenever a decision is already resolved ({@link
     * Future#isComplete()}), and only ever calls back into itself — via {@code compose}, resuming on a
     * fresh callback stack frame posted through the event loop — the one time a decision is genuinely
     * still pending. At the maximum budget with every decision completing immediately (the case that
     * would otherwise recurse), this method never grows the call stack past its own single frame.
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
        List<McpToolDescriptor> currentVisible = visible;
        int currentIndex = index;
        int currentExamined = examined;
        String currentLastExaminedName = lastExaminedName;
        while (true) {
            if (currentVisible.size() >= pageSize || currentExamined >= budget || currentIndex >= names.size()) {
                boolean candidatesRemain = currentIndex < names.size();
                return Future.succeededFuture(new ScanResult(
                        currentVisible, candidatesRemain ? currentLastExaminedName : null, currentExamined));
            }
            String name = names.get(currentIndex);
            McpToolDescriptor descriptor = toolRegistry.descriptorsByName().get(name);
            var decisionFuture = policyEnforcer.decide(descriptor, caller);
            if (!decisionFuture.isComplete()) {
                // Genuinely asynchronous: resume through compose, on a fresh stack frame, instead of
                // looping here — looping would spin-wait on a future that is not yet resolved.
                List<McpToolDescriptor> visibleSnapshot = currentVisible;
                int examinedSnapshot = currentExamined;
                int indexSnapshot = currentIndex;
                return decisionFuture.compose(decision -> {
                    List<McpToolDescriptor> updated = visibleSnapshot;
                    if (decision.permitted()) {
                        updated = new ArrayList<>(visibleSnapshot);
                        updated.add(descriptor);
                    }
                    int updatedExamined = examinedSnapshot + 1;
                    if (gateTimedOut(decision)) {
                        // Stop scanning (issue #417): the gate SecurityPolicyEnforcer#decide just
                        // bounded already paid its deadline once; continuing would pay it again for
                        // every remaining candidate — the exact per-candidate amplification a shared
                        // decision-gate deadline must not reintroduce. One timeout ends this page here,
                        // with the timed-out candidate itself as the next-page anchor.
                        boolean candidatesRemain = indexSnapshot + 1 < names.size();
                        return Future.succeededFuture(
                                new ScanResult(updated, candidatesRemain ? name : null, updatedExamined));
                    }
                    return scan(names, indexSnapshot + 1, pageSize, budget, updatedExamined, updated, name, caller);
                });
            }
            if (decisionFuture.failed()) {
                return Future.failedFuture(decisionFuture.cause());
            }
            AuthorizationDecision decision = decisionFuture.result();
            if (decision.permitted()) {
                List<McpToolDescriptor> updated = new ArrayList<>(currentVisible);
                updated.add(descriptor);
                currentVisible = updated;
            }
            currentLastExaminedName = name;
            currentExamined = currentExamined + 1;
            currentIndex = currentIndex + 1;
            if (gateTimedOut(decision)) {
                // Defensive mirror of the async stop above. In practice a genuine gate timeout is
                // scheduled by Future#timeout on a later event-loop tick, so it is never observed
                // through this already-complete synchronous branch — but stopping here too means this
                // method's termination guarantee does not depend on that scheduling detail.
                boolean candidatesRemain = currentIndex < names.size();
                return Future.succeededFuture(new ScanResult(
                        currentVisible, candidatesRemain ? currentLastExaminedName : null, currentExamined));
            }
        }
    }

    /**
     * Reports whether {@code decision} is the specific fail-closed shape {@link
     * SecurityPolicyEnforcer#decide} produces when a gate future missed the shared decision deadline
     * (issue #417), as opposed to any other deny (including a different fail-closed cause).
     */
    private static boolean gateTimedOut(AuthorizationDecision decision) {
        return Boolean.TRUE.equals(decision.safeAttributes().get(SecurityPolicyEnforcer.GATE_TIMEOUT_ATTRIBUTE));
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
        putIdentityFilteredCacheHeaders(context);
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
        int status = httpStatusFor(error.code());
        byte[] body;
        try {
            // The echoed id is client-controlled (up to the envelope codec's bounded maxStringLength),
            // so — exactly like every other terminal writer in this class — this response is bounded at
            // mcp.output.maxBytes through encodeCapped rather than the unbounded codec.encode, with the
            // same degrade-to-id-less fallback below when even that cannot fit.
            body = encodeCapped(response);
        } catch (OutputCapExceededException overCap) {
            byte[] fallback = codec.internalFallback(envelope.get("id"), overCap);
            if (fallback.length > config.outputMaxBytes()) {
                fallback = codec.internalFallback(null, overCap);
            }
            McpRequestTerminalEvent overCapTerminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    method,
                    method == McpMethod.TOOLS_CALL ? toolName : McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    null,
                    security,
                    null);
            write(context, 500, fallback, overCapTerminal);
            return;
        }
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
     * <p>An unresolved name is never short-circuited straight to that response: it is first evaluated
     * against {@link #UNKNOWN_TOOL_PLACEHOLDER_DESCRIPTOR} through the same {@link
     * McpPolicyEnforcer#decide} decision point a known-but-denied name reaches, so the two paths carry
     * the same asynchronous latency shape and cannot be distinguished by timing. The terminal event for
     * an unresolved name carries {@link McpRequestTerminalEvent#UNKNOWN_TOOL_NAME}, never the
     * caller-supplied string: an unresolved name touches no real {@link McpToolDescriptor}, so nothing
     * bounds it except the wire's own 20,000,000-char string limit, and it would otherwise reach every
     * lifecycle observer and listener verbatim.
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
        // establishedSecurityContext() (never null) rather than the raw securityRuntime.current():
        // McpPolicyEnforcer#decide null-checks its caller and would throw for the null the raw runtime
        // value can carry.
        SecurityContext caller = establishedSecurityContext();
        if (invoker == null) {
            policyEnforcer
                    .decide(UNKNOWN_TOOL_PLACEHOLDER_DESCRIPTOR, caller)
                    .onComplete(ar -> writeUnknownOrUnauthorized(
                            context,
                            envelope,
                            security,
                            McpMethod.TOOLS_CALL,
                            McpRequestTerminalEvent.UNKNOWN_TOOL_NAME));
            return;
        }
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
     * absent or explicit-{@code null} {@code arguments} member; a present member that is neither
     * absent/null nor a JSON object (an array, string, number, or boolean) is rejected outright — see
     * {@link #isMalformedArguments} — rather than silently coerced to the empty map, so a zero-argument
     * tool never executes from a schema-invalid call.
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
        if (isMalformedArguments(envelope)) {
            // A present, non-null, non-object arguments member (e.g. [], "x", 3) is rejected outright
            // rather than coerced to {}: coercion would let a zero-argument tool execute from a
            // schema-invalid call. Settles exactly like a stage-1 schema rejection — bounded text-only
            // isError=true — and never calls prepare().
            writeToolResult(
                    context,
                    envelope,
                    security,
                    toolName,
                    McpToolResult.error(ARGUMENTS_TYPE_REJECTION_MESSAGE),
                    null,
                    McpErrorType.INPUT_VALIDATION);
            return;
        }
        Map<String, Object> arguments = argumentsOf(envelope);
        if (!schemaValid(toolName, arguments)) {
            writeToolResult(
                    context,
                    envelope,
                    security,
                    toolName,
                    McpToolResult.error(SCHEMA_REJECTION_MESSAGE),
                    null,
                    McpErrorType.INPUT_VALIDATION);
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
            writeToolResult(
                    context,
                    envelope,
                    security,
                    toolName,
                    McpToolResult.error(rejected.getMessage()),
                    null,
                    McpErrorType.INPUT_PROCESSING);
            return;
        } catch (RuntimeException prepareFailure) {
            writeSseFallback(context, envelope, security, toolName, prepareFailure);
            return;
        }
        // T017: the ordered, fail-closed post-validation tool-interceptor stage runs here — strictly
        // after Bean Validation (prepare() above already ran it) and strictly before the generated
        // invocation (prepared.invoke() below). The context it exposes projects only the pre-dispatch
        // McpRequestContext and the resolved descriptor: no raw or normalized argument ever reaches an
        // interceptor. A rejection settles through the same bounded, SSE-framed writeToolResult path
        // stage 1 and stages 2-4 already use, and prepared.invoke() is never called.
        McpToolInvocationContext toolContext = new McpToolInvocationContext(
                new McpRequestContext(McpMethod.TOOLS_CALL, establishedSecurityContext(), null, null),
                invoker.descriptor());
        // T018: the opt-in, capability-gated value-observation callback fires here — after Bean
        // Validation (prepare() above already ran it) but strictly before the tool-interceptor stage
        // just below (contract §4.4 callback order). Delivered only to a session implementing
        // McpToolValueObservation; the coordinator retains no reference to the observation once every
        // onToolInput call has returned (McpCompletionCoordinator#publishToolInput). Gated on
        // hasValueObservers() BEFORE the observation is even constructed: McpToolInputObservation's
        // compact constructor deep-copies the entire normalized argument tree, so a request with no
        // capable session never pays that copy for an attacker-sized argument tree.
        if (coordinator != null && coordinator.hasValueObservers()) {
            // Security review (P05 finding #5): mirrors stage 7's guard below. Constructing the
            // observation deep-copies the whole normalized argument tree (McpToolInputObservation's
            // compact constructor), and every capable session's onToolInput callback runs
            // synchronously inside publishToolInput (e.g. an audit adapter's String.valueOf on a
            // nested Map/List value recurses natively). A pathologically deep or wide argument tree
            // — the envelope codec permits 1,000 levels of nesting — can therefore throw a
            // RuntimeException or drive a native-recursion StackOverflowError here, which would
            // otherwise escape before any response was ever begun: no response, no terminal, no
            // completion, permanently stranding the request (compounded by the fact that MCP relies
            // on the shared HttpConfig liveness bound, not a stage-local timer, to ever reclaim it).
            try {
                coordinator.publishToolInput(new McpToolInputObservation(toolContext, prepared.normalizedArguments()));
            } catch (RuntimeException | StackOverflowError stage5Failure) {
                writeSseFallback(context, envelope, security, toolName, stage5Failure);
                return;
            }
        }
        runToolInterceptors(0, toolContext).onComplete(interceptorResult -> {
            if (interceptorResult.failed()) {
                writeToolResult(
                        context,
                        envelope,
                        security,
                        toolName,
                        McpToolResult.error(TOOL_INTERCEPTOR_REJECTED_MESSAGE),
                        null,
                        McpErrorType.INTERCEPTOR);
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
                // T020: every complete result is normalized exactly once, bounded by
                // mcp.output.maxBytes as bytes are produced, validated against the advertised output
                // schema, offered to the opt-in output observation, and only then handed to the single
                // terminal writer (contract §4.7 stage 7). The raw application value is converted to
                // its bounded, JSON-compatible canonical shape here — once — and that exact same value
                // is reused below for schema validation, the observation callback, and the wire embed;
                // nothing downstream re-serializes the original application object.
                //
                // The whole stage runs inside this try: normalizeStructuredContent bounds serialization
                // size but can still throw for a pathologically shaped value (e.g. IllegalArgumentException
                // from a cyclic object graph Jackson cannot convert), and a deeply nested value can drive a
                // native-recursion StackOverflowError in code this stage calls. Either would otherwise
                // escape this lambda after beginWrite was never called — no response, no terminal, no
                // completion — permanently stranding the request (compounded by the fact that MCP relies on
                // the shared HttpConfig liveness bound, not a stage-local timer, to ever reclaim it). Both
                // degrade to the same bounded, SSE-framed internal-error fallback every other invocation
                // failure in this method already uses.
                McpToolResult<?> toolResult = ar.result();
                try {
                    Object normalizedOutput = normalizeStructuredContent(toolResult.structuredContent());
                    if (!outputSchemaValid(toolName, normalizedOutput)) {
                        // The schema-invalid value never reaches writeToolResult/encodeCapped: it is
                        // rejected here, before any wire byte is produced and before the output
                        // observation fires, so an invalid structured result never reaches the wire or a
                        // capable session.
                        writeOutputValidationFailure(context, envelope, security, toolName);
                        return;
                    }
                    // T020: the opt-in, capability-gated output-value callback fires here — strictly
                    // after bounded normalization and output-schema validation, strictly before the wire
                    // write below (contract §4.4 callback order). Delivered only to a session
                    // implementing McpToolValueObservation, exactly like publishToolInput above. Gated on
                    // hasValueObservers() BEFORE construction for the same reason: McpToolOutputObservation's
                    // compact constructor deep-copies the entire normalized result tree.
                    if (coordinator != null && coordinator.hasValueObservers()) {
                        coordinator.publishToolOutput(new McpToolOutputObservation(toolContext, normalizedOutput));
                    }
                    writeToolResult(
                            context, envelope, security, toolName, toolResult, normalizedOutput, McpErrorType.HANDLER);
                } catch (RuntimeException | StackOverflowError stage7Failure) {
                    writeSseFallback(context, envelope, security, toolName, stage7Failure);
                }
            });
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
     * Runs the T020 output stage's single normalization pass: converts an application handler's
     * structured result value to the one bounded, JSON-compatible canonical shape ({@code Map}/
     * {@code List}/scalar) reused for output-schema validation, the {@code onToolOutput} observation,
     * and the wire embed.
     *
     * <p>Called exactly once per completed invocation, from {@link #invokeAndRespond}'s {@code
     * result.onComplete} handler, before validation, observation, or encoding ever run. Nothing else in
     * this class converts a handler's raw structured value a second time: {@link #writeToolResult} and
     * {@link #toolCallResponse} accept and reuse the already-normalized value.
     *
     * <p><strong>Not independently bounded.</strong> Contract §4.3 describes two halves for the output
     * cap to enforce: the normalization of an application structured value, and the complete terminal
     * message. Only the second half is implemented, by {@link #encodeCapped} (used by every terminal
     * writer, including {@link #writeToolResult}). This method's own conversion is an unbounded {@link
     * ObjectMapper#convertValue} tree build: a P04 remediation slice evaluated adding an independent
     * byte-counting probe ahead of it, but rejected that approach because it requires serializing
     * {@code value} a second time — which regresses the already-frozen T020 TP-001 "normalized exactly
     * once" guarantee ({@code McpOutputPipelineIT#shouldNormalizeAndValidateAStructuredResultOnce}),
     * pinned because a handler's raw value may itself carry side-effecting or expensive conversion
     * logic that must run at most once. A genuinely single-pass bounded conversion (e.g. a custom
     * byte-budgeted {@code JsonGenerator}) is a larger change than this remediation slice's scope.
     * {@link #encodeCapped} still bounds every response actually reaching a client or an observation:
     * see {@link #outputSchemaValid} and the caller in {@link #invokeAndRespond} for the schema and
     * observation gates that run before that write.
     *
     * @param value the application handler's structured content, or {@code null} for a text-only result
     * @return the normalized JSON-compatible value, or {@code null} when {@code value} is {@code null}
     */
    private static @Nullable Object normalizeStructuredContent(@Nullable Object value) {
        return value == null ? null : OUTPUT_ENCODER.convertValue(value, Object.class);
    }

    /**
     * Runs the T020 output stage's schema-validation gate: validates an already-normalized structured
     * value against {@code toolName}'s precompiled output validator, when the tool publishes a
     * structured output schema.
     *
     * <p>Never compiles a schema or a validator here — {@link McpSchemaRegistry} compiled every output
     * validator exactly once, at composition, alongside the input validators (T009); this call only
     * invokes the already-compiled instance. A tool with no declared output schema, or a {@code null}
     * normalized value (a text-only or structured-content-free result), is trivially valid: there is
     * nothing to validate.
     *
     * @param toolName the resolved tool's name
     * @param normalizedValue the value {@link #normalizeStructuredContent} already produced, or {@code
     *     null}
     * @return {@code true} when {@code normalizedValue} is {@code null}, the tool declares no output
     *     schema, or {@code normalizedValue} satisfies the declared output schema
     */
    private boolean outputSchemaValid(String toolName, @Nullable Object normalizedValue) {
        if (normalizedValue == null) {
            return true;
        }
        return toolRegistry
                .schemaRegistry()
                .outputValidator(toolName)
                .map(validator -> validator.validate(normalizedValue).getValid())
                .orElse(true);
    }

    /**
     * Settles a schema-invalid structured result through the bounded, non-leaking internal-error
     * fallback (T020): the invalid value is never embedded in a response, so it never reaches the wire,
     * and — because {@link #invokeAndRespond} calls this before publishing the output observation — it
     * never reaches a capable session either. Mirrors {@link #writeSseFallback}'s degrade-to-id-less
     * shape, framed as SSE since SSE was already selected before invocation began.
     *
     * @param context the request context
     * @param envelope the validated request envelope whose id is echoed when it fits the cap
     * @param security the established security snapshot, recorded on the terminal event
     * @param toolName the resolved tool's name, recorded on the terminal event
     */
    private void writeOutputValidationFailure(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security, String toolName) {
        byte[] fallback = codec.internalFallback(envelope.get("id"), new OutputSchemaValidationException());
        if (fallback.length > config.outputMaxBytes()) {
            // Degrade to the id-less internal error so the hard cap holds, exactly like the discovery,
            // tools/list, and writeSseFallback degrade paths.
            fallback = codec.internalFallback(null, new OutputSchemaValidationException());
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                McpErrorType.OUTPUT_VALIDATION,
                500,
                INTERNAL_ERROR,
                null,
                security,
                null);
        writeSse(context, 500, fallback, terminal);
    }

    /**
     * Reports whether the {@code tools/call} {@code arguments} member is present, non-null, and not a
     * JSON object — an array, string, number, or boolean. {@link #invokeAndRespond} checks this before
     * ever calling {@link #argumentsOf}, and rejects such a request outright instead of letting it
     * silently coerce to the empty map: without this guard a zero-argument tool would execute from a
     * schema-invalid call.
     *
     * @param envelope the validated {@code tools/call} request envelope
     * @return {@code true} when {@code arguments} is present, non-null, and not an object
     */
    private static boolean isMalformedArguments(JsonNode envelope) {
        JsonNode arguments = envelope.get("params").get("arguments");
        return arguments != null && !arguments.isNull() && !arguments.isObject();
    }

    /**
     * Normalizes the {@code tools/call} {@code arguments} member to a bounded, non-null map: absent or
     * explicit {@code null} both normalize to the same immutable empty map as {@code {}} (§4.7). A
     * present non-null, non-object value is never passed here — {@link #invokeAndRespond} rejects it
     * through {@link #isMalformedArguments} first. A present object is shallow-converted to
     * {@code Map<String, Object>}; deeper structure is preserved as nested {@code Map}/{@code List}/
     * scalar values exactly as Jackson's generic conversion produces them.
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
     *
     * <p>{@code normalizedStructuredContent} is the already-normalized, already-schema-validated value
     * {@link #invokeAndRespond} computed exactly once (T020); this method never re-derives it from
     * {@code result.structuredContent()} and never re-serializes the original application value. Every
     * call site that never carries structured content (a schema, input-processing, or interceptor
     * rejection) passes {@code null}, matching {@code result}'s own {@code null} structured content.
     *
     * <p>{@code errorType} classifies the terminal event recorded when {@code result.isError()} is
     * {@code true}, so a caller-side rejection is never misclassified as a genuine handler fault (the
     * frozen lifecycle algebra distinguishes {@link McpErrorType#INPUT_VALIDATION} (stage 1 schema
     * rejection), {@link McpErrorType#INPUT_PROCESSING} (stages 2-4 {@link McpInputRejectionException}),
     * and {@link McpErrorType#HANDLER} (the tool actually ran and returned an error)). {@link
     * McpErrorType#INTERCEPTOR} is a distinct case: because the tool-interceptor stage runs before any
     * handler invocation, a rejection there settles as {@link McpOutcome#REJECTED}/{@code
     * resultType=NONE} on the terminal event even though the wire body is the same bounded, SSE-framed,
     * text-only {@code isError=true} tool result every other rejection at this method produces — the
     * response bytes never distinguish the stages, only the internal lifecycle classification does.
     * Ignored when {@code result.isError()} is {@code false}, since a successful result is always
     * classified {@link McpOutcome#SUCCESS}/{@link McpErrorType#NONE} regardless of which stage called
     * this method.
     */
    private void writeToolResult(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            McpToolResult<?> result,
            @Nullable Object normalizedStructuredContent,
            McpErrorType errorType) {
        byte[] payload;
        try {
            payload = encodeCapped(toolCallResponse(envelope, result, normalizedStructuredContent));
        } catch (OutputCapExceededException overCap) {
            writeSseFallback(context, envelope, security, toolName, overCap);
            return;
        }
        McpRequestTerminalEvent terminal = toolResultTerminal(context, security, toolName, result, errorType);
        writeSse(context, 200, payload, terminal);
    }

    /**
     * Builds the terminal event for {@link #writeToolResult}, applying the frozen lifecycle algebra:
     * a successful result is always {@link McpOutcome#SUCCESS}, an {@link McpErrorType#INTERCEPTOR}
     * rejection is {@link McpOutcome#REJECTED} with {@code resultType=NONE} (no handler ever ran), and
     * every other error classification ({@link McpErrorType#INPUT_VALIDATION}, {@link
     * McpErrorType#INPUT_PROCESSING}, {@link McpErrorType#HANDLER}) is a completed {@link
     * McpOutcome#TOOL_ERROR}.
     */
    private static McpRequestTerminalEvent toolResultTerminal(
            RoutingContext context,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            McpToolResult<?> result,
            McpErrorType errorType) {
        if (!result.isError()) {
            return McpRequestTerminalEvent.success(
                    startedAt(context), Instant.now(), McpMethod.TOOLS_CALL, toolName, 200, null, security, null);
        }
        if (errorType == McpErrorType.INTERCEPTOR) {
            return McpRequestTerminalEvent.rejected(
                    startedAt(context),
                    Instant.now(),
                    McpMethod.TOOLS_CALL,
                    toolName,
                    errorType,
                    200,
                    null,
                    null,
                    security,
                    null);
        }
        return McpRequestTerminalEvent.toolError(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                errorType,
                200,
                null,
                security,
                null);
    }

    /**
     * Builds the canonical {@code CallToolResult} response node: the text content items, the optional
     * structured content, the {@code isError} flag, and the mandatory server-identity {@code _meta}.
     *
     * <p>Embeds {@code normalizedStructuredContent} — the T020 single-pass normalized value — rather
     * than {@code result.structuredContent()}: converting the already-normalized bounded
     * {@code Map}/{@code List}/scalar tree to a {@link JsonNode} is a structural copy, never a second
     * serialization pass over the original application object.
     */
    private ObjectNode toolCallResponse(
            JsonNode envelope, McpToolResult<?> result, @Nullable Object normalizedStructuredContent) {
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
        if (normalizedStructuredContent != null) {
            toolResult.set("structuredContent", OUTPUT_ENCODER.valueToTree(normalizedStructuredContent));
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
     * <p>Builds the error bytes from {@code decoded} through {@link McpProtocolCodec#errorResponseFor}
     * rather than {@link McpProtocolCodec#errorResponse(byte[])}: {@code decoded} already carries
     * every fact ({@code id}, code, message) that a re-analysis of the raw body would recompute, so
     * the request body is decoded exactly once on this path — by {@link #dispatch}'s {@code
     * codec.decodeEnvelope} call — never twice.
     *
     * @param context the request context
     * @param decoded the already-decoded envelope this dispatch produced, reused so the body is
     *     decoded only once on the dispatch path
     * @param security the established security snapshot, or {@code null}
     */
    private void emitProtocolError(
            RoutingContext context, McpProtocolCodec.Decoded decoded, @Nullable SecurityContextSnapshot security) {
        int code = decoded.isError() ? decoded.error().code() : INTERNAL_ERROR;
        int status = httpStatusFor(code);
        McpErrorType errorType = code == INTERNAL_ERROR ? McpErrorType.INTERNAL : McpErrorType.PROTOCOL;
        byte[] errorBytes = decoded.isError()
                ? codec.errorResponseFor(decoded)
                : codec.internalFallback(
                        null, new IllegalStateException("emitProtocolError called on a non-error decode"));
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
            case INTERCEPTOR_REJECTED -> 403;
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
        // unbounded PROVIDED the shared HttpConfig liveness bound is actually armed: the
        // disconnect/exception settlement hooks registered in registerSettlementHooks reach the
        // coordinator on the same request-owning context, and McpCompletionCoordinator's
        // completeOnContext drives finishWrite from there when it finds settlement already claimed by
        // this beginWrite — so a stalled end() cannot strand the request past that bound. The bound
        // itself is not automatic (idleTimeoutSeconds/readIdleTimeoutSeconds/writeIdleTimeoutSeconds
        // all default to 0/disabled); McpServerConfigValidator's startup gate is what guarantees an
        // enabled mount always has at least one of them armed, which is what makes this comment true.
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

    /**
     * Signals that a tool's normalized structured result failed validation against its declared output
     * schema (T020). Carries no schema detail, failing property, or value text — {@link
     * #writeOutputValidationFailure} never reads this exception's message, matching {@link
     * #OutputCapExceededException}'s established non-leaking convention for a different stage.
     */
    static final class OutputSchemaValidationException extends RuntimeException {
        OutputSchemaValidationException() {
            super("MCP tool structured output failed schema validation");
        }
    }
}
