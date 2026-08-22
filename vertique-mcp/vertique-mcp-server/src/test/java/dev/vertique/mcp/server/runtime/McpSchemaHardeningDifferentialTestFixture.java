// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Framework wiring for {@link McpSchemaHardeningDifferentialTest}: committed corpus loading only. */
final class McpSchemaHardeningDifferentialTestFixture {

    private static final String CORPUS_RESOURCE = "/mcp/schema/differential-corpus/json005-golden.jsonl";

    private static final ObjectMapper LINE_MAPPER = new ObjectMapper();

    private McpSchemaHardeningDifferentialTestFixture() {}

    /**
     * Reads every committed {@code name}/{@code input}/{@code expected} JSONL line from the corpus
     * resource.
     *
     * @return the corpus entries, in file order
     */
    static List<CorpusEntry> readCorpus() {
        List<CorpusEntry> entries = new ArrayList<>();
        try (InputStream resource =
                        McpSchemaHardeningDifferentialTestFixture.class.getResourceAsStream(CORPUS_RESOURCE);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(resource, CORPUS_RESOURCE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode parsedLine = LINE_MAPPER.readTree(line);
                entries.add(new CorpusEntry(
                        parsedLine.get("name").asText(),
                        parsedLine.get("input").asText(),
                        parsedLine.get("expected").asText()));
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("failed to read the differential corpus", failed);
        }
        return List.copyOf(entries);
    }

    /**
     * One committed input/expected-bytes pair.
     *
     * @param name the entry's descriptive name
     * @param input the JSON-005 canonical document text
     * @param expected the committed expected canonical bytes, as a string
     */
    record CorpusEntry(String name, String input, String expected) {}
}
