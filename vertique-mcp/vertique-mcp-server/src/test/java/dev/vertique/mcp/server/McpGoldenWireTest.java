// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.rest.core.config.HttpConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-003 — pins the replacement bounded Jackson envelope codec against the final-2026 golden wire
 * fixtures, unchanged since T003.
 *
 * <p>Every valid frame must round-trip byte-identically through the T007 {@link McpEnvelopeJsonCodec}
 * decode and a canonical encode, and every invalid frame must yield the exact pinned final-spec
 * JSON-RPC error code and message. A one-byte mutation of a valid frame must flip it into a bounded
 * rejection, proving the fixtures are load-bearing rather than incidental. Expected initial result:
 * GREEN — the replacement codec must preserve every wire byte the protocol pins; a mismatch here is a
 * regression in this slice, not an expected red.
 */
class McpGoldenWireTest {

    @Test
    @DisplayName("valid frames round-trip byte-identically and invalid frames yield the pinned errors")
    void shouldMatchPinnedFinal2026WireFixtures() {
        HttpConfig httpConfig = HttpConfig.builder().build();
        McpEnvelopeJsonCodec envelopeCodec = new McpEnvelopeJsonCodec(httpConfig);
        McpProtocolCodec codec = new McpProtocolCodec(httpConfig);
        List<byte[]> validFrames = McpGoldenWireTestFixture.loadValidFrames();
        List<McpGoldenWireTestFixture.InvalidCase> invalidCases = McpGoldenWireTestFixture.loadInvalidCases();

        for (byte[] frame : validFrames) {
            String rendered = new String(frame, StandardCharsets.UTF_8);
            McpEnvelopeJsonCodec.Result parsed = envelopeCodec.decode(frame);
            assertThat(parsed.isRejected())
                    .as("valid frame decodes without rejection: %s", rendered)
                    .isFalse();
            byte[] canonical = codec.encode(parsed.value());
            assertThat(canonical)
                    .as("valid frame round-trips byte-identically: %s", rendered)
                    .isEqualTo(frame);
        }

        for (McpGoldenWireTestFixture.InvalidCase invalid : invalidCases) {
            McpProtocolCodec.Decoded decoded = codec.decodeEnvelope(invalid.frame());
            assertThat(decoded.isError())
                    .as("invalid frame classified as a bounded error: %s", invalid.name())
                    .isTrue();
            assertThat(decoded.error().code())
                    .as("invalid frame yields the pinned JSON-RPC code: %s", invalid.name())
                    .isEqualTo(invalid.code());
            assertThat(decoded.error().message())
                    .as("invalid frame yields the pinned JSON-RPC message: %s", invalid.name())
                    .isEqualTo(invalid.message());

            byte[] errorResponse = codec.errorResponse(invalid.frame());
            assertThat(errorResponse)
                    .as("invalid frame re-encodes to the pinned canonical error-response bytes: %s", invalid.name())
                    .isEqualTo(invalid.expectedResponse());
            assertThat(new JsonObject(Buffer.buffer(errorResponse)).getValue("id"))
                    .as("invalid frame echoes exactly the pinned request id: %s", invalid.name())
                    .isEqualTo(invalid.expectedId());
        }

        byte[] pinnedError = McpGoldenWireTestFixture.firstErrorResponse(codec, invalidCases);
        byte[] mutatedError = McpGoldenWireTestFixture.flipFirstByte(pinnedError);
        assertThat(envelopeCodec.decode(mutatedError).isRejected())
                .as("a one-byte mutation of a pinned canonical error response is no longer a decodable frame")
                .isTrue();

        byte[] valid = validFrames.getFirst();
        byte[] mutant = McpGoldenWireTestFixture.appendTrailingByte(valid);
        assertThat(envelopeCodec.decode(mutant).isRejected())
                .as("one injected trailing byte turns the valid frame into a bounded rejection")
                .isTrue();
        assertThat(envelopeCodec.decode(valid).isRejected())
                .as("removing that byte restores a bounded canonical parse")
                .isFalse();
    }

    /** Framework wiring for the golden proof: fixture loading and one-byte mutation, nothing decisive. */
    private static final class McpGoldenWireTestFixture {
        private static final String VALID_RESOURCE = "/mcp/wire/final-2026-valid.jsonl";
        private static final String INVALID_RESOURCE = "/mcp/wire/final-2026-invalid.jsonl";

        private McpGoldenWireTestFixture() {}

        static List<byte[]> loadValidFrames() {
            List<byte[]> frames = new ArrayList<>();
            for (String line : readLines(VALID_RESOURCE)) {
                frames.add(line.getBytes(StandardCharsets.UTF_8));
            }
            return List.copyOf(frames);
        }

        static List<InvalidCase> loadInvalidCases() {
            List<InvalidCase> cases = new ArrayList<>();
            for (String line : readLines(INVALID_RESOURCE)) {
                JsonObject descriptor = new JsonObject(line);
                cases.add(new InvalidCase(
                        descriptor.getString("name"),
                        descriptor.getString("frame").getBytes(StandardCharsets.UTF_8),
                        descriptor.getInteger("code"),
                        descriptor.getString("message"),
                        descriptor.getValue("id"),
                        descriptor.getString("expectedResponse").getBytes(StandardCharsets.UTF_8)));
            }
            return List.copyOf(cases);
        }

        static byte[] appendTrailingByte(byte[] frame) {
            byte[] mutant = new byte[frame.length + 1];
            System.arraycopy(frame, 0, mutant, 0, frame.length);
            mutant[frame.length] = (byte) '}';
            return mutant;
        }

        static byte[] firstErrorResponse(McpProtocolCodec codec, List<InvalidCase> invalidCases) {
            return codec.errorResponse(invalidCases.getFirst().frame());
        }

        static byte[] flipFirstByte(byte[] response) {
            byte[] mutant = response.clone();
            mutant[0] = (byte) (mutant[0] ^ 0x01);
            return mutant;
        }

        private static List<String> readLines(String resource) {
            try (InputStream stream = McpGoldenWireTest.class.getResourceAsStream(resource)) {
                assertThat(stream).as("golden fixture resource %s", resource).isNotNull();
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return content.lines().filter(line -> !line.isBlank()).toList();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        /**
         * One pinned invalid-frame case.
         *
         * @param name the case name
         * @param frame the raw UTF-8 request bytes
         * @param code the pinned final-spec JSON-RPC error code
         * @param message the pinned safe error message
         * @param expectedId the pinned echoed request id (an {@link Integer}, a {@link String}, or
         *     {@code null} when no trustworthy id is echoed)
         * @param expectedResponse the pinned canonical error-response bytes the codec must re-encode
         */
        record InvalidCase(
                String name, byte[] frame, int code, String message, Object expectedId, byte[] expectedResponse) {}
    }
}
