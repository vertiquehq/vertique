// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * R12 — bounded error serialization (merge blocker 5).
 *
 * <p>Drives {@link McpRequestDispatcher#dispatch} directly against a mocked {@link RoutingContext},
 * mirroring {@code McpProtocolNegotiationTest}'s driving style, with a small {@code
 * mcp.output.maxBytes} (the validator-enforced 1,024-byte floor) and a request id large enough that
 * the fully enveloped, id-bearing error response would exceed it. Covers both error-writing paths R12
 * names by name: the pre-dispatch protocol-rejection path ({@link
 * McpRequestDispatcher#writePreDispatchProtocolRejection}) and the ordinary protocol-error path ({@link
 * McpRequestDispatcher#emitProtocolError}).
 *
 * <p><strong>Proves early rejection, not rejection.</strong> Following R04's own proof shape exactly
 * ({@code McpOutputPipelineIT#assertCappedStreamAbortsBeforeMaterializing}): a live HTTP-level check
 * alone (bounded response, id-less body) cannot distinguish the fix from the defect it replaces — the
 * defective code also produced a bounded final response, just after fully allocating the oversized
 * array first. {@link #assertCappedStreamAbortsBeforeMaterializing} instead feeds a document
 * byte-identical in shape to what each fixed path now builds directly to the real, unmodified {@link
 * McpRequestDispatcher.CappedOutputStream}, and proves its internal buffer never grows to the
 * document's true, uncapped size before it throws {@link McpRequestDispatcher.OutputCapExceededException}.
 *
 * <p>One control row per path proves the degrade is genuinely conditional, not unconditional: a small
 * id must still echo the original classified code and id, never degrading to the generic id-less
 * internal error — otherwise a mutation that always degraded would pass the over-cap rows undetected.
 *
 * <p><strong>Known limitation, stated rather than hidden — closed by R14 item 4.</strong> Reverting only
 * the call site (e.g. putting {@code codec.errorResponseFor(...)} back in {@code
 * writePreDispatchProtocolRejection}) does not turn any assertion above red: the defective and fixed code
 * degrade to the identical final bytes for any id short of triggering an {@code OutOfMemoryError}, so
 * no external, mock-observable signal distinguishes them — this is the exact "the defective code also
 * produced a bounded response" trap R12 names. R14 item 4 supplies the protection no assertion here
 * could: that overload was deleted (zero callers in main or test), so the mutation no longer
 * <em>compiles</em>, and {@code McpBoundedWritePathArchitectureTest} keeps the three surviving
 * byte-unbounded helpers off every production write path.
 * {@link #assertCappedStreamAbortsBeforeMaterializing} is decisive against a regression in the shared
 * {@link McpRequestDispatcher.CappedOutputStream} mechanism itself (verified by mutation: reordering its
 * bounds check after the write turns both over-cap rows red), which is the only non-flaky signal
 * available for this response shape — a plain id-echoing object has no per-element access hook the way
 * R04's {@code CountingOversizedResultToolInvoker} counts iterable access. The control rows above cover
 * the complementary risk (unconditional degrade) that a call-site regression would actually introduce.
 */
class McpBoundedErrorSerializationTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String HEADER_METHOD = "Mcp-Method";
    private static final String HEADER_NAME = "Mcp-Name";

    /** The validator-enforced floor ({@code McpServerConfigValidator}), used throughout this suite. */
    private static final int OUTPUT_MAX_BYTES = 1_024;

    /**
     * Large enough that the fully enveloped pre-dispatch protocol-rejection or protocol-error response —
     * {@code {"jsonrpc":"2.0","id":"<ID>","error":{"code":...,"message":"..."}}}, well under 100 bytes
     * of fixed overhead — exceeds {@link #OUTPUT_MAX_BYTES}, while staying far under the envelope
     * codec's own frozen 20,000,000-character {@code maxStringLength} so the request still decodes.
     */
    private static final String OVERSIZED_ID = "a".repeat(5_000);

    // --- Path 1: pre-dispatch protocol rejection (writePreDispatchProtocolRejection) ---

    @Test
    @DisplayName("R12: an oversized id degrades an official-params rejection to the bounded id-less shape, "
            + "aborting serialization before the full response is materialized")
    void shouldDegradeOfficialParamsRejectionEarlyWhenIdOverflowsTheCap() throws Exception {
        JsonObject body = discoverBodyMissingMeta(OVERSIZED_ID);
        MultiMap headers = validHeaders("server/discover", null);

        Outcome outcome = drive(body, headers);

        assertThat(outcome.status())
                .as("degraded response is a bounded 500 internal error")
                .isEqualTo(500);
        assertThat(outcome.bodyBytes().length)
                .as("the degraded response must respect the hard output cap")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);
        JsonObject decoded = new JsonObject(Buffer.buffer(outcome.bodyBytes()));
        assertThat(decoded.getValue("id"))
                .as("the oversized id must never reach the wire")
                .isNull();
        assertThat(decoded.getJsonObject("error").getInteger("code"))
                .as("a degraded response reports the generic internal error, not -32602")
                .isEqualTo(-32603);
        assertThat(outcome.rawBody())
                .as("DECISIVE: the oversized id text must never reach the wire in any form, even partially")
                .doesNotContain("a".repeat(64));

        // DECISIVE ordering proof: the live outcome above cannot by itself distinguish a streaming abort
        // from an implementation that first materializes the full byte array and then rejects it on
        // length — both produce the same bounded external response (this is exactly the R12 defect).
        assertCappedStreamAbortsBeforeMaterializing(OUTPUT_MAX_BYTES, OVERSIZED_ID, -32602, "Invalid params");
    }

    @Test
    @DisplayName("R12 control: a small id is echoed as-is by an official-params rejection, never degraded")
    void shouldNotDegradeOfficialParamsRejectionWhenIdFitsUnderTheCap() throws Exception {
        JsonObject body = discoverBodyMissingMeta("1");
        MultiMap headers = validHeaders("server/discover", null);

        Outcome outcome = drive(body, headers);

        assertThat(outcome.status()).isEqualTo(400);
        JsonObject decoded = new JsonObject(Buffer.buffer(outcome.bodyBytes()));
        assertThat(decoded.getValue("id"))
                .as("CONTROL: a response that fits under the cap must still echo the real id")
                .isEqualTo("1");
        assertThat(decoded.getJsonObject("error").getInteger("code"))
                .as("CONTROL: a response that fits under the cap must still carry the real classified code")
                .isEqualTo(-32602);
    }

    // --- Path 2: ordinary protocol error (emitProtocolError) ---

    @Test
    @DisplayName("R12: an oversized id degrades an ordinary protocol error to the bounded id-less shape, "
            + "aborting serialization before the full response is materialized")
    void shouldDegradeOrdinaryProtocolErrorEarlyWhenIdOverflowsTheCap() throws Exception {
        JsonObject body = unknownMethodBody(OVERSIZED_ID);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        Outcome outcome = drive(body, headers);

        assertThat(outcome.status())
                .as("degraded response is a bounded 500 internal error")
                .isEqualTo(500);
        assertThat(outcome.bodyBytes().length)
                .as("the degraded response must respect the hard output cap")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);
        JsonObject decoded = new JsonObject(Buffer.buffer(outcome.bodyBytes()));
        assertThat(decoded.getValue("id"))
                .as("the oversized id must never reach the wire")
                .isNull();
        assertThat(decoded.getJsonObject("error").getInteger("code"))
                .as("a degraded response reports the generic internal error, not -32601")
                .isEqualTo(-32603);

        assertCappedStreamAbortsBeforeMaterializing(OUTPUT_MAX_BYTES, OVERSIZED_ID, -32601, "Method not found");
    }

    @Test
    @DisplayName("R12 control: a small id is echoed as-is by an ordinary protocol error, never degraded")
    void shouldNotDegradeOrdinaryProtocolErrorWhenIdFitsUnderTheCap() throws Exception {
        JsonObject body = unknownMethodBody("1");
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();

        Outcome outcome = drive(body, headers);

        assertThat(outcome.status()).isEqualTo(404);
        JsonObject decoded = new JsonObject(Buffer.buffer(outcome.bodyBytes()));
        assertThat(decoded.getValue("id"))
                .as("CONTROL: a response that fits under the cap must still echo the real id")
                .isEqualTo("1");
        assertThat(decoded.getJsonObject("error").getInteger("code"))
                .as("CONTROL: a response that fits under the cap must still carry the real classified code")
                .isEqualTo(-32601);
    }

    // --- Decisive early-rejection proof, shared by both paths ---

    /**
     * Feeds a {@code {jsonrpc, id, error:{code, message}}} document — byte-identical in shape to the
     * {@code errorNode} every error-writing path in {@link McpRequestDispatcher} now builds directly —
     * to the real, unmodified {@link McpRequestDispatcher.CappedOutputStream}, and proves its buffer
     * never grows to the document's true, uncapped size before it throws {@link
     * McpRequestDispatcher.OutputCapExceededException}. Mirrors {@code
     * McpOutputPipelineIT#assertCappedStreamAbortsBeforeMaterializing} exactly, for the error-response
     * shape rather than the tool-result shape.
     */
    private static void assertCappedStreamAbortsBeforeMaterializing(int cap, String id, int code, String message)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);

        byte[] fullBytes = mapper.writeValueAsBytes(response);
        assertThat(fullBytes.length)
                .as("the fixture document must genuinely exceed the cap for this proof to be non-vacuous")
                .isGreaterThan(cap);

        McpRequestDispatcher.CappedOutputStream capped = new McpRequestDispatcher.CappedOutputStream(cap);
        assertThatExceptionOfType(McpRequestDispatcher.OutputCapExceededException.class)
                .isThrownBy(() -> mapper.writeValue(capped, response));
        assertThat(capped.toByteArray().length)
                .as("DECISIVE: the capped stream's internal buffer must never reach the document's true "
                        + fullBytes.length + "-byte size before throwing — proving the abort happens while "
                        + "bytes are being produced, not after the full byte array was already materialized "
                        + "and then measured")
                .isLessThanOrEqualTo(cap)
                .isLessThan(fullBytes.length);
    }

    // --- R14 item 1 (superseding R12): the normalization reader's token bound is node-derived ---

    /**
     * R14 item 1 — the reparse token bound must reject a document the byte cap would admit.
     *
     * <p>R12 set {@code maxTokenCount} to {@code outputMaxBytes} and shipped a test that asserted the
     * <em>constraint value</em>. That test passed either way, because the bound it pinned could never
     * fire: the reader's only input is {@code encodeCapped}'s own output, already at most {@code
     * outputMaxBytes} bytes, and a JSON token never consumes less than one source byte — so {@code
     * tokens <= bytes} always held and {@code maxDocumentLength} rejected first at equality.
     *
     * <p>This proof asserts a <strong>materialized node count</strong> instead. The fixture is a flat
     * array of empty arrays, a few kilobytes long — orders of magnitude inside {@code outputMaxBytes},
     * asserted below so the claim is not taken on trust — sized to exactly the node budget and to one
     * node past it. If the token bound is ever re-derived from a byte figure, the over-budget document
     * parses instead of being rejected and this test turns red; the node-count assertion on the accepted
     * document turns red the same way if the budget silently grows.
     */
    @Test
    @DisplayName("R14 item 1: the normalization reader rejects one node past its budget, far inside the byte cap")
    void shouldBoundNormalizationReparseByMaterializedNodeCountNotBySourceBytes() throws Exception {
        int outputMaxBytes = McpServerConfig.builder().build().outputMaxBytes();
        McpRequestDispatcher dispatcher = dispatcher(
                McpServerConfig.builder().outputMaxBytes(outputMaxBytes).build());
        ObjectMapper reader = dispatcher.normalizationDecoder();

        // nodes = 1 outer + K inner; tokens = 2 + 2K. The budget admits exactly NORMALIZATION_NODE_BUDGET
        // nodes, so K = budget - 1 is the largest accepted document of this shape.
        byte[] atBudget = flatArrayOfEmptyArrays(McpRequestDispatcher.NORMALIZATION_NODE_BUDGET - 1);
        byte[] onePastBudget = flatArrayOfEmptyArrays(McpRequestDispatcher.NORMALIZATION_NODE_BUDGET);

        assertThat(onePastBudget.length)
                .as("NON-VACUITY: the rejected document must be far inside mcp.output.maxBytes, so only a "
                        + "node-derived bound can possibly reject it — the byte bound would admit it")
                .isLessThan(outputMaxBytes / 100);

        Object accepted = reader.readValue(atBudget, Object.class);
        assertThat(countMaterializedNodes(accepted))
                .as("DECISIVE: the largest document this reader accepts must materialize no more than the "
                        + "stated node budget — this is the quantity the heap derivation bounds, and it is "
                        + "asserted on the real materialized value, not on the constraint's configured number")
                .isEqualTo(McpRequestDispatcher.NORMALIZATION_NODE_BUDGET)
                .isLessThanOrEqualTo(McpRequestDispatcher.NORMALIZATION_NODE_BUDGET);

        assertThatExceptionOfType(StreamConstraintsException.class)
                .as("DECISIVE: one node past the budget must be rejected even though the document is "
                        + "kilobytes inside the byte cap — under R12's byte-derived bound this parsed fine "
                        + "and materialized every node")
                .isThrownBy(() -> reader.readValue(onePastBudget, Object.class));
    }

    /**
     * R14 item 1 — the byte half of the bound stays per-instance, the token half stays fixed.
     *
     * <p>{@code maxDocumentLength} is a genuine byte bound and must keep tracking this instance's own
     * {@code mcp.output.maxBytes}; {@code maxTokenCount} must not, because a byte-derived token count is
     * the no-op the test above exists to prevent.
     */
    @Test
    @DisplayName("R14 item 1: maxDocumentLength tracks mcp.output.maxBytes; maxTokenCount does not")
    void shouldKeepTheDocumentBoundPerInstanceAndTheTokenBoundFixed() {
        McpRequestDispatcher small =
                dispatcher(McpServerConfig.builder().outputMaxBytes(2_048).build());
        McpRequestDispatcher large =
                dispatcher(McpServerConfig.builder().outputMaxBytes(8_192).build());

        StreamReadConstraints smallConstraints =
                small.normalizationDecoder().getFactory().streamReadConstraints();
        StreamReadConstraints largeConstraints =
                large.normalizationDecoder().getFactory().streamReadConstraints();

        assertThat(smallConstraints.getMaxDocumentLength()).isEqualTo(2_048L);
        assertThat(largeConstraints.getMaxDocumentLength())
                .as("the byte bound must remain derived from this instance's own configuration")
                .isEqualTo(8_192L);
        assertThat(smallConstraints.getMaxTokenCount())
                .as("DECISIVE: the token bound must be the fixed node-derived cap, identical across "
                        + "differently configured instances — a value that tracked outputMaxBytes here "
                        + "would be the unreachable R12 bound again")
                .isEqualTo(McpRequestDispatcher.NORMALIZATION_MAX_TOKEN_COUNT)
                .isEqualTo(largeConstraints.getMaxTokenCount());
    }

    /** Builds {@code [[],[],…]} with {@code innerArrays} empty inner arrays — one node each, plus the outer. */
    private static byte[] flatArrayOfEmptyArrays(int innerArrays) {
        StringBuilder json = new StringBuilder(innerArrays * 3 + 2);
        json.append('[');
        for (int i = 0; i < innerArrays; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("[]");
        }
        json.append(']');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Counts every container and scalar instance the canonical Map/List/scalar shape materialized. */
    private static int countMaterializedNodes(Object value) {
        if (value instanceof List<?> list) {
            int total = 1;
            for (Object child : list) {
                total += countMaterializedNodes(child);
            }
            return total;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 1;
            for (Object child : map.values()) {
                total += countMaterializedNodes(child);
            }
            return total;
        }
        return 1;
    }

    // --- Shared framework construction ---

    private Outcome drive(JsonObject body, MultiMap headers) {
        McpServerConfig config =
                McpServerConfig.builder().outputMaxBytes(OUTPUT_MAX_BYTES).build();
        McpRequestDispatcher dispatcher = dispatcher(config);

        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);

        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer()).thenReturn(Buffer.buffer(body.toBuffer().getBytes()));
        when(request.headers()).thenReturn(headers);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

        dispatcher.dispatch(context);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatusCode(statusCaptor.capture());
        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        return new Outcome(statusCaptor.getValue(), bodyCaptor.getValue().getBytes());
    }

    private static McpRequestDispatcher dispatcher(McpServerConfig config) {
        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);
        return new McpRequestDispatcher(
                config,
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /** One drive's captured HTTP outcome. */
    private record Outcome(int status, byte[] bodyBytes) {
        String rawBody() {
            return new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // --- Body/header builders ---

    /**
     * A {@code server/discover} body whose {@code params} carries no {@code _meta} at all — a
     * official-params rejection ({@code -32602}), not an envelope-decode rejection: the envelope
     * codec only requires {@code params} to be an object, so this still decodes successfully and only
     * fails {@link McpProtocolCodec#validateOfficialParams}'s {@code _meta}-presence check.
     */
    private static JsonObject discoverBodyMissingMeta(String id) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "server/discover")
                .put("params", new JsonObject());
    }

    /**
     * A structurally valid envelope naming a method outside the supported set — {@code -32601}, decoded
     * by {@link McpProtocolCodec#decodeEnvelope} itself, reaching {@link
     * McpRequestDispatcher#emitProtocolError} directly from {@link McpRequestDispatcher#dispatch}
     * before negotiation or any interceptor ever runs.
     */
    private static JsonObject unknownMethodBody(String id) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "not/a/real/method")
                .put("params", new JsonObject());
    }

    private static MultiMap validHeaders(String method, String toolName) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION);
        headers.set(HEADER_METHOD, method);
        headers.set(HEADER_NAME, toolName != null ? toolName : method);
        return headers;
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding (R09). */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };
}
