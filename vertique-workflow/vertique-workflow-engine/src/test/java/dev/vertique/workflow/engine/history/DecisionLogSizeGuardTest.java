// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.engine.history.DecisionLog.Decision;
import dev.vertique.workflow.engine.spi.DecisionEntry;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the size of a state is measured before a patch is computed: a state over the bound is logged as a
 * full-state baseline and the diff is never asked for, so a large state never reaches the library's array diff.
 */
class DecisionLogSizeGuardTest {

    /** The most bytes of canonical JSON a state may have before its change is logged as a baseline. */
    private static final int SIZE_BOUND_BYTES = 64 * 1024;

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "size-guard", 1);

    private static final WorkflowInstanceId INSTANCE = new WorkflowInstanceId(UUID.randomUUID());

    /** States of chosen sizes. */
    private static final class States {
        /** A state holding an array of {@code count} small objects. */
        static JsonObject arrayOf(int count) {
            JsonArray items = new JsonArray();
            for (int i = 0; i < count; i++) {
                items.add(new JsonObject().put("n", i).put("label", "item-" + i));
            }
            return new JsonObject().put("ref", "guard").put("items", items);
        }

        /** A state of about one KiB. */
        static JsonObject oneKiB() {
            return new JsonObject().put("ref", "guard").put("note", "x".repeat(1000));
        }

        static int bytes(JsonObject state) {
            return state.encode().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /** A diff that counts how often it is asked and then answers as the real one does. */
    private static final class CountingDiff extends JsonPatchDiff {
        int calls;

        @Override
        public JsonArray diff(JsonObject before, JsonObject after) {
            calls++;
            return super.diff(before, after);
        }
    }

    /** The in-memory sink: the entries the log appended, in order. */
    private static final class MemoryHistory implements WorkflowHistoryRepository<SqlClient> {
        final List<DecisionEntry> entries = new ArrayList<>();
        private long sequence;

        @Override
        public Future<Void> appendDecision(DecisionEntry entry, SqlClient tx) {
            entries.add(entry);
            return Future.succeededFuture();
        }

        @Override
        public Future<Long> nextSequence(WorkflowInstanceId id, SqlClient tx) {
            return Future.succeededFuture(++sequence);
        }

        @Override
        public Future<Void> append(WorkflowHistoryEntry e, SqlClient tx) {
            throw new AssertionError("a decision must be logged through appendDecision");
        }

        @Override
        public Future<List<WorkflowHistoryEntry>> listRecentByInstance(WorkflowInstanceId id, int limit, SqlClient tx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<List<WorkflowHistoryEntry>> listByInstance(WorkflowInstanceId id, SqlClient tx) {
            throw new UnsupportedOperationException();
        }
    }

    private final CountingDiff diff = new CountingDiff();
    private final MemoryHistory history = new MemoryHistory();
    private final DecisionLog log = new DecisionLog(
            diff, history, Optional.empty(), Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));

    /** Logs one decision whose state changed from before to after and returns what was appended for it. */
    private List<DecisionEntry> logDecision(JsonObject before, JsonObject after) throws Exception {
        int already = history.entries.size();
        Decision decision = new Decision(INSTANCE, 7L, "step", "outcome", KEY, "plan-hash", 3L, 4L, "node-1");
        log.log(decision, before, after, mock(SqlClient.class))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        return List.copyOf(history.entries.subList(already, history.entries.size()));
    }

    @Test
    @DisplayName("a state over the size bound is logged as a baseline and the diff is never asked for")
    void stateOverTheSizeBoundIsLoggedAsABaselineWithoutDiffing() throws Exception {
        // Given a state of 1 KiB that grows into an array of 5,000 objects over the bound
        JsonObject small = States.oneKiB();
        JsonObject large = States.arrayOf(5000);
        assertThat(States.bytes(small)).isLessThan(SIZE_BOUND_BYTES);
        assertThat(States.bytes(large)).isGreaterThan(SIZE_BOUND_BYTES);

        // When the decision is logged
        List<DecisionEntry> grown = logDecision(small, large);

        // Then the diff was never called and the decision has no patch
        assertThat(diff.calls).isZero();
        assertThat(grown)
                .extracting(DecisionEntry::entryType)
                .containsExactly(WorkflowEntryType.DECISION, WorkflowEntryType.STATE_BASELINE);
        assertThat(grown.get(0).patchJson()).isNull();

        // And a baseline entry with the after state follows it
        assertThat(new JsonObject(grown.get(1).stateJson())).isEqualTo(large);

        // Given a state over the bound that shrinks into a small one
        // When the decision is logged
        List<DecisionEntry> shrunk = logDecision(large, small);

        // Then the before state alone is enough to skip the diff, and the baseline holds the small state
        assertThat(diff.calls).isZero();
        assertThat(shrunk)
                .extracting(DecisionEntry::entryType)
                .containsExactly(WorkflowEntryType.DECISION, WorkflowEntryType.STATE_BASELINE);
        assertThat(shrunk.get(0).patchJson()).isNull();
        assertThat(new JsonObject(shrunk.get(1).stateJson())).isEqualTo(small);

        // Given two states under the bound
        JsonObject before = new JsonObject().put("ref", "guard").put("count", 1);
        JsonObject after = new JsonObject().put("ref", "guard").put("count", 2);

        // When the decision is logged
        List<DecisionEntry> changed = logDecision(before, after);

        // Then the diff was called once and the one entry carries its patch
        assertThat(diff.calls).isEqualTo(1);
        assertThat(changed).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(WorkflowEntryType.DECISION);
            assertThat(new JsonArray(entry.patchJson()))
                    .isEqualTo(new JsonArray()
                            .add(new JsonObject()
                                    .put("op", "replace")
                                    .put("path", "/count")
                                    .put("value", 2)));
        });
    }
}
