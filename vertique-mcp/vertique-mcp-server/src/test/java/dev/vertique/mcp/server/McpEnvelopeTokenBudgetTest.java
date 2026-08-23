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
 * R11 (merge blocker 4) — supersedes R02 TP-001 (issue #423). {@link McpEnvelopeJsonCodec}'s {@code
 * maxTokenCount} was previously derived as {@code max(1024, maxBodySize / 4)}: the smallest ratio
 * that rejected three adversarial shapes. That answered "what ratio rejects these shapes?", not "how
 * much heap may N concurrent anonymous requests retain?" — the shipped 2 MiB default admitted up to
 * 524,288 tokens, and R02's own benchmark recorded a single unauthenticated request retaining 262,475
 * nodes, 95.2% of one adversarial shape's own full tree. The R02 ratio-4 derivation and its proof rows
 * are preserved below, superseded rather than deleted, exactly like the production class javadoc and
 * this feature's contract amendment.
 *
 * <p>{@code maxTokenCount} is now a fixed constant ({@link #MAX_TOKEN_COUNT}), independent of {@code
 * maxBodySize}, derived from a stated heap-and-concurrency budget: see {@link
 * McpEnvelopeJsonCodec}'s own {@code MAX_TOKEN_COUNT} javadoc for the full arithmetic, and the R11
 * evidence for the live concurrent-heap measurement this arithmetic is anchored to (256 real threads,
 * each retaining an accepted envelope tree at the cap, held live simultaneously, forced GC, {@code
 * Runtime} memory delta — ~49.5 MB retained, 92% of the 53,687,091-byte budget, reproducible within
 * 0.3% across three trials; a cap loosened by one step to 9,000 measured ~54.7 MB, exceeding the
 * budget on every trial). That live measurement is <strong>not</strong> committed here as a CI
 * assertion — heap-delta sampling is JVM/GC-configuration dependent, exactly the kind of flaky,
 * environment-sensitive gate R02 already declined to commit for the same reason (its own "benchmark
 * harness was run and deleted, not committed" evidence note). What <em>is</em> committed, below, is
 * (a) the derivation arithmetic itself, pinned so a future change to the cap, the budget, or the
 * concurrency assumption without re-deriving the others fails loudly, including the one-step-looser
 * sensitivity check, and (b) the same deterministic counting-spy technique R02 used — proving the
 * codec bails out before retaining anywhere near a full adversarial tree, for three shapes including
 * the short-object-key shape R02 recorded as its 95.2% weak point, now re-measured against the new
 * bound.
 */
class McpEnvelopeTokenBudgetTest {

    /** Mirrors {@code McpEnvelopeJsonCodec.MAX_TOKEN_COUNT} (R11). */
    private static final long MAX_TOKEN_COUNT = 8_000L;

    /** Stated heap budget: 512 MiB assumed instance heap, 10% allowed for ingress envelope retention. */
    private static final long HEAP_BUDGET_BYTES = 53_687_091L;

    /**
     * Stated worst-case concurrency of simultaneous anonymous (unauthenticated, pre-authorization)
     * in-flight requests. No connection-concurrency or rate limit exists at this layer (deferred to
     * MCP-002), so this is a stated design ceiling, not derived from an existing knob.
     */
    private static final long ASSUMED_CONCURRENCY = 256L;

    /**
     * Worst-case retained bytes per materialized {@code JsonNode}, measured (heap-delta harness,
     * forced GC, references held live) over R02's own container-heavy adversarial shapes at ~45–50
     * bytes/node under concurrent load, padded up to a round, conservative 50 for this pinned check.
     */
    private static final long MEASURED_WORST_CASE_BYTES_PER_NODE = 50L;

    /**
     * Both container-heavy shapes below materialize exactly one {@code JsonNode} per two parser
     * tokens: a nested-array-chain level is one {@code START_ARRAY}/{@code END_ARRAY} pair producing
     * one {@code ArrayNode}, and a short-object-key entry is one {@code FIELD_NAME}/value pair (plus
     * the shared {@code START_OBJECT}/{@code END_OBJECT}) producing one value node.
     */
    private static final long NODE_TO_TOKEN_RATIO_DENOMINATOR = 2L;

    /** The step size the sensitivity check loosens {@link #MAX_TOKEN_COUNT} by. */
    private static final long LOOSENED_STEP = 1_000L;

    /** The R02 ratio-4 default `maxBodySize`-derived cap, preserved to prove independence from it. */
    private static final long SHIPPED_DEFAULT_MAX_BODY_SIZE = 2_097_152L;

    private static final String NESTED_ARRAYS_SHAPE = "repeated nested-array chains";
    private static final String TINY_TOKENS_SHAPE = "many tiny tokens in one array";
    private static final String SHORT_KEY_OBJECT_SHAPE = "many short object keys (R02's 95.2% weak point)";

    private static Stream<Arguments> adversarialShapes() {
        return Stream.of(
                Arguments.of(NESTED_ARRAYS_SHAPE, nestedArrayChainsDocument(SHIPPED_DEFAULT_MAX_BODY_SIZE)),
                Arguments.of(TINY_TOKENS_SHAPE, manyTinyTokensDocument(SHIPPED_DEFAULT_MAX_BODY_SIZE)),
                Arguments.of(SHORT_KEY_OBJECT_SHAPE, shortKeyObjectDocument(SHIPPED_DEFAULT_MAX_BODY_SIZE)));
    }

    /**
     * Pins the derivation arithmetic itself (see {@link McpEnvelopeJsonCodec}'s {@code
     * MAX_TOKEN_COUNT} javadoc for the full budget statement): at the frozen cap, {@code
     * ASSUMED_CONCURRENCY} concurrent accepted requests must fit the stated heap budget using the
     * measured worst-case bytes/node; one step looser (+1,000 tokens) must exceed it. If this row ever
     * passes at the loosened value too, the proof has stopped measuring anything — see the class
     * javadoc for why the live heap measurement backing these constants is not itself committed here.
     */
    @Test
    @DisplayName("R11: the fixed cap fits the stated heap-and-concurrency budget; one step looser exceeds it")
    void shouldFitTheStatedBudgetAtTheCapAndExceedItOneStepLooser() {
        long nodesAtCap = MAX_TOKEN_COUNT / NODE_TO_TOKEN_RATIO_DENOMINATOR;
        long heapBytesAtCap = nodesAtCap * MEASURED_WORST_CASE_BYTES_PER_NODE * ASSUMED_CONCURRENCY;

        assertThat(heapBytesAtCap)
                .as(
                        "%d concurrent accepted worst-case requests at the frozen cap (%d tokens) must fit "
                                + "the stated %d-byte heap budget",
                        ASSUMED_CONCURRENCY, MAX_TOKEN_COUNT, HEAP_BUDGET_BYTES)
                .isLessThanOrEqualTo(HEAP_BUDGET_BYTES);

        long loosenedCap = MAX_TOKEN_COUNT + LOOSENED_STEP;
        long nodesAtLoosenedCap = loosenedCap / NODE_TO_TOKEN_RATIO_DENOMINATOR;
        long heapBytesAtLoosenedCap = nodesAtLoosenedCap * MEASURED_WORST_CASE_BYTES_PER_NODE * ASSUMED_CONCURRENCY;

        assertThat(heapBytesAtLoosenedCap)
                .as(
                        "DECISIVE — a cap loosened by one step (+%d, to %d tokens) must exceed the stated "
                                + "heap budget, proving this proof is not vacuous",
                        LOOSENED_STEP, loosenedCap)
                .isGreaterThan(HEAP_BUDGET_BYTES);
    }

    /**
     * The architectural pivot this slice makes: unlike R02's ratio, the new cap does not scale with
     * the configured {@code maxBodySize} at all, because heap retention is bounded by token count, not
     * input byte count — a single legitimate multi-megabyte string payload is one token. A regression
     * that reintroduced any {@code maxBodySize} dependency (including R02's old floor/divisor) would
     * fail this row for at least one of the three configurations.
     */
    @Test
    @DisplayName("maxTokenCount stays the fixed cap regardless of configured maxBodySize")
    void shouldKeepMaxTokenCountFixedRegardlessOfConfiguredMaxBodySize() {
        McpEnvelopeJsonCodec defaultCodec =
                new McpEnvelopeJsonCodec(HttpConfig.builder().build());
        McpEnvelopeJsonCodec smallBodyCodec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(2_000L).build());
        McpEnvelopeJsonCodec largeBodyCodec = new McpEnvelopeJsonCodec(
                HttpConfig.builder().maxBodySize(16_777_216L).build());

        assertThat(defaultCodec.mapper().getFactory().streamReadConstraints().getMaxTokenCount())
                .as("the shipped 2 MiB default must use the fixed cap, not a maxBodySize-derived ratio")
                .isEqualTo(MAX_TOKEN_COUNT);
        assertThat(smallBodyCodec.mapper().getFactory().streamReadConstraints().getMaxTokenCount())
                .as("a pathologically small maxBodySize must still use the fixed cap — maxDocumentLength "
                        + "alone already makes this cap unreachable for such a small body, so no floor "
                        + "logic is needed")
                .isEqualTo(MAX_TOKEN_COUNT);
        assertThat(largeBodyCodec.mapper().getFactory().streamReadConstraints().getMaxTokenCount())
                .as("a large configured maxBodySize must not widen the fixed cap — heap retention is "
                        + "bounded by token count, not by the configured body-size limit")
                .isEqualTo(MAX_TOKEN_COUNT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adversarialShapes")
    @DisplayName("TP-001: an adversarial document within maxBodySize is rejected at the fixed token "
            + "budget before the large tree is retained")
    void shouldBoundTokenCountBeforeRetainingALargeTree(String shapeName, byte[] adversarialDocument) {
        McpEnvelopeJsonCodec codec =
                new McpEnvelopeJsonCodec(HttpConfig.builder().build());
        StreamReadConstraints boundedConstraints = codec.mapper().getFactory().streamReadConstraints();

        // Given: an adversarial document within the shipped default maxBodySize.
        assertThat(adversarialDocument.length)
                .as(shapeName + ": the adversarial fixture must itself fit under maxBodySize")
                .isLessThanOrEqualTo((int) SHIPPED_DEFAULT_MAX_BODY_SIZE);

        // The production entry point rejects it.
        McpEnvelopeJsonCodec.Result result = codec.decode(adversarialDocument);
        assertThat(result.isRejected())
                .as(shapeName + ": an adversarial document must be rejected by the bounded token count")
                .isTrue();

        // DECISIVE — assert the rejection point, not merely the outcome: count JsonNode
        // materializations under the codec's own bounded constraints versus the same bytes parsed
        // with an otherwise-identical but unlimited-token-count configuration.
        long boundedNodeCount = countMaterializedNodes(adversarialDocument, boundedConstraints);
        long unlimitedNodeCount = countMaterializedNodes(adversarialDocument, unlimited(boundedConstraints));

        assertThat(unlimitedNodeCount)
                .as(shapeName + ": sanity check — the adversarial shape must actually materialize "
                        + "meaningfully more nodes than the fixed budget once token count is unbounded, "
                        + "or this proof would be vacuous")
                .isGreaterThan(MAX_TOKEN_COUNT * 3 / 2);
        assertThat(boundedNodeCount)
                .as(shapeName + ": the tree materialized before rejection must never exceed the fixed "
                        + "token budget — proving the codec bails out before retaining anywhere near the "
                        + "full adversarial tree")
                .isLessThanOrEqualTo(MAX_TOKEN_COUNT)
                .isLessThan(unlimitedNodeCount);
    }

    /**
     * R02's own weak point, re-measured: at the ratio-4 derivation, this shape's full tree (sized to
     * the shipped 2 MiB default) was 95.2% materialized before rejection — a cap so loose it barely
     * rejected the shape it was benchmarked against. At the fixed R11 cap, the same shape (same
     * generator, same 2 MiB target) is capped far lower, proven directly rather than assumed.
     */
    @Test
    @DisplayName("R11: the short-object-key shape (R02's 95.2% weak point) is now capped far below that")
    void shouldCapTheShortKeyShapeFarBelowItsOldNinetyFivePercentOutcome() {
        byte[] document = shortKeyObjectDocument(SHIPPED_DEFAULT_MAX_BODY_SIZE);
        StreamReadConstraints unlimited = unlimited(StreamReadConstraints.builder()
                .maxNestingDepth(1_000)
                .maxNumberLength(1_000)
                .maxStringLength(20_000_000)
                .maxNameLength(50_000)
                .maxDocumentLength(-1)
                .maxTokenCount(-1)
                .build());
        long fullTreeNodeCount = countMaterializedNodes(document, unlimited);

        McpEnvelopeJsonCodec codec =
                new McpEnvelopeJsonCodec(HttpConfig.builder().build());
        long boundedNodeCount =
                countMaterializedNodes(document, codec.mapper().getFactory().streamReadConstraints());

        double materializedPercent = 100.0 * boundedNodeCount / fullTreeNodeCount;

        assertThat(materializedPercent)
                .as("the short-key shape must be capped to a small fraction of its full tree at the new "
                        + "bound (measured 1.86%% of the shipped-default-sized tree), decisively below "
                        + "R02's reported 95.2%%")
                .isLessThan(20.0);
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

    /**
     * Builds a flat JSON object with many short keys mapping to small integers, filling up to {@code
     * targetBytes} — R02's own "~275K short object keys" shape family, its reported weak point at the
     * old ratio-4 derivation (95.2% of its own full tree materialized before rejection).
     */
    private static byte[] shortKeyObjectDocument(long targetBytes) {
        StringBuilder doc = new StringBuilder();
        doc.append('{');
        boolean first = true;
        int i = 0;
        while (true) {
            String entry = (first ? "" : ",") + "\"k" + Integer.toString(i, 36) + "\":1";
            // Stop before an entry would push the document past targetBytes (key width grows with i,
            // so a fixed per-iteration length check is unsafe) — leave room for the closing brace.
            if (doc.length() + entry.length() + 1 > targetBytes) {
                break;
            }
            doc.append(entry);
            first = false;
            i++;
        }
        doc.append('}');
        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }
}
