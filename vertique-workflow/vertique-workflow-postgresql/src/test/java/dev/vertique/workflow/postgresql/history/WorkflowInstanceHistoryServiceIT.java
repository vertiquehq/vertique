// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import static dev.vertique.workflow.postgresql.history.AuditRun.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.history.WorkflowInstanceHistoryService;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import dev.vertique.workflow.postgresql.executor.ActorScenario;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.HistoryActor;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the public history service over a real database: an instance's log read in order and in pages with the
 * actors, decisions, plan hashes and patches the executor wrote, the five-field form that {@code query} keeps, and the
 * open commands of an instance, a dead-lettered signal among them, with a last error that is a reason code and a
 * sanitized message.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class WorkflowInstanceHistoryServiceIT {

    // The container starts empty; the three migration sets all begin at V1, so each gets a history table of its own.
    static final PostgresContainer db =
            new PostgresContainer().withDatabaseName("workflow_instance_history_service_it");

    /** A failure text of the kind an exception carries: class name, stack frame, SQL text and a line break. */
    private static final String HOSTILE_FAILURE = "java.lang.IllegalStateException: boom\n\tat"
            + " dev.vertique.Example.run(Example.java:42)\nSELECT secret FROM vault";

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    private static void migrate(String location, String historyTable, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(location)
                .table(historyTable);
        if (baseline) {
            flyway.baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    // --- Tests ---

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the audit of a user and of a relayed service reads in order, in pages and in five fields from query")
    void auditOfAUserAndARelayedServiceReadsInOrder(Vertx vertx) {
        try (AuditRun run = AuditRun.start(vertx, db)) {
            // Given user u-1 who completed a human task, and service relay-svc that later signalled the instance for
            // user u-2 through the outbox, with the instance run to idle after each
            WorkflowInstanceId id = run.startAtTask("audit");
            run.completeTaskAs(AuditRun.TASK_USER, id);
            run.relaySignalAs(AuditRun.SIGNALLING_USER, id, "signal-key");
            long start = run.commandIdOf(id, "START");
            long completion = run.commandIdOf(id, "COMPLETION");
            long signal = run.commandIdOf(id, "SIGNAL");
            WorkflowInstanceHistoryService history = run.graph().historyService();

            // When the whole log is read, then in pages of three by afterSequence, then through query
            List<WorkflowHistoryEntry> all = await(history.entries(id, 0, 500));
            List<WorkflowHistoryEntry> paged = readInPagesOf(3, history, id);
            WorkflowView view = await(run.graph().operations().query(id));

            // Then the entries are in ascending, gap-free sequence
            assertThat(all)
                    .extracting(WorkflowHistoryEntry::sequence)
                    .isSorted()
                    .doesNotHaveDuplicates();
            assertThat(all.getFirst().sequence()).isEqualTo(1L);
            assertThat(all.getLast().sequence()).isEqualTo(all.size());

            // And the commands and the decisions that followed them carry their actors in order
            HistoryAssert.of(all)
                    .ofTypes(WorkflowEntryType.COMMAND_RECEIVED, WorkflowEntryType.DECISION)
                    .hasSteps(
                            step(WorkflowEntryType.COMMAND_RECEIVED, null, "NONE ABSENT", null),
                            step(WorkflowEntryType.DECISION, start, "NONE ABSENT", "wait-task"),
                            step(WorkflowEntryType.COMMAND_RECEIVED, null, "USER/u-1 VERIFIED", null),
                            step(WorkflowEntryType.DECISION, completion, "USER/u-1 VERIFIED", "task-decided"),
                            step(WorkflowEntryType.DECISION, completion, "USER/u-1 VERIFIED", "wait-signal"),
                            step(WorkflowEntryType.COMMAND_RECEIVED, null, "SERVICE/relay-svc for u-2 VERIFIED", null),
                            step(
                                    WorkflowEntryType.DECISION,
                                    signal,
                                    "SERVICE/relay-svc for u-2 VERIFIED",
                                    "signal-applied"),
                            step(
                                    WorkflowEntryType.DECISION,
                                    signal,
                                    "SERVICE/relay-svc for u-2 VERIFIED",
                                    "completed"));

            // And the service entry names the service as the actor, acting for the user who wrote the outbox row
            assertThat(all)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.SIGNAL_RECEIVED)
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.actor())
                                .isEqualTo(new HistoryActor(HistoryActor.Kind.SERVICE, "relay-svc", null));
                        assertThat(entry.onBehalfOf()).isEqualTo("u-2");
                        assertThat(entry.attributionStatus()).isEqualTo("VERIFIED");
                    });

            // And every decision names its definition, step, one plan hash, an executor and the entry version two
            List<WorkflowHistoryEntry> decisions = all.stream()
                    .filter(entry -> entry.entryType() == WorkflowEntryType.DECISION)
                    .toList();
            assertThat(decisions).hasSize(5).allSatisfy(decision -> {
                assertThat(decision.entryVersion()).isEqualTo(2);
                assertThat(decision.definitionKey())
                        .isEqualTo(new DefinitionKey(WorkflowScopes.DEFAULT, HistoryGraph.DEFINITION_ID, 1L));
                assertThat(decision.planHash()).isNotBlank();
                assertThat(decision.executorId()).isNotBlank();
                assertThat(decision.stepId()).isNotBlank();
                assertThat(decision.stateVersionBefore()).isNotNull();
                assertThat(decision.stateVersionAfter()).isGreaterThanOrEqualTo(decision.stateVersionBefore());
            });
            assertThat(decisions)
                    .extracting(WorkflowHistoryEntry::planHash)
                    .containsOnly(decisions.getFirst().planHash());

            // And the state change of a decision is its patch, and a decision that changes nothing has an empty one
            assertThat(patchOf(decisions, "task-decided")).isEqualTo(new JsonArray().add(replace("/reviewed")));
            assertThat(patchOf(decisions, "signal-applied")).isEqualTo(new JsonArray().add(replace("/done")));
            assertThat(patchOf(decisions, "wait-task")).isEmpty();
            assertThat(patchOf(decisions, "completed")).isEmpty();

            // And the first segment logged the full state before its first decision
            assertThat(all)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.STATE_BASELINE)
                    .singleElement()
                    .satisfies(baseline -> {
                        assertThat(baseline.sequence())
                                .isLessThan(decisions.getFirst().sequence());
                        assertThat(baseline.entryVersion()).isEqualTo(2);
                        assertThat(baseline.planHash())
                                .isEqualTo(decisions.getFirst().planHash());
                    });

            // And the other entries are the ones of entry version one, which carry the attribution but no decision data
            assertThat(all)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.COMMAND_RECEIVED)
                    .isNotEmpty()
                    .allSatisfy(entry -> {
                        assertThat(entry.entryVersion()).isEqualTo(1);
                        assertThat(entry.attributionStatus()).isNotNull();
                        assertThat(entry.executorId()).isNull();
                        assertThat(entry.planHash()).isNull();
                        assertThat(entry.patchJson()).isNull();
                    });

            // And a start recorded with no security context is logged as no actor, not as a missing one
            assertThat(all.getFirst().actor()).isEqualTo(new HistoryActor(HistoryActor.Kind.NONE, null, null));

            // And the pages concatenate to the same list, with every entry carrying its attribution status
            assertThat(paged).isEqualTo(all);
            assertThat(paged)
                    .allSatisfy(entry -> assertThat(entry.attributionStatus()).isNotNull());

            // And afterSequence starts after the given entry and the limit bounds the page, at both ends of its range
            assertThat(await(history.entries(id, 3, 2)))
                    .extracting(WorkflowHistoryEntry::sequence)
                    .containsExactly(4L, 5L);
            assertThat(await(history.entries(id, 0, 1))).containsExactly(all.getFirst());
            assertThat(await(history.entries(id, 0, 500))).hasSameSizeAs(all);
            assertThat(await(history.entries(id, all.getLast().sequence(), 10))).isEmpty();

            // And query lists the same entries in the same order with the five original fields, the stored entry
            // version, and nothing else that is new
            List<WorkflowHistoryEntry> recent = view.recentHistory();
            assertThat(recent).hasSameSizeAs(all);
            for (int i = 0; i < all.size(); i++) {
                WorkflowHistoryEntry enriched = all.get(i);
                WorkflowHistoryEntry fiveFields = recent.get(i);
                assertThat(fiveFields.instanceId()).as("entry %d", i).isEqualTo(enriched.instanceId());
                assertThat(fiveFields.sequence()).as("entry %d", i).isEqualTo(enriched.sequence());
                assertThat(fiveFields.entryType()).as("entry %d", i).isEqualTo(enriched.entryType());
                assertThat(fiveFields.payloadJson()).as("entry %d", i).isEqualTo(enriched.payloadJson());
                assertThat(fiveFields.recordedAt()).as("entry %d", i).isEqualTo(enriched.recordedAt());
                assertThat(fiveFields.entryVersion()).as("entry %d", i).isEqualTo(enriched.entryVersion());
                assertNothingElseNew(fiveFields);
            }

            // And the entry version is the stored one: two for the decision entries, one for the others
            assertThat(recent)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.DECISION)
                    .extracting(WorkflowHistoryEntry::entryVersion)
                    .containsOnly(2);
            assertThat(recent)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.COMMAND_RECEIVED)
                    .extracting(WorkflowHistoryEntry::entryVersion)
                    .containsOnly(1);

            // And an entry stored before the entry version existed reads as version one with nothing else new, from
            // the service and from query, while a decision entry beside it still reads as version two
            WorkflowInstanceId older = run.startAtTask("older");
            long storedBefore = await(run.pool()
                            .preparedQuery(
                                    "INSERT INTO workflow_history (workflow_id, sequence, entry_type, payload_json)"
                                            + " SELECT $1, COALESCE(MAX(sequence), 0) + 1, 'SIGNAL_RECEIVED', '{}'::jsonb"
                                            + " FROM workflow_history WHERE workflow_id = $1 RETURNING sequence")
                            .execute(Tuple.of(older.value())))
                    .iterator()
                    .next()
                    .getLong("sequence");
            WorkflowHistoryEntry fromService = entryAt(await(history.entries(older, 0, 500)), storedBefore);
            WorkflowHistoryEntry fromQuery =
                    entryAt(await(run.graph().operations().query(older)).recentHistory(), storedBefore);
            for (WorkflowHistoryEntry stored : List.of(fromService, fromQuery)) {
                assertThat(stored.entryVersion()).isEqualTo(1);
                assertThat(stored.payloadJson()).isEqualTo("{}");
                assertNothingElseNew(stored);
            }
            assertThat(await(run.graph().operations().query(older)).recentHistory())
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.DECISION)
                    .isNotEmpty()
                    .extracting(WorkflowHistoryEntry::entryVersion)
                    .containsOnly(2);

            // And a limit outside one to five hundred is refused before any query runs: on a service whose pool is
            // closed, the refusal is the argument error, not a failed connection
            WorkflowInstanceHistoryService unreachable = unreachableService(vertx);
            assertThat(refusalOf(() -> unreachable.entries(id, 0, 0))).isInstanceOf(IllegalArgumentException.class);
            assertThat(refusalOf(() -> unreachable.entries(id, 0, 501))).isInstanceOf(IllegalArgumentException.class);
            assertThat(refusalOf(() -> unreachable.entries(id, 0, -1))).isInstanceOf(IllegalArgumentException.class);
            assertThat(refusalOf(() -> history.entries(id, 0, 0))).isInstanceOf(IllegalArgumentException.class);
            assertThat(refusalOf(() -> history.entries(id, 0, 501))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a dead-lettered signal is listed with a sanitized last error and a retry re-offers it")
    void deadLetteredSignalIsListedAndRetryReOffersIt(Vertx vertx) {
        PgConnectOptions options = HistoryGraph.options(db);
        Pool reading = HistoryGraph.pool(vertx, options);
        try (ActorScenario scenario = ActorScenario.deadLetteringWait(db.toPoolConfig())) {
            // Given a service that reads the log of the database, a signal recorded for a waiting instance, and
            // every segment of the instance crashing with a failure whose text names a class, a stack frame and SQL,
            // until the signal is dead-lettered after its one attempt
            WorkflowInstanceHistoryService history =
                    HistoryGraph.reading(vertx, options, reading).historyService();
            WorkflowInstanceId id = scenario.start(null, "dead-letter", Map.of());
            scenario.runToIdle(id);
            scenario.signal(null, id, "approve", "k-approve");
            scenario.deadLetter(id, "k-approve", "k-none", HOSTILE_FAILURE);
            long signalCommand = commandIdOf(scenario, id, "SIGNAL");

            // When the open commands and the log are read
            List<PendingCommand> open = await(history.openCommands(id));
            List<WorkflowHistoryEntry> before = await(history.entries(id, 0, 500));

            // Then the dead-lettered signal is listed as dead, with its attempts, among the pending timer, in order
            assertThat(open).extracting(PendingCommand::kind).containsExactly("TIMER", "SIGNAL");
            assertThat(open).extracting(PendingCommand::sequence).isSorted();
            assertThat(open).extracting(PendingCommand::instanceId).containsOnly(id);
            PendingCommand dead = open.get(1);
            assertThat(dead.state()).isEqualTo("DEAD");
            assertThat(dead.attempts()).isEqualTo(1);
            assertThat(dead.availableAt()).isNotNull();
            assertThat(open.getFirst().state()).isEqualTo("PENDING");
            assertThat(open.getFirst().lastError()).isNull();

            // And its last error is the stored one: a reason code and a message with no exception class, stack frame,
            // SQL text, control character or text of the failure, within the bound
            String lastError = dead.lastError();
            assertThat(lastError).isEqualTo(storedLastError(scenario, signalCommand));
            assertThat(lastError).matches("[A-Z][A-Z_]*: \\S.*").hasSizeLessThanOrEqualTo(4000);
            assertThat(lastError).doesNotContainPattern("\\p{Cntrl}");
            assertThat(lastError)
                    .doesNotContain("Exception")
                    .doesNotContain("java.lang")
                    .doesNotContain("dev.vertique.Example")
                    .doesNotContain(".java:")
                    .doesNotContain("\tat ")
                    .doesNotContain("boom")
                    .doesNotContain("SELECT")
                    .doesNotContain("vault");

            // And the log shows the dead letter, with no text of the failure in it
            assertThat(before)
                    .filteredOn(entry -> entry.entryType() == WorkflowEntryType.COMMAND_DEAD_LETTERED)
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.commandId()).isEqualTo(signalCommand);
                        assertThat(entry.payloadJson()).doesNotContain("boom").doesNotContain("vault");
                    });

            // When a retry is recorded and the instance runs to idle
            scenario.retry(null, id);
            scenario.runToIdle(id);
            List<PendingCommand> openAfter = await(history.openCommands(id));
            List<WorkflowHistoryEntry> after = await(history.entries(id, 0, 500));

            // Then no command is open any more
            assertThat(openAfter).isEmpty();

            // And the log shows, in order, the dead letter, the retry that re-offered the signal and the signal applied
            int deadLettered = indexOf(after, WorkflowEntryType.COMMAND_DEAD_LETTERED, null);
            int reOffered = indexOf(after, WorkflowEntryType.COMMAND_RECEIVED, "RETRY");
            int applied = indexOf(after, WorkflowEntryType.SIGNAL_APPLIED, null);
            assertThat(deadLettered).isNotNegative().isLessThan(reOffered);
            assertThat(reOffered).isLessThan(applied);
            assertThat(new JsonObject(after.get(applied).payloadJson()).getLong("commandId"))
                    .isEqualTo(signalCommand);

            // And the entries the first read returned are the start of the second, unchanged
            assertThat(after.subList(0, before.size())).isEqualTo(before);
        } finally {
            await(reading.close());
        }
    }

    // --- Reading ---

    /** The pages of the given size, read one after the other by the sequence of the last entry of the page before. */
    private static List<WorkflowHistoryEntry> readInPagesOf(
            int size, WorkflowInstanceHistoryService history, WorkflowInstanceId id) {
        List<WorkflowHistoryEntry> entries = new ArrayList<>();
        long after = 0;
        while (true) {
            List<WorkflowHistoryEntry> page = await(history.entries(id, after, size));
            assertThat(page).hasSizeLessThanOrEqualTo(size);
            if (page.isEmpty()) {
                return entries;
            }
            entries.addAll(page);
            after = page.getLast().sequence();
        }
    }

    /** A service that is built over a pool that is already closed. */
    private static WorkflowInstanceHistoryService unreachableService(Vertx vertx) {
        PgConnectOptions options = HistoryGraph.options(db);
        Pool closed = HistoryGraph.pool(vertx, options);
        await(closed.close());
        return HistoryGraph.reading(vertx, options, closed).historyService();
    }

    /** The failure of the call, whether it throws or returns a failed future. */
    private static Throwable refusalOf(Supplier<Future<?>> call) {
        Future<?> result;
        try {
            result = call.get();
        } catch (RuntimeException thrown) {
            return thrown;
        }
        assertThat(result.failed()).as("the call is refused").isTrue();
        return result.cause();
    }

    private static long commandIdOf(ActorScenario scenario, WorkflowInstanceId id, String kind) {
        return await(scenario.observer()
                        .preparedQuery("SELECT id FROM workflow_commands WHERE workflow_id = $1 AND kind = $2")
                        .execute(Tuple.of(id.value(), kind)))
                .iterator()
                .next()
                .getLong("id");
    }

    private static String storedLastError(ActorScenario scenario, long commandId) {
        return await(scenario.observer()
                        .preparedQuery("SELECT last_error FROM workflow_commands WHERE id = $1")
                        .execute(Tuple.of(commandId)))
                .iterator()
                .next()
                .getString("last_error");
    }

    private static int indexOf(List<WorkflowHistoryEntry> entries, WorkflowEntryType type, String commandKind) {
        for (int i = 0; i < entries.size(); i++) {
            WorkflowHistoryEntry entry = entries.get(i);
            if (entry.entryType() == type
                    && (commandKind == null
                            || commandKind.equals(new JsonObject(entry.payloadJson()).getString("kind")))) {
                return i;
            }
        }
        throw new AssertionError("no " + type + " entry of kind " + commandKind + " in " + entries);
    }

    private static WorkflowHistoryEntry entryAt(List<WorkflowHistoryEntry> entries, long sequence) {
        return entries.stream()
                .filter(entry -> entry.sequence() == sequence)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + sequence + " in " + entries));
    }

    private static JsonArray patchOf(List<WorkflowHistoryEntry> decisions, String outcome) {
        return decisions.stream()
                .filter(decision -> outcome.equals(decision.outcome()))
                .findFirst()
                .map(decision -> new JsonArray(decision.patchJson()))
                .orElseThrow(() -> new AssertionError("no decision with the outcome " + outcome));
    }

    private static JsonObject replace(String path) {
        return new JsonObject().put("op", "replace").put("path", path).put("value", true);
    }

    /** Every new component except the entry version is null. */
    private static void assertNothingElseNew(WorkflowHistoryEntry entry) {
        assertThat(entry.actor()).as("actor of %d", entry.sequence()).isNull();
        assertThat(entry.onBehalfOf()).as("onBehalfOf of %d", entry.sequence()).isNull();
        assertThat(entry.attributionStatus())
                .as("attributionStatus of %d", entry.sequence())
                .isNull();
        assertThat(entry.executorId()).as("executorId of %d", entry.sequence()).isNull();
        assertThat(entry.commandId()).as("commandId of %d", entry.sequence()).isNull();
        assertThat(entry.stepId()).as("stepId of %d", entry.sequence()).isNull();
        assertThat(entry.outcome()).as("outcome of %d", entry.sequence()).isNull();
        assertThat(entry.definitionKey())
                .as("definitionKey of %d", entry.sequence())
                .isNull();
        assertThat(entry.planHash()).as("planHash of %d", entry.sequence()).isNull();
        assertThat(entry.stateVersionBefore())
                .as("stateVersionBefore of %d", entry.sequence())
                .isNull();
        assertThat(entry.stateVersionAfter())
                .as("stateVersionAfter of %d", entry.sequence())
                .isNull();
        assertThat(entry.patchJson()).as("patchJson of %d", entry.sequence()).isNull();
    }

    private static HistoryAssert.Step step(WorkflowEntryType type, Long command, String actor, String outcome) {
        return new HistoryAssert.Step(type, command, actor, outcome);
    }

    /** Compares the (type, command, actor, outcome) tuples of the entries of the given types. */
    static final class HistoryAssert {
        private final List<Step> steps;

        private HistoryAssert(List<Step> steps) {
            this.steps = steps;
        }

        /** The type of an entry, the command a decision belongs to, its actor as text and its outcome. */
        record Step(WorkflowEntryType type, Long command, String actor, String outcome) {}

        static HistoryAssert of(List<WorkflowHistoryEntry> entries) {
            return new HistoryAssert(entries.stream().map(HistoryAssert::stepOf).toList());
        }

        HistoryAssert ofTypes(WorkflowEntryType... types) {
            Set<WorkflowEntryType> wanted = Set.of(types);
            return new HistoryAssert(
                    steps.stream().filter(step -> wanted.contains(step.type())).toList());
        }

        void hasSteps(Step... expected) {
            org.assertj.core.api.Assertions.assertThat(steps).containsExactly(expected);
        }

        private static Step stepOf(WorkflowHistoryEntry entry) {
            HistoryActor actor = entry.actor();
            String described = actor == null
                    ? "no actor"
                    : actor.kind()
                            + (actor.id() == null ? "" : "/" + actor.id())
                            + (entry.onBehalfOf() == null ? "" : " for " + entry.onBehalfOf())
                            + " "
                            + entry.attributionStatus();
            return new Step(entry.entryType(), entry.commandId(), described, entry.outcome());
        }
    }
}
