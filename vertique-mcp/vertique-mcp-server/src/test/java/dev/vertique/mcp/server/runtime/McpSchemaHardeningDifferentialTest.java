// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T009 TP-002 — the committed differential compatibility corpus.
 *
 * <p>Every corpus entry under {@code mcp/schema/differential-corpus/json005-golden.jsonl} is an
 * <strong>input/expected-bytes pair</strong> whose {@code expected} value was produced by JSON-005's
 * own {@code generateCanonical(Type)} inside {@code vertique-json-schema}'s own test sources, for a
 * record, a nested-object, a map-valued, and a polymorphic type — copied here as a fixture. This
 * test invokes only {@link McpCanonicalJsonWriter}; JSON-005's package-private
 * {@code SchemaCanonicalizer} is never called, referenced, or reflected into from this module.
 *
 * <p>Since every entry's {@code input} is already a JSON-005 canonical document, round-tripping it
 * through MCP's reader and writer alone (no hardening — that is proven separately by
 * {@link McpSchemaHardeningTest}) must reproduce it byte for byte. That is the drift bound: if
 * MCP's UTF-16 key ordering or compact encoding ever diverged from JSON-005's own canonicalizer,
 * this round trip would stop being a no-op.
 */
@DisplayName("MCP schema hardening — T009 TP-002 differential corpus")
class McpSchemaHardeningDifferentialTest {

    @Test
    @DisplayName("shouldReproduceTheCommittedJson005CanonicalBytes")
    void shouldReproduceTheCommittedJson005CanonicalBytes() {
        List<McpSchemaHardeningDifferentialTestFixture.CorpusEntry> corpus =
                McpSchemaHardeningDifferentialTestFixture.readCorpus();

        assertThat(corpus).as("the committed corpus must not be empty").isNotEmpty();

        for (McpSchemaHardeningDifferentialTestFixture.CorpusEntry entry : corpus) {
            JsonNode parsedInput = McpCanonicalJsonWriter.read(entry.input());
            String actualBytes = McpCanonicalJsonWriter.writeCanonical(parsedInput);

            assertThat(actualBytes)
                    .as("corpus entry '%s' must reproduce its committed expected bytes exactly", entry.name())
                    .isEqualTo(entry.expected());
        }
    }
}
