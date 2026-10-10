// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Proves every negotiation rejection carries a bounded, enum-valued {@code error.data.reason}. The
 * code is {@code -32020} for every cause except an unsupported version, which is {@code -32022}; the
 * HTTP status and {@code message} of each cause are unchanged.
 *
 * <p>The codec-level table covers every rejection cause {@link
 * McpProtocolCodec#validateNegotiation} can produce (some are unreachable through the dispatcher
 * because the official params schema rejects them first). The wire-level table drives {@link
 * McpRequestDispatcher#dispatch} for the causes a client can actually trigger, asserting the bytes
 * the client receives. Reason constants are spelled out as literals here on purpose: they are wire
 * values, so a rename must fail this table.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpNegotiationRejectionReasonTest {

    private static final String VERSION = "2026-07-28";
    private static final String TOOL = "greet";
    private static final String MESSAGE = "Header/body mismatch";
    private static final String UNSUPPORTED_MESSAGE = "Unsupported protocol version";

    /** The code of every negotiation rejection except an unsupported version. */
    private static final int NEGOTIATION_CODE = -32020;

    /** The code of an unsupported-version rejection. */
    private static final int UNSUPPORTED_VERSION_CODE = -32022;

    /** Text no response may ever echo through {@code error.data.reason}. */
    private static final String HOSTILE = "<script>alert('x')</script>\"evil";

    private static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    private static final String MISSING_HEADER = "MISSING_HEADER";
    private static final String HEADER_MISMATCH = "HEADER_MISMATCH";
    private static final String META_SHAPE = "META_SHAPE";
    private static final String RESERVED_FIELD = "RESERVED_FIELD";
    private static final String UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";

    private Logger codecLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureCodecLogs() {
        codecLogger = (Logger) LoggerFactory.getLogger(McpProtocolCodec.class);
        previousLevel = codecLogger.getLevel();
        codecLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        codecLogger.addAppender(appender);
    }

    @AfterEach
    void releaseCodecLogs() {
        codecLogger.detachAppender(appender);
        appender.stop();
        codecLogger.setLevel(previousLevel);
    }

    // --- Codec-level table: every cause ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecRows")
    @DisplayName("each rejection cause reports exactly its bounded reason and keeps its message")
    void shouldReportBoundedReasonForEveryCause(Row row) {
        McpProtocolCodec.CodecError error = negotiate(row);

        assertThat(error).as(row.name() + " must be rejected").isNotNull();
        assertThat(error.code()).isEqualTo(expectedCode(row));
        assertThat(error.message()).isEqualTo(row.expectedMessage());
        JsonObject data = dataOf(error);
        assertThat(data).as(row.name() + " must carry error.data").isNotNull();
        assertThat(data.getString("reason")).isEqualTo(row.expectedReason());
        if (UNSUPPORTED_VERSION.equals(row.expectedReason())) {
            assertThat(data.fieldNames())
                    .as("the existing unsupported-version members stay alongside the reason")
                    .containsExactlyInAnyOrder("reason", "supported", "requested");
            assertThat(data.getJsonArray("supported")).isEqualTo(new JsonArray().add(VERSION));
            assertThat(data.getString("requested")).isEqualTo(row.requested());
        } else {
            assertThat(data)
                    .as(row.name() + " data is exactly the reason constant")
                    .isEqualTo(new JsonObject().put("reason", row.expectedReason()));
        }
    }

    @Test
    @DisplayName("an accepted request carries no rejection")
    void shouldNotRejectAFullyNegotiatedRequest() {
        McpProtocolCodec codec = codec();
        McpProtocolCodec.Decoded decoded =
                codec.decodeEnvelope(callBody(callParams()).toBuffer().getBytes());

        McpProtocolCodec.NegotiationResult result =
                codec.validateNegotiation(decoded.envelope(), headers("tools/call", TOOL));

        assertThat(result.isError()).isFalse();
        assertThat(negotiationLogs()).isEmpty();
    }

    // --- Client-supplied text never becomes a reason ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileRows")
    @DisplayName("hostile client text never reaches error.data.reason")
    void shouldNeverEchoClientTextIntoTheReason(Row row) {
        McpProtocolCodec.CodecError error = negotiate(row);

        assertThat(error).isNotNull();
        JsonObject data = dataOf(error);
        assertThat(data).isNotNull();
        assertThat(data.getString("reason")).isEqualTo(row.expectedReason());
        assertThat(data.getString("reason")).doesNotContain("script").doesNotContain("evil");
        if (!UNSUPPORTED_VERSION.equals(row.expectedReason())) {
            assertThat(data)
                    .as("a non-version cause carries exactly the constant, never the offending value")
                    .isEqualTo(new JsonObject().put("reason", row.expectedReason()));
            assertThat(error.message()).isEqualTo(MESSAGE);
        }
    }

    // --- Exactly one bounded DEBUG line per rejection ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecRows")
    @DisplayName("each rejection logs exactly one DEBUG line naming only the reason")
    void shouldLogOneBoundedDebugLinePerRejection(Row row) {
        negotiate(row);

        List<ILoggingEvent> logs = negotiationLogs();
        assertThat(logs).hasSize(1);
        ILoggingEvent event = logs.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage()).contains(row.expectedReason());
        assertThat(event.getFormattedMessage()).doesNotContain(HOSTILE).doesNotContain(VERSION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileRows")
    @DisplayName("the DEBUG line never carries client-supplied text")
    void shouldNeverLogClientText(Row row) {
        negotiate(row);

        assertThat(negotiationLogs()).hasSize(1);
        assertThat(negotiationLogs().get(0).getFormattedMessage())
                .doesNotContain("script")
                .doesNotContain("evil");
    }

    // --- Wire level: what the client receives ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("wireRows")
    @DisplayName("the HTTP response carries status 400, the cause's code, its unchanged message and the reason")
    void shouldReportReasonOnTheWire(Row row) {
        Wire wire = drive(row.body(), row.headers());

        assertThat(wire.status()).isEqualTo(400);
        JsonObject error = wire.body().getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(expectedCode(row));
        assertThat(error.getString("message")).isEqualTo(row.expectedMessage());
        JsonObject data = error.getJsonObject("data");
        assertThat(data).as(row.name() + " must carry error.data on the wire").isNotNull();
        assertThat(data.getString("reason")).isEqualTo(row.expectedReason());
        if (UNSUPPORTED_VERSION.equals(row.expectedReason())) {
            assertThat(data.fieldNames()).containsExactlyInAnyOrder("reason", "supported", "requested");
        } else {
            assertThat(data).isEqualTo(new JsonObject().put("reason", row.expectedReason()));
        }
    }

    @Test
    @DisplayName("an unsupported version is -32022 with exactly the supported, requested and reason members")
    void shouldReportAnUnsupportedVersionWithTheSchemaCodeAndItsRequiredData() {
        Wire wire = drive(
                discover(meta().put(META_VERSION, "1999-01-01")),
                headers("server/discover", null).set("MCP-Protocol-Version", "1999-01-01"));

        assertThat(wire.status()).isEqualTo(400);
        JsonObject error = wire.body().getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(-32022);
        assertThat(error.getString("message")).isEqualTo(UNSUPPORTED_MESSAGE);
        assertThat(error.getJsonObject("data"))
                .isEqualTo(new JsonObject()
                        .put("supported", new JsonArray().add(VERSION))
                        .put("requested", "1999-01-01")
                        .put("reason", UNSUPPORTED_VERSION));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileWireRows")
    @DisplayName("a hostile header or field is never echoed anywhere in the response body")
    void shouldNotEchoHostileTextOnTheWire(Row row) {
        Wire wire = drive(row.body(), row.headers());

        assertThat(wire.status()).isEqualTo(400);
        assertThat(wire.body().getJsonObject("error").getJsonObject("data"))
                .isEqualTo(new JsonObject().put("reason", row.expectedReason()));
        assertThat(wire.rawBody()).doesNotContain("script").doesNotContain("evil");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaRejectedWireRows")
    @DisplayName("shapes the official params schema rejects first are -32602, not -32020")
    void shouldRejectSchemaShapesAsInvalidParamsBeforeNegotiation(Row row) {
        Wire wire = drive(row.body(), row.headers());

        assertThat(wire.status()).isEqualTo(400);
        JsonObject error = wire.body().getJsonObject("error");
        assertThat(error.getInteger("code")).isEqualTo(-32602);
        assertThat(error.getString("message")).isEqualTo(row.expectedMessage());
        assertThat(error.containsKey("data")).isFalse();
    }

    private static int expectedCode(Row row) {
        return UNSUPPORTED_VERSION.equals(row.expectedReason()) ? UNSUPPORTED_VERSION_CODE : NEGOTIATION_CODE;
    }

    // --- Rows ---

    private static Stream<Row> codecRows() {
        return Stream.concat(
                Stream.concat(metaRows(), reservedRows()),
                Stream.concat(
                        headerRows(),
                        Stream.concat(
                                headerOrderRows(),
                                Stream.concat(precedenceRows(), unsupportedVersionWithHeaderFaultRows()))));
    }

    private static Stream<Row> metaRows() {
        JsonObject noMeta = list(null).put("params", new JsonObject());
        JsonObject nonObjectMeta = list(null).put("params", new JsonObject().put("_meta", "not-an-object"));
        return Stream.of(
                Row.codec("absent _meta", noMeta, headers("tools/list", null), META_SHAPE),
                Row.codec("non-object _meta", nonObjectMeta, headers("tools/list", null), META_SHAPE),
                Row.codec(
                        "missing protocolVersion",
                        list(without(meta(), META_VERSION)),
                        headers("tools/list", null),
                        META_SHAPE),
                Row.codec(
                        "non-string protocolVersion",
                        list(meta().put(META_VERSION, 20260728)),
                        headers("tools/list", null),
                        META_SHAPE),
                Row.codec(
                        "blank protocolVersion",
                        list(meta().put(META_VERSION, "   ")),
                        headers("tools/list", null),
                        META_SHAPE),
                Row.codec(
                        "oversized protocolVersion",
                        list(meta().put(META_VERSION, "9".repeat(65))),
                        headers("tools/list", null),
                        META_SHAPE),
                Row.codec(
                        "control character in protocolVersion",
                        list(meta().put(META_VERSION, VERSION + "\t")),
                        headers("tools/list", null).set("MCP-Protocol-Version", VERSION + "\t"),
                        META_SHAPE),
                Row.unsupported(
                        "unsupported protocolVersion",
                        list(meta().put(META_VERSION, "1999-01-01")),
                        headers("tools/list", null).set("MCP-Protocol-Version", "1999-01-01"),
                        "1999-01-01"),
                Row.codec(
                        "missing clientCapabilities",
                        list(without(meta(), META_CAPABILITIES)),
                        headers("tools/list", null),
                        META_SHAPE),
                Row.codec(
                        "non-object clientCapabilities",
                        list(meta().put(META_CAPABILITIES, "none")),
                        headers("tools/list", null),
                        META_SHAPE));
    }

    private static Stream<Row> reservedRows() {
        return Stream.of(
                Row.codec(
                        "reserved inputResponses",
                        callBody(callParams().put("inputResponses", new JsonObject())),
                        headers("tools/call", TOOL),
                        RESERVED_FIELD),
                Row.codec(
                        "reserved requestState",
                        callBody(callParams().put("requestState", "opaque")),
                        headers("tools/call", TOOL),
                        RESERVED_FIELD));
    }

    private static Stream<Row> headerRows() {
        JsonObject discover = discover();
        return Stream.of(
                Row.codec(
                        "missing MCP-Protocol-Version",
                        discover,
                        headers("server/discover", null).remove("MCP-Protocol-Version"),
                        MISSING_HEADER),
                Row.codec(
                        "mismatched MCP-Protocol-Version",
                        discover,
                        headers("server/discover", null).set("MCP-Protocol-Version", "1999-01-01"),
                        HEADER_MISMATCH),
                Row.codec(
                        "duplicated MCP-Protocol-Version",
                        discover,
                        headers("server/discover", null).add("MCP-Protocol-Version", VERSION),
                        HEADER_MISMATCH),
                Row.codec(
                        "missing Mcp-Method",
                        discover,
                        headers("server/discover", null).remove("Mcp-Method"),
                        MISSING_HEADER),
                Row.codec(
                        "mismatched Mcp-Method",
                        discover,
                        headers("server/discover", null).set("Mcp-Method", "tools/list"),
                        HEADER_MISMATCH),
                Row.codec(
                        "duplicated Mcp-Method",
                        discover,
                        headers("server/discover", null).add("Mcp-Method", "server/discover"),
                        HEADER_MISMATCH),
                Row.codec(
                        "missing Mcp-Name on tools/call",
                        callBody(callParams()),
                        headers("tools/call", null),
                        MISSING_HEADER),
                Row.codec(
                        "mismatched Mcp-Name on tools/call",
                        callBody(callParams()),
                        headers("tools/call", "other"),
                        HEADER_MISMATCH),
                Row.codec(
                        "duplicated Mcp-Name on tools/call",
                        callBody(callParams()),
                        headers("tools/call", TOOL).add("Mcp-Name", TOOL),
                        HEADER_MISMATCH));
    }

    /**
     * Several header faults at once: the protocol-version header is checked first, then the method
     * header, then the name header. Each row would report {@code MISSING_HEADER} if the order changed.
     */
    private static Stream<Row> headerOrderRows() {
        return Stream.of(
                Row.codec(
                        "mismatched protocol-version header outranks missing Mcp-Method",
                        discover(),
                        headers("server/discover", null).remove("Mcp-Method").set("MCP-Protocol-Version", "1999-01-01"),
                        HEADER_MISMATCH),
                Row.codec(
                        "mismatched Mcp-Method outranks missing Mcp-Name",
                        callBody(callParams()),
                        headers("tools/call", null).set("Mcp-Method", "tools/list"),
                        HEADER_MISMATCH));
    }

    /** Several faults at once: the first failing check keeps deciding, as before. */
    private static Stream<Row> precedenceRows() {
        return Stream.of(
                Row.codec(
                        "reserved field outranks missing headers",
                        callBody(callParams().put("requestState", "opaque")),
                        MultiMap.caseInsensitiveMultiMap(),
                        RESERVED_FIELD),
                Row.unsupported(
                        "unsupported version outranks missing clientCapabilities",
                        list(without(meta(), META_CAPABILITIES).put(META_VERSION, "1999-01-01")),
                        headers("tools/list", null).set("MCP-Protocol-Version", "1999-01-01"),
                        "1999-01-01"),
                Row.codec(
                        "bad _meta outranks missing headers",
                        list(null).put("params", new JsonObject().put("_meta", "x")),
                        MultiMap.caseInsensitiveMultiMap(),
                        META_SHAPE));
    }

    /**
     * An unsupported body version whose {@code MCP-Protocol-Version} header is absent or disagrees
     * is a header fault, not an unsupported-version rejection: only a request whose header and body
     * agree on an unsupported version is reported as one.
     */
    private static Stream<Row> unsupportedVersionWithHeaderFaultRows() {
        JsonObject unsupported = discover(meta().put(META_VERSION, "v999.0.0"));
        return Stream.of(
                Row.codec(
                        "unsupported body version with a mismatched protocol-version header",
                        unsupported,
                        headers("server/discover", null),
                        HEADER_MISMATCH),
                Row.codec(
                        "unsupported body version with a duplicated protocol-version header",
                        unsupported,
                        headers("server/discover", null)
                                .set("MCP-Protocol-Version", "v999.0.0")
                                .add("MCP-Protocol-Version", "v999.0.0"),
                        HEADER_MISMATCH),
                Row.codec(
                        "unsupported body version with no protocol-version header",
                        unsupported,
                        headers("server/discover", null).remove("MCP-Protocol-Version"),
                        MISSING_HEADER));
    }

    private static Stream<Row> hostileRows() {
        return Stream.concat(
                hostileWireRows(),
                Stream.of(
                        Row.codec(
                                "hostile inputResponses member name and value",
                                callBody(callParams().put("inputResponses", new JsonObject().put(HOSTILE, HOSTILE))),
                                headers("tools/call", TOOL),
                                RESERVED_FIELD),
                        Row.unsupported(
                                "hostile unsupported version",
                                list(meta().put(META_VERSION, "2999-01-01" + HOSTILE)),
                                headers("tools/list", null).set("MCP-Protocol-Version", "2999-01-01" + HOSTILE),
                                "2999-01-01" + HOSTILE)));
    }

    private static Stream<Row> hostileWireRows() {
        return Stream.of(
                Row.codec(
                        "hostile MCP-Protocol-Version",
                        discover(),
                        headers("server/discover", null).set("MCP-Protocol-Version", HOSTILE),
                        HEADER_MISMATCH),
                Row.codec(
                        "hostile Mcp-Method",
                        discover(),
                        headers("server/discover", null).set("Mcp-Method", HOSTILE),
                        HEADER_MISMATCH),
                Row.codec("hostile Mcp-Name", callBody(callParams()), headers("tools/call", HOSTILE), HEADER_MISMATCH),
                Row.codec(
                        "hostile reserved requestState value",
                        callBody(callParams().put("requestState", HOSTILE)),
                        headers("tools/call", TOOL),
                        RESERVED_FIELD));
    }

    /** The causes a client can trigger through the dispatcher: the official params schema passes. */
    private static Stream<Row> wireRows() {
        return Stream.concat(
                Stream.concat(headerOrderRows(), unsupportedVersionWithHeaderFaultRows()),
                Stream.of(
                        Row.codec(
                                "blank protocolVersion",
                                discover(meta().put(META_VERSION, "   ")),
                                headers("server/discover", null).set("MCP-Protocol-Version", "   "),
                                META_SHAPE),
                        Row.codec(
                                "oversized protocolVersion",
                                discover(meta().put(META_VERSION, "9".repeat(65))),
                                headers("server/discover", null).set("MCP-Protocol-Version", "9".repeat(65)),
                                META_SHAPE),
                        Row.codec(
                                "missing MCP-Protocol-Version",
                                discover(),
                                headers("server/discover", null).remove("MCP-Protocol-Version"),
                                MISSING_HEADER),
                        Row.codec(
                                "mismatched MCP-Protocol-Version",
                                discover(),
                                headers("server/discover", null).set("MCP-Protocol-Version", "1999-01-01"),
                                HEADER_MISMATCH),
                        Row.codec(
                                "missing Mcp-Method",
                                discover(),
                                headers("server/discover", null).remove("Mcp-Method"),
                                MISSING_HEADER),
                        Row.codec(
                                "mismatched Mcp-Method",
                                discover(),
                                headers("server/discover", null).set("Mcp-Method", "tools/list"),
                                HEADER_MISMATCH),
                        Row.codec(
                                "missing Mcp-Name on tools/call",
                                callBody(callParams()),
                                headers("tools/call", null),
                                MISSING_HEADER),
                        Row.codec(
                                "mismatched Mcp-Name on tools/call",
                                callBody(callParams()),
                                headers("tools/call", "other"),
                                HEADER_MISMATCH),
                        Row.codec(
                                "reserved requestState",
                                callBody(callParams().put("requestState", "opaque")),
                                headers("tools/call", TOOL),
                                RESERVED_FIELD),
                        Row.codec(
                                "control character in protocolVersion",
                                discover(meta().put(META_VERSION, VERSION + "\t")),
                                headers("server/discover", null).set("MCP-Protocol-Version", VERSION + "\t"),
                                META_SHAPE),
                        Row.unsupported(
                                "unsupported protocolVersion",
                                discover(meta().put(META_VERSION, "1999-01-01")),
                                headers("server/discover", null).set("MCP-Protocol-Version", "1999-01-01"),
                                "1999-01-01")));
    }

    /**
     * Shapes the codec also guards but the official params schema rejects first, so a client never
     * sees {@code -32020} for them.
     */
    private static Stream<Row> schemaRejectedWireRows() {
        return Stream.of(
                schemaRejected("absent _meta", list(null), headers("tools/list", null)),
                schemaRejected(
                        "non-object _meta",
                        list(null).put("params", new JsonObject().put("_meta", "not-an-object")),
                        headers("tools/list", null)),
                schemaRejected(
                        "missing protocolVersion", list(without(meta(), META_VERSION)), headers("tools/list", null)),
                schemaRejected(
                        "non-string protocolVersion",
                        list(meta().put(META_VERSION, 20260728)),
                        headers("tools/list", null)),
                schemaRejected(
                        "missing clientCapabilities",
                        list(without(meta(), META_CAPABILITIES)),
                        headers("tools/list", null)),
                schemaRejected(
                        "non-object clientCapabilities",
                        list(meta().put(META_CAPABILITIES, "none")),
                        headers("tools/list", null)));
    }

    private static Row schemaRejected(String name, JsonObject body, MultiMap headers) {
        return new Row(name, body, headers, null, "Invalid params", null);
    }

    // --- Fixture ---

    private static McpProtocolCodec codec() {
        return new McpProtocolCodec(
                HttpConfig.builder().build(), McpServerConfig.defaults().ingressMaxTokens());
    }

    /** Runs the row's request through the codec's negotiation step and returns its rejection, if any. */
    private static McpProtocolCodec.CodecError negotiate(Row row) {
        McpProtocolCodec codec = codec();
        McpProtocolCodec.Decoded decoded =
                codec.decodeEnvelope(row.body().toBuffer().getBytes());
        assertThat(decoded.isError())
                .as(row.name() + " must decode as an envelope")
                .isFalse();
        return codec.validateNegotiation(decoded.envelope(), row.headers()).error();
    }

    private static JsonObject dataOf(McpProtocolCodec.CodecError error) {
        return error.data() == null ? null : new JsonObject(error.data().toString());
    }

    private List<ILoggingEvent> negotiationLogs() {
        return List.copyOf(appender.list);
    }

    private static JsonObject meta() {
        return new JsonObject().put(META_VERSION, VERSION).put(META_CAPABILITIES, new JsonObject());
    }

    private static JsonObject discover() {
        return discover(meta());
    }

    private static JsonObject discover(JsonObject meta) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", meta));
    }

    private static JsonObject list(JsonObject meta) {
        JsonObject body = new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");
        return body.put("params", meta == null ? new JsonObject() : new JsonObject().put("_meta", meta));
    }

    private static JsonObject callParams() {
        return new JsonObject().put("_meta", meta()).put("name", TOOL).put("arguments", new JsonObject());
    }

    private static JsonObject callBody(JsonObject params) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params);
    }

    private static JsonObject without(JsonObject object, String key) {
        object.remove(key);
        return object;
    }

    /** Required headers for {@code method}; {@code name} adds an {@code Mcp-Name} when non-null. */
    private static MultiMap headers(String method, String name) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", VERSION);
        headers.set("Mcp-Method", method);
        if (name != null) {
            headers.set("Mcp-Name", name);
        }
        return headers;
    }

    private Wire drive(JsonObject body, MultiMap headers) {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);
        McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                mock(McpToolRegistry.class),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));

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

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatusCode(status.capture());
        ArgumentCaptor<Buffer> bytes = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bytes.capture());
        return new Wire(status.getValue(), bytes.getValue().toString());
    }

    private record Wire(int status, String rawBody) {
        JsonObject body() {
            return new JsonObject(rawBody);
        }
    }

    private record Row(
            String name,
            JsonObject body,
            MultiMap headers,
            String expectedReason,
            String expectedMessage,
            String requested) {

        static Row codec(String name, JsonObject body, MultiMap headers, String reason) {
            return new Row(name, body, headers, reason, MESSAGE, null);
        }

        static Row unsupported(String name, JsonObject body, MultiMap headers, String requested) {
            return new Row(name, body, headers, UNSUPPORTED_VERSION, UNSUPPORTED_MESSAGE, requested);
        }

        @Override
        public String toString() {
            return name;
        }
    }

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
