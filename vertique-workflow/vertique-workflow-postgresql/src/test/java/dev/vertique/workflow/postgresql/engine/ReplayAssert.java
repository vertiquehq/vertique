// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.zjsonpatch.JsonPatch;
import dev.vertique.workflow.postgresql.executor.DecisionScenario.Entry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.pointer.JsonPointer;
import java.util.List;
import java.util.Set;

/**
 * Replays a logged instance: it starts from the latest full-state entry and applies the patches of the entries after
 * it, in order, with the JSON Patch library, and compares the result with the state the instance row stored at that
 * point. The stored state is the oracle; the log never supplies the expected value. A value at a redacted pointer is
 * replaced by the marker in the stored state before the comparison, because the log never held it.
 */
final class ReplayAssert {

    /** The marker a redacted value is replaced by. */
    static final String REDACTED = "[redacted]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReplayAssert() {
        // helpers
    }

    /**
     * Asserts that replaying the whole log reproduces the stored state, except at the redacted pointers.
     *
     * @param entries          the instance's log
     * @param storedState      the instance's stored state
     * @param redactedPointers the pointers the log never held a value for
     */
    static void reproduces(List<Entry> entries, JsonObject storedState, Set<String> redactedPointers) {
        reproducesAt(entries, Long.MAX_VALUE, storedState, redactedPointers);
    }

    /**
     * Asserts that replaying the log up to a sequence reproduces the state the instance had stored at that point,
     * except at the redacted pointers.
     *
     * @param entries          the instance's log
     * @param throughSequence  the last sequence to replay
     * @param storedState      the instance's stored state at that point
     * @param redactedPointers the pointers the log never held a value for
     */
    static void reproducesAt(
            List<Entry> entries, long throughSequence, JsonObject storedState, Set<String> redactedPointers) {
        JsonNode replayed = replay(entries, throughSequence);
        JsonNode expected = tree(redacted(storedState, redactedPointers));
        assertThat(replayed)
                .as("the state replayed from the latest full-state entry through sequence %s", throughSequence)
                .isEqualTo(expected);
    }

    /** The state that the latest full-state entry up to the sequence and the patches after it describe. */
    static JsonNode replay(List<Entry> entries, long throughSequence) {
        Entry base = null;
        for (Entry entry : entries) {
            if (entry.sequence() <= throughSequence && entry.isFullState()) {
                base = entry;
            }
        }
        assertThat(base)
                .as("a full-state entry up to sequence %s", throughSequence)
                .isNotNull();
        JsonNode state = tree(base.fullState());
        for (Entry entry : entries) {
            if (entry.sequence() <= base.sequence() || entry.sequence() > throughSequence) {
                continue;
            }
            JsonArray operations = entry.patchOperations();
            if (operations != null) {
                state = JsonPatch.apply(tree(operations), state);
            }
        }
        return state;
    }

    /** A copy of the state with the value at each resolvable pointer replaced by the marker. */
    static JsonObject redacted(JsonObject state, Set<String> pointers) {
        JsonObject copy = state.copy();
        for (String pointer : pointers) {
            JsonPointer parsed = JsonPointer.from(pointer);
            if (parsed.queryJson(copy) != null) {
                parsed.writeJson(copy, REDACTED, false);
            }
        }
        return copy;
    }

    /** Asserts that the instance's log has every sequence from one to its last, once. */
    static void sequencesAreDense(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i).sequence())
                    .as(
                            "the sequence of entry %s of %s",
                            i, entries.stream().map(Entry::type).toList())
                    .isEqualTo(i + 1L);
        }
    }

    private static JsonNode tree(Object json) {
        try {
            return JSON.readTree(json instanceof JsonObject object ? object.encode() : ((JsonArray) json).encode());
        } catch (JsonProcessingException e) {
            throw new AssertionError("not JSON", e);
        }
    }
}
