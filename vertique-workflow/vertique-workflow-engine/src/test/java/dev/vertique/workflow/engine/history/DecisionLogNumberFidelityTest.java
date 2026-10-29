// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.flipkart.zjsonpatch.JsonPatch;
import dev.vertique.workflow.engine.history.DecisionLog.Decision;
import dev.vertique.workflow.engine.spi.DecisionEntry;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WorkflowEntryType;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
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
 * Verifies that the decision log keeps numbers exactly: a decimal that changes beyond the precision of a {@code double}
 * is a change that a patch records, an integer that becomes a decimal of the same value is a change, a decimal that
 * only gains trailing zeros is not, and replaying a baseline and the patches after it gives back the exact state.
 */
class DecisionLogNumberFidelityTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "fidelity", 1);

    private final List<DecisionEntry> entries = new ArrayList<>();
    private final DecisionLog log = new DecisionLog(new JsonPatchDiff(), history(), Optional.empty(), clock());

    @SuppressWarnings("unchecked")
    private WorkflowHistoryRepository<SqlClient> history() {
        WorkflowHistoryRepository<SqlClient> repository = mock(WorkflowHistoryRepository.class);
        when(repository.appendDecision(any(), any())).thenAnswer(invocation -> {
            entries.add(invocation.getArgument(0));
            return Future.succeededFuture();
        });
        return repository;
    }

    private static Clock clock() {
        return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /** Logs a change from one state text to another and returns the entries it appended. */
    private List<DecisionEntry> logChange(String before, String after) throws Exception {
        entries.clear();
        Decision decision = new Decision(
                new WorkflowInstanceId(UUID.randomUUID()), 1L, "step", "outcome", KEY, "plan", 1L, 2L, "node");
        log.log(decision, DecisionLog.parseState(before), DecisionLog.parseState(after), mock(SqlClient.class))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        return List.copyOf(entries);
    }

    @Test
    @DisplayName("a decimal that differs beyond the precision of a double is a change and replays exactly")
    void decimalDifferingBeyondDoublePrecisionIsAChangeAndReplaysExactly() throws Exception {
        // Given two amounts that are the same double but different decimals
        String before = "{\"amount\":1.00000000000000001}";
        String after = "{\"amount\":1.00000000000000002}";

        // When the change is logged
        List<DecisionEntry> logged = logChange(before, after);

        // Then one decision with a patch is written, not an empty patch
        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(WorkflowEntryType.DECISION);
            assertThat(entry.patchJson()).contains("1.00000000000000002");
        });

        // And replaying the patch on the state before gives the exact state after
        JsonNode replayed = JsonPatch.apply(ExactJson.read(logged.get(0).patchJson()), ExactJson.read(before));
        assertThat(replayed).isEqualTo(ExactJson.read(after));
        assertThat(replayed.toString()).isEqualTo(after);
    }

    @Test
    @DisplayName("an integer that becomes a decimal of the same value is a change")
    void integerBecomingDecimalOfSameValueIsAChange() throws Exception {
        // Given a state whose number is written as an integer and then as a decimal
        // When the change is logged
        List<DecisionEntry> logged = logChange("{\"a\":1}", "{\"a\":1.0}");

        // Then the patch replaces the value, and replay gives the decimal
        assertThat(logged).singleElement().satisfies(entry -> assertThat(entry.patchJson())
                .isEqualTo("[{\"op\":\"replace\",\"path\":\"/a\",\"value\":1.0}]"));
        JsonNode replayed = JsonPatch.apply(ExactJson.read(logged.get(0).patchJson()), ExactJson.read("{\"a\":1}"));
        assertThat(replayed.toString()).isEqualTo("{\"a\":1.0}");
    }

    @Test
    @DisplayName("a decimal that only gains trailing zeros is not a change")
    void decimalGainingTrailingZerosIsNotAChange() throws Exception {
        // Given the same decimal written with one more trailing zero
        // When the change is logged
        List<DecisionEntry> logged = logChange("{\"a\":1.0}", "{\"a\":1.00}");

        // Then the decision carries an empty patch
        assertThat(logged).singleElement().satisfies(entry -> assertThat(entry.patchJson())
                .isEqualTo("[]"));
    }

    @Test
    @DisplayName("a baseline keeps a decimal beyond double precision unrounded")
    void baselineKeepsDecimalUnrounded() throws Exception {
        // Given a migration, which always writes a baseline, to a state with a long decimal
        entries.clear();
        Decision decision = new Decision(
                new WorkflowInstanceId(UUID.randomUUID()), 1L, "step", "migrated", KEY, "plan", 1L, 2L, "node");

        // When it is logged
        log.logMigration(
                        decision,
                        KEY,
                        DecisionLog.parseState("{\"amount\":1}"),
                        DecisionLog.parseState("{\"amount\":123456789.123456789123456789}"),
                        mock(SqlClient.class))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        // Then the baseline holds the exact text
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).stateJson()).isEqualTo("{\"amount\":123456789.123456789123456789}");
    }
}
