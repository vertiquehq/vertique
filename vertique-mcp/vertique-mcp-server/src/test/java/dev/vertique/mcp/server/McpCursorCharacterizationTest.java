// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** T031's fixed characterization of bounded, unsigned cursor decoding. */
class McpCursorCharacterizationTest {

    private static final String SEED_RESOURCE = "/mcp/characterization/cursor-seeds.jsonl";

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("T031: every fixed cursor seed retains its pinned bounded classification")
    void shouldClassifyEveryCursorSeedWithinBounds(Seed seed) {
        var fixture = new McpCursorCharacterizationTestFixture();

        Classification actual = fixture.classify(seed);

        assertThat(actual.errorCode()).isEqualTo(seed.expectedCode());
        assertThat(actual.anchor()).isEqualTo(seed.expectedAnchor());
        assertThat(actual.decoderInvocations()).isEqualTo(seed.expectedDecoderInvocations());
        assertThat(actual.parserInvocations()).isEqualTo(seed.expectedParserInvocations());
    }

    private static Stream<Seed> seeds() {
        return readSeeds().stream();
    }

    private static List<Seed> readSeeds() {
        try (var input = McpCursorCharacterizationTest.class.getResourceAsStream(SEED_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing characterization resource " + SEED_RESOURCE);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(Seed::fromJson)
                        .toList();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read characterization resource " + SEED_RESOURCE, unreadable);
        }
    }

    private record Seed(
            String id,
            String cursor,
            String registryDigest,
            Integer expectedCode,
            String expectedAnchor,
            int expectedDecoderInvocations,
            int expectedParserInvocations) {

        private static Seed fromJson(String line) {
            JsonObject json = new JsonObject(line);
            return new Seed(
                    json.getString("id"),
                    json.getString("cursor"),
                    json.getString("registryDigest"),
                    json.getInteger("expectedCode"),
                    json.getString("expectedAnchor"),
                    json.getInteger("expectedDecoderInvocations"),
                    json.getInteger("expectedParserInvocations"));
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private record Classification(Integer errorCode, String anchor, int decoderInvocations, int parserInvocations) {}

    /** Owns the instrumented codec wiring used to classify one literal seed exactly once. */
    private static final class McpCursorCharacterizationTestFixture {
        private int decoderInvocations;
        private final CountingObjectMapper mapper = new CountingObjectMapper();
        private final McpCursorCodec codec = new McpCursorCodec(2_048, mapper, cursor -> {
            decoderInvocations++;
            return Base64.getUrlDecoder().decode(cursor);
        });

        private Classification classify(Seed seed) {
            McpCursorCodec.Decoded decoded = codec.decode(seed.cursor(), seed.registryDigest());
            Integer errorCode = decoded.isInvalid() ? McpPolicyEnforcer.UNKNOWN_OR_UNAUTHORIZED_CODE : null;
            return new Classification(errorCode, decoded.anchor(), decoderInvocations, mapper.readTreeCount());
        }
    }

    /** Counts parser entry so the corpus pins which malformed inputs are rejected before parsing. */
    private static final class CountingObjectMapper extends ObjectMapper {
        private int readTreeCount;

        @Override
        public JsonNode readTree(byte[] content) throws IOException {
            readTreeCount++;
            return super.readTree(content);
        }

        private int readTreeCount() {
            return readTreeCount;
        }
    }
}
