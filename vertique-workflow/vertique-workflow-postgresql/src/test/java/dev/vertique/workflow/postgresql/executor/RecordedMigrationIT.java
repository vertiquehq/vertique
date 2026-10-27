// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.signal;
import static dev.vertique.workflow.postgresql.executor.RecordedSignalIT.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.ParkedSignalIT.LoopFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.History;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.State;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@code migrate} is a recorded command: an invalid migration is rejected with the instance unchanged on
 * its version, a migration that overruns the step cap is rejected with the step-limit reason, and a migration that this
 * node cannot run is deferred to a node that can.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
public class RecordedMigrationIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_recorded_migration_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String ID = "recorded-migration-order";
    private static final String SECRET = "zz-secret-marker";

    @Test
    @DisplayName("an invalid migration is rejected and the instance stays on its version")
    void invalidMigrationIsRejectedWithTheInstanceUnchanged() {
        Versioned v1 = versioned(1, true);
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), v1, List.of("paid", "note"))) {
            // Given instances A, B and C on version 1, waiting, versions 2 to 4 available, a handler to version 2
            // that throws, a handler to version 3 whose target state type is not the state type of version 3, and
            // a valid handler to version 4 that does not declare the note signal
            seed(f, versioned(2, true), List.of("paid", "note"));
            seed(f, versioned(3, true), List.of("paid", "note"));
            seed(f, versioned(4, false), List.of("paid"));
            Node node = f.node("engine")
                    .migrations(new DefaultWorkflowMigrationRegistry(
                            Set.<WorkflowMigrationHandler<?, ?>>of(
                                    handler(ID, 1, 2, State.class, State.class, (state, ctx) -> {
                                        throw new IllegalStateException(SECRET + " handler failed");
                                    }),
                                    handler(
                                            ID,
                                            1,
                                            3,
                                            State.class,
                                            Other.class,
                                            (state, ctx) -> MigrationResult.anchorAtInitial(new Other(state.ref()))),
                                    handler(
                                            ID,
                                            1,
                                            4,
                                            State.class,
                                            State.class,
                                            (state, ctx) -> MigrationResult.anchorAtInitial(
                                                    state.add("migrated", signal("v4"))))),
                            f.registry))
                    .build();
            WorkflowInstanceId a = f.waiting(node, "invalid-a");
            WorkflowInstanceId b = f.waiting(node, "invalid-b");
            WorkflowInstanceId c = f.waiting(node, "invalid-c");
            f.record(node, c, "note", signal("parked"), "note");
            f.idle(node, c);
            assertThat(f.signal(c, "note").state()).isEqualTo("PARKED");
            JsonObject beforeA = f.snapshot(a);
            JsonObject beforeB = f.snapshot(b);
            JsonObject pinA = pin(f, a);
            JsonObject pinB = pin(f, b);

            // When each migration is recorded and the instances run until idle
            migrate(f, node, a, 2);
            migrate(f, node, b, 3);
            migrate(f, node, c, 4);
            f.idle(node, a);
            f.idle(node, b);
            f.idle(node, c);

            // Then A: the throwing handler rejected the command, with a sanitized reason, and the instance is unchanged
            assertRejectedUnchanged(f, a, beforeA, pinA);
            // And B: the state type mismatch rejected the command and the instance is unchanged
            assertRejectedUnchanged(f, b, beforeB, pinB);

            // And C: the instance is on version 4 and the parked signal that version 4 does not declare is rejected
            CommandRow migration = migrationOf(f, c);
            assertThat(migration.state()).isEqualTo("COMPLETE");
            assertThat(pin(f, c).getLong("definition_version")).isEqualTo(4L);
            assertThat(f.snapshot(c).getString("status")).isEqualTo("WAITING");
            assertThat(f.snapshot(c).getString("current_step_id")).isEqualTo("paid");
            assertThat(String.valueOf(f.snapshot(c).getValue("state_json"))).contains("migrated:v4");
            CommandRow note = f.signal(c, "note");
            assertThat(note.state()).isEqualTo("REJECTED");
            assertThat(f.entry(c, "COMMAND_REJECTED", note.id())).isNotNull();
            assertThat(f.openSignals(c)).isZero();
            assertThat(f.fixture.historyOf(c)).contains("WORKFLOW_MIGRATED");
        }
    }

    @Test
    @DisplayName("a migration whose target class is missing is deferred and another executor migrates the instance")
    void migrationDefersWhenTheTargetIsNotRunnableHere() {
        Versioned v1 = versioned(1, false);
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), v1, List.of("paid"))) {
            // Given executor B that holds versions 1 and 2, executor A that holds only version 1, and an instance
            // on version 1 waiting
            Versioned v2 = versioned(2, false);
            seed(f, v2, List.of("paid"));
            WorkflowMigrationHandler<State, State> handler = handler(
                    ID,
                    1,
                    2,
                    State.class,
                    State.class,
                    (state, ctx) -> MigrationResult.anchorAtInitial(state.add("migrated", signal("v2"))));
            Node b = f.node("B")
                    .migrations(new DefaultWorkflowMigrationRegistry(Set.of(handler), f.registry))
                    .build();
            DefaultWorkflowRegistry onlyV1 = new DefaultWorkflowRegistry();
            onlyV1.register(v1);
            Node a = f.node("A")
                    .withRegistry(onlyV1)
                    .migrations(new DefaultWorkflowMigrationRegistry(Set.of(handler), onlyV1))
                    .build();
            WorkflowInstanceId id = f.waiting(b, "deferred");

            // When the migration to version 2 is recorded and A runs alone for one backoff
            migrate(f, b, id, 2);
            CommandRow recorded = migrationOf(f, id);
            assertThat(recorded.state()).isEqualTo("PENDING");
            a.startExecutor();
            try {
                awaitCondition("A to defer the migration", IDLE, () -> migrationOf(f, id)
                        .state()
                        .equals("DEFERRED"));
            } finally {
                a.stopExecutor();
            }

            // Then A deferred it without spending an attempt and the instance is untouched
            CommandRow deferred = migrationOf(f, id);
            assertThat(deferred.state()).isEqualTo("DEFERRED");
            assertThat(deferred.deferralCount()).isGreaterThanOrEqualTo(1);
            assertThat(deferred.attempts()).isZero();
            assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
            assertThat(pin(f, id).getLong("definition_version")).isEqualTo(1L);

            // When the backoff has elapsed and B starts
            f.fixture.fastForward(deferred.id());
            b.startExecutor();
            try {
                b.awaitIdle(id);
            } finally {
                b.stopExecutor();
            }

            // Then B migrated the instance to version 2
            CommandRow migrated = migrationOf(f, id);
            assertThat(migrated.state()).isEqualTo("COMPLETE");
            assertThat(migrated.attempts()).isEqualTo(1);
            assertThat(migrated.deferralCount()).isGreaterThanOrEqualTo(1);
            assertThat(pin(f, id).getLong("definition_version")).isEqualTo(2L);
            assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
            assertThat(String.valueOf(f.snapshot(id).getValue("state_json"))).contains("migrated:v2");
            assertThat(f.fixture.historyOf(id)).contains("WORKFLOW_MIGRATED");
        }
    }

    @Test
    @DisplayName("a migration that overruns the step cap is rejected with STEP_LIMIT and the instance is unchanged")
    void migrationOverrunningTheStepCapIsRejectedWithStepLimit() {
        Versioned v1 = versioned(1, false);
        try (SignalFixture f = new SignalFixture(db.toPoolConfig(), v1, List.of("paid"))) {
            // Given a step cap of 50, an instance on version 1 waiting at paid, and a handler to version 2 whose
            // target enters a decision loop of 60 nodes
            Definition spinning = new Versioned(ID, 2, wf -> {
                wf.initialStep("spin-0");
                LoopFixture.pathOf(wf, "spin", 60, "paid", () -> {});
                waitFor(wf, "paid", "paid", "done");
                wf.complete("done");
            });
            seed(f, spinning, List.of("paid"));
            Node node = f.node("engine")
                    .maxStepsPerSegment(50)
                    .migrations(new DefaultWorkflowMigrationRegistry(
                            Set.of(handler(
                                    ID,
                                    1,
                                    2,
                                    State.class,
                                    State.class,
                                    (state, ctx) -> MigrationResult.anchorAtInitial(state))),
                            f.registry))
                    .build();
            WorkflowInstanceId id = f.waiting(node, "spinning");
            JsonObject before = f.snapshot(id);
            JsonObject pinBefore = pin(f, id);

            // When the migration to version 2 is recorded and the instance runs until idle
            migrate(f, node, id, 2);
            f.idle(node, id);

            // Then the migration is rejected with the step-limit reason
            CommandRow migration = migrationOf(f, id);
            assertThat(migration.state()).isEqualTo("REJECTED");
            assertThat(migration.lastError()).startsWith(ReasonCodes.STEP_LIMIT);
            assertThat(f.entry(id, "COMMAND_REJECTED", migration.id()).reason()).startsWith(ReasonCodes.STEP_LIMIT);

            // And the instance is unchanged on version 1, waiting, never failed
            assertThat(f.snapshot(id)).isEqualTo(before);
            assertThat(pin(f, id)).isEqualTo(pinBefore);
            assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
            assertThat(f.snapshot(id).getString("current_step_id")).isEqualTo("paid");
            assertThat(f.fixture.historyOf(id)).doesNotContain("FAILED", "WORKFLOW_MIGRATED");
        }
    }

    // --- Observation ---

    private static void assertRejectedUnchanged(
            SignalFixture f, WorkflowInstanceId id, JsonObject before, JsonObject pin) {
        CommandRow migration = migrationOf(f, id);
        assertThat(migration.state()).isEqualTo("REJECTED");
        assertThat(migration.attempts()).isEqualTo(1);
        assertThat(migration.lastError()).matches("(?s)^[A-Z][A-Z_]*: .+").doesNotContain(SECRET);
        History rejected = f.entry(id, "COMMAND_REJECTED", migration.id());
        assertThat(rejected.text()).doesNotContain(SECRET, "IllegalStateException");
        assertThat(f.snapshot(id)).isEqualTo(before);
        assertThat(pin(f, id)).isEqualTo(pin);
        assertThat(f.snapshot(id).getString("status")).isEqualTo("WAITING");
        assertThat(f.fixture.historyOf(id)).doesNotContain("FAILED", "WORKFLOW_MIGRATED");
    }

    private static CommandRow migrationOf(SignalFixture f, WorkflowInstanceId id) {
        List<CommandRow> migrations = f.fixture.commandsOf(id).stream()
                .filter(row -> row.kind().equals("MIGRATION"))
                .toList();
        if (migrations.size() != 1) {
            throw new AssertionError("expected one migration command of " + id + " but found "
                    + f.fixture.commandsOf(id).stream()
                            .map(row -> row.kind() + " " + row.state())
                            .toList());
        }
        return migrations.getFirst();
    }

    /** The definition version and plan hash the instance is pinned to. */
    private static JsonObject pin(SignalFixture f, WorkflowInstanceId id) {
        return f.row("SELECT definition_version, plan_hash FROM workflow_instances WHERE id=$1", id);
    }

    /** Records a migration to the version in the caller's own transaction and commits it. */
    private static void migrate(SignalFixture f, Node node, WorkflowInstanceId id, long version) {
        await(f.caller(() -> node.pool.withTransaction(tx -> node.engine.migrate(id, version, tx))));
    }

    // --- Fixture workflows and handlers ---

    /** Records a version of a fixture definition as available in the durable registry, without activating it. */
    private static void seed(SignalFixture f, Definition code, List<String> signalNames) {
        f.registry.register(code);
        RuntimeWorkflow runtime = f.registry.allRegistered().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(code.definitionId())
                        && candidate.plan().definitionVersion() == code.definitionVersion())
                .findFirst()
                .orElseThrow();
        Instant now = Instant.now();
        await(f.store.upsertCode(
                new WorkflowDefinitionRecord(
                        new DefinitionKey(WorkflowScopes.DEFAULT, code.definitionId(), code.definitionVersion()),
                        DefinitionKind.CODE,
                        runtime.contractName(),
                        runtime.plan().stateTypeName(),
                        runtime.plan().planHash(),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance("code", "test", null, null, null, now),
                        null,
                        now,
                        now,
                        Map.of(),
                        null,
                        new JsonObject()
                                .put("instance", new JsonArray(signalNames))
                                .put("branches", new JsonObject()),
                        null),
                false));
    }

    /**
     * A version of the migration fixture: waits for paid, and optionally for note after it.
     *
     * @param version  the version
     * @param withNote whether the version also declares the note signal
     */
    private static Versioned versioned(long version, boolean withNote) {
        return new Versioned(ID, version, wf -> {
            wf.initialStep("paid");
            waitFor(wf, "paid", "paid", withNote ? "note-wait" : "done");
            if (withNote) {
                waitFor(wf, "note-wait", "note", "done");
            }
            wf.complete("done");
        });
    }

    /** A state type that no version of the fixture has. */
    record Other(String ref) {}

    /** A fixture definition of a chosen version. */
    static final class Versioned extends Definition {
        private final long version;

        Versioned(String id, long version, Consumer<WorkflowBuilder<State>> body) {
            super(id, body);
            this.version = version;
        }

        @Override
        public long definitionVersion() {
            return version;
        }
    }

    private static <S, T> WorkflowMigrationHandler<S, T> handler(
            String definitionId,
            long from,
            long to,
            Class<S> sourceType,
            Class<T> targetType,
            BiFunction<S, MigrationContext, MigrationResult<T>> migration) {
        return new WorkflowMigrationHandler<>() {
            @Override
            public String definitionId() {
                return definitionId;
            }

            @Override
            public long fromDefinitionVersion() {
                return from;
            }

            @Override
            public long targetDefinitionVersion() {
                return to;
            }

            @Override
            public Class<S> sourceStateType() {
                return sourceType;
            }

            @Override
            public Class<T> targetStateType() {
                return targetType;
            }

            @Override
            public MigrationResult<T> migrate(S sourceState, MigrationContext ctx) {
                return migration.apply(sourceState, ctx);
            }
        };
    }
}
