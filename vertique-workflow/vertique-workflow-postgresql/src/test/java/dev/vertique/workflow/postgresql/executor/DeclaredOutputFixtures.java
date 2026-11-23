// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.State;
import dev.vertique.workflow.types.WorkflowType;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Definitions, output types and row readers shared by the proofs of a declared workflow output.
 *
 * <p>Every definition here reads the final state of the signal fixture and maps it to {@link Receipt}, so a proof can
 * decide, through its {@link MapperSwitch}, whether the mapper succeeds or fails and how.
 */
final class DeclaredOutputFixtures {

    private DeclaredOutputFixtures() {}

    /** The output of the fixture definitions; {@code extra} carries a payload the mapper can make arbitrarily large. */
    record Receipt(String ref, int applied, @Nullable Object extra) {}

    /** Another output type, to ask for the wrong class. */
    record Invoice(String ref) {}

    /** An output type with a logical name, to prove the type match across names. */
    @WorkflowType("declared.output.AnnotatedReceipt")
    record AnnotatedReceipt(String ref) {}

    /** A marker that appears in every value the proofs must not find in a stored error or an exception message. */
    static final String LEAK_MARKER = "leak-marker";

    /** What the mapper of a definition does for the next completion. */
    enum Mode {
        GOOD,
        THROW,
        NULL,
        OVERSIZED,
        TOO_DEEP
    }

    /** The mapper of a definition, with behaviour a proof switches between completions. */
    static final class MapperSwitch {
        private volatile Mode mode;

        MapperSwitch(Mode initial) {
            this.mode = initial;
        }

        /** Makes the next completion succeed. */
        void fix() {
            mode = Mode.GOOD;
        }

        @Nullable
        Receipt apply(State state) {
            int applied = state.applied().size();
            return switch (mode) {
                case GOOD -> new Receipt(state.ref(), applied, null);
                case THROW -> throw new IllegalStateException("mapper echoed " + state.ref());
                case NULL -> null;
                case OVERSIZED -> new Receipt(state.ref(), applied, (LEAK_MARKER + "-").repeat(500));
                case TOO_DEEP -> {
                    Object nested = LEAK_MARKER;
                    for (int i = 0; i < 70; i++) {
                        nested = List.of(nested);
                    }
                    yield new Receipt(state.ref(), applied, nested);
                }
            };
        }
    }

    /** An output type with no wait in front of it: a start reaches the end. */
    static Definition instantComplete(String id, MapperSwitch mapper) {
        return new Definition(id, wf -> {
            wf.output(Receipt.class, mapper::apply);
            wf.initialStep("done");
            wf.complete("done");
        });
    }

    /** The same shape as {@link #instantComplete} with no declared output. */
    static Definition instantCompleteWithoutOutput(String id) {
        return new Definition(id, wf -> {
            wf.initialStep("done");
            wf.complete("done");
        });
    }

    /** Waits for the signal {@code approve}, then completes with an output. */
    static Definition signalThenComplete(String id, MapperSwitch mapper) {
        return new Definition(id, wf -> {
            wf.output(Receipt.class, mapper::apply);
            wf.initialStep("w");
            RecordedSignalIT.waitFor(wf, "w", "approve", "done");
            wf.complete("done");
        });
    }

    /** Waits on a timer of an hour, then completes with an output. */
    static Definition timerThenComplete(String id, MapperSwitch mapper) {
        return new Definition(id, wf -> {
            wf.output(Receipt.class, mapper::apply);
            wf.initialStep("t");
            wf.timer("t", Duration.ofHours(1)).toStep("done");
            wf.complete("done");
        });
    }

    /** Waits for a human decision, then completes with an output. */
    static Definition taskThenComplete(String id, MapperSwitch mapper) {
        return new Definition(id, wf -> {
            wf.output(Receipt.class, mapper::apply);
            wf.initialStep("review");
            wf.task("review")
                    .assignToRole("compliance")
                    .decision("approve", RecordedCompletionIT.Decision.class)
                    .onDecision((state, decision) -> state)
                    .toStep("done")
                    .build();
            wf.complete("done");
        });
    }

    /** Completes at once with an output of a type that carries a logical name. */
    static Definition annotatedOutput(String id) {
        return new Definition(id, wf -> {
            wf.output(AnnotatedReceipt.class, state -> new AnnotatedReceipt(state.ref()));
            wf.initialStep("done");
            wf.complete("done");
        });
    }

    /** What the instance row holds of the declared output and of the ending. */
    record OutputRow(
            String status,
            @Nullable String errorType,
            @Nullable String errorMessage,
            @Nullable Object outputJson,
            @Nullable String outputTypeName) {}

    /** Reads the output columns, the status and the error of the instance row. */
    static OutputRow outputRowOf(SignalFixture f, WorkflowInstanceId id) {
        var row = await(f.fixture
                        .observer()
                        .preparedQuery("SELECT status, error_type, error_message, output_json, output_type_name"
                                + " FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next();
        return new OutputRow(
                row.getString("status"),
                row.getString("error_type"),
                row.getString("error_message"),
                row.getValue("output_json"),
                row.getString("output_type_name"));
    }

    /**
     * The stored output as a JSON object. The column holds the encoded JSON the way the state column does, so the
     * driver may return it as text or as a document; both are read as the one value they are.
     */
    static JsonObject outputObject(OutputRow row) {
        if (row.outputJson() == null) {
            throw new AssertionError("expected an output but the row holds none");
        }
        return new JsonObject(row.outputJson().toString());
    }

    /** The history entry types of the instance, in order, with the completion entries named. */
    static List<String> completedEntries(SignalFixture f, WorkflowInstanceId id) {
        List<String> completed = new ArrayList<>();
        for (String type : f.fixture.historyOf(id)) {
            if (type.equals("COMPLETED")) {
                completed.add(type);
            }
        }
        return completed;
    }
}
