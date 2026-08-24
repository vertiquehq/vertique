// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Independent anti-drift proof for the shared, byte-pinned R18 JSONL artifact. */
class McpJsonTokenCorpusTest {

    private static final int DEFAULT_TOKENS = 65_536;
    private static final String CORPUS_SHA_256 = "72cc374aa77dfb6383bf95318d745e5206cf95095df171eb32a20ef78f01fbf0";
    private static final Map<String, Long> REQUIRED_STRUCTURAL_CLASS_COUNTS = Map.ofEntries(
            Map.entry("flat-array", 7L),
            Map.entry("flat-scalar-array", 7L),
            Map.entry("flat-object", 2L),
            Map.entry("nested-array", 1L),
            Map.entry("nested-object", 1L),
            Map.entry("long-string", 1L),
            Map.entry("high-field-name-diversity", 1L),
            Map.entry("valid-tools-call-arguments", 1L),
            Map.entry("byte-first-precedence", 1L),
            Map.entry("numeric-fidelity", 1L),
            Map.entry("conformance-discover-request", 1L),
            Map.entry("conformance-tools-list-request", 1L),
            Map.entry("conformance-tools-call-request", 1L),
            Map.entry("conformance-tool-text-result", 1L),
            Map.entry("conformance-tool-error-result", 1L),
            Map.entry("typescript-discover-request", 1L),
            Map.entry("typescript-tools-list-request", 1L),
            Map.entry("typescript-tools-call-request", 1L),
            Map.entry("typescript-discover-result", 1L),
            Map.entry("typescript-tools-list-anonymous-result", 1L),
            Map.entry("typescript-tools-list-bearer-result", 1L),
            Map.entry("typescript-tool-call-result", 1L));

    @Test
    @DisplayName("R18: the shared JSON corpus has its recorded digest and every required structural class")
    void shouldMatchItsRecordedDigestAndCoverEveryRequiredStructuralClass() {
        var rows = McpJsonTokenCorpus.rows();
        assertAll(
                () -> assertThat(sha256(McpJsonTokenCorpus.resourceBytes())).isEqualTo(CORPUS_SHA_256),
                () -> {
                    assertThat(rows).hasSize(27);
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
                    assertThat(McpJsonTokenCorpus.outputNormalizationRows())
                            .as("R19 output boundaries select the same shared loader/artifact")
                            .hasSize(7)
                            .allSatisfy(row -> assertThat(row.consumers())
                                    .contains(McpJsonTokenCorpus.OUTPUT_NORMALIZATION_CONSUMER));
                    assertThat(McpJsonTokenCorpus.bothConfiguredDefaultsRows())
                            .as("both default consumers select only JSON shapes that fit 65,536 tokens")
                            .isNotEmpty()
                            .allSatisfy(row -> assertThat(row.tokenCount()).isLessThanOrEqualTo(DEFAULT_TOKENS));
                });

        assertRejectedCorpusNumber("tokenCount", "1.5", "tokenCount must be a positive integer");
        assertRejectedCorpusNumber("configuredBudget", "2147483648", "configuredBudget must be a positive integer");
        assertRejectedGeneratorParameter("1.5");
        assertRejectedGeneratorParameter("2147483648");
        assertRejectedGeneratorParameter("18446744073709551617");
    }

    @Test
    @DisplayName("R19: every shared JSON shape fitting the configured defaults reaches both production consumers")
    void shouldFitEveryCorpusShapeWithinBothConfiguredDefaults() {
        McpEnvelopeJsonCodec ingress = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(2_097_152).build(), DEFAULT_TOKENS);
        McpRequestDispatcher output = outputDispatcher();

        for (McpJsonTokenCorpus.Row row : McpJsonTokenCorpus.bothConfiguredDefaultsRows()) {
            McpEnvelopeJsonCodec.Result decoded = ingress.decode(row.renderedUtf8());
            assertThat(decoded.isRejected())
                    .as("%s must fit the default ingress token budget", row.id())
                    .isFalse();
            assertThat(output.normalizeStructuredContent(decoded.value()))
                    .as("%s must fit the independent default output token budget", row.id())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("R19: output normalization preserves pinned finite numbers and rejects non-finite Java values")
    void shouldPreserveNumericFidelityAcrossOutputNormalization() {
        McpJsonTokenCorpus.Row numericRow = McpJsonTokenCorpus.outputNormalizationRows().stream()
                .filter(row -> row.id().equals("output-numeric-fidelity"))
                .findFirst()
                .orElseThrow();
        McpEnvelopeJsonCodec ingress = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(numericRow.maxBodyBytes()).build(), DEFAULT_TOKENS);
        McpEnvelopeJsonCodec.Result decoded = ingress.decode(numericRow.renderedUtf8());
        assertThat(decoded.isRejected()).isFalse();

        McpRequestDispatcher dispatcher = outputDispatcher();
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) dispatcher.normalizeStructuredContent(decoded.value());
        assertThat(normalized.get("precise"))
                .isInstanceOf(BigDecimal.class)
                .extracting(value -> ((BigDecimal) value).toPlainString())
                .isEqualTo("0.1000");
        assertThat(normalized.get("large"))
                .isInstanceOf(BigDecimal.class)
                .extracting(value -> ((BigDecimal) value).toPlainString())
                .isEqualTo(decoded.value().get("large").decimalValue().toPlainString());
        for (Number nonFinite : List.of(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY)) {
            assertThatThrownBy(() -> dispatcher.normalizeStructuredContent(nonFinite))
                    .as("non-finite Java value %s must not cross output normalization as a quoted string", nonFinite)
                    .isInstanceOf(RuntimeException.class);
        }
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

    private static McpRequestDispatcher outputDispatcher() {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        return new McpRequestDispatcher(
                McpServerConfig.builder().outputMaxTokens(DEFAULT_TOKENS).build(),
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
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

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };
}
