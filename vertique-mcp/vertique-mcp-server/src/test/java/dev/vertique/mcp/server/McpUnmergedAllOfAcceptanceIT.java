// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpResultType;
import dev.vertique.mcp.server.McpUnmergedAllOfAcceptanceITFixture.CountingToolInvoker;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * gap-3c89fb8e: an MCP tool-input false-reject defect. When victools cannot consolidate an {@code
 * allOf} whose parts both declare the same property with different schemas — a polymorphic subtype
 * whose discriminator {@code kind} is also a declared property (HJ03's {@code J03AnySub} /
 * {@code J03PlainSub}), or a {@code @JsonUnwrapped} child whose definition provider leaves an
 * {@code allOf} part with the child's own properties (UW2's {@code UW2UnwrappedAlias}) — an
 * unconsolidated {@code allOf} left in the input document lets {@code
 * McpSchemaHardener.closeRecursively} close each part separately with {@code additionalProperties:
 * false}. Every key outside one part then becomes an "additional property" of the other, so a body the
 * binder and the REST gate both accept is rejected at the MCP tool-input boundary. {@link AllOfFold}
 * (in {@code vertique-json-schema}) folds every such {@code allOf} into one flat {@code properties}
 * set before this boundary's schema is published, removing the shape the closure guard targets.
 *
 * <p>Measured rows: {@code docs/specs/rest-021-deserializer-driven-schema-description
 * /evidence/proof-runs/gap-3c89fb8e-v.txt} lines 68-71 (HJ03) and 115-118 (UW2) — binder ACCEPT, REST
 * ACCEPT, MCP REJECT for both {@code HJ03} and {@code UW2} (the pre-fix measurement). The shapes are
 * copied verbatim from that package's {@code evidence/proof-harness/deser-validation-bv/src/probe
 * /Shapes.java}.
 *
 * <p>Each acceptance row proves the fold widens acceptance to match the binder/REST gate; each control
 * row proves a rejection the fold must never widen away — a wrong-typed value, an over-long alias, a
 * key closed off to one subtype branch, or a key the unwrapped child's own {@code propertyNames}
 * exclusion still reserves. Every rejection row asserts the terminal event's {@link
 * McpErrorType#INPUT_VALIDATION} classification and that {@code prepare()} was never entered, matching
 * {@link McpToolInputShapesIT}'s own convention so a binder rejection can never be mistaken for the
 * stage-1 schema rejection this defect concerns.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpUnmergedAllOfAcceptanceIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String SCHEMA_MESSAGE = "Invalid tool arguments: schema validation failed";

    private final Vertx vertx = Vertx.vertx();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);

    private McpUnmergedAllOfAcceptanceITFixture fixture;
    private io.vertx.core.http.HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("HJ03: the any-branch's valid body is accepted (the folded branch carries 'name'/'x'"
            + " in its own flat properties set, so they are never rejected as additional properties of"
            + " J03PlainSub's sibling branch)")
    void hj03AnyBranchValidBodyIsAccepted() throws Exception {
        startServer();

        assertAccepted(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put(
                                "value",
                                new JsonObject()
                                        .put("kind", "any")
                                        .put("name", "a")
                                        .put("x", "1")),
                "HJ03 (evidence/proof-runs/gap-3c89fb8e-v.txt line 68-71): binder ACCEPT, REST ACCEPT,"
                        + " but the MCP hardener closes each unmerged allOf part separately, so 'name' and 'x'"
                        + " — properties of J03AnySub's part only — are rejected as additional properties of"
                        + " J03PlainSub's part");
    }

    @Test
    @DisplayName("HJ03: the plain-branch's valid body is accepted ('other' is folded into"
            + " J03PlainSub's own flat properties set, not rejected as an additional property of"
            + " J03AnySub's sibling branch; control row)")
    void hj03PlainBranchValidBodyIsAccepted() throws Exception {
        startServer();

        assertAccepted(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put("value", new JsonObject().put("kind", "plain").put("other", "x")),
                "HJ03 plain branch: 'other' is a property of J03PlainSub's part only; under the same"
                        + " unmerged-allOf closure that rejects the any branch, it must be rejected as an"
                        + " additional property of J03AnySub's part");
    }

    @Test
    @DisplayName("HJ03: a wrong-typed extra on the any branch stays INPUT_VALIDATION (control: rejected"
            + " independent of the allOf-fold behavior)")
    void hj03WrongTypedExtraStaysRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put(
                                "value",
                                new JsonObject()
                                        .put("kind", "any")
                                        .put("name", "a")
                                        .put("x", 1)),
                "CONTROL: 'x' is J03AnySub's String-valued any-setter extra; the number 1 must be"
                        + " rejected against its String type (a 'type' keyword failure), independent of the"
                        + " allOf-fold fix — a fix that widens acceptance must not also widen this rejection"
                        + " away");
    }

    @Test
    @DisplayName("UW2: the unwrapped alias's valid body is accepted ('nm' — the unwrapped child's"
            + " alias — is folded into the parent's own flat properties set, not rejected as an"
            + " additional property of the child's own allOf part)")
    void uw2ValidBodyIsAccepted() throws Exception {
        startServer();

        assertAccepted(
                McpUnmergedAllOfAcceptanceITFixture.UW2_TOOL,
                new JsonObject().put("label", "l").put("nm", "ab"),
                "UW2 (evidence/proof-runs/gap-3c89fb8e-v.txt line 115-118): binder ACCEPT, REST ACCEPT,"
                        + " but the MCP hardener closes each unmerged allOf part separately, so 'nm' — the"
                        + " unwrapped child's alias, published on the child's own allOf part — is rejected as"
                        + " an additional property of the parent's part");
    }

    @Test
    @DisplayName("UW2: an over-long alias value stays INPUT_VALIDATION (control: rejected independent"
            + " of the allOf-fold behavior)")
    void uw2OverLongAliasStaysRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.UW2_TOOL,
                new JsonObject().put("label", "l").put("nm", "TOOLONG"),
                "CONTROL: 'nm' carries the unwrapped child's own @Size(max = 3); a 7-character value must"
                        + " be rejected against its maxLength: 3 (a 'maxLength' keyword failure), independent"
                        + " of the allOf-fold fix — a fix that widens acceptance must not also widen this"
                        + " rejection away");
    }

    // --- Soundness controls: the fold must never widen acceptance past what each branch's own"
    // closure still forbids ---

    @Test
    @DisplayName("HJ03: kind=\"plain\" with the any branch's own keys is rejected (the discriminator's"
            + " const still gates the branch; the any branch's keys are not accepted under"
            + " kind: \"plain\")")
    void hj03PlainKindWithAnyBranchKeysIsRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put(
                                "value",
                                new JsonObject()
                                        .put("kind", "plain")
                                        .put("name", "a")
                                        .put("x", "1")),
                "CONTROL: kind=\"plain\" selects J03PlainSub's branch alone (const gates it); 'name' and"
                        + " 'x' are J03AnySub's own keys and must be rejected as additional properties of"
                        + " the closed plain branch — folding must never let one branch's keys leak into a"
                        + " sibling branch's acceptance");
    }

    @Test
    @DisplayName("HJ03: an unrecognized key on the plain branch is rejected (the plain subtype's own"
            + " closure is unaffected by folding)")
    void hj03PlainBranchUnknownKeyIsRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put(
                                "value",
                                new JsonObject()
                                        .put("kind", "plain")
                                        .put("other", "x")
                                        .put("zzz", "1")),
                "CONTROL: J03PlainSub has no any-setter, so its folded branch is closed"
                        + " (additionalProperties: false); an unrecognized key 'zzz' must still be rejected");
    }

    @Test
    @DisplayName("UW2: a body naming the unwrapped child's own reserved key is rejected (propertyNames)")
    void uw2InnerKeyIsRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.UW2_TOOL,
                new JsonObject().put("label", "l").put("inner", new JsonObject().put("name", "ab")),
                "CONTROL: propertyNames excludes 'inner' — the unwrapped child's own container key must"
                        + " never be an addressable key at the flattened parent level, independent of the"
                        + " allOf-fold behavior");
    }

    @Test
    @DisplayName("HJ03: a wrong-typed value is rejected (type)")
    void hj03WrongTypedValueIsRejected() throws Exception {
        startServer();

        assertSchemaRejection(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject().put("label", "l").put("value", 42),
                "CONTROL: 'value' must be an object under every anyOf branch; a number must be rejected"
                        + " by 'type', independent of the allOf-fold behavior");
    }

    @Test
    @DisplayName("HJ03: the any-branch's accepted body materializes through the folded schema without"
            + " corrupting the discriminator")
    void hj03AnyBranchAcceptedBodyMaterializesCleanly() throws Exception {
        startServer();
        CountingToolInvoker<?> tool = fixture.tool(McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL);

        HttpResponse<Buffer> response = await(callTool(
                McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL,
                new JsonObject()
                        .put("label", "l")
                        .put(
                                "value",
                                new JsonObject()
                                        .put("kind", "any")
                                        .put("name", "a")
                                        .put("x", "1"))));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as("the folded schema must admit the any branch's own body")
                .isFalse();
        assertThat(tool.lastPayload())
                .as("CONTROL: the materialized carrier must exist once the folded schema admits the body")
                .isNotNull();
    }

    @Test
    @DisplayName("HJ03: the published value schema folds each subtype branch into one flat properties"
            + " set, with 'kind' present once per branch and no unmerged allOf left inside it")
    void hj03PublishedValueSchemaIsFlatPerBranch() throws Exception {
        startServer();

        JsonNode schema = new ObjectMapper()
                .readTree(fixture.tool(McpUnmergedAllOfAcceptanceITFixture.HJ03_TOOL)
                        .inputSchema());
        JsonNode branches = schema.at("/properties/payload/properties/value/anyOf");

        assertThat(branches.isMissingNode())
                .as("'value' must publish an anyOf of subtype branches; schema: " + schema)
                .isFalse();
        for (JsonNode branch : branches) {
            assertThat(branch.has("allOf"))
                    .as("each subtype branch must be one flat properties set, not an unmerged allOf;" + " branch: "
                            + branch)
                    .isFalse();
            assertThat(branch.at("/properties/kind").isMissingNode())
                    .as("each branch must publish 'kind' exactly once, in its own flat properties set;" + " branch: "
                            + branch)
                    .isFalse();
        }
    }

    // --- Shared actions and assertions (mirrors McpToolInputShapesIT) ---

    private void assertSchemaRejection(String toolName, JsonObject shapeArguments, String why) throws Exception {
        CountingToolInvoker<?> tool = fixture.tool(toolName);
        int prepareCallsBefore = tool.prepareCallCount();
        int invocationsBefore = tool.invocationCount();

        HttpResponse<Buffer> response = await(callTool(toolName, shapeArguments));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as(toolName + ": " + why + " — the call must settle as a tool-error result")
                .isTrue();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                .as(toolName + ": " + why + " — the rejection must be the bounded schema rejection")
                .isEqualTo(SCHEMA_MESSAGE);

        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.TOOL_ERROR);
        assertThat(terminal.errorType())
                .as(toolName + ": " + why + "; only the stage-1 schema check classifies as" + " INPUT_VALIDATION")
                .isEqualTo(McpErrorType.INPUT_VALIDATION);
        assertThat(terminal.resultType()).isEqualTo(McpResultType.COMPLETE);
        assertThat(tool.prepareCallCount())
                .as(toolName + ": the generated fixed input boundary must never be entered")
                .isEqualTo(prepareCallsBefore);
        assertThat(tool.invocationCount())
                .as(toolName + ": the handler must never run")
                .isEqualTo(invocationsBefore);
    }

    private void assertAccepted(String toolName, JsonObject shapeArguments, String why) throws Exception {
        CountingToolInvoker<?> tool = fixture.tool(toolName);
        int invocationsBefore = tool.invocationCount();

        HttpResponse<Buffer> response = await(callTool(toolName, shapeArguments));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError"))
                .as(toolName + ": " + why + " — the call must not settle as an error; text: "
                        + result.getJsonArray("content").getJsonObject(0).getString("text"))
                .isFalse();
        assertThat(tool.invocationCount())
                .as(toolName + ": " + why + "; the handler must have run exactly once")
                .isEqualTo(invocationsBefore + 1);
    }

    private void startServer() throws Exception {
        fixture = McpUnmergedAllOfAcceptanceITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private Future<HttpResponse<Buffer>> callTool(String toolName, JsonObject shapeArguments) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", toolName)
                .put("arguments", new JsonObject().put("payload", shapeArguments));
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", nextRequestId.getAndIncrement())
                .put("method", "tools/call")
                .put("params", params);
        return client.post(fixture.port(), McpUnmergedAllOfAcceptanceITFixture.LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName)
                .sendBuffer(body.toBuffer());
    }

    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody)
                .as("every admitted tools/call commits to SSE framing before the input pipeline runs")
                .startsWith(SSE_PREFIX);
        JsonObject result =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        assertThat(result)
                .as("every row here settles as a CallToolResult, never a JSON-RPC error")
                .isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
