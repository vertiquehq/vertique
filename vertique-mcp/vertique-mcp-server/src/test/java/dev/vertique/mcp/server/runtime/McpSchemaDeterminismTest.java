// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T009 — determinism of MCP's own harden-then-canonicalize pipeline.
 *
 * <p>Equal logical documents and parameter metadata must produce byte-identical hardened, canonical
 * output regardless of the source object's member insertion order. Each seed in the committed
 * {@code mcp/schema/differential-corpus/random-seeds.txt} drives a different, Jackson-tree-level
 * member insertion order for the same logical nested-object document; every permutation must harden
 * and re-serialize to the one canonical form, and repeating the same call twice must reproduce it
 * exactly.
 */
@DisplayName("MCP schema hardening — determinism across insertion order and repetition")
class McpSchemaDeterminismTest {

    @Test
    @DisplayName("shouldProduceByteIdenticalOutputAcrossSeededInsertionOrdersAndRepetition")
    void shouldProduceByteIdenticalOutputAcrossSeededInsertionOrdersAndRepetition() {
        List<Long> seeds = McpSchemaDeterminismTestFixture.readSeeds();
        assertThat(seeds).as("the committed seed file must not be empty").isNotEmpty();

        List<McpToolParameterMetadata> parameters = List.of(
                new McpToolParameterMetadata("argument0", "label", "The label."),
                new McpToolParameterMetadata("argument1", "address", "The address."));

        String referenceOutput = harden(McpSchemaDeterminismTestFixture.orderedDocument(seeds.get(0)), parameters);

        for (Long seed : seeds) {
            ObjectNode shuffled = McpSchemaDeterminismTestFixture.orderedDocument(seed);
            String firstPass = harden(shuffled, parameters);
            String secondPass = harden(McpSchemaDeterminismTestFixture.orderedDocument(seed), parameters);

            assertThat(firstPass)
                    .as("seed %d must harden to the one canonical form regardless of member insertion order", seed)
                    .isEqualTo(referenceOutput);
            assertThat(secondPass)
                    .as("repeating the same call for seed %d must reproduce byte-identical output", seed)
                    .isEqualTo(firstPass);
        }
    }

    private static String harden(JsonNode document, List<McpToolParameterMetadata> parameters) {
        return McpCanonicalJsonWriter.writeCanonical(McpSchemaHardener.harden(document, parameters));
    }
}
