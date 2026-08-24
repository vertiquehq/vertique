// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpAuthorizationSummary;
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
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthzReasonCodes;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
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
import java.util.Collections;
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
 * result (T020/R04) is then normalized exactly once, bounded at {@code mcp.output.maxBytes} as bytes
 * are produced, validated against the tool's advertised output schema before exposure, encoded into the
 * same bounded terminal envelope, offered to the opt-in {@code onToolOutput} observation only once that
 * envelope exists, and only then handed to the single terminal writer — contract §4.7 stage 7's fixed
 * order.
 */
final class McpRequestDispatcher {
    private static final String DISCOVER_METHOD = "server/discover";
    private static final String TOOLS_LIST_METHOD = "tools/list";
    private static final String TOOLS_CALL_METHOD = "tools/call";
    private static final String PROTOCOL_VERSION = McpCursorCodec.PROTOCOL_VERSION;

    /** The final protocol's mandatory discriminator for every successfully transported result. */
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
     * The standard, non-leaking message paired with {@link #INTERNAL_ERROR}. Mirrors {@link
     * McpProtocolCodec}'s own private {@code MSG_INTERNAL_ERROR} constant of the same value — this class
     * already redeclares every other codec-classified code/message it needs locally (see {@link
     * #NEGOTIATION_MISMATCH} below) rather than routing a bounded response through a codec call whose own
     * serialization step is unbounded (R12, merge blocker 5; see {@link #boundedErrorResponse}).
     */
    private static final String INTERNAL_ERROR_MESSAGE = "Internal error";

    /**
     * The bounded JSON-RPC server-error-range code {@link McpProtocolCodec#validateNegotiation} settles
     * a protocol-negotiation failure as (R05, issue #429; contract §4.7 — "Header/body mismatch is HTTP
     * 400 with -32020"). Mirrors {@link McpProtocolCodec}'s own private constant of the same value —
     * this class already redeclares every other codec-classified code above for the same reason: {@link
     * #httpStatusFor} needs it locally.
     */
    private static final int NEGOTIATION_MISMATCH = -32020;

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
     * Routing-context key for a snapshot of this request's live {@link CorrelationContext} (R05, issue
     * #431; R09, merge blocker 2), established once in {@link #begin} — via {@link #bindCorrelation} —
     * contract §4.7 stage 2, "establish correlation ... before optional authentication" — and read by
     * every terminal-event construction site and every tool-interceptor context for the remainder of
     * the request through {@link #correlationOf}. Always a snapshot of the same live context bound onto
     * {@code ContextHolder} by {@link #bindCorrelation}, never an independently generated value. {@code
     * null} (the key absent) only before {@link #begin} runs, i.e. for a cheap-admission rejection in
     * {@link #admitCheap}.
     */
    private static final String CORRELATION_KEY = KEY_PREFIX + ".correlation";

    /**
     * Routing-context key for this request's negotiated {@code
     * io.modelcontextprotocol/protocolVersion} (R05, issue #431), set only once {@link
     * McpProtocolCodec#validateNegotiation} succeeds in {@link #dispatch}, and read by every later
     * terminal-event construction site through {@link #protocolVersionOf}. {@code null} whenever
     * negotiation never ran or did not complete — a request rejected at or before negotiation carries
     * no negotiated version, exactly as contract §4.7's "emit only when negotiation completed" requires.
     */
    private static final String PROTOCOL_VERSION_KEY = KEY_PREFIX + ".protocolVersion";

    /**
     * Routing-context key for the {@link McpAuthorizationSummary} of the one real {@link
     * McpPolicyEnforcer#decide} evaluation a {@code tools/call} request's dispatch performs (R05, issue
     * #431), set in {@link #writeToolsCall} immediately once that decision resolves and read by every
     * later terminal-event construction site on the same request through {@link #authorizationOf}.
     * {@code null} for every other method, and for a {@code tools/call} request that never reaches an
     * actual policy evaluation (a structurally missing/blank name) — matching contract §4.7's
     * "authorization is present only after an actual policy evaluation."
     */
    private static final String AUTHORIZATION_KEY = KEY_PREFIX + ".authorization";

    /**
     * Compact, insertion-order-preserving success encoder, canonicalized identically to the codec's
     * encoder ({@code WRITE_BIGDECIMAL_AS_PLAIN}). The response is streamed through a byte-bounded
     * {@link CappedOutputStream} so serialization stops at {@code mcp.output.maxBytes} as bytes are
     * produced, rather than materializing a full buffer that the cap then rejects.
     */
    private static final ObjectMapper OUTPUT_ENCODER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            // Keep non-finite spellings unquoted so the strict normalization reparse rejects them
            // rather than admitting a JSON string value.
            .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .build();

    /**
     * Parses the bytes {@link #encodeCapped} already produced back into the canonical {@code
     * Map}/{@code List}/scalar output shape. Its independent document and token constraints come from
     * {@code mcp.outputMaxBytes} and {@code mcp.outputMaxTokens}; no fixed node budget remains.
     *
     * <p>{@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} preserves scale-sensitive and
     * large finite decimals. This reader never sees the raw application object and therefore does not
     * create a second serialization pass.
     */
    private final ObjectMapper normalizationDecoder;

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
    private final ContextHolder contextHolder;
    private final CorrelationContextFactory correlationContextFactory;

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
            McpPolicyEnforcer policyEnforcer,
            ContextHolder contextHolder,
            CorrelationContextFactory correlationContextFactory) {
        this.config = config;
        this.securityRuntime = securityRuntime;
        this.lifecycleObservers = Set.copyOf(lifecycleObservers);
        this.completedListeners = Set.copyOf(completedListeners);
        this.orderedRequestInterceptors = sortedAndValidatedRequestInterceptors(requestInterceptors);
        this.orderedToolInterceptors = sortedAndValidatedToolInterceptors(toolInterceptors);
        this.codec = new McpProtocolCodec(httpConfig, config.ingressMaxTokens());
        this.toolRegistry = toolRegistry;
        this.policyEnforcer = policyEnforcer;
        this.cursorCodec = new McpCursorCodec();
        this.contextHolder = contextHolder;
        this.correlationContextFactory = correlationContextFactory;
        this.normalizationDecoder = buildNormalizationDecoder(config.outputMaxBytes(), config.outputMaxTokens());
    }

    /**
     * Builds the output-normalization reader from the two independent configured resource boundaries.
     *
     * @param outputMaxBytes this instance's configured {@code mcp.output.maxBytes}
     * @param outputMaxTokens this instance's configured {@code mcp.outputMaxTokens}
     * @return a reader bounded independently by bytes and parser tokens
     */
    private static ObjectMapper buildNormalizationDecoder(int outputMaxBytes, int outputMaxTokens) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxTokenCount(outputMaxTokens)
                .maxDocumentLength(outputMaxBytes)
                .build();
        JsonFactory factory =
                JsonFactory.builder().streamReadConstraints(constraints).build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    /**
     * Exposes {@link #normalizationDecoder}'s constraints for structural regression assertions
     * (test-only seam; no production caller) — mirrors {@link McpEnvelopeJsonCodec#mapper()}'s
     * established pattern for the same purpose on the ingress side.
     *
     * @return this instance's normalization reader
     */
    ObjectMapper normalizationDecoder() {
        return normalizationDecoder;
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
     * Constructs the request's completion coordinator, establishes the live request-scoped {@link
     * CorrelationContext}, and registers its disconnect/reset settlement hooks (§4.7 stage 2), for a
     * request that already passed {@link #admitCheap}'s cheap admission checks and {@code
     * BodyHandler}'s body aggregation.
     *
     * <p>Opens the request's lifecycle observation: every observer and completed-listener call for this
     * request is scoped to the coordinator constructed here, so — like the cheap-admission rejections
     * above it — nothing before this point (a disallowed method/Origin/Content-Type/Accept, or a
     * body-limit rejection) ever produces a lifecycle observation.
     */
    void begin(RoutingContext context) {
        Instant startedAt = startedAt(context);
        bindCorrelation(context);
        McpCompletionCoordinator coordinator = new McpCompletionCoordinator(
                context.vertx().getOrCreateContext(), lifecycleObservers, completedListeners, startedAt);
        context.put(COMPLETION_COORDINATOR_KEY, coordinator);
        registerSettlementHooks(context, coordinator, startedAt);
        context.next();
    }

    /**
     * Establishes the one live {@link CorrelationContext} for this request (R09, merge blocker 2;
     * contract §4.7 stage 2 — "establish correlation ... before optional authentication") and binds it
     * onto the shared {@code ContextHolder} substrate — the same mechanism REST's {@code
     * CorrelationIngressMiddleware} uses for its own ingress boundary. MCP defines no inbound
     * correlation header of its own, so the context is minted, not resolved, via {@link
     * CorrelationContextFactory#seed(String)} exactly as the existing non-REST first-ingress boundaries
     * do (Kafka, the outbox relay, a delayed job) — tagged {@code "seeded:mcp"} — which is also what
     * routes an application-supplied {@link dev.vertique.core.correlation.CorrelationIdGenerator}
     * override into every id this dispatcher mints, instead of bypassing it with a raw {@code
     * UUID.randomUUID()} call.
     *
     * <p>The bind {@link ContextHolder.Scope} is registered with this request's {@link
     * RequestContextLifecycle.Handle}, so it is torn down at request end on every exit path —
     * normal completion, an interceptor or authorization rejection, a thrown failure, a client
     * disconnect, or a stream reset — exactly like every other holder binding on this request, and
     * never leaks onto the next request scheduled on the same event-loop thread.
     *
     * <p><strong>R09's original claim was false when written; R14 item 5 made it true.</strong> The
     * last two of those exit paths did not hold: {@link #registerSettlementHooks} overwrote the
     * single-slot response {@code closeHandler}/{@code exceptionHandler} that Vert.x Web's own routing
     * context installs to drive its end handlers, so {@link RequestContextLifecycle.Handle#closeAll()}
     * — and therefore this scope's close — never ran for a disconnect or a reset. Settlement now runs
     * through {@link RoutingContext#addEndHandler} instead, which is multicast and leaves Vert.x Web's
     * handlers in place; see {@link #registerSettlementHooks} for the measurement, and {@code
     * McpDisconnectCleanupIT} for the proof that this scope is genuinely closed on a real disconnect.
     *
     * <p>This is the single live context every downstream consumer reads: {@code
     * IdentityResolutionMiddleware} (via {@code ContextHolder.current(CorrelationContext.class)}, for
     * {@code CredentialAcceptedEvent} emission), {@code SecurityPolicyEnforcer} (for every {@code
     * AuthorizationDecisionEvent} this dispatcher's {@link McpPolicyEnforcer#decide} calls trigger), and
     * this class's own {@link #correlationOf} — which snapshots the same live context rather than a
     * second, independently generated source of correlation.
     */
    private void bindCorrelation(RoutingContext context) {
        RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(context);
        CorrelationContext live = correlationContextFactory.seed("mcp");
        ContextHolder.Scope scope = contextHolder.bind(CorrelationContext.class, live);
        lifecycle.onClose(scope);
        context.put(CORRELATION_KEY, live.snapshot());
    }

    /**
     * Returns this request's {@link CorrelationContextSnapshot}, or {@code null} when {@link #begin}
     * has not yet run (a cheap-admission rejection).
     */
    @Nullable
    private static CorrelationContextSnapshot correlationOf(RoutingContext context) {
        return context.get(CORRELATION_KEY);
    }

    /**
     * The maximum accepted length of a stored negotiated protocol version, mirroring {@link
     * dev.vertique.mcp.lifecycle.McpRequestTerminalEvent}'s own bound on the same fact.
     */
    private static final int MAX_PROTOCOL_VERSION_CHARS = 64;

    /**
     * Returns this request's negotiated {@code io.modelcontextprotocol/protocolVersion}, or {@code
     * null} when negotiation never ran or did not complete (contract §4.7 — "emit only when negotiation
     * completed").
     *
     * <p>Security review finding (post-R05): total by construction — every terminal-event construction
     * site in this class feeds this method's return value straight into a factory that would throw
     * {@link IllegalArgumentException} for a blank, over-length, or control-character-bearing value
     * ({@link McpRequestTerminalEvent}'s own compact constructor). {@link McpProtocolCodec#validateNegotiation}
     * already rejects such a value before it is ever stored under {@link #PROTOCOL_VERSION_KEY}, so this
     * re-check is defense in depth, not the primary gate: it exists so that no construction site can ever
     * throw regardless of how a value happened to reach the routing context, rather than trusting every
     * future caller of {@code context.put(PROTOCOL_VERSION_KEY, ...)} to already have validated it.
     */
    @Nullable
    private static String protocolVersionOf(RoutingContext context) {
        String stored = context.get(PROTOCOL_VERSION_KEY);
        if (stored == null
                || stored.isBlank()
                || stored.length() > MAX_PROTOCOL_VERSION_CHARS
                || stored.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return stored;
    }

    /**
     * Returns the {@link McpAuthorizationSummary} of this request's one real policy evaluation, or
     * {@code null} when this request never reached one (contract §4.7 — "authorization is present only
     * after an actual policy evaluation").
     */
    @Nullable
    private static McpAuthorizationSummary authorizationOf(RoutingContext context) {
        return context.get(AUTHORIZATION_KEY);
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
     * <p>After envelope decoding, the complete official per-method {@code params} schema validates
     * before negotiation or application policy. A violation is a JSON-RPC {@code -32602} response over
     * HTTP 400. Only then does header/body and Phase-1 negotiation run; those failures are {@code
     * -32020}. A negotiated request enters the ordered, fail-closed pre-dispatch request-interceptor
     * stage (T016, contract §4.7 stage 5) before method dispatch, tool lookup, authorization, or
     * application input processing.
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
        McpProtocolCodec.ParamsValidationResult paramsValidation = codec.validateOfficialParams(envelope);
        if (paramsValidation.isError()) {
            writePreDispatchProtocolRejection(context, envelope, method, security, paramsValidation.error());
            return;
        }
        // Header/body negotiation and Phase-1 negotiation policy run only after the official schema
        // boundary, but still before every application policy or dispatch stage.
        McpProtocolCodec.NegotiationResult negotiation =
                codec.validateNegotiation(envelope, context.request().headers());
        if (negotiation.isError()) {
            writePreDispatchProtocolRejection(context, envelope, method, security, negotiation.error());
            return;
        }
        context.put(PROTOCOL_VERSION_KEY, negotiation.protocolVersion());
        McpRequestContext requestContext =
                new McpRequestContext(method, establishedSecurityContext(), correlationOf(context), null);
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
     * the bounded internal-error fallback, exactly like {@link #writeToolsListFallback}. Bounded through
     * {@link #boundedErrorResponse} (R12) rather than an unchecked {@link McpProtocolCodec#internalFallback}
     * call: the earlier revision here built and sent the fallback with no cap check at all.
     */
    private void writeDispatchFallback(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.OTHER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.INTERNAL,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
        // cause is deliberately never read, mirroring McpProtocolCodec#internalFallback's own established
        // convention (R12): only its occurrence mattered before this fix routed the fallback through
        // boundedErrorResponse, and still only its occurrence matters now.
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                method,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.INTERNAL,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
        } catch (RuntimeException | StackOverflowError thrown) {
            // R13 (#440-adjacent isolation sweep): interceptor.beforeRequest is application-supplied
            // lifecycle code, exactly like the stage-5/stage-7 callbacks below — a pathologically deep
            // argument or context graph can drive native-recursion StackOverflowError here just as
            // easily as a RuntimeException. Failing the future (rather than letting it escape
            // synchronously through dispatch(), out to the Vert.x router) keeps this on the same
            // bounded, terminal-event-emitting settlement path as every other rejection this chain
            // produces.
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
        } catch (RuntimeException | StackOverflowError thrown) {
            // R13: mirrors runRequestInterceptors above — interceptor.beforeInvocation is the same
            // class of application-supplied lifecycle callback, and a StackOverflowError from it is no
            // less able to strand this request than one from stage 5 or stage 7.
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
     * <p>This method is reached from {@link #dispatch} only once {@link McpProtocolCodec#validateNegotiation}
     * has already succeeded — negotiation always completes strictly before {@link #runRequestInterceptors}
     * ever runs — so, unlike {@link #writePreDispatchProtocolRejection}, the terminal event here does carry the
     * negotiated {@code protocolVersion} via {@link #protocolVersionOf}. (Security review, post-R05: an
     * earlier revision passed a hardcoded {@code null} on the normal-response branch below while the
     * over-cap branch correctly used {@link #protocolVersionOf} — the same request reported a version
     * only when its error response happened to exceed the output cap.)
     *
     * @param context the request context
     * @param envelope the validated envelope whose id is echoed
     * @param method the classified method, recorded on the terminal event
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void writeInterceptorRejection(
            RoutingContext context, JsonNode envelope, McpMethod method, @Nullable SecurityContextSnapshot security) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        JsonNode id = envelope.get("id");
        int status = httpStatusFor(INTERCEPTOR_REJECTED);
        byte[] responseBytes;
        try {
            // The echoed id is client-controlled (up to the envelope codec's bounded maxStringLength),
            // so — exactly like every other terminal writer in this class — this response is bounded at
            // mcp.output.maxBytes through encodeCapped rather than the unbounded codec.encode, with the
            // same degrade-to-id-less fallback below when even that cannot fit.
            responseBytes = encodeCapped(errorNode(id, INTERCEPTOR_REJECTED, INTERCEPTOR_REJECTED_MESSAGE));
        } catch (OutputCapExceededException overCap) {
            // R12: routed through boundedErrorResponse rather than the previous codec.internalFallback(id)
            // + measure-the-completed-array idiom, which itself fully allocated an id-bearing response
            // before checking whether it fit.
            byte[] fallback = boundedErrorResponse(id, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
            McpRequestTerminalEvent overCapTerminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    method,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
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
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
        write(context, status, responseBytes, terminal);
    }

    /**
     * Writes the bounded, non-leaking JSON-RPC error response for a failed official-params or
     * negotiation check. Official {@code params} validation produces {@code -32602 Invalid params};
     * header/body disagreement and Phase-1 negotiation policy produce {@code -32020 Header/body
     * mismatch}. Both occur before negotiation completes, so the terminal event carries no {@code
     * protocolVersion}. This is unlike {@link #writeInterceptorRejection}, which runs only after
     * negotiation has succeeded and therefore does carry the negotiated version. Correlation and
     * security are already established and remain recorded.
     *
     * @param context the request context
     * @param envelope the successfully decoded envelope whose id is echoed when it fits the cap
     * @param method the classified method, recorded on the terminal event
     * @param security the established security snapshot, recorded on the terminal event
     * @param error the bounded official-params or negotiation error
     */
    private void writePreDispatchProtocolRejection(
            RoutingContext context,
            JsonNode envelope,
            McpMethod method,
            @Nullable SecurityContextSnapshot security,
            McpProtocolCodec.CodecError error) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        int code = error.code();
        int status = httpStatusFor(code);
        byte[] errorBytes;
        try {
            // R12 (merge blocker 5): the earlier revision here called codec.errorResponseFor — an
            // unrestricted writeValueAsBytes — and only then compared the completed array's length
            // against the cap, so a large client-controlled id allocated the entire response before the
            // promised cap ever applied. This now serializes once through the same capped stream every
            // other terminal writer in this class uses, degrading to the id-less internal error below
            // only when the cap actually trips while bytes are being produced.
            errorBytes = encodeCapped(errorNode(envelope.get("id"), code, error.message()));
        } catch (OutputCapExceededException overCap) {
            byte[] fallback = boundedErrorResponse(null, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
            McpRequestTerminalEvent overCapTerminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    method,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    null,
                    null,
                    security,
                    correlationOf(context));
            write(context, 500, fallback, overCapTerminal);
            return;
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.rejected(
                startedAt(context),
                Instant.now(),
                method,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.PROTOCOL,
                status,
                code,
                null,
                null,
                security,
                correlationOf(context));
        write(context, status, errorBytes, terminal);
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
            // R12: boundedErrorResponse serializes the id-bearing attempt through the same capped stream
            // and degrades to the minimal id-less internal error — itself encoded the same bounded way,
            // not assumed to fit — only when that attempt also exceeds the cap.
            byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
            McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    McpMethod.SERVER_DISCOVER,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
            write(context, 500, fallback, terminal);
            return;
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.success(
                startedAt(context),
                Instant.now(),
                McpMethod.SERVER_DISCOVER,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
     * <p>A valid cursor anchor is a lexicographic position hint, not a registry membership proof: a
     * nonmember anchor resumes at the first registry name strictly greater than the anchor. A cursor is
     * emitted only after this scan examined at least one candidate and candidates remain.
     *
     * @param context the request context
     * @param envelope the validated {@code tools/list} request envelope
     * @param security the established security snapshot, recorded on the terminal event
     */
    private void writeToolsList(RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        McpCompletionCoordinator coordinator = context.get(COMPLETION_COORDINATOR_KEY);
        if (coordinator != null && coordinator.cancellation().isCancelled()) {
            return;
        }
        JsonNode cursorNode = envelope.get("params").get("cursor");
        if (cursorNode != null && !cursorNode.isTextual()) {
            writeUnknownOrUnauthorized(
                    context, envelope, security, McpMethod.TOOLS_LIST, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
            return;
        }
        String anchor = null;
        if (cursorNode != null) {
            McpCursorCodec.Decoded decoded = cursorCodec.decode(cursorNode.asText(), toolRegistry.digest());
            if (decoded.isInvalid()) {
                writeUnknownOrUnauthorized(
                        context, envelope, security, McpMethod.TOOLS_LIST, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
                return;
            }
            anchor = decoded.anchor();
        }
        List<String> names = List.copyOf(toolRegistry.descriptorsByName().keySet());
        int startIndex = anchor != null ? firstIndexStrictlyAfter(names, anchor) : 0;
        int pageSize = config.toolsPageSize();
        int budget = pageSize * EXAMINATION_BUDGET_MULTIPLIER;
        // establishedSecurityContext() (never null) rather than the raw securityRuntime.current():
        // McpPolicyEnforcer#decide null-checks its caller and would throw for the null the raw runtime
        // value can carry.
        SecurityContext caller = establishedSecurityContext();
        McpCancellationSignal cancellation =
                coordinator != null ? coordinator.cancellation() : NoOpCancellationSignal.INSTANCE;
        scan(names, startIndex, pageSize, budget, 0, new ArrayList<>(pageSize), null, caller, cancellation)
                .onComplete(ar -> {
                    if (cancellation.isCancelled()) {
                        return;
                    }
                    if (ar.failed()) {
                        // McpPolicyEnforcer#decide never fails per its own contract; defended here so a
                        // contract-violating extension cannot escape as an unhandled exception.
                        writeToolsListFallback(context, envelope, security, ar.cause());
                        return;
                    }
                    if (ar.result().outcome() == ScanOutcome.CANCELLED) {
                        return;
                    }
                    if (ar.result().outcome() == ScanOutcome.AUTHORIZATION_FAILURE) {
                        writeToolsListAuthorizationFailure(context, envelope, security);
                        return;
                    }
                    writeToolsListResult(context, envelope, security, ar.result());
                });
    }

    /** Returns the first sorted registry index strictly greater than {@code anchor}. */
    private static int firstIndexStrictlyAfter(List<String> names, String anchor) {
        int search = Collections.binarySearch(names, anchor);
        return search >= 0 ? search + 1 : -search - 1;
    }

    /**
     * Scans candidates {@code names[index..)} for one bounded page, reauthorizing every candidate it
     * examines exactly once, and stopping at the first of: the page reaching {@code pageSize} visible
     * tools, {@code examined} reaching {@code budget}, the candidate list being exhausted, or {@code
     * cancellation} firing.
     *
     * <p>{@code cancellation} is checked before a decision starts and after it resolves because a
     * disconnect can arrive while a decision is in flight. A resolved decision keeps its outcome, but
     * the scan does not chain into another candidate.
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
     *
     * @param cancellation this request's cancellation signal (T013) — the same signal a cooperative
     *     tool handler observes, not a second, invented one; {@link McpCancellationSignal#isCancelled()}
     *     is checked synchronously, never {@link McpCancellationSignal#cancelled()}, since this loop
     *     must never itself wait on a future to learn whether it should stop
     */
    private Future<ScanResult> scan(
            List<String> names,
            int index,
            int pageSize,
            int budget,
            int examined,
            ArrayList<McpToolDescriptor> visible,
            @Nullable String lastExaminedName,
            SecurityContext caller,
            McpCancellationSignal cancellation) {
        ArrayList<McpToolDescriptor> currentVisible = visible;
        int currentIndex = index;
        int currentExamined = examined;
        String currentLastExaminedName = lastExaminedName;
        while (true) {
            if (currentVisible.size() >= pageSize || currentExamined >= budget || currentIndex >= names.size()) {
                boolean candidatesRemain = currentIndex < names.size();
                return Future.succeededFuture(new ScanResult(
                        List.copyOf(currentVisible),
                        candidatesRemain ? currentLastExaminedName : null,
                        ScanOutcome.COMPLETED));
            }
            String name = names.get(currentIndex);
            if (cancellation.isCancelled()) {
                return Future.succeededFuture(ScanResult.cancelled());
            }
            McpToolDescriptor descriptor = toolRegistry.descriptorsByName().get(name);
            var decisionFuture = policyEnforcer.decide(descriptor, caller);
            if (!decisionFuture.isComplete()) {
                // Genuinely asynchronous: resume through compose, on a fresh stack frame, instead of
                // looping here — looping would spin-wait on a future that is not yet resolved.
                ArrayList<McpToolDescriptor> visibleSnapshot = currentVisible;
                int examinedSnapshot = currentExamined;
                int indexSnapshot = currentIndex;
                return decisionFuture.compose(decision -> {
                    // A disconnect can race the in-flight decision. Its result is intentionally
                    // ignored: no further candidate is scheduled and the completed request writes no
                    // response. The shared authorization SPI offers no forced cancellation contract.
                    if (cancellation.isCancelled()) {
                        return Future.succeededFuture(ScanResult.cancelled());
                    }
                    if (isAuthorizationInfrastructureFailure(decision)) {
                        return Future.succeededFuture(ScanResult.authorizationFailure());
                    }
                    if (decision.permitted()) {
                        visibleSnapshot.add(descriptor);
                    }
                    int updatedExamined = examinedSnapshot + 1;
                    return scan(
                            names,
                            indexSnapshot + 1,
                            pageSize,
                            budget,
                            updatedExamined,
                            visibleSnapshot,
                            name,
                            caller,
                            cancellation);
                });
            }
            if (decisionFuture.failed()) {
                return Future.failedFuture(decisionFuture.cause());
            }
            AuthorizationDecision decision = decisionFuture.result();
            if (cancellation.isCancelled()) {
                return Future.succeededFuture(ScanResult.cancelled());
            }
            if (isAuthorizationInfrastructureFailure(decision)) {
                return Future.succeededFuture(ScanResult.authorizationFailure());
            }
            if (decision.permitted()) {
                currentVisible.add(descriptor);
            }
            currentExamined = currentExamined + 1;
            currentLastExaminedName = name;
            currentIndex = currentIndex + 1;
        }
    }

    /**
     * Reports whether {@code decision} represents an authorization-infrastructure failure rather than
     * an ordinary access denial.
     */
    private static boolean isAuthorizationInfrastructureFailure(AuthorizationDecision decision) {
        return !decision.permitted() && AuthzReasonCodes.INTERNAL_AUTHZ_ERROR.equals(decision.reasonCode());
    }

    /** One bounded page's outcome: the visible tools, next-page anchor, and settlement state. */
    private record ScanResult(
            List<McpToolDescriptor> visible, @Nullable String nextAnchor, ScanOutcome outcome) {

        /** Creates the response-suppressed outcome for a client-disconnected request. */
        private static ScanResult cancelled() {
            return new ScanResult(List.of(), null, ScanOutcome.CANCELLED);
        }

        /** Creates the fail-whole-list outcome for authorization infrastructure failure. */
        private static ScanResult authorizationFailure() {
            return new ScanResult(List.of(), null, ScanOutcome.AUTHORIZATION_FAILURE);
        }
    }

    /** The terminal state reached by one bounded authorization scan. */
    private enum ScanOutcome {
        /** The scan completed normally and may serialize a list result. */
        COMPLETED,
        /** A disconnect or reset won; no response may be emitted. */
        CANCELLED,
        /** Authorization infrastructure failed; the whole list maps to a generic internal error. */
        AUTHORIZATION_FAILURE
    }

    /**
     * Settles a {@link McpPolicyEnforcer#decide} contract violation through the internal fallback.
     * Bounded through {@link #boundedErrorResponse} (R12): the earlier revision here sent the fallback
     * with no cap check at all.
     */
    private void writeToolsListFallback(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security, Throwable cause) {
        // cause is deliberately never read (see writeDispatchByMethodFailure's identical note).
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_LIST,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.INTERNAL,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
        write(context, 500, fallback, terminal);
    }

    /**
     * Fails the entire list when the authorization layer cannot establish candidate visibility.
     *
     * <p>No cache headers or partial result are created before this writer runs, so this bounded
     * generic response cannot present an incomplete authorization computation as a cacheable page.
     */
    private void writeToolsListAuthorizationFailure(
            RoutingContext context, JsonNode envelope, @Nullable SecurityContextSnapshot security) {
        context.response().putHeader("content-type", JSON_CONTENT_TYPE);
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_LIST,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                McpErrorType.AUTHORIZATION,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
            // R12: see writeDiscovery's identical note — boundedErrorResponse replaces the previous
            // materialize-then-measure idiom.
            byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
            McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    McpMethod.TOOLS_LIST,
                    McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
            write(context, 500, fallback, terminal);
            return;
        }
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.success(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_LIST,
                McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                200,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
        write(context, 200, payload, terminal);
    }

    /**
     * Builds the canonical {@code ListToolsResult} response node: the visible tools in scanned order,
     * an opaque {@code nextCursor} only after this page examined a candidate and candidates remain,
     * and the mandatory {@code ttlMs} and {@code cacheScope=private} cache hints.
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
        JsonNode id = envelope.get("id");
        int status = httpStatusFor(error.code());
        byte[] body;
        try {
            // The echoed id is client-controlled (up to the envelope codec's bounded maxStringLength),
            // so — exactly like every other terminal writer in this class — this response is bounded at
            // mcp.output.maxBytes through encodeCapped rather than the unbounded codec.encode, with the
            // same degrade-to-id-less fallback below when even that cannot fit.
            body = encodeCapped(errorNode(id, error.code(), error.message()));
        } catch (OutputCapExceededException overCap) {
            // R12: see writeDiscovery's identical note.
            byte[] fallback = boundedErrorResponse(id, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
            McpRequestTerminalEvent overCapTerminal = McpRequestTerminalEvent.failed(
                    startedAt(context),
                    Instant.now(),
                    method,
                    method == McpMethod.TOOLS_CALL ? toolName : McpRequestTerminalEvent.UNKNOWN_TOOL_NAME,
                    McpErrorType.SERIALIZATION,
                    500,
                    INTERNAL_ERROR,
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
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
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
            policyEnforcer.decide(UNKNOWN_TOOL_PLACEHOLDER_DESCRIPTOR, caller).onComplete(ar -> {
                // R05 (issue #431): a real policy evaluation occurred — against the synthetic
                // placeholder, exactly like a known-but-denied name — so its summary is recorded
                // like every other actual decision, even though ar.result() here is always a
                // denial. ar.failed() never happens per McpPolicyEnforcer#decide's own contract
                // (defended below for the same reason the sibling branch defends it); recording
                // nothing on that unreachable branch matches "authorization is present only
                // after an actual policy evaluation".
                if (ar.succeeded()) {
                    context.put(AUTHORIZATION_KEY, McpPolicyEnforcer.summarize(ar.result()));
                }
                writeUnknownOrUnauthorized(
                        context, envelope, security, McpMethod.TOOLS_CALL, McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
            });
            return;
        }
        policyEnforcer.decide(invoker.descriptor(), caller).onComplete(ar -> {
            if (ar.failed()) {
                // McpPolicyEnforcer#decide never fails per its own contract; defended here so a
                // contract-violating extension cannot escape as an unhandled exception. No invocation
                // was ever attempted, so this stays a JSON (never SSE) response. No decision was ever
                // actually produced, so no summary is recorded (contract §4.7 — "authorization is
                // present only after an actual policy evaluation").
                writeUnknownOrUnauthorized(context, envelope, security, McpMethod.TOOLS_CALL, toolName);
                return;
            }
            // R05 (issue #431): recorded for every terminal event this request still produces,
            // whether the decision denies (writeUnknownOrUnauthorized below) or permits (every
            // terminal writeToolResult/writeSseFallback eventually reaches inside invokeAndRespond).
            context.put(AUTHORIZATION_KEY, McpPolicyEnforcer.summarize(ar.result()));
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
     * #writeSseFallback}, never a JSON response. The official schema has already admitted only an
     * absent or object-valued {@code arguments} member; explicit {@code null} and every other
     * non-object value are rejected as {@code -32602} before SSE selection.
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
            // Same rationale as above: rejected before invocation, no output observation.
            writeToolResult(
                    context,
                    envelope,
                    security,
                    toolName,
                    McpToolResult.error(SCHEMA_REJECTION_MESSAGE),
                    null,
                    McpErrorType.INPUT_VALIDATION,
                    null,
                    null);
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
            // The generated fixed input boundary rejected before any handler ran: no output observation.
            writeToolResult(
                    context,
                    envelope,
                    security,
                    toolName,
                    McpToolResult.error(rejected.getMessage()),
                    null,
                    McpErrorType.INPUT_PROCESSING,
                    null,
                    null);
            return;
        } catch (RuntimeException | StackOverflowError prepareFailure) {
            // R13: invoker.prepare() (stages 2-4, the generated fixed input boundary) walks the
            // envelope-permitted 1,000-level-deep argument tree — the same depth stage 5's comment
            // above already calls out as StackOverflowError-capable — and may itself invoke
            // application-supplied Bean Validation constraint code. A RuntimeException-only catch here
            // would let a native-recursion StackOverflowError escape before beginWrite is ever called:
            // no response, no terminal, no completion.
            writeSseFallback(context, envelope, security, toolName, prepareFailure);
            return;
        }
        // T017: the ordered, fail-closed post-validation tool-interceptor stage runs here — strictly
        // after Bean Validation (prepare() above already ran it) and strictly before the generated
        // invocation (prepared.invoke() below). The context it exposes projects only the pre-dispatch
        // McpRequestContext and the resolved descriptor: no raw or normalized argument ever reaches an
        // interceptor. A rejection settles through the same bounded, SSE-framed writeToolResult path
        // stage 1 and stages 2-4 already use, and prepared.invoke() is never called.
        //
        // correlationOf(context) (R09, merge blocker 2): the same live-context snapshot every other
        // terminal-event and interceptor site on this request reads, never a hardcoded null — a tool
        // interceptor observes the same correlation the terminal event for this request will carry.
        McpToolInvocationContext toolContext = new McpToolInvocationContext(
                new McpRequestContext(McpMethod.TOOLS_CALL, establishedSecurityContext(), correlationOf(context), null),
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
                // The interceptor stage rejected before the handler ever ran: no output to observe.
                writeToolResult(
                        context,
                        envelope,
                        security,
                        toolName,
                        McpToolResult.error(TOOL_INTERCEPTOR_REJECTED_MESSAGE),
                        null,
                        McpErrorType.INTERCEPTOR,
                        null,
                        null);
                return;
            }
            Future<McpToolResult<?>> result;
            try {
                result = prepared.invoke();
            } catch (RuntimeException | StackOverflowError invokeFailure) {
                // R13: prepared.invoke() is the generated call directly into the application's own tool
                // handler — the most direct lifecycle callback on this whole path. A RuntimeException-only
                // catch left a recursing handler free to strand the request exactly like the already-fixed
                // stage 5/7 callbacks below.
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
                // R04 (closing #426/#427): every complete result is normalized exactly once — bounded by
                // mcp.output.maxBytes as bytes are produced, exactly like the terminal write below —
                // validated against the advertised output schema, encoded into the bounded terminal
                // envelope, offered to the opt-in output observation only once that envelope exists, and
                // only then handed to the single terminal writer (contract §4.7 stage 7). The raw
                // application value is converted to its bounded, JSON-compatible canonical shape here —
                // once — and that exact same value is reused below for schema validation, the observation
                // callback, and the wire embed; nothing downstream re-serializes the original application
                // object. writeToolResult (not this block) publishes the output observation, strictly
                // after its own encodeCapped call succeeds — see its Javadoc — so an observer can never
                // see a value the wire cap or the schema check would still reject.
                //
                McpToolResult<?> toolResult = ar.result();
                Object normalizedOutput;
                try {
                    normalizedOutput = normalizeStructuredContent(toolResult.structuredContent());
                } catch (RuntimeException | StackOverflowError serializationFailure) {
                    // This boundary owns only the sole raw-value serialization and bounded-byte
                    // reparse. Byte/token exhaustion, cyclic or non-finite output, and recursion are
                    // therefore serialization failures, never generic handler failures.
                    writeSseFallback(
                            context, envelope, security, toolName, serializationFailure, McpErrorType.SERIALIZATION);
                    return;
                }
                try {
                    if (!outputSchemaValid(toolName, normalizedOutput)) {
                        // The schema-invalid value never reaches writeToolResult/encodeCapped: it is
                        // rejected here, before any wire byte is produced and before the output
                        // observation fires, so an invalid structured result never reaches the wire or a
                        // capable session.
                        writeOutputValidationFailure(context, envelope, security, toolName);
                        return;
                    }
                    writeToolResult(
                            context,
                            envelope,
                            security,
                            toolName,
                            toolResult,
                            normalizedOutput,
                            McpErrorType.HANDLER,
                            coordinator,
                            toolContext);
                } catch (RuntimeException | StackOverflowError downstreamFailure) {
                    // Schema infrastructure, observation, and other callbacks after normalization are
                    // not serialization failures. Keep their existing internal classification.
                    writeSseFallback(context, envelope, security, toolName, downstreamFailure);
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
     * Runs the R04 output stage's single, bounded normalization pass: converts an application handler's
     * structured result value to the one bounded, JSON-compatible canonical shape ({@code Map}/
     * {@code List}/scalar) reused for output-schema validation, the {@code onToolOutput} observation,
     * and the wire embed — closing #427, "the output cap bounds only the terminal message, not
     * structured-value normalization".
     *
     * <p>Called exactly once per completed invocation, from {@link #invokeAndRespond}'s {@code
     * result.onComplete} handler, before validation, observation, or encoding ever run. Nothing else in
     * this class converts a handler's raw structured value a second time: {@link #writeToolResult} and
     * {@link #toolCallResponse} accept and reuse the already-normalized value.
     *
     * <p><strong>Bounded as bytes are produced, in one pass over {@code value}.</strong> Contract §4.3
     * describes two halves for the output cap to enforce independently: the normalization of an
     * application structured value, and the complete terminal message. Both now run through the same
     * mechanism: {@code value} is serialized exactly once into a {@link CappedOutputStream} via {@link
     * #encodeCapped}, which aborts with {@link OutputCapExceededException} the moment the running byte
     * count would exceed {@code mcp.output.maxBytes} — before a full byte array, let alone a full tree,
     * is ever materialized. The resulting bounded byte array — never {@code value} itself again — is
     * then parsed back into the canonical {@code Map}/{@code List}/scalar shape through {@link #normalizationDecoder}, never {@link #OUTPUT_ENCODER}: a plain {@code readValue} loses precision
     * relative to {@link #encodeCapped}'s own {@code WRITE_BIGDECIMAL_AS_PLAIN} encode (see {@link #normalizationDecoder}'s own javadoc for the two ways that showed up on the wire — R07 item 4).
     * This still touches
     * {@code value}'s own state (bean getters, {@code toString}, custom serializers) exactly once: the
     * parse step reads the bytes {@link #encodeCapped} already produced, not {@code value}. An earlier
     * remediation attempt rejected an independent byte-counting probe ahead of an unbounded {@code
     * convertValue} because a probe-then-convert shape serializes {@code value} twice, regressing the
     * already-frozen T020 TP-001 "normalized exactly once" guarantee ({@code
     * McpOutputPipelineIT#shouldNormalizeAndValidateAStructuredResultOnce}); reusing {@link
     * #encodeCapped} as the sole serialization step — parsed once, not re-serialized — keeps that
     * guarantee while also bounding this stage independently of the terminal-message encode in {@link
     * #writeToolResult}.
     *
     * @param value the application handler's structured content, or {@code null} for a text-only result
     * @return the normalized JSON-compatible value, or {@code null} when {@code value} is {@code null}
     * @throws OutputCapExceededException when {@code value}'s own canonical JSON representation would
     *     exceed {@code mcp.output.maxBytes}
     */
    @Nullable
    Object normalizeStructuredContent(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        byte[] bounded = encodeCapped(value);
        try {
            return normalizationDecoder.readValue(bounded, Object.class);
        } catch (IOException parseFailure) {
            // Parsing bytes encodeCapped just produced from a well-formed write cannot fail on I/O or
            // malformed content; a failure here is a programming error, not a wire condition — mirrors
            // encodeCapped's own IOException-to-UncheckedIOException conversion.
            throw new UncheckedIOException(parseFailure);
        }
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
        // R12: boundedErrorResponse serializes the id-bearing attempt through the capped stream and
        // degrades to the id-less internal error, itself encoded the same bounded way, only when that
        // attempt also exceeds the cap — replacing the previous materialize-then-measure idiom.
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                McpErrorType.OUTPUT_VALIDATION,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
        writeSse(context, 500, fallback, terminal);
    }

    /**
     * Normalizes the {@code tools/call} {@code arguments} member to a bounded, non-null map. An absent
     * member normalizes to the immutable empty map ({@code {}}); the official per-method schema already
     * guaranteed that any present member is an object. The defensive explicit-{@code null} branch is
     * unreachable on the dispatch path because {@code null} is rejected as {@code -32602} before SSE
     * selection. A present object is shallow-converted to {@code Map<String, Object>}; deeper structure
     * is preserved as nested {@code Map}/{@code List}/scalar values exactly as Jackson's generic
     * conversion produces them.
     */
    private static Map<String, Object> argumentsOf(JsonNode envelope) {
        JsonNode arguments = envelope.get("params").get("arguments");
        if (arguments == null || arguments.isNull()) {
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
     *
     * <p><strong>R04 (closing #426).</strong> {@code coordinator} and {@code toolContext} are non-{@code
     * null} only for the one call site that reaches this method after an actual invocation completed
     * (the caller in {@link #invokeAndRespond}'s {@code result.onComplete} handler); every earlier-stage
     * rejection (malformed arguments, input-schema, input-processing, tool-interceptor) passes {@code
     * null} for both, since no handler ever ran and there is no output value to observe. When both are
     * given, the opt-in {@code onToolOutput} observation is published here — strictly after {@link
     * #encodeCapped} has successfully produced the bounded terminal envelope below, and strictly before
     * {@link #writeSse} commits any byte to the wire. This ordering is the fix: an observer only ever
     * receives a value that also reached the wire, never one the cap or the output-schema check (already
     * run by the caller before this method) would still reject. Gated on {@code
     * coordinator.hasValueObservers()} before the observation is even constructed, for the same reason
     * {@link #invokeAndRespond}'s {@code onToolInput} publish is: {@link McpToolOutputObservation}'s
     * compact constructor deep-copies the entire normalized result tree.
     *
     * @param coordinator the request's completion coordinator, or {@code null} when this call site never
     *     publishes an output observation
     * @param toolContext the invocation's immutable context snapshot, or {@code null} exactly when
     *     {@code coordinator} is {@code null}
     */
    private void writeToolResult(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            McpToolResult<?> result,
            @Nullable Object normalizedStructuredContent,
            McpErrorType errorType,
            @Nullable McpCompletionCoordinator coordinator,
            @Nullable McpToolInvocationContext toolContext) {
        byte[] payload;
        try {
            payload = encodeCapped(toolCallResponse(envelope, result, normalizedStructuredContent));
        } catch (OutputCapExceededException overCap) {
            writeSseFallback(context, envelope, security, toolName, overCap, McpErrorType.SERIALIZATION);
            return;
        }
        if (coordinator != null && toolContext != null && coordinator.hasValueObservers()) {
            coordinator.publishToolOutput(new McpToolOutputObservation(toolContext, normalizedStructuredContent));
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
                    startedAt(context),
                    Instant.now(),
                    McpMethod.TOOLS_CALL,
                    toolName,
                    200,
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
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
                    protocolVersionOf(context),
                    authorizationOf(context),
                    security,
                    correlationOf(context));
        }
        return McpRequestTerminalEvent.toolError(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                errorType,
                200,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
    }

    /**
     * Builds the canonical {@code CallToolResult} response node: the mandatory completed-result
     * discriminator, the text content items, the optional structured content, the {@code isError}
     * flag, and the mandatory server-identity {@code _meta}.
     *
     * <p>Embeds {@code normalizedStructuredContent} — the T020 single-pass normalized value — rather
     * than {@code result.structuredContent()}: converting the already-normalized bounded
     * {@code Map}/{@code List}/scalar tree to a {@link JsonNode} is a structural copy, never a second
     * serialization pass over the original application object.
     *
     * <p><strong>R07 item 4 (security review).</strong> {@code structuredContent} is attached via
     * {@link ObjectNode#putPOJO}, not {@link ObjectMapper#valueToTree}: {@code valueToTree} (and {@code
     * convertValue(..., JsonNode.class)}, which shares the same code path) materializes a real {@code
     * BigDecimal}-typed value through Jackson's {@code TokenBuffer}-backed tree construction, which —
     * independently of {@code WRITE_BIGDECIMAL_AS_PLAIN} and independently of {@link #normalizationDecoder}'s own fix — silently strips trailing zeros in the process ({@code
     * BigDecimal("0.1000")} becomes a {@code DecimalNode} whose own value is {@code
     * BigDecimal("0.1")}). {@code putPOJO} instead wraps {@code normalizedStructuredContent} in a
     * {@code POJONode} that defers to this exact object's ordinary bean/collection serializer when the
     * whole envelope is later written by {@link #encodeCapped} — the same code path that already
     * writes a directly-serialized {@code BigDecimal} losslessly (as plain text, {@code
     * WRITE_BIGDECIMAL_AS_PLAIN}) — so no intermediate tree-node materialization ever touches the
     * value's numeric precision.
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
        toolResult.put("resultType", COMPLETE_RESULT_TYPE);
        toolResult.set("content", content);
        toolResult.put("isError", result.isError());
        if (normalizedStructuredContent != null) {
            toolResult.putPOJO("structuredContent", normalizedStructuredContent);
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
        writeSseFallback(context, envelope, security, toolName, cause, McpErrorType.INTERNAL);
    }

    /** Settles one invocation-path failure with its operation-owned lifecycle classification. */
    private void writeSseFallback(
            RoutingContext context,
            JsonNode envelope,
            @Nullable SecurityContextSnapshot security,
            String toolName,
            Throwable cause,
            McpErrorType errorType) {
        // cause is deliberately never read (see writeDispatchByMethodFailure's identical note). R12:
        // boundedErrorResponse replaces the previous materialize-then-measure idiom.
        byte[] fallback = boundedErrorResponse(envelope.get("id"), INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
        McpRequestTerminalEvent terminal = McpRequestTerminalEvent.failed(
                startedAt(context),
                Instant.now(),
                McpMethod.TOOLS_CALL,
                toolName,
                errorType,
                500,
                INTERNAL_ERROR,
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
     * Registers the disconnect and reset settlement hook for one request.
     *
     * <p>Settlement is driven from {@link RoutingContext#addEndHandler}, which Vert.x Web multicasts to
     * every registered handler and which fires on normal end, on an exception, and on a connection
     * close. A failed outcome is a premature disconnect or a stream reset, classified by cause; a
     * succeeded outcome is a normal end, already settled by the two-phase write path, so nothing is done
     * with it. Each settlement drives the coordinator's first-observed-wins guard, so a hook that fires
     * after a normal write is suppressed. MCP arms no whole-request timer of its own: transport liveness
     * is shared {@link HttpConfig} idle/read/write timeout behavior, so an idle or slow connection is
     * closed by the shared HTTP layer and reaches this same hook (T007), classified as transport
     * cancellation rather than a distinct timeout.
     *
     * <p><strong>R14 item 5 — why not the response handlers.</strong> This previously called {@code
     * context.response().closeHandler(...)} and {@code .exceptionHandler(...)}. Both are single-slot
     * setters: last writer wins. Vert.x Web's {@code RoutingContextImpl} installs its <em>own</em>
     * response {@code endHandler}, {@code exceptionHandler} and {@code closeHandler} the first time
     * anything calls {@code addEndHandler} — which {@link RequestContextLifecycle} does, first, for
     * every request — so registering here silently replaced Vert.x Web's, and every routing-context end
     * handler stopped firing on this mount for a disconnect or a reset. The victim was {@link
     * RequestContextLifecycle.Handle#closeAll()}: the correlation binding {@link #bindCorrelation}
     * registers with it, and every other holder binding on the request, were never torn down when a
     * client vanished. Not cross-request contamination — those bindings live in duplicated-context
     * storage that dies with the request — but the framework's cleanup contract was disabled here, and
     * R09's javadoc claiming teardown on "a client disconnect, or a stream reset" was false. Measured
     * against a real Vert.x 5.1.6 server rather than assumed: with the response handlers overwritten,
     * neither {@code addEndHandler} nor {@code closeAll} ran for an orderly FIN close or a hard RST;
     * without the overwrite, both ran for both. {@code McpDisconnectCleanupIT} pins it.
     *
     * <p><strong>Classification.</strong> The same measurement fixes the transport outcome, which the
     * single {@code AsyncResult} must now carry rather than two separate handlers: Vert.x 5.1.6
     * delivers {@link HttpClosedException} for an orderly close and a plain {@code SocketException}
     * ("Connection reset") for a hard RST, so the class of the cause — not which of two handlers fired —
     * selects {@code DISCONNECTED} or {@code RESET}. This is the same "classify by the end-handler
     * cause's class" technique {@code RestRequestCompletionEmitter} already uses, and it is strictly
     * more accurate than what it replaces: through the response handlers an orderly FIN close reached
     * {@code exceptionHandler} first and was recorded as {@code RESET}.
     */
    private void registerSettlementHooks(
            RoutingContext context, McpCompletionCoordinator coordinator, Instant startedAt) {
        context.addEndHandler(outcome -> {
            if (outcome.succeeded()) {
                // A normal response end: the two-phase write path owns this settlement.
                return;
            }
            McpRequestTerminalEvent terminal = settlementTerminal(context, startedAt, McpErrorType.TRANSPORT);
            boolean responseCommitted = context.response().headWritten();
            if (outcome.cause() instanceof HttpClosedException) {
                coordinator.settleDisconnected(terminal, responseCommitted);
            } else {
                coordinator.settleReset(terminal, responseCommitted);
            }
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
                protocolVersionOf(context),
                authorizationOf(context),
                establishedSecurity(),
                correlationOf(context));
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
     * <p>Builds the error node from {@code decoded}'s own already-classified id, code, and message —
     * never by re-analyzing the raw body a second time (that classification is {@link #dispatch}'s one
     * {@code codec.decodeEnvelope} call, reused here, not repeated) — and serializes it exactly once
     * through the capped stream {@link #encodeCapped} every other terminal writer in this class uses
     * (R12, merge blocker 5). The earlier revision here called {@link McpProtocolCodec#errorResponseFor},
     * an unrestricted {@code writeValueAsBytes}, and only then compared the completed array's length
     * against the cap: this is the "ordinary protocol errors have the same defect" finding named
     * alongside the negotiation-rejection defect this same repair slice fixes in {@link
     * #writePreDispatchProtocolRejection}. {@link McpProtocolCodec#errorResponseFor} itself is unchanged and
     * still used directly by {@code McpGoldenWireTest} to pin the codec's own wire-format bytes.
     *
     * @param context the request context
     * @param decoded the already-decoded envelope this dispatch produced, reused so the body is
     *     decoded only once on the dispatch path
     * @param security the established security snapshot, or {@code null}
     */
    private void emitProtocolError(
            RoutingContext context, McpProtocolCodec.Decoded decoded, @Nullable SecurityContextSnapshot security) {
        JsonNode id = decoded.isError() ? decoded.id() : null;
        int code = decoded.isError() ? decoded.error().code() : INTERNAL_ERROR;
        String message = decoded.isError() ? decoded.error().message() : INTERNAL_ERROR_MESSAGE;
        int status = httpStatusFor(code);
        McpErrorType errorType = code == INTERNAL_ERROR ? McpErrorType.INTERNAL : McpErrorType.PROTOCOL;
        byte[] errorBytes;
        try {
            errorBytes = encodeCapped(errorNode(id, code, message));
        } catch (OutputCapExceededException overCap) {
            // The classified error echoes the request id, whose only unbounded element can push the
            // response past mcp.output.maxBytes (a string id is bounded by the envelope codec's frozen
            // maxStringLength, 20,000,000 chars, far above the minimum cap). Degrade to a bounded
            // id-less internal error so the hard cap holds — the emitted status, terminal, and body
            // stay consistent as a 500 internal error. boundedErrorResponse(null, ...) is trusted to
            // fit, exactly like every other degrade path in this class.
            errorBytes = boundedErrorResponse(null, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE);
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
                protocolVersionOf(context),
                authorizationOf(context),
                security,
                correlationOf(context));
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
            case NEGOTIATION_MISMATCH -> 400;
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
                        protocolVersionOf(context),
                        authorizationOf(context),
                        security,
                        correlationOf(context)));
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
     * Builds the {@code {jsonrpc, id, error:{code, message}}} JSON-RPC error node every bounded error
     * writer in this class shares, mirroring {@link McpProtocolCodec}'s own private {@code encodeError}
     * field order exactly ({@code jsonrpc}, {@code id}, {@code error} — {@code code}, {@code message})
     * so a caller that switches from that codec method to this one (R12) produces byte-identical output
     * for the same facts.
     *
     * @param id the request id to echo, or {@code null} to build the id-less shape
     * @param code the final-spec or implementation-defined JSON-RPC error code
     * @param message the standard, non-leaking JSON-RPC error message
     * @return the unencoded response node
     */
    private static ObjectNode errorNode(@Nullable JsonNode id, int code, String message) {
        ObjectNode response = OUTPUT_ENCODER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id != null ? id : NullNode.getInstance());
        ObjectNode error = OUTPUT_ENCODER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

    /**
     * Serializes the bounded {@code id}/{@code code}/{@code message} JSON-RPC error shape exactly once,
     * through the same {@link CappedOutputStream} {@link #encodeCapped} already uses for every success
     * payload, degrading to the always-fitting id-less {@link #INTERNAL_ERROR}/{@link
     * #INTERNAL_ERROR_MESSAGE} shape — itself encoded the same bounded way, never assumed to fit without
     * going through the cap — only when the with-id shape would exceed {@code mcp.output.maxBytes} (R12,
     * merge blocker 5).
     *
     * <p>Replaces the idiom every error-writing path in this class previously shared: build the full
     * response through {@link McpProtocolCodec#errorResponseFor} or {@link
     * McpProtocolCodec#internalFallback} — both an unrestricted {@code writeValueAsBytes} — then compare
     * the <em>completed</em> array's length against the cap. That idiom always allocated the whole
     * response, id included, before the promised cap could ever apply: a large client-controlled id (the
     * only unbounded element in any of these shapes) forced the full allocation regardless of how it was
     * eventually classified. Those two codec methods are unchanged and still used directly by {@code
     * McpGoldenWireTest} and {@code McpCodecFailureTest} to pin the codec's own wire-format bytes, which
     * have no {@code mcp.output.maxBytes} to honor; this dispatcher no longer calls either of them for any
     * client-facing write — every terminal writer below builds and encodes its own error node instead.
     *
     * @param id the request id to echo, or {@code null} to build the id-less shape directly
     * @param code the final-spec or implementation-defined JSON-RPC error code
     * @param message the standard, non-leaking JSON-RPC error message
     * @return the complete, bounded response bytes
     */
    private byte[] boundedErrorResponse(@Nullable JsonNode id, int code, String message) {
        try {
            return encodeCapped(errorNode(id, code, message));
        } catch (OutputCapExceededException overCap) {
            // The id-less shape is trusted to fit without a further length check, exactly as every
            // degrade path in this class already trusted its own id-less fallback: every message this
            // method pairs with it is a short, fixed constant, and the validator-enforced 1,024-byte
            // floor on mcp.output.maxBytes (McpServerConfigValidator) makes a second cap trip here
            // unreachable in practice. Still routed through encodeCapped rather than assumed unencoded,
            // so a pathological misconfiguration fails loudly instead of silently.
            return encodeCapped(errorNode(null, INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
        }
    }

    /**
     * Encodes a value to canonical UTF-8 bytes, bounding the output at {@code mcp.output.maxBytes} as
     * bytes are produced rather than after the full byte array is materialized. Accepts any
     * Jackson-serializable value — a {@link JsonNode} response envelope (every terminal writer) or a
     * handler's raw structured-content object ({@link #normalizeStructuredContent}, R04) — so the same
     * single mechanism bounds both halves contract §4.3 names: normalization of an application
     * structured value, and the complete terminal message.
     *
     * @param value the value to encode
     * @return the canonical UTF-8 bytes, at most {@code mcp.output.maxBytes} long
     * @throws OutputCapExceededException when serialization would exceed the configured cap
     */
    private byte[] encodeCapped(Object value) {
        CappedOutputStream out = new CappedOutputStream(config.outputMaxBytes());
        try {
            OUTPUT_ENCODER.writeValue(out, value);
        } catch (OutputCapExceededException overCap) {
            throw overCap;
        } catch (IOException encodeFailure) {
            // Writing a fully in-memory node tree, or a handler's own structured value, to a byte sink
            // cannot fail on I/O; a failure here is a programming error, not a wire condition.
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
