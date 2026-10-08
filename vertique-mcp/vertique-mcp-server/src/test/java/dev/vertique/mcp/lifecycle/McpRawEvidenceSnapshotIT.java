// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.McpInputLifecycleObservationITFixture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A raw-evidence observer is read-only: the bodies it receives are snapshots, so overwriting them
 * changes neither the response the server writes nor what a second raw-evidence observer sees.
 *
 * <p>Two observers each record their own copy of the delivered bodies and then overwrite the arrays
 * they were handed with zeros. The observer set is unordered, so whichever runs second would read the
 * first one's zeros if the evidence shared an array — with the other observer or with the wire write.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpRawEvidenceSnapshotIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private HttpClient rawClient;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
    }

    @Test
    @DisplayName("an observer overwriting the evidence bodies changes neither the wire response nor another observer")
    void shouldKeepWireAndOtherObserversIntactWhenAnObserverOverwritesEvidenceBodies() throws Exception {
        // Given: a port-0 server with two raw-evidence observers that each overwrite what they receive.
        OverwritingObserver first = new OverwritingObserver();
        OverwritingObserver second = new OverwritingObserver();
        McpInputLifecycleObservationITFixture.FixtureToolInvoker tool =
                new McpInputLifecycleObservationITFixture.FixtureToolInvoker();
        McpInputLifecycleObservationITFixture.Started started =
                McpInputLifecycleObservationITFixture.startWithObservers(vertx, Set.of(first, second), tool);
        server = started.server();
        rawClient = vertx.createHttpClient();
        WebClient client = WebClient.wrap(rawClient);
        Buffer requestBody = toolCallBody();

        // When: one tool call is made.
        HttpResponse<Buffer> response = client.post(started.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpInputLifecycleObservationITFixture.TOOL_NAME)
                .sendBuffer(requestBody)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Then: the call really ran and the caller received an intact, parseable result.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(tool.invocationCount()).isEqualTo(1);
        String wireBody = response.bodyAsString();
        assertThat(wireBody).startsWith(SSE_PREFIX);
        JsonObject result =
                new JsonObject(wireBody.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        assertThat(result)
                .as("the response on the wire must survive an observer overwriting its evidence")
                .isNotNull();

        // Then: both observers were handed the real bodies, whichever of them ran second.
        for (OverwritingObserver observer : new OverwritingObserver[] {first, second}) {
            assertThat(observer.admittedBody)
                    .as("each observer must receive the request bytes the client sent")
                    .isEqualTo(requestBody.getBytes());
            assertThat(new String(observer.writtenBody, StandardCharsets.UTF_8))
                    .as("each observer must receive the response bytes the caller received")
                    .isNotBlank()
                    .contains("\"result\"");
            assertThat(wireBody)
                    .as("the delivered response snapshot must be what went on the wire")
                    .contains(new String(observer.writtenBody, StandardCharsets.UTF_8));
        }
    }

    private static Buffer toolCallBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", McpInputLifecycleObservationITFixture.TOOL_NAME)
                .put("arguments", new JsonObject().put("customer", new JsonObject().put("name", "Ada")));
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    /** Records a copy of each delivered body, then overwrites the array it was handed. */
    private static final class OverwritingObserver implements McpRequestLifecycleObserver {
        private volatile byte[] admittedBody;
        private volatile byte[] writtenBody;

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return new McpRawEvidenceObservation() {
                @Override
                public void onRequestAdmitted(McpRequestAdmissionEvidence evidence) {
                    byte[] body = evidence.body();
                    admittedBody = body.clone();
                    Arrays.fill(body, (byte) 0);
                }

                @Override
                public void onResponseWritten(McpResponseEvidence evidence) {
                    byte[] body = evidence.body();
                    writtenBody = body.clone();
                    Arrays.fill(body, (byte) 0);
                }
            };
        }
    }
}
