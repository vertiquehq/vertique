// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.engine.history.DecisionLog.Decision;
import dev.vertique.workflow.engine.spi.DecisionEntry;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.history.WorkflowHistoryRedactor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a wildcard pointer keeps the numbers of every card out of the patch, the baseline, the copy of a payload
 * and a reason, however the array shifts between two decisions.
 */
class DecisionLogWildcardRedactionTest {

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "cards", 1);

    private final List<DecisionEntry> entries = new ArrayList<>();
    private final DecisionLog log;

    @SuppressWarnings("unchecked")
    DecisionLogWildcardRedactionTest() {
        WorkflowHistoryRepository<SqlClient> history = mock(WorkflowHistoryRepository.class);
        when(history.appendDecision(any(), any())).thenAnswer(invocation -> {
            entries.add(invocation.getArgument(0));
            return Future.succeededFuture();
        });
        WorkflowHistoryRedactor redactor = key -> Set.of("/cards/*/number");
        this.log = new DecisionLog(
                new JsonPatchDiff(),
                history,
                Optional.of(redactor),
                Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static String cards(String... numbers) {
        JsonArray cards = new JsonArray();
        for (String number : numbers) {
            cards.add(new JsonObject().put("number", number).put("brand", "visa"));
        }
        return new JsonObject().put("cards", cards).encode();
    }

    private void logged(String before, String after, boolean migration) throws Exception {
        entries.clear();
        SqlClient tx = mock(SqlClient.class);
        Decision decision = new Decision(
                new WorkflowInstanceId(UUID.randomUUID()), 1L, "step", "outcome", KEY, "plan", 1L, 2L, "node");
        Future<Void> written = migration
                ? log.logMigration(decision, KEY, DecisionLog.parseState(before), DecisionLog.parseState(after), tx)
                : log.log(decision, DecisionLog.parseState(before), DecisionLog.parseState(after), tx);
        written.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("no card number reaches a patch or a baseline when an array loses its first element")
    void noCardNumberReachesPatchOrBaselineWhenAnArrayShifts() throws Exception {
        // Given a state with two cards that loses the first, so the second moves to index 0
        String before = cards("4111111111111111", "5500000000000004");
        String after = cards("5500000000000004");

        // When the change is logged as a decision and as a migration (which writes a baseline)
        logged(before, after, false);
        List<DecisionEntry> decision = List.copyOf(entries);
        logged(before, after, true);
        List<DecisionEntry> migration = List.copyOf(entries);

        // Then no logged form holds a number, and the marker stands where the numbers were
        for (DecisionEntry entry : concat(decision, migration)) {
            assertThat(String.valueOf(entry.patchJson())).doesNotContain("4111111111111111", "5500000000000004");
            assertThat(String.valueOf(entry.stateJson())).doesNotContain("4111111111111111", "5500000000000004");
        }
        assertThat(migration.get(1).stateJson()).contains("[redacted]").contains("visa");
    }

    @Test
    @DisplayName("a payload copy and a reason hold no card number of any element")
    void payloadCopyAndReasonHoldNoCardNumber() {
        // Given a payload with three cards
        JsonObject payload = new JsonObject(cards("1111", "2222", "3333"));
        SqlClient tx = mock(SqlClient.class);

        // When the payload is copied for the log
        Object copy = log.redactedCopy(KEY, payload, tx);

        // Then every number is replaced and the input is untouched
        assertThat(copy.toString()).doesNotContain("1111", "2222", "3333").contains("visa");
        assertThat(payload.encode()).contains("1111", "2222", "3333");

        // And a reason that echoes any of them loses it
        assertThat(log.redactReason(KEY, "bad card 2222 and 3333", payload.encode(), null, tx))
                .isEqualTo("bad card [redacted] and [redacted]");
    }

    private static List<DecisionEntry> concat(List<DecisionEntry> first, List<DecisionEntry> second) {
        List<DecisionEntry> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
