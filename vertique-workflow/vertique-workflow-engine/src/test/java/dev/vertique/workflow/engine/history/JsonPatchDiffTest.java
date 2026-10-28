// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.JsonPatch;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the patch that describes a state change: it applies to the state before to give the state after, it holds
 * only {@code add}, {@code remove} and {@code replace}, and an unchanged state gives an empty patch.
 */
class JsonPatchDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> ALLOWED_OPERATIONS = Set.of("add", "remove", "replace");

    /**
     * One before and after state as JSON literals, and the paths the patch must name (none when the case does not care
     * how the library addresses the change).
     */
    private record Pair(String name, String before, String after, List<String> pathsNamed) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> pairs() {
        return Stream.of(
                        new Pair("equal objects", """
                                {"a":1,"b":{"c":[1,2],"d":"x"}}""", """
                                {"a":1,"b":{"c":[1,2],"d":"x"}}""", List.of()),
                        new Pair("a key added at depth 3", """
                                {"a":{"b":{"x":1}}}""", """
                                {"a":{"b":{"x":1,"c":2}}}""", List.of("/a/b/c")),
                        new Pair("a key removed at depth 3", """
                                {"a":{"b":{"x":1,"c":2}}}""", """
                                {"a":{"b":{"c":2}}}""", List.of("/a/b/x")),
                        new Pair("a key changed at depth 3", """
                                {"a":{"b":{"x":1,"c":2}}}""", """
                                {"a":{"b":{"x":1,"c":3}}}""", List.of("/a/b/c")),
                        new Pair("an array that grows at its end", """
                                {"items":["a","b"]}""", """
                                {"items":["a","b","c","d"]}""", List.of()),
                        new Pair("an array element changed in the middle", """
                                {"items":["a","b","c"]}""", """
                                {"items":["a","B","c"]}""", List.of()),
                        new Pair("an array element removed from the middle", """
                                {"items":["a","b","c","d"]}""", """
                                {"items":["a","c","d"]}""", List.of()),
                        new Pair("an array element inserted in the middle", """
                                {"items":["a","c","d"]}""", """
                                {"items":["a","b","c","d"]}""", List.of()),
                        new Pair("a value moved from one key to another", """
                                {"from":{"card":"4111-1111-1111-1111","kept":1}}""", """
                                {"to":{"card":"4111-1111-1111-1111","kept":1}}""", List.of()),
                        new Pair("a value copied to a second key", """
                                {"origin":{"card":"4111-1111-1111-1111"}}""", """
                                {"origin":{"card":"4111-1111-1111-1111"},"copy":{"card":"4111-1111-1111-1111"}}""", List.of()),
                        new Pair("a value changed to null", """
                                {"a":1,"b":{"c":"x"}}""", """
                                {"a":null,"b":{"c":null}}""", List.of("/a", "/b/c")),
                        new Pair("keys that need escaping", """
                                {"a/b":1,"c~d":2,"kept":3}""", """
                                {"a/b":10,"c~d":20,"e/f":30,"g~h":40,"kept":3}""", List.of("/a~1b", "/c~0d", "/e~1f", "/g~0h")))
                .map(pair -> Arguments.of(pair));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("a diff applied to the state before yields the state after, with add, remove and replace only")
    void diffAppliedToBeforeYieldsAfter(Pair pair) throws Exception {
        // Given a state before and after, as the persisted JSON
        JsonObject before = new JsonObject(pair.before());
        JsonObject after = new JsonObject(pair.after());

        // When the diff is computed and the library applies it to the state before
        JsonArray patch = new JsonPatchDiff().diff(before, after);
        JsonNode patched = JsonPatch.apply(JSON.readTree(patch.encode()), JSON.readTree(pair.before()));

        // Then the result is the state after
        assertThat(patched).isEqualTo(JSON.readTree(pair.after()));

        // And every operation is an add, a remove or a replace
        List<String> operations = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < patch.size(); i++) {
            JsonObject operation = patch.getJsonObject(i);
            operations.add(operation.getString("op"));
            paths.add(operation.getString("path"));
            // And no remove carries the removed value
            if ("remove".equals(operation.getString("op"))) {
                assertThat(operation.containsKey("value"))
                        .as("remove %s carries a value", operation.getString("path"))
                        .isFalse();
            }
        }
        assertThat(operations).isSubsetOf(ALLOWED_OPERATIONS);

        // And equal states give an empty patch, while the paths the case names are addressed as RFC 6901 pointers
        if (before.equals(after)) {
            assertThat(patch).isEmpty();
        } else {
            assertThat(patch).isNotEmpty();
        }
        assertThat(paths).containsAll(pair.pathsNamed());
    }
}
