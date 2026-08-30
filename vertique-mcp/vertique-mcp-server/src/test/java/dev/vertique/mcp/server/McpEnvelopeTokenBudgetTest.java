// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
import dev.vertique.rest.core.config.HttpConfig;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R18 proof that the ingress parser consumes its configured token budget and no output budget. */
class McpEnvelopeTokenBudgetTest {

    private static final int MIN_TOKENS = 1_024;
    private static final int MAX_TOKENS = 262_144;
    private static final Set<String> REQUIRED_STRUCTURAL_CLASSES = Set.of(
            "flat-array",
            "flat-object",
            "nested-array",
            "nested-object",
            "long-string",
            "high-field-name-diversity",
            "byte-first-precedence");

    @Test
    @DisplayName("R18: enforces configured ingress token boundaries from the canonical JSON corpus")
    void shouldEnforceConfiguredIngressTokenBoundaries() {
        var rows = McpJsonTokenCorpus.ingressUnitRows();

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream()
                        .flatMap(row -> row.structuralClasses().stream())
                        .collect(java.util.stream.Collectors.toSet()))
                .containsAll(REQUIRED_STRUCTURAL_CLASSES);
        assertThat(rows.stream().map(McpJsonTokenCorpus.Row::id))
                .contains(
                        "minimum-flat-array-exact",
                        "minimum-flat-array-plus-one",
                        "default-flat-array-exact",
                        "default-flat-array-plus-one",
                        "maximum-flat-array-exact",
                        "maximum-flat-array-plus-one",
                        "byte-first-precedence");

        for (McpJsonTokenCorpus.Row row : rows) {
            McpServerConfig lowOutputConfig = config(row.configuredBudget(), MIN_TOKENS);
            McpServerConfig highOutputConfig = config(row.configuredBudget(), MAX_TOKENS);
            HttpConfig httpConfig =
                    HttpConfig.builder().maxBodySize(row.maxBodyBytes()).build();

            McpEnvelopeJsonCodec lowOutputCodec =
                    new McpEnvelopeJsonCodec(httpConfig, lowOutputConfig.ingressMaxTokens());
            McpEnvelopeJsonCodec highOutputCodec =
                    new McpEnvelopeJsonCodec(httpConfig, highOutputConfig.ingressMaxTokens());

            boolean rejectedWithLowOutput =
                    lowOutputCodec.decode(row.renderedUtf8()).isRejected();
            boolean rejectedWithHighOutput =
                    highOutputCodec.decode(row.renderedUtf8()).isRejected();

            assertThat(lowOutputCodec
                            .mapper()
                            .getFactory()
                            .streamReadConstraints()
                            .getMaxTokenCount())
                    .as("%s must pass its configured ingress budget to Jackson", row.id())
                    .isEqualTo(row.configuredBudget());
            assertThat(rejectedWithLowOutput)
                    .as("%s must have the corpus-declared ingress outcome", row.id())
                    .isEqualTo(row.expected() == McpJsonTokenCorpus.Expected.REJECT);
            assertThat(rejectedWithHighOutput)
                    .as("%s must retain its ingress outcome when only outputMaxTokens changes", row.id())
                    .isEqualTo(rejectedWithLowOutput);

            if (row.structuralClasses().contains("byte-first-precedence")) {
                assertThat(row.renderedUtf8().length)
                        .as("%s must exceed its independent HTTP byte cap before token exhaustion", row.id())
                        .isGreaterThan(row.maxBodyBytes());
                assertThat(lowOutputCodec
                                .mapper()
                                .getFactory()
                                .streamReadConstraints()
                                .getMaxDocumentLength())
                        .as("%s must retain HttpConfig.maxBodySize as the first ingress boundary", row.id())
                        .isEqualTo(row.maxBodyBytes());
            }
        }
    }

    private static McpServerConfig config(int ingressMaxTokens, int outputMaxTokens) {
        return McpServerConfig.builder()
                .ingressMaxTokens(ingressMaxTokens)
                .outputMaxTokens(outputMaxTokens)
                .build();
    }
}
