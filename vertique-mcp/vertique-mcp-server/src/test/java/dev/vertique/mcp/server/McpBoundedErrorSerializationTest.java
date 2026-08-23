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
 * names by name: the negotiation-rejection path ({@link McpRequestDispatcher#writeNegotiationRejection})
 * and the ordinary protocol-error path ({@link McpRequestDispatcher#emitProtocolError}).
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
 * <p><strong>Known limitation, stated rather than hidden.</strong> Reverting only the call site (e.g.
 * putting {@code codec.errorResponseFor(...)} back in {@code writeNegotiationRejection}) does not turn
 * any assertion above red: the defective and fixed code degrade to the identical final bytes for any id
 * short of triggering an {@code OutOfMemoryError}, so no external, mock-observable signal distinguishes
 * them — this is the exact "the defective code also produced a bounded response" trap R12 names.
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
     * Large enough that the fully enveloped negotiation-rejection or protocol-error response —
     * {@code {"jsonrpc":"2.0","id":"<ID>","error":{"code":...,"message":"..."}}}, well under 100 bytes
     * of fixed overhead — exceeds {@link #OUTPUT_MAX_BYTES}, while staying far under the envelope
     * codec's own frozen 20,000,000-character {@code maxStringLength} so the request still decodes.
     */
    private static final String OVERSIZED_ID = "a".repeat(5_000);

    // --- Path 1: negotiation rejection (writeNegotiationRejection) ---

    @Test
    @DisplayName("R12: an oversized id degrades a negotiation rejection to the bounded id-less shape, "
            + "aborting serialization before the full response is materialized")
    void shouldDegradeNegotiationRejectionEarlyWhenIdOverflowsTheCap() throws Exception {
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
                .as("a degraded response reports the generic internal error, not -32020")
                .isEqualTo(-32603);
        assertThat(outcome.rawBody())
                .as("DECISIVE: the oversized id text must never reach the wire in any form, even partially")
                .doesNotContain("a".repeat(64));

        // DECISIVE ordering proof: the live outcome above cannot by itself distinguish a streaming abort
        // from an implementation that first materializes the full byte array and then rejects it on
        // length — both produce the same bounded external response (this is exactly the R12 defect).
        assertCappedStreamAbortsBeforeMaterializing(OUTPUT_MAX_BYTES, OVERSIZED_ID, -32020, "Header/body mismatch");
    }

    @Test
    @DisplayName("R12 control: a small id is echoed as-is by a negotiation rejection, never degraded")
    void shouldNotDegradeNegotiationRejectionWhenIdFitsUnderTheCap() throws Exception {
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
                .isEqualTo(-32020);
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

    // --- R12 also-in-scope: NORMALIZATION_DECODER constraints are derived from outputMaxBytes ---

    /**
     * Structural regression pin, not a behavioral rejection proof — the derivation ({@code
     * normalizationDecoder}'s own javadoc in {@link McpRequestDispatcher} explains why) makes {@code
     * maxTokenCount}/{@code maxDocumentLength} mathematically incapable of ever rejecting a document
     * that already passed the byte cap that produced it (a JSON token can never be shorter than one
     * byte of source text), so no functional "it now rejects X" test can exist without contradicting
     * that same cap. This instead pins that the constraint is genuinely <em>derived</em> from {@code
     * outputMaxBytes} — not a copy-pasted constant — by varying the config and observing both bounds
     * track it, which would fail if a future edit hardcoded a fixed value or dropped the constraint back
     * to Jackson's implicit unbounded default.
     */
    @Test
    @DisplayName("R12: NORMALIZATION_DECODER's maxTokenCount/maxDocumentLength track mcp.output.maxBytes")
    void shouldDeriveNormalizationDecoderConstraintsFromOutputMaxBytes() {
        McpRequestDispatcher small =
                dispatcher(McpServerConfig.builder().outputMaxBytes(2_048).build());
        McpRequestDispatcher large =
                dispatcher(McpServerConfig.builder().outputMaxBytes(8_192).build());

        StreamReadConstraints smallConstraints =
                small.normalizationDecoder().getFactory().streamReadConstraints();
        StreamReadConstraints largeConstraints =
                large.normalizationDecoder().getFactory().streamReadConstraints();

        assertThat(smallConstraints.getMaxTokenCount())
                .as("DECISIVE: maxTokenCount must equal this instance's own outputMaxBytes, not a fixed "
                        + "constant shared across instances")
                .isEqualTo(2_048L);
        assertThat(largeConstraints.getMaxTokenCount())
                .as("DECISIVE: a differently configured instance must derive a different maxTokenCount")
                .isEqualTo(8_192L);
        assertThat(smallConstraints.getMaxDocumentLength()).isEqualTo(2_048L);
        assertThat(largeConstraints.getMaxDocumentLength()).isEqualTo(8_192L);
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
     * negotiation-stage rejection ({@code -32020}), not an envelope-decode rejection: the envelope
     * codec only requires {@code params} to be an object, so this still decodes successfully and only
     * fails {@link McpProtocolCodec#validateNegotiation}'s {@code _meta}-presence check.
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
