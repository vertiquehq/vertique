// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Independent anti-drift proof for the shared, byte-pinned R18 JSONL artifact. */
class McpJsonTokenCorpusTest {

    private static final String CORPUS_SHA_256 = "2fce430b23dca89f39da04037bce7c0022eb0f2e96ea5c68a800b6472273898a";
    private static final Map<String, Long> REQUIRED_STRUCTURAL_CLASS_COUNTS = Map.ofEntries(
            Map.entry("flat-array", 7L),
            Map.entry("flat-scalar-array", 7L),
            Map.entry("flat-object", 2L),
            Map.entry("nested-array", 1L),
            Map.entry("nested-object", 1L),
            Map.entry("long-string", 1L),
            Map.entry("high-field-name-diversity", 1L),
            Map.entry("valid-tools-call-arguments", 1L),
            Map.entry("byte-first-precedence", 1L));

    @Test
    @DisplayName("R18: the shared JSON corpus has its recorded digest and every required structural class")
    void shouldMatchItsRecordedDigestAndCoverEveryRequiredStructuralClass() {
        var rows = McpJsonTokenCorpus.rows();
        assertAll(
                () -> assertThat(sha256(McpJsonTokenCorpus.resourceBytes())).isEqualTo(CORPUS_SHA_256),
                () -> {
                    assertThat(rows).hasSize(14);
                    assertThat(rows.stream()
                                    .collect(Collectors.groupingBy(McpJsonTokenCorpus.Row::id, Collectors.counting())))
                            .allSatisfy((id, count) -> assertThat(count)
                                    .as("id %s must be unique", id)
                                    .isEqualTo(1L));
                },
                () -> assertThat(rows.stream()
                                .flatMap(row -> row.structuralClasses().stream())
                                .collect(Collectors.groupingBy(
                                        structuralClass -> structuralClass, Collectors.counting())))
                        .isEqualTo(REQUIRED_STRUCTURAL_CLASS_COUNTS),
                () -> {
                    assertBoundary(rows, "minimum-flat-array-exact", 1_024, 1_024, McpJsonTokenCorpus.Expected.ACCEPT);
                    assertBoundary(
                            rows, "minimum-flat-array-plus-one", 1_025, 1_024, McpJsonTokenCorpus.Expected.REJECT);
                    assertBoundary(
                            rows, "default-flat-array-exact", 65_536, 65_536, McpJsonTokenCorpus.Expected.ACCEPT);
                    assertBoundary(
                            rows, "default-flat-array-plus-one", 65_537, 65_536, McpJsonTokenCorpus.Expected.REJECT);
                    assertBoundary(
                            rows, "maximum-flat-array-exact", 262_144, 262_144, McpJsonTokenCorpus.Expected.ACCEPT);
                    assertBoundary(
                            rows, "maximum-flat-array-plus-one", 262_145, 262_144, McpJsonTokenCorpus.Expected.REJECT);
                },
                () -> {
                    assertThat(McpJsonTokenCorpus.ingressUnitRows())
                            .as("McpEnvelopeTokenBudgetTest selects its rows from the one shared loader/artifact")
                            .hasSize(13)
                            .allSatisfy(row ->
                                    assertThat(row.consumers()).contains(McpJsonTokenCorpus.INGRESS_UNIT_CONSUMER));
                    assertThat(McpJsonTokenCorpus.ingressIntegrationRows())
                            .as("McpIngressTokenBudgetIT selects its rows from the one shared loader/artifact")
                            .singleElement()
                            .satisfies(row -> {
                                assertThat(row.id()).isEqualTo("valid-tools-call-arguments-plus-one");
                                assertThat(row.tokenCount()).isEqualTo(1_025);
                                assertThat(row.consumers()).contains(McpJsonTokenCorpus.INGRESS_INTEGRATION_CONSUMER);
                            });
                });

        assertRejectedCorpusNumber("tokenCount", "1.5", "tokenCount must be a positive integer");
        assertRejectedCorpusNumber("configuredBudget", "2147483648", "configuredBudget must be a positive integer");
        assertRejectedGeneratorParameter("1.5");
        assertRejectedGeneratorParameter("2147483648");
        assertRejectedGeneratorParameter("18446744073709551617");
    }

    private static void assertBoundary(
            java.util.List<McpJsonTokenCorpus.Row> rows,
            String id,
            int tokenCount,
            int configuredBudget,
            McpJsonTokenCorpus.Expected expected) {
        assertThat(rows).filteredOn(row -> row.id().equals(id)).singleElement().satisfies(row -> {
            assertThat(row.tokenCount()).isEqualTo(tokenCount);
            assertThat(row.configuredBudget()).isEqualTo(configuredBudget);
            assertThat(row.expected()).isEqualTo(expected);
        });
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("SHA-256 is required by the JDK", unavailable);
        }
    }

    private static void assertRejectedCorpusNumber(String field, String value, String expectedMessage) {
        String tokenCount = field.equals("tokenCount") ? value : "2";
        String configuredBudget = field.equals("configuredBudget") ? value : "1024";
        String row = "{\"id\":\"invalid\",\"structuralClasses\":[\"flat-array\"],"
                + "\"consumers\":[\"ingress-unit\"],\"generator\":{\"description\":\"literal\","
                + "\"parameters\":{\"json\":\"[]\"}},\"tokenCount\":" + tokenCount
                + ",\"configuredBudget\":" + configuredBudget
                + ",\"expected\":\"accept\",\"maxBodyBytes\":1024}";

        assertThatThrownBy(() -> McpJsonTokenCorpus.parseBytes(row.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static void assertRejectedGeneratorParameter(String value) {
        String row = "{\"id\":\"invalid\",\"structuralClasses\":[\"flat-array\"],"
                + "\"consumers\":[\"ingress-unit\"],\"generator\":{\"description\":\"flat-scalar-array\","
                + "\"parameters\":{\"elementCount\":" + value + "}},\"tokenCount\":3,"
                + "\"configuredBudget\":1024,\"expected\":\"accept\",\"maxBodyBytes\":1024}";

        assertThatThrownBy(() -> McpJsonTokenCorpus.parseBytes(row.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elementCount must be a positive integer");
    }
}
