// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T015 TP-002 — a fixture matrix over nested records, collections ({@code List<String>}), and maps
 * ({@code Map<String, String>}) at traversal depths one through four, exercising the same
 * production mechanism {@code McpInputPipelineIT} (TP-001) exercises: {@link
 * InputObjectProcessor#processInput} at {@link InputLocation#PAYLOAD}, the same call
 * {@code PipelineToolInvoker.prepare()} makes on stage 2 of the fixed request-time pipeline for a
 * real generated invoker.
 *
 * <p><strong>This fixture matrix was newly authored for this feature — it is NOT the published
 * INP-001 cross-transport fixture set the frozen contract text for TP-002 names.</strong> That
 * corpus does not exist: no resource, class, or test-jar anywhere in this repository is a published
 * cross-transport fixture set, and no dependency path makes one reachable from
 * {@code vertique-mcp-server}. The existing {@code *Parity*} tests are per-module, not a shared
 * corpus. Rather than fabricate one and call it "the published set" — which would make a parity
 * claim false — this test proves the substantive half that IS provable with what exists: that the
 * production traversal engine propagates {@link InputLocation#PAYLOAD}, never {@code BODY}, at
 * every depth of a nested/collection/map object graph.
 *
 * <p><strong>What this test proves.</strong> {@link InputLocation#PAYLOAD} is the {@link
 * InputValueContext#location()} every {@link Sanitizer} invocation observes, at all twelve leaf
 * positions in the fixture matrix (3 shapes — record scalar, string collection, string map — ×
 * 4 depths). Each of the twelve is observed <em>individually</em>, from a real {@link Sanitizer}
 * invocation during one real traversal of the whole {@code Level1} graph, keyed by that leaf's own
 * dot-separated path — never asserted once at the root and assumed to recurse (the decisive trap
 * this task calls out: a single top-level assertion proves nothing about depth two, three, or
 * four). It also proves no {@code BODY} compatibility-mode location is reachable on any path.
 *
 * <p><strong>What this test does NOT prove.</strong> Cross-transport parity against REST is not
 * established here and is not claimed anywhere in this class — no REST-side counterpart runs in
 * this test, and this fixture matrix has no relationship to any published corpus. That parity claim
 * remains unproven and is tracked separately from this task.
 */
class McpInputPolicyFixtureMatrixTest {

    @Test
    @DisplayName("InputLocation.PAYLOAD propagates at every traversal depth (1-4) across nested "
            + "records, collections, and maps — observed per depth, never BODY")
    void shouldPropagatePayloadLocationAtEveryTraversalDepth() {
        // Given: the real production traversal engine, with a recording Sanitizer wired as every
        // leaf's declared @Sanitize processor, and a fixture matrix of nested records/collections/
        // maps four levels deep (Level1 -> Level2 -> Level3 -> Level4).
        RecordingSanitizer sanitizer = new RecordingSanitizer();
        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException(
                            "no canonicalizer declared in this fixture: " + canonicalizerType);
                },
                sanitizerType -> {
                    if (sanitizerType == RecordingSanitizer.class) {
                        return sanitizer;
                    }
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });
        processor.precomputeFieldNameResolution(Level1.class, InputFieldNameResolver.IDENTITY);

        Map<String, Object> level4 = Map.of(
                "scalar4", "depth4-scalar",
                "list4", List.of("depth4-list-0"),
                "map4", Map.of("k4", "depth4-map-v"));
        Map<String, Object> level3 = Map.of(
                "scalar3",
                "depth3-scalar",
                "list3",
                List.of("depth3-list-0"),
                "map3",
                Map.of("k3", "depth3-map-v"),
                "nested",
                level4);
        Map<String, Object> level2 = Map.of(
                "scalar2",
                "depth2-scalar",
                "list2",
                List.of("depth2-list-0"),
                "map2",
                Map.of("k2", "depth2-map-v"),
                "nested",
                level3);
        Map<String, Object> level1 = Map.of(
                "scalar1",
                "depth1-scalar",
                "list1",
                List.of("depth1-list-0"),
                "map1",
                Map.of("k1", "depth1-map-v"),
                "nested",
                level2);

        // When: the same call PipelineToolInvoker.prepare() makes on stage 2 of the T015 fixed
        // pipeline — processInput at InputLocation.PAYLOAD — runs over the whole graph.
        processor.processInput(
                level1,
                Level1.class,
                EffectiveInputPolicies.NONE,
                InputLocation.PAYLOAD,
                InputFieldNameResolver.IDENTITY);

        // Then: every one of the twelve leaf positions — record scalar, string-collection element,
        // and string-map entry, at depths one through four — independently observed PAYLOAD from
        // its own real Sanitizer invocation. Soft assertions so every leaf is genuinely evaluated in
        // this run regardless of any other leaf's outcome: a depth-three-only regression must show up
        // as exactly the depth-three failures below, with every other depth's checks still evaluated
        // and still passing in the same run — never masked by fail-fast stopping at the first failure.
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(sanitizer.locationAt("scalar1"))
                .as("DECISIVE depth 1 record leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("list1[0]"))
                .as("DECISIVE depth 1 collection leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("map1.k1"))
                .as("DECISIVE depth 1 map leaf")
                .isEqualTo(InputLocation.PAYLOAD);

        softly.assertThat(sanitizer.locationAt("nested.scalar2"))
                .as("DECISIVE depth 2 record leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.list2[0]"))
                .as("DECISIVE depth 2 collection leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.map2.k2"))
                .as("DECISIVE depth 2 map leaf")
                .isEqualTo(InputLocation.PAYLOAD);

        softly.assertThat(sanitizer.locationAt("nested.nested.scalar3"))
                .as("DECISIVE depth 3 record leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.nested.list3[0]"))
                .as("DECISIVE depth 3 collection leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.nested.map3.k3"))
                .as("DECISIVE depth 3 map leaf")
                .isEqualTo(InputLocation.PAYLOAD);

        softly.assertThat(sanitizer.locationAt("nested.nested.nested.scalar4"))
                .as("DECISIVE depth 4 record leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.nested.nested.list4[0]"))
                .as("DECISIVE depth 4 collection leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertThat(sanitizer.locationAt("nested.nested.nested.map4.k4"))
                .as("DECISIVE depth 4 map leaf")
                .isEqualTo(InputLocation.PAYLOAD);
        softly.assertAll();

        // And: exactly the twelve expected leaves were observed — nothing more, nothing fewer — and
        // no BODY compatibility-mode location was ever recorded, on any path, at any depth.
        assertThat(sanitizer.observedLocations())
                .as("DECISIVE: exactly the fixture matrix's twelve leaf positions were reached")
                .hasSize(12);
        assertThat(sanitizer.observedLocations())
                .as("DECISIVE: no BODY compatibility-mode location is reachable on any path")
                .doesNotContainValue(InputLocation.BODY);
    }

    // --- Fixture matrix: nested records, collections, and maps, four levels deep ---

    private record Level1(
            @Sanitize(RecordingSanitizer.class) String scalar1,
            @Sanitize(RecordingSanitizer.class) List<String> list1,
            @Sanitize(RecordingSanitizer.class) Map<String, String> map1,
            Level2 nested) {}

    private record Level2(
            @Sanitize(RecordingSanitizer.class) String scalar2,
            @Sanitize(RecordingSanitizer.class) List<String> list2,
            @Sanitize(RecordingSanitizer.class) Map<String, String> map2,
            Level3 nested) {}

    private record Level3(
            @Sanitize(RecordingSanitizer.class) String scalar3,
            @Sanitize(RecordingSanitizer.class) List<String> list3,
            @Sanitize(RecordingSanitizer.class) Map<String, String> map3,
            Level4 nested) {}

    private record Level4(
            @Sanitize(RecordingSanitizer.class) String scalar4,
            @Sanitize(RecordingSanitizer.class) List<String> list4,
            @Sanitize(RecordingSanitizer.class) Map<String, String> map4) {}

    /**
     * Records the {@link InputLocation} observed by every {@link Sanitizer#sanitize} invocation,
     * keyed by {@link InputValueContext#path()} — the dot-separated leaf path the production
     * traversal computed for that value. Never mutates the value: this fixture exists solely to
     * observe what the engine propagated, not to alter it.
     */
    private static final class RecordingSanitizer implements Sanitizer {

        private final Map<String, InputLocation> observed = new ConcurrentHashMap<>();

        @Override
        public String sanitize(String value, InputValueContext context) {
            observed.put(context.path(), context.location());
            return value;
        }

        InputLocation locationAt(String path) {
            return observed.get(path);
        }

        Map<String, InputLocation> observedLocations() {
            return observed;
        }
    }
}
