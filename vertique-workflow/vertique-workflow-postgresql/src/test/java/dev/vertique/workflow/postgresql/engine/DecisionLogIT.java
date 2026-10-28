// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.history.WorkflowHistoryRedactor;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.DecisionScenario;
import dev.vertique.workflow.postgresql.executor.DecisionScenario.Command;
import dev.vertique.workflow.postgresql.executor.DecisionScenario.Entry;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the decision log of a segment: one entry per engine decision with the command, step, outcome, definition,
 * plan hash, state versions and executor; patches that reproduce the stored state from the latest full-state entry; a
 * baseline for an instance that existed before the log and for a patch that grew too large; the rollback of a rejected
 * parked signal's entries; and the redaction of the values a bound redactor names from every logged form.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class DecisionLogIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_decision_log_it")
            .withMigration("classpath:db/migration/workflow");

    /** The most bytes a stored patch may have; a larger change is logged as a full state. */
    private static final int PATCH_BOUND_BYTES = 64 * 1024;

    private static final String NUMBER = "4111111111111111";

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static DbPoolConfig pool() {
        return db.toPoolConfig();
    }

    // --- Proofs ---

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a decision node's choice is logged with the plan hash, the state versions and an empty patch")
    void decisionNodeChoiceIsLoggedWithPlanHashAndEmptyPatch() {
        try (DecisionScenario scenario = DecisionScenario.routeDecision(pool())) {
            // Given a plan whose decision node route returns approve without changing state
            // When a start by a user is recorded and run to idle
            WorkflowInstanceId id = scenario.start(user("u-route"), DecisionScenario.ROUTE, "route-1");
            scenario.runToIdle(id);

            // Then the history has a DECISION entry for step route naming the START command and the outcome approve
            Command start = scenario.commandOf(id, "START");
            List<Entry> entries = entries(scenario, id);
            Entry route = only(entries, e -> e.type().equals("DECISION") && "route".equals(e.stepId()));
            assertThat(route.commandId()).isEqualTo(start.id());
            assertThat(route.outcome()).isEqualTo("approve");

            // And the definition key and the plan hash of the instance
            assertThat(route.scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(route.definitionId()).isEqualTo(DecisionScenario.ROUTE);
            assertThat(route.definitionVersion()).isEqualTo(1L);
            assertThat(route.planHash()).isNotBlank().isEqualTo(scenario.planHash(id));

            // And equal state versions before and after, and an empty patch
            assertThat(route.versionBefore()).isNotNull().isEqualTo(route.versionAfter());
            assertThat(route.patchOperations()).isNotNull().isEmpty();

            // And the node id of the executor that ran the segment, stored as the entry version 2
            assertThat(route.executorId()).isNotBlank();
            if (start.claimedBy() != null) {
                assertThat(route.executorId()).isEqualTo(start.claimedBy());
            }
            assertThat(route.entryVersion()).isEqualTo(2);

            // And the first segment's full-state entry precedes it and holds the initial state
            Entry baseline = entries.stream()
                    .filter(Entry::isFullState)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no full-state entry in " + types(entries)));
            assertThat(baseline.sequence()).isLessThan(route.sequence());
            assertThat(baseline.fullState().getString("ref")).isEqualTo("route-1");

            // And the entries of the segment carry the actor that recorded the command
            assertThat(entries.stream().filter(e -> e.type().equals("DECISION") || e.isFullState()))
                    .isNotEmpty()
                    .allSatisfy(entry -> {
                        assertThat(entry.actorKind()).as(entry.type()).isEqualTo("USER");
                        assertThat(entry.actorId()).as(entry.type()).isEqualTo("u-route");
                        assertThat(entry.attribution()).as(entry.type()).isEqualTo("VERIFIED");
                    });
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the patches after the latest full-state entry reproduce the stored state")
    void patchesFromTheLatestFullStateReproduceTheStoredState() {
        try (DecisionScenario scenario = DecisionScenario.flow(pool())) {
            // Given version 1 with a signal wait, a task whose decision changes state, and a fork of two branches
            // joined by a reducer, and version 2 with a migration handler
            WorkflowInstanceId id = scenario.start(null, DecisionScenario.FLOW, "flow-1");
            scenario.runToIdle(id);

            // When the instance runs: a signal, a task decision, the fork and join, a migration to version 2 and the
            // completion
            scenario.signal(null, id, "pay", new JsonObject().put("value", "p1"), "k-pay");
            scenario.runToIdle(id);
            scenario.completeTask(id, "approve", new JsonObject().put("note", "ok"), "k-task");
            scenario.runToIdle(id);
            JsonObject beforeMigration = scenario.state(id);
            long sequenceBeforeMigration = scenario.lastSequence(id);
            scenario.migrate(id, 2);
            scenario.runToIdle(id);
            scenario.signal(null, id, "close", new JsonObject().put("value", "c1"), "k-close");
            scenario.runToIdle(id);

            // Then the instance completed and the patches after the migration's full-state entry give the stored
            // state, with no redactor bound so the comparison is exact
            List<Entry> entries = entries(scenario, id);
            assertThat(scenario.status(id)).isEqualTo("COMPLETED");
            ReplayAssert.reproduces(entries, scenario.state(id), Set.of());

            // And the same holds from the start's full-state entry up to the migration
            ReplayAssert.reproducesAt(entries, sequenceBeforeMigration, beforeMigration, Set.of());

            // And only the start and the migration logged the full state
            assertThat(entries.stream().filter(Entry::isFullState)).hasSize(2);

            // And the signal, the task decision and the join reducer each changed the state through a patch
            List<String> patchedValues = new ArrayList<>();
            List<String> patchedPaths = new ArrayList<>();
            for (Entry entry : entries) {
                JsonArray operations = entry.patchOperations();
                if (operations != null && !entry.isFullState()) {
                    for (int i = 0; i < operations.size(); i++) {
                        JsonObject operation = operations.getJsonObject(i);
                        patchedPaths.add(operation.getString("path"));
                        patchedValues.add(String.valueOf(operation.getValue("value")));
                    }
                }
            }
            assertThat(patchedValues).contains("pay:p1", "approve:ok", "joined:2", "close:c1");

            // And no branch token or join row appears in any patch: every path addresses the state
            assertThat(patchedPaths).isNotEmpty().allMatch(path -> path.equals("/ref") || path.startsWith("/applied"));

            // And the log has no gap in its sequence
            ReplayAssert.sequencesAreDense(entries);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an instance that existed before the log writes a full-state baseline when first claimed")
    void legacyInstanceLogsAFullStateBaselineWhenFirstClaimed() {
        try (DecisionScenario scenario = DecisionScenario.legacy(pool())) {
            // Given an instance waiting at a signal, made legacy by clearing its baseline mark and deleting its history
            WorkflowInstanceId id = scenario.start(null, DecisionScenario.LEGACY, "legacy-1");
            scenario.runToIdle(id);
            JsonObject waiting = scenario.state(id);
            scenario.makeLegacy(id);
            assertThat(scenario.baselineLogged(id)).isFalse();
            assertThat(scenario.entries(id)).isEmpty();

            // When a signal is recorded and executed
            scenario.signal(null, id, "first", new JsonObject().put("value", "f1"), "k-first");
            scenario.runToIdle(id);

            // Then the segment wrote one STATE_BASELINE with the full state as it was, before its decisions
            List<Entry> afterFirst = entries(scenario, id);
            Entry baseline = only(afterFirst, e -> e.type().equals("STATE_BASELINE"));
            assertThat(baseline.fullState()).isEqualTo(waiting);
            assertThat(afterFirst.stream().filter(e -> e.type().equals("DECISION")))
                    .isNotEmpty()
                    .allSatisfy(decision -> assertThat(decision.sequence()).isGreaterThan(baseline.sequence()));

            // And the instance is marked as having its baseline logged
            assertThat(scenario.baselineLogged(id)).isTrue();

            // When a second signal is recorded and executed
            scenario.signal(null, id, "second", new JsonObject().put("value", "s1"), "k-second");
            scenario.runToIdle(id);

            // Then no second baseline was written, and the patches from the first one reproduce the stored state
            List<Entry> afterSecond = entries(scenario, id);
            assertThat(afterSecond.stream().filter(e -> e.type().equals("STATE_BASELINE")))
                    .extracting(Entry::sequence)
                    .containsExactly(baseline.sequence());
            ReplayAssert.reproduces(afterSecond, scenario.state(id), Set.of());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an oversized patch is replaced by a full-state baseline and replay crosses it")
    void oversizedPatchIsReplacedByAFullStateBaselineAndReplayCrossesIt() {
        try (DecisionScenario scenario = DecisionScenario.blob(pool())) {
            // Given a plan whose signal updater appends small items to a list, grows a text, shrinks it and appends
            WorkflowInstanceId id = scenario.start(null, DecisionScenario.BLOB, "blob-1");
            scenario.runToIdle(id);
            long sequence0 = scenario.lastSequence(id);

            // When four signals are recorded and run to idle, one after the other, noting the state after each
            scenario.signal(null, id, "append1", edit(List.of("a", "b"), null), "k1");
            scenario.runToIdle(id);
            JsonObject state1 = scenario.state(id);
            long sequence1 = scenario.lastSequence(id);
            scenario.signal(null, id, "grow", edit(null, "x".repeat(70_000)), "k2");
            scenario.runToIdle(id);
            JsonObject state2 = scenario.state(id);
            long sequence2 = scenario.lastSequence(id);
            scenario.signal(null, id, "shrink", edit(null, "0123456789"), "k3");
            scenario.runToIdle(id);
            JsonObject state3 = scenario.state(id);
            long sequence3 = scenario.lastSequence(id);
            scenario.signal(null, id, "append2", edit(List.of("c"), null), "k4");
            scenario.runToIdle(id);
            JsonObject state4 = scenario.state(id);
            long sequence4 = scenario.lastSequence(id);
            List<Entry> entries = entries(scenario, id);

            // Then the first segment's entry has a patch of add operations for the appended items
            List<Entry> first = segment(entries, sequence0, sequence1);
            assertThat(first.stream().filter(e -> e.type().equals("STATE_BASELINE")))
                    .isEmpty();
            assertThat(operationsOf(first)).hasSize(2).allSatisfy(operation -> {
                assertThat(operation.getString("op")).isEqualTo("add");
                assertThat(operation.getString("path")).startsWith("/items");
            });

            // And the second segment's decision entry has no patch and a STATE_BASELINE with the full state follows
            // it, because its after-state is over the bound
            List<Entry> second = segment(entries, sequence1, sequence2);
            assertPatchlessDecisionsAreFollowedByABaseline(entries, second);
            assertThat(lastBaseline(second).fullState()).isEqualTo(state2);
            assertThat(state2.encode().getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(PATCH_BOUND_BYTES);

            // And the third segment's decision entry also has no patch and another baseline follows it, because its
            // before-state is over the bound
            List<Entry> third = segment(entries, sequence2, sequence3);
            assertPatchlessDecisionsAreFollowedByABaseline(entries, third);
            assertThat(lastBaseline(third).fullState()).isEqualTo(state3);
            assertThat(state3.encode().getBytes(StandardCharsets.UTF_8)).hasSizeLessThan(PATCH_BOUND_BYTES);

            // And the fourth segment, with both states under the bound, has a normal patch: patching resumed
            List<Entry> fourth = segment(entries, sequence3, sequence4);
            assertThat(fourth.stream().filter(e -> e.type().equals("STATE_BASELINE")))
                    .isEmpty();
            assertThat(fourth.stream().filter(e -> e.type().equals("DECISION") && e.patch() == null))
                    .isEmpty();
            assertThat(operationsOf(fourth)).hasSize(1).allSatisfy(operation -> {
                assertThat(operation.getString("op")).isEqualTo("add");
                assertThat(operation.getString("path")).startsWith("/items");
            });

            // And the patches reproduce the state stored at each of these points, across the baselines
            ReplayAssert.reproducesAt(entries, sequence1, state1, Set.of());
            ReplayAssert.reproducesAt(entries, sequence2, state2, Set.of());
            ReplayAssert.reproducesAt(entries, sequence3, state3, Set.of());
            ReplayAssert.reproducesAt(entries, sequence4, state4, Set.of());
            ReplayAssert.reproduces(entries, scenario.state(id), Set.of());

            // And no stored patch exceeds the bound
            assertThat(entries.stream().map(Entry::patchOperations).filter(java.util.Objects::nonNull))
                    .allSatisfy(patch -> assertThat(patch.encode().getBytes(StandardCharsets.UTF_8))
                            .hasSizeLessThanOrEqualTo(PATCH_BOUND_BYTES));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a rejected parked signal leaves its rejection and none of its decision entries")
    void rejectedParkedSignalLeavesNoDecisionEntries() {
        try (DecisionScenario scenario = DecisionScenario.parked(pool())) {
            // Given an instance waiting at paid, and a parked note whose offer applies and then fails the step after
            WorkflowInstanceId id = scenario.start(null, DecisionScenario.PARKED, "parked-1");
            scenario.runToIdle(id);
            scenario.signal(user("u-parked"), id, "note", new JsonObject().put("explode", true), "k-note");
            scenario.runToIdle(id);
            assertThat(commandByKey(scenario, id, "k-note").state()).isEqualTo("PARKED");

            // When the segment reaching the note wait runs
            scenario.signal(user("u-exec"), id, "paid", new JsonObject().put("value", "p1"), "k-paid");
            scenario.runToIdle(id);

            // Then the note's command is rejected, with a rejection entry
            Command note = commandByKey(scenario, id, "k-note");
            Command paid = commandByKey(scenario, id, "k-paid");
            assertThat(note.state()).isEqualTo("REJECTED");
            List<Entry> entries = entries(scenario, id);
            Entry rejection = only(entries, e -> e.type().equals("COMMAND_REJECTED") && note.id() == idOf(e));

            // And no DECISION entry of the note's offer survives
            assertThat(entries.stream().filter(e -> e.type().equals("DECISION") && note.id() == idOf(e)))
                    .isEmpty();

            // And the segment's own decisions are logged, with the actor of the command that ran the segment, and
            // the rejection comes after them
            List<Entry> own = entries.stream()
                    .filter(e -> e.type().equals("DECISION") && paid.id() == idOf(e))
                    .toList();
            assertThat(own).isNotEmpty().allSatisfy(entry -> assertThat(entry.actorId())
                    .isEqualTo("u-exec"));
            assertThat(rejection.sequence()).isGreaterThan(own.getFirst().sequence());

            // And the offer's state change is gone, and the patches still reproduce the stored state
            assertThat(scenario.state(id).getJsonArray("applied")).containsExactly("paid:p1");
            ReplayAssert.reproduces(entries, scenario.state(id), Set.of());
            ReplayAssert.sequencesAreDense(entries);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a redacted pointer is never logged and replay matches the stored state except there")
    void redactedPointerIsNeverLoggedAndReplayMatchesExceptThere() {
        try (DecisionScenario scenario = RedactingFixture.payment(pool())) {
            // Given a bound redactor naming /card/number for payment and nothing for order
            Set<String> pointers = Set.of("/card/number");

            // When a payment start and an order start with the card number in their payloads are recorded, and the
            // payment instance runs through a note, a 70,000-character field change, its shrinking and a signal
            // that removes the card and repeats the number
            WorkflowInstanceId payment =
                    scenario.start(null, DecisionScenario.PAYMENT, RedactingFixture.begin("p-1", NUMBER));
            scenario.runToIdle(payment);
            WorkflowInstanceId order =
                    scenario.start(null, DecisionScenario.ORDER, RedactingFixture.begin("o-1", NUMBER));
            scenario.runToIdle(order);
            scenario.signal(null, order, "note", RedactingFixture.text("order note"), "o-note");
            scenario.runToIdle(order);

            scenario.signal(null, payment, "note", RedactingFixture.text("first note"), "k-note");
            scenario.runToIdle(payment);
            JsonObject afterNote = scenario.state(payment);
            long sequenceNote = scenario.lastSequence(payment);
            scenario.signal(null, payment, "bulk", RedactingFixture.text("x".repeat(70_000)), "k-bulk");
            scenario.runToIdle(payment);
            JsonObject afterBulk = scenario.state(payment);
            long sequenceBulk = scenario.lastSequence(payment);
            scenario.signal(null, payment, "shrink", RedactingFixture.text(""), "k-shrink");
            scenario.runToIdle(payment);
            JsonObject afterShrink = scenario.state(payment);
            long sequenceShrink = scenario.lastSequence(payment);
            scenario.signal(null, payment, "cardCleared", RedactingFixture.card(NUMBER), "k-clear");
            scenario.runToIdle(payment);
            JsonObject afterClear = scenario.state(payment);
            long sequenceClear = scenario.lastSequence(payment);
            scenario.signal(null, payment, "tail", RedactingFixture.text("last note"), "k-tail");
            scenario.runToIdle(payment);
            List<Entry> entries = entries(scenario, payment);

            // Then no history row of the payment instance holds the number, in any column
            assertThat(scenario.historyText(payment)).doesNotContain(NUMBER);

            // And the snapshot kept it while the instance held it
            assertThat(afterNote.getJsonObject("card").getString("number")).isEqualTo(NUMBER);
            assertThat(afterShrink.getJsonObject("card").getString("number")).isEqualTo(NUMBER);

            // And the marker stands at /card/number in the start's full-state entry
            Entry startState =
                    entries.stream().filter(Entry::isFullState).findFirst().orElseThrow();
            assertThat(startState.fullState().getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED);

            // And in the logged start payload and the logged signal payload
            Command start = scenario.commandOf(payment, "START");
            assertThat(loggedPayloads(entries, start.id())).isNotEmpty().allSatisfy(payload -> assertThat(
                            payload.getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED));
            Command cleared = commandByKey(scenario, payment, "k-clear");
            assertThat(loggedPayloads(entries, cleared.id())).isNotEmpty().allSatisfy(payload -> assertThat(
                            payload.getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED));

            // And in the fallback baseline the 70,000-character change forced
            List<Entry> fallback = segment(entries, sequenceNote, sequenceBulk);
            assertThat(lastBaseline(fallback).fullState().getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED);

            // And replay reproduces the stored state except at the pointer, at every point and at the end
            ReplayAssert.reproducesAt(entries, sequenceNote, afterNote, pointers);
            ReplayAssert.reproducesAt(entries, sequenceBulk, afterBulk, pointers);
            ReplayAssert.reproducesAt(entries, sequenceShrink, afterShrink, pointers);
            ReplayAssert.reproducesAt(entries, sequenceClear, afterClear, pointers);
            ReplayAssert.reproduces(entries, scenario.state(payment), pointers);

            // And the START command row still holds the number, because the executor needs it
            assertThat(scenario.commandPayload(start.id()).encode()).contains(NUMBER);

            // And the order instance's log holds it unredacted
            assertThat(scenario.historyText(order)).contains(NUMBER);
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a migration redacts with the source pointers before it and the target pointers after it")
    void migrationRedactsWithSourcePointersBeforeAndTargetPointersAfter() {
        try (DecisionScenario scenario = RedactingFixture.paymentMigration(pool())) {
            // Given a redactor naming /card/number for version 1 and /payer/iban for version 2, a version 1 instance
            // reached through a start and a version 1 signal that repeats the number, and a handler that moves the
            // number into payer.iban and removes the card
            WorkflowInstanceId id =
                    scenario.start(null, DecisionScenario.PAYMENT, RedactingFixture.begin("m-1", NUMBER));
            scenario.runToIdle(id);
            scenario.signal(null, id, "v1note", RedactingFixture.card(NUMBER), "k-v1");
            scenario.runToIdle(id);
            JsonObject beforeMigration = scenario.state(id);
            long sequenceBeforeMigration = scenario.lastSequence(id);

            // When the migration and then a version 2 signal that carries the number are recorded and run to idle
            scenario.migrate(id, 2);
            scenario.runToIdle(id);
            scenario.signal(null, id, "v2note", RedactingFixture.payer(NUMBER), "k-v2");
            scenario.runToIdle(id);
            List<Entry> entries = entries(scenario, id);

            // Then no history row holds the number, and no log line does
            assertThat(scenario.historyText(id)).doesNotContain(NUMBER);
            assertThat(scenario.loggedText()).doesNotContain(NUMBER);

            // And the marker stands at /card/number in the start's full-state entry and the logged version 1 signal
            List<Entry> fullStates = entries.stream().filter(Entry::isFullState).toList();
            assertThat(fullStates).hasSize(2);
            Entry startState = fullStates.getFirst();
            assertThat(startState.fullState().getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED);
            Command v1 = commandByKey(scenario, id, "k-v1");
            assertThat(loggedPayloads(entries, v1.id())).isNotEmpty().allSatisfy(payload -> assertThat(
                            payload.getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED));

            // And at /payer/iban in the migrated baseline and the logged version 2 signal
            Entry migrated = fullStates.getLast();
            assertThat(migrated.fullState().getJsonObject("payer").getString("iban"))
                    .isEqualTo(ReplayAssert.REDACTED);
            Command v2 = commandByKey(scenario, id, "k-v2");
            assertThat(loggedPayloads(entries, v2.id())).isNotEmpty().allSatisfy(payload -> assertThat(
                            payload.getJsonObject("payer").getString("iban"))
                    .isEqualTo(ReplayAssert.REDACTED));

            // And the migration's own patch carries the redacted iban, which both versions' pointers cover
            Command migration = scenario.commandOf(id, "MIGRATION");
            assertThat(entries.stream()
                            .filter(e -> e.type().equals("DECISION") && migration.id() == idOf(e))
                            .map(Entry::patchOperations)
                            .filter(java.util.Objects::nonNull)
                            .map(JsonArray::encode))
                    .anyMatch(patch -> patch.contains(ReplayAssert.REDACTED));

            // And replay reproduces the stored state up to the migration with the source pointers
            ReplayAssert.reproducesAt(entries, sequenceBeforeMigration, beforeMigration, Set.of("/card/number"));

            // And from the migrated baseline to the end with the target pointers
            ReplayAssert.reproduces(entries, scenario.state(id), Set.of("/payer/iban"));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a type mismatch at a redacted pointer is rejected without echoing the value")
    void typeMismatchAtARedactedPointerIsRejectedWithoutEchoingTheValue() {
        String secret = "4111-1111-secret";
        try (DecisionScenario scenario = RedactingFixture.typedPayment(pool())) {
            // Given a bound redactor naming /card/number for payment and an instance waiting at cardUpdated, whose
            // payload type declares card.number as an integer
            WorkflowInstanceId id = scenario.start(null, DecisionScenario.PAYMENT, "typed-1");
            scenario.runToIdle(id);

            // When cardUpdated is recorded with a string at the number, and the instance runs to idle
            scenario.signal(null, id, "cardUpdated", RedactingFixture.card(secret), "k-typed");
            scenario.runToIdle(id);

            // Then the signal is rejected
            Command signal = commandByKey(scenario, id, "k-typed");
            assertThat(signal.state()).isEqualTo("REJECTED");

            // And no history row and no stored error holds the value
            assertThat(scenario.historyText(id)).doesNotContain(secret);
            assertThat(scenario.errorText(id)).doesNotContain(secret);
            assertThat(signal.lastError()).doesNotContain(secret);

            // And the rejection reason starts with the deterministic-failure reason code
            List<Entry> entries = entries(scenario, id);
            Entry rejection = only(entries, e -> e.type().equals("COMMAND_REJECTED") && signal.id() == idOf(e));
            assertThat(rejection.payload().getString("reason")).startsWith("DETERMINISTIC_FAILURE");
            assertThat(signal.lastError()).startsWith("DETERMINISTIC_FAILURE");

            // And the logged payload holds the marker at /card/number
            assertThat(loggedPayloads(entries, signal.id())).isNotEmpty().allSatisfy(payload -> assertThat(
                            payload.getJsonObject("card").getString("number"))
                    .isEqualTo(ReplayAssert.REDACTED));
        }
    }

    // --- Observation ---

    /** The log of the instance. */
    private static List<Entry> entries(DecisionScenario scenario, WorkflowInstanceId id) {
        return scenario.entries(id);
    }

    private static String types(List<Entry> entries) {
        return entries.stream().map(Entry::type).toList().toString();
    }

    /** The only entry that matches. */
    private static Entry only(List<Entry> entries, java.util.function.Predicate<Entry> matching) {
        List<Entry> found = entries.stream().filter(matching).toList();
        assertThat(found).as("the matching entries of %s", types(entries)).hasSize(1);
        return found.getFirst();
    }

    private static long idOf(Entry entry) {
        return entry.commandId() == null ? -1L : entry.commandId();
    }

    /** The command recorded under the dedup key. */
    private static Command commandByKey(DecisionScenario scenario, WorkflowInstanceId id, String key) {
        return scenario.commands(id).stream()
                .filter(command -> key.equals(command.dedupKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no command with key " + key + ": " + scenario.commands(id)));
    }

    /** The entries written after one sequence and up to another. */
    private static List<Entry> segment(List<Entry> entries, long afterSequence, long throughSequence) {
        return entries.stream()
                .filter(e -> e.sequence() > afterSequence && e.sequence() <= throughSequence)
                .toList();
    }

    /** Every patch operation of the DECISION entries. */
    private static List<JsonObject> operationsOf(List<Entry> entries) {
        List<JsonObject> operations = new ArrayList<>();
        for (Entry entry : entries) {
            JsonArray patch = entry.type().equals("DECISION") ? entry.patchOperations() : null;
            for (int i = 0; patch != null && i < patch.size(); i++) {
                operations.add(patch.getJsonObject(i));
            }
        }
        return operations;
    }

    private static Entry lastBaseline(List<Entry> entries) {
        return entries.stream()
                .filter(e -> e.type().equals("STATE_BASELINE"))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no STATE_BASELINE in " + types(entries)));
    }

    /**
     * Asserts that the segment has a DECISION entry without a patch and that each such entry is followed at once by a
     * STATE_BASELINE entry.
     */
    private static void assertPatchlessDecisionsAreFollowedByABaseline(List<Entry> all, List<Entry> segment) {
        List<Entry> patchless = segment.stream()
                .filter(e -> e.type().equals("DECISION") && e.patch() == null)
                .toList();
        assertThat(patchless)
                .as("the DECISION entries without a patch in %s", types(segment))
                .isNotEmpty();
        for (Entry decision : patchless) {
            Entry next = all.stream()
                    .filter(e -> e.sequence() == decision.sequence() + 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("nothing follows entry " + decision.sequence()));
            assertThat(next.type())
                    .as("the entry after the patchless decision %s", decision.sequence())
                    .isEqualTo("STATE_BASELINE");
        }
    }

    /** The command payloads that the entries of the command log, which are the {@code payload} objects of their payloads. */
    private static List<JsonObject> loggedPayloads(List<Entry> entries, long commandId) {
        return entries.stream()
                .filter(e -> idOf(e) == commandId)
                .map(e -> e.payload().getValue("payload"))
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .toList();
    }

    private static JsonObject edit(List<String> items, String text) {
        JsonObject edit = new JsonObject();
        if (items != null) {
            edit.put("items", new JsonArray(items));
        }
        if (text != null) {
            edit.put("text", text);
        }
        return edit;
    }

    // --- Redacting fixtures ---

    /** The redactor of the redaction proofs and the payloads that carry a card. */
    static final class RedactingFixture {

        private RedactingFixture() {
            // fixtures
        }

        /** A scenario with payment and order, and a redactor naming /card/number for payment only. */
        static DecisionScenario payment(DbPoolConfig db) {
            return DecisionScenario.payment(db, redactor(Set.of("/card/number"), Set.of()));
        }

        /** A scenario with payment v1 and v2, and a redactor naming a different pointer for each version. */
        static DecisionScenario paymentMigration(DbPoolConfig db) {
            return DecisionScenario.paymentMigration(db, redactor(Set.of("/card/number"), Set.of("/payer/iban")));
        }

        /** A scenario with payment whose card number is typed as an integer, and a redactor naming /card/number. */
        static DecisionScenario typedPayment(DbPoolConfig db) {
            return DecisionScenario.typedPayment(db, redactor(Set.of("/card/number"), Set.of()));
        }

        /**
         * A redactor that names the first set of pointers for version 1 of payment, the second for version 2 and
         * nothing for any other definition.
         */
        private static WorkflowHistoryRedactor redactor(Set<String> v1, Set<String> v2) {
            return key -> {
                if (!key.definitionId().equals(DecisionScenario.PAYMENT)) {
                    return Set.of();
                }
                return key.definitionVersion() == 2L ? v2 : v1;
            };
        }

        static JsonObject begin(String ref, String number) {
            return new JsonObject().put("ref", ref).put("card", new JsonObject().put("number", number));
        }

        static JsonObject card(String number) {
            return new JsonObject().put("card", new JsonObject().put("number", number));
        }

        static JsonObject payer(String iban) {
            return new JsonObject().put("payer", new JsonObject().put("iban", iban));
        }

        static JsonObject text(String text) {
            return new JsonObject().put("text", text);
        }
    }
}
