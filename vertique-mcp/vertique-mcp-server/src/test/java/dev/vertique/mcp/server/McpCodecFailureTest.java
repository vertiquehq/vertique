// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.core.config.HttpConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves failure frames settle through a bounded, non-leaking external response.
 *
 * <p>Malformed JSON, an invalid envelope, an unknown method, and a duplicate-id frame each produce
 * the pinned bounded JSON-RPC error with the original usable id or a null id and never leak internal
 * text.
 */
class McpCodecFailureTest {

    @Test
    @DisplayName("failure frames yield the pinned bounded error and never leak SECRET")
    void shouldYieldPinnedBoundedErrorWithoutLeakingInternalText() {
        McpProtocolCodec codec = new McpProtocolCodec(
                HttpConfig.builder().build(), McpServerConfig.defaults().ingressMaxTokens());

        for (McpCodecFailureTestFixture.Row row : McpCodecFailureTestFixture.decodeRows()) {
            byte[] response = McpCodecErrorBytes.errorResponse(codec, row.frame());
            JsonObject decoded = McpCodecFailureTestFixture.parse(response);
            assertThat(decoded.getJsonObject("error").getInteger("code"))
                    .as("frame %s yields the pinned JSON-RPC code", row.name())
                    .isEqualTo(row.expectedCode());
            assertThat(decoded.getValue("id"))
                    .as("frame %s carries its original usable id or null", row.name())
                    .isEqualTo(row.expectedId());
            assertThat(new String(response, StandardCharsets.UTF_8))
                    .as("frame %s leaks no internal text", row.name())
                    .doesNotContain("SECRET");
        }
    }

    /** Framework wiring for the failure proof: the pinned failure rows and response parsing. */
    private static final class McpCodecFailureTestFixture {

        private McpCodecFailureTestFixture() {}

        static List<Row> decodeRows() {
            return List.of(
                    new Row("malformedJson", utf8("{not json"), -32700, null),
                    new Row("invalidEnvelope", utf8("{\"jsonrpc\":\"2.0\",\"id\":7,\"params\":{}}"), -32600, 7),
                    new Row(
                            "unknownMethod",
                            utf8("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"does/notexist\",\"params\":{\"_meta\":"
                                    + "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                                    + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}"),
                            -32601,
                            8),
                    new Row(
                            "duplicateId",
                            utf8("{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"server/discover\"}"),
                            -32700,
                            null),
                    // An absent id is a structurally invalid request envelope (id must be a string
                    // or integer); it classifies as -32600 with a null usable id.
                    new Row(
                            "missingId",
                            utf8("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":{}}"),
                            -32600,
                            null),
                    // A fractional id is neither a string nor an integer, so it is untrustworthy to
                    // echo; the envelope is -32600 and the usable id is null.
                    new Row(
                            "fractionalId",
                            utf8("{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"tools/list\",\"params\":{}}"),
                            -32600,
                            null),
                    // Params, when present, must be an object; a non-object params is -32600, and the
                    // valid integer id is still echoed.
                    new Row(
                            "nonObjectParams",
                            utf8("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":[]}"),
                            -32600,
                            3));
        }

        static JsonObject parse(byte[] response) {
            assertThat(response).as("the codec wrote a non-empty response").isNotEmpty();
            return new JsonObject(Buffer.buffer(response));
        }

        private static byte[] utf8(String literal) {
            return literal.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * One pinned failure row.
         *
         * @param name the row name
         * @param frame the raw UTF-8 request bytes
         * @param expectedCode the pinned final-spec JSON-RPC error code
         * @param expectedId the original usable id, or {@code null} when none is trustworthy
         */
        record Row(
                String name,
                byte[] frame,
                int expectedCode,
                @Nullable Integer expectedId) {}
    }
}
