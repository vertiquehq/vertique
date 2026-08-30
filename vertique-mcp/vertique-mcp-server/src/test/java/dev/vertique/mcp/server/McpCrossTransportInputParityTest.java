// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.input.processing.testkit.CrossTransportInputCorpus;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R03 TP-001 — {@code shouldMatchRestParityOnTheSharedCorpus}, MCP half.
 *
 * <p>Runs {@link dev.vertique.input.processing.testkit.CrossTransportInputCorpus#rawInput()} through
 * the real generated MCP stage-2 boundary — {@code prepare()} on a real, compiled generated invoker
 * (see {@link McpCrossTransportInputParityTestFixture}) — and asserts the resulting processed argument
 * tree equals the corpus's published {@code expectedProcessedInput()}.
 *
 * <p>The REST half of this same claim is {@code dev.vertique.rest.jaxrs.RestCrossTransportInputParityTest}
 * in {@code vertique-rest-jaxrs}. Neither test references the other directly — each asserts
 * independently against the one published {@link CrossTransportInputCorpus#expectedProcessedInput()}
 * ground truth, computed once in the corpus itself. Both modules depend on the exact same
 * {@code dev.vertique:vertique-input-processing:test-jar} coordinate and reference the exact same
 * corpus class, so the two assertions can only both pass if MCP's and REST's real input-processing
 * boundaries produce the same result for the same fixtures — a corpus authored twice would let the two
 * halves silently drift apart undetected, which publishing one shared artifact rules out.
 */
class McpCrossTransportInputParityTest {

    @Test
    @DisplayName("shouldMatchRestParityOnTheSharedCorpus")
    void shouldMatchRestParityOnTheSharedCorpus() throws Exception {
        // Given: the real generated invoker for a tool whose sole parameter is the published corpus's
        // root record type, and the corpus's raw input tree wired as that parameter's argument.
        McpCrossTransportInputParityTestFixture fixture = McpCrossTransportInputParityTestFixture.start();
        Map<String, Object> arguments = Map.of("root", CrossTransportInputCorpus.rawInput());

        // When: the real generated stage-2 pipeline (INP-001 canonicalization/sanitization at
        // InputLocation.PAYLOAD, then materialization, then Bean Validation) runs via prepare().
        McpPreparedToolCall prepared = fixture.invoker()
                .prepare(arguments, new McpCrossTransportInputParityTestFixture.NeverCancelledSignal());

        // Then (DECISIVE): the exact post-INP argument tree the real generated invoker produced for the
        // "root" parameter equals the corpus's published expected result — not a value this test
        // restates itself, so a regression in either the corpus's transform or the real generated
        // pipeline shows up as a mismatch here, never a vacuous self-agreement.
        assertThat(prepared.normalizedArguments())
                .as("DECISIVE: the real generated invoker's post-INP argument tree matches the published corpus")
                .containsEntry("root", CrossTransportInputCorpus.expectedProcessedInput());

        // And: the corpus's transform is non-trivial — every leaf actually changed — so this assertion
        // could not pass by the pipeline silently doing nothing.
        assertThat(prepared.normalizedArguments().get("root"))
                .as("the sanitizer transform is non-identity; a no-op pipeline could not pass the assertion above")
                .isNotEqualTo(CrossTransportInputCorpus.rawInput());
    }
}
