// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.vertique.rest.core.config.HttpConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R02 TP-001 (issue #423) — {@link McpEnvelopeJsonCodec}'s {@code maxTokenCount} is now derived from
 * the effective {@code maxBodySize} rather than left unlimited, because a finite document length only
 * bounds the parser's <em>input</em>, not its retained node allocation. Benchmarked against the
 * shipped 2 MiB {@code maxBodySize} default (recorded in the R02 evidence, not re-run here): an
 * unlimited token count let a repeated 999-deep nested-array-chain body retain roughly 52x its own
 * byte size (~109 MB for a 2 MB body) before this codec's bounded rejection ever ran, because the
 * whole tree was already built by the time any check would fire. The derived {@code maxBodySize / 4}
 * budget (floored at 1,024) caps that same shape to roughly a quarter of its unbounded node count.
 *
 * <p>Every row below proves the derived budget rejects a shape <strong>before</strong> the full
 * adversarial tree is retained, using a counting-spy {@link JsonNodeFactory} — the same
 * count-invocations technique {@code McpToolsListGateTimeoutTerminatesScanIT} (R01 TP-001) uses to
 * prove a scan stops early rather than merely to prove an eventual outcome — instead of trusting that
 * {@link McpEnvelopeJsonCodec#decode} returning a rejection alone proves early termination: a cap that
 * fired only after full materialization would pass an outcome-only check.
 */
class McpEnvelopeTokenBudgetTest {

    /** A smaller {@code maxBodySize} than the 2 MiB shipped default, to keep this test fast. */
    private static final long TEST_MAX_BODY_SIZE = 200_000L;

    /** {@code TEST_MAX_BODY_SIZE / 4}, mirroring the codec's derivation formula. */
    private static final long EXPECTED_TOKEN_BUDGET = TEST_MAX_BODY_SIZE / 4;

    private static final String NESTED_ARRAYS_SHAPE = "repeated nested-array chains";
    private static final String TINY_TOKENS_SHAPE = "many tiny tokens in one array";

    private static Stream<Arguments> adversarialShapes() {
        return Stream.of(
                Arguments.of(NESTED_ARRAYS_SHAPE, nestedArrayChainsDocument(TEST_MAX_BODY_SIZE)),
                Arguments.of(TINY_TOKENS_SHAPE, manyTinyTokensDocument(TEST_MAX_BODY_SIZE)));
    }

    /**
     * Pins the derivation formula itself: {@code max(1024, maxBodySize / 4)}, including the floor for
     * a pathologically small configured {@code maxBodySize}. If a future change alters the divisor or
     * the floor, this row fails alongside the benchmark-backed javadoc explaining the choice.
     */
    @Test
    @DisplayName("maxTokenCount is derived as max(1024, maxBodySize / 4)")
    void shouldDeriveMaxTokenCountFromEffectiveMaxBodySize() {
        McpEnvelopeJsonCodec defaultCodec =
                new McpEnvelopeJsonCodec(HttpConfig.builder().build());
        long defaultMaxBodySize = HttpConfig.builder().build().maxBodySize();

        assertThat(defaultCodec.mapper().getFactory().streamReadConstraints().getMaxTokenCount())
                .as("the shipped 2 MiB default must derive maxTokenCount = maxBodySize / 4")
                .isEqualTo(defaultMaxBodySize / 4);

        McpEnvelopeJsonCodec smallBodyCodec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(TEST_MAX_BODY_SIZE).build());
        assertThat(smallBodyCodec.mapper().getFactory().streamReadConstraints().getMaxTokenCount())
                .as("a smaller configured maxBodySize must still derive maxBodySize / 4")
                .isEqualTo(EXPECTED_TOKEN_BUDGET);

        McpEnvelopeJsonCodec pathologicallySmallBodyCodec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(2_000L).build());
        assertThat(pathologicallySmallBodyCodec
                        .mapper()
                        .getFactory()
                        .streamReadConstraints()
                        .getMaxTokenCount())
                .as("a pathologically small maxBodySize (2000 / 4 = 500) must be floored at 1,024")
                .isEqualTo(1_024L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adversarialShapes")
    @DisplayName("TP-001: an adversarial document within maxBodySize is rejected at the token budget "
            + "before the large tree is retained")
    void shouldBoundTokenCountBeforeRetainingALargeTree(String shapeName, byte[] adversarialDocument) {
        McpEnvelopeJsonCodec codec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(TEST_MAX_BODY_SIZE).build());
        StreamReadConstraints boundedConstraints = codec.mapper().getFactory().streamReadConstraints();

        // Given: an adversarial document within the configured maxBodySize.
        assertThat(adversarialDocument.length)
                .as(shapeName + ": the adversarial fixture must itself fit under maxBodySize")
                .isLessThanOrEqualTo((int) TEST_MAX_BODY_SIZE);

        // The production entry point rejects it.
        McpEnvelopeJsonCodec.Result result = codec.decode(adversarialDocument);
        assertThat(result.isRejected())
                .as(shapeName + ": an adversarial document must be rejected by the bounded token count")
                .isTrue();

        // DECISIVE — assert the rejection point, not merely the outcome: count JsonNode
        // materializations under the codec's own bounded constraints versus the same bytes parsed
        // with an otherwise-identical but unlimited-token-count configuration. A regression that
        // stopped deriving the budget (e.g. reverting maxTokenCount to -1) would make
        // boundedNodeCount equal unlimitedNodeCount, failing the assertion below — a rejection that
        // merely happened after full materialization could never satisfy it either.
        long boundedNodeCount = countMaterializedNodes(adversarialDocument, boundedConstraints);
        long unlimitedNodeCount = countMaterializedNodes(adversarialDocument, unlimited(boundedConstraints));

        assertThat(unlimitedNodeCount)
                .as(shapeName + ": sanity check — the adversarial shape must actually materialize "
                        + "meaningfully more nodes than the derived budget once token count is unbounded, "
                        + "or this proof would be vacuous")
                .isGreaterThan(EXPECTED_TOKEN_BUDGET * 3 / 2);
        assertThat(boundedNodeCount)
                .as(shapeName + ": the tree materialized before rejection must never exceed the derived "
                        + "token budget — proving the codec bails out before retaining anywhere near the "
                        + "full adversarial tree")
                .isLessThanOrEqualTo(EXPECTED_TOKEN_BUDGET)
                .isLessThan(unlimitedNodeCount);
    }

    /** Builds an unlimited-token-count sibling of {@code constraints}, otherwise identical. */
    private static StreamReadConstraints unlimited(StreamReadConstraints constraints) {
        return StreamReadConstraints.builder()
                .maxNestingDepth(constraints.getMaxNestingDepth())
                .maxNumberLength(constraints.getMaxNumberLength())
                .maxStringLength(constraints.getMaxStringLength())
                .maxNameLength(constraints.getMaxNameLength())
                .maxDocumentLength(constraints.getMaxDocumentLength())
                .maxTokenCount(-1L)
                .build();
    }

    /**
     * Parses {@code document} under {@code constraints} with a counting {@link JsonNodeFactory},
     * returning the number of {@link JsonNode} instances actually created before the parse either
     * completes or is rejected. A rejection (expected once {@code maxTokenCount} is finite and
     * exceeded) is swallowed here — the counter already reflects what was materialized up to that
     * point, which is the whole point of this counting-spy technique.
     */
    private static long countMaterializedNodes(byte[] document, StreamReadConstraints constraints) {
        AtomicLong count = new AtomicLong();
        JsonNodeFactory countingFactory = new JsonNodeFactory(false) {
            @Override
            public ArrayNode arrayNode() {
                count.incrementAndGet();
                return super.arrayNode();
            }

            @Override
            public ArrayNode arrayNode(int capacity) {
                count.incrementAndGet();
                return super.arrayNode(capacity);
            }

            @Override
            public ObjectNode objectNode() {
                count.incrementAndGet();
                return super.objectNode();
            }

            @Override
            public NumericNode numberNode(int v) {
                count.incrementAndGet();
                return super.numberNode(v);
            }

            @Override
            public TextNode textNode(String text) {
                count.incrementAndGet();
                return super.textNode(text);
            }
        };
        JsonMapper mapper = JsonMapper.builder(
                        JsonFactory.builder().streamReadConstraints(constraints).build())
                .nodeFactory(countingFactory)
                .build();
        try {
            mapper.readValue(document, JsonNode.class);
        } catch (Exception rejectedOrOtherwise) {
            // Expected once maxTokenCount is finite and exceeded; the counter already reflects
            // everything materialized up to the rejection point.
        }
        return count.get();
    }

    /**
     * Builds a JSON array of many repeated, individually shallow nested-array chains (well under the
     * frozen {@code maxNestingDepth} of 1,000), filling up to {@code targetBytes}. Every repeat
     * contributes one {@code ArrayNode} materialization per nesting level, so the shape is
     * container-dense rather than byte-dense — the same amplification driver issue #423 measured.
     */
    private static byte[] nestedArrayChainsDocument(long targetBytes) {
        int depth = 400;
        String occurrence = "[".repeat(depth) + "]".repeat(depth);
        StringBuilder doc = new StringBuilder();
        doc.append('[');
        boolean first = true;
        while (doc.length() + occurrence.length() + 2 < targetBytes) {
            if (!first) {
                doc.append(',');
            }
            doc.append(occurrence);
            first = false;
        }
        doc.append(']');
        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a single flat JSON array of many single-digit integers, filling up to {@code
     * targetBytes}. Every element contributes exactly one {@code NumericNode} materialization, so the
     * shape is token-dense rather than container-dense — the second independent amplification driver
     * issue #423 measured.
     */
    private static byte[] manyTinyTokensDocument(long targetBytes) {
        StringBuilder doc = new StringBuilder();
        doc.append('[');
        boolean first = true;
        while (doc.length() < targetBytes - 2) {
            if (!first) {
                doc.append(',');
            }
            doc.append('1');
            first = false;
        }
        doc.append(']');
        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }
}
