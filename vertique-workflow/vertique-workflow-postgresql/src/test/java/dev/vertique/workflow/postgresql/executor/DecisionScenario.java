// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.history.WorkflowHistoryRedactor;
import dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * A node over a migrated database with a few fixture workflows whose state changes are logged, and the reads that show
 * what the log holds: the history entries with their decision columns, the stored state, the commands and the log lines.
 *
 * <p>This type opens the package-private executor fixtures to the decision-log proofs, which live beside the engine
 * proofs. Everything it exposes is a public type of the module under test or a plain value.
 *
 * <p>The scenarios: {@link #routeDecision} (a decision node that picks a step), {@link #flow} (a signal, a task, a
 * fork with a join reducer and a migration), {@link #legacy} (two signals in a row), {@link #blob} (a list and a text
 * that can grow past the size bound), {@link #parked} (a signal that waits for its step and then fails),
 * {@link #payment}, {@link #paymentMigration} and {@link #typedPayment} (card numbers in the start payload, the state
 * and the signals, with a history redactor bound).
 */
public final class DecisionScenario implements AutoCloseable {

    public static final String ROUTE = "decision-route";
    public static final String FLOW = "decision-flow";
    public static final String LEGACY = "decision-legacy";
    public static final String BLOB = "decision-blob";
    public static final String PARKED = "decision-parked";
    public static final String PAYMENT = "payment";
    public static final String ORDER = "order";

    /**
     * One row of {@code workflow_history} with its decision columns.
     *
     * @param sequence       the position in the instance's log
     * @param type           the entry type
     * @param commandId      the command the entry belongs to ({@code command_id}, or the {@code commandId} of its
     *                       payload when the column is null)
     * @param stepId         {@code step_id}
     * @param outcome        {@code outcome}
     * @param scopeKey       {@code definition_scope_key}
     * @param definitionId   {@code definition_id}
     * @param definitionVersion {@code definition_version}
     * @param planHash       {@code plan_hash}
     * @param versionBefore  {@code state_version_before}
     * @param versionAfter   {@code state_version_after}
     * @param executorId     {@code executor_id}
     * @param patch          {@code patch_json} as read: a {@link JsonArray}, a {@link JsonObject} or null
     * @param payload        {@code payload_json}
     * @param entryVersion   {@code entry_version}
     * @param actorKind      {@code actor_kind}
     * @param actorId        {@code actor_id}
     * @param onBehalfOf     {@code on_behalf_of}
     * @param attribution    {@code attribution_status}
     * @param rowText        the whole row as JSON text, every column
     */
    public record Entry(
            long sequence,
            String type,
            @Nullable Long commandId,
            @Nullable String stepId,
            @Nullable String outcome,
            @Nullable String scopeKey,
            @Nullable String definitionId,
            @Nullable Long definitionVersion,
            @Nullable String planHash,
            @Nullable Long versionBefore,
            @Nullable Long versionAfter,
            @Nullable String executorId,
            @Nullable Object patch,
            JsonObject payload,
            int entryVersion,
            @Nullable String actorKind,
            @Nullable String actorId,
            @Nullable String onBehalfOf,
            @Nullable String attribution,
            String rowText) {

        /** The operations of the entry's patch, or null when it has none (a column that is null, or a full state). */
        public @Nullable JsonArray patchOperations() {
            return patch instanceof JsonArray operations ? operations : null;
        }

        /**
         * The full state a {@code STATE_BASELINE} entry holds: the {@code state} object of its payload, or, when the
         * payload has none, a patch column that holds an object. Null for any other entry.
         */
        public @Nullable JsonObject fullState() {
            if (!"STATE_BASELINE".equals(type)) {
                return null;
            }
            if (payload.getValue("state") instanceof JsonObject state) {
                return state;
            }
            return patch instanceof JsonObject state ? state : null;
        }

        /** Whether the entry holds the full state of the instance. */
        public boolean isFullState() {
            return fullState() != null;
        }
    }

    /**
     * One command of an instance.
     *
     * @param id        the command id
     * @param kind      the command kind
     * @param state     the command state
     * @param dedupKey  the dedup key of a signal
     * @param lastError {@code last_error}
     * @param claimedBy the executor that claimed it
     */
    public record Command(
            long id,
            String kind,
            String state,
            @Nullable String dedupKey,
            @Nullable String lastError,
            @Nullable String claimedBy) {}

    // --- Fixture payloads and states ---

    record Begin(String ref) {}

    record Pay(String value) {}

    record Review(String note) {}

    record Flow(String ref, List<String> applied) {
        Flow add(String entry) {
            List<String> next = new ArrayList<>(applied);
            next.add(entry);
            return new Flow(ref, next);
        }
    }

    record RouteState(String ref) {}

    record Edit(@Nullable List<String> items, @Nullable String text) {}

    record Blob(String ref, List<String> items, String text) {
        Blob edit(Edit edit) {
            List<String> next = new ArrayList<>(items);
            if (edit.items() != null) {
                next.addAll(edit.items());
            }
            return new Blob(ref, next, edit.text() == null ? text : edit.text());
        }
    }

    record Note(boolean explode) {}

    record Parked(String ref, List<String> applied, boolean explode) {}

    record Card(@Nullable String number) {}

    record PaymentBegin(String ref, @Nullable Card card) {}

    record Text(String text) {}

    record CardPayload(@Nullable Card card) {}

    record PaymentState(String ref, @Nullable Card card, String note, String blob) {}

    record Payer(@Nullable String iban) {}

    record PayerPayload(@Nullable Payer payer) {}

    /** The state of both versions of the migrating payment: version 1 holds a card, version 2 a payer. */
    record PayState(
            String ref, @Nullable Card card, @Nullable Payer payer) {}

    record CardInt(@Nullable Integer number) {}

    record CardUpdate(@Nullable CardInt card) {}

    record TypedState(String ref) {}

    interface RouteContract {
        @WorkflowSignal("noop")
        Future<Void> noop(WorkflowInstanceId id, String payload, @SignalDedupKey String key);
    }

    interface FlowContract {
        @WorkflowSignal("pay")
        Future<Void> pay(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);

        @WorkflowSignal("ship")
        Future<Void> ship(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);

        @WorkflowSignal("close")
        Future<Void> close(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);
    }

    interface LegacyContract {
        @WorkflowSignal("first")
        Future<Void> first(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);

        @WorkflowSignal("second")
        Future<Void> second(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);
    }

    interface BlobContract {
        @WorkflowSignal("append1")
        Future<Void> append1(WorkflowInstanceId id, Edit payload, @SignalDedupKey String key);

        @WorkflowSignal("grow")
        Future<Void> grow(WorkflowInstanceId id, Edit payload, @SignalDedupKey String key);

        @WorkflowSignal("shrink")
        Future<Void> shrink(WorkflowInstanceId id, Edit payload, @SignalDedupKey String key);

        @WorkflowSignal("append2")
        Future<Void> append2(WorkflowInstanceId id, Edit payload, @SignalDedupKey String key);
    }

    interface ParkedContract {
        @WorkflowSignal("paid")
        Future<Void> paid(WorkflowInstanceId id, Pay payload, @SignalDedupKey String key);

        @WorkflowSignal("note")
        Future<Void> note(WorkflowInstanceId id, Note payload, @SignalDedupKey String key);
    }

    interface PaymentContract {
        @WorkflowSignal("note")
        Future<Void> note(WorkflowInstanceId id, Text payload, @SignalDedupKey String key);

        @WorkflowSignal("bulk")
        Future<Void> bulk(WorkflowInstanceId id, Text payload, @SignalDedupKey String key);

        @WorkflowSignal("shrink")
        Future<Void> shrink(WorkflowInstanceId id, Text payload, @SignalDedupKey String key);

        @WorkflowSignal("cardCleared")
        Future<Void> cardCleared(WorkflowInstanceId id, CardPayload payload, @SignalDedupKey String key);

        @WorkflowSignal("tail")
        Future<Void> tail(WorkflowInstanceId id, Text payload, @SignalDedupKey String key);
    }

    interface PaymentMigrationContract {
        @WorkflowSignal("v1note")
        Future<Void> v1note(WorkflowInstanceId id, CardPayload payload, @SignalDedupKey String key);

        @WorkflowSignal("hold")
        Future<Void> hold(WorkflowInstanceId id, CardPayload payload, @SignalDedupKey String key);

        @WorkflowSignal("v2note")
        Future<Void> v2note(WorkflowInstanceId id, PayerPayload payload, @SignalDedupKey String key);
    }

    interface TypedContract {
        @WorkflowSignal("cardUpdated")
        Future<Void> cardUpdated(WorkflowInstanceId id, CardUpdate payload, @SignalDedupKey String key);
    }

    /** A code-first definition of a chosen version. */
    private static final class Def<S, C> implements WorkflowDefinition<S, C> {
        private final String id;
        private final long version;
        private final Class<C> contract;
        private final Class<S> stateType;
        private final Consumer<WorkflowBuilder<S>> body;

        Def(String id, long version, Class<C> contract, Class<S> stateType, Consumer<WorkflowBuilder<S>> body) {
            this.id = id;
            this.version = version;
            this.contract = contract;
            this.stateType = stateType;
            this.body = body;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<S> stateType() {
            return stateType;
        }

        @Override
        public String definitionId() {
            return id;
        }

        @Override
        public long definitionVersion() {
            return version;
        }

        @Override
        public void define(WorkflowBuilder<S> wf) {
            body.accept(wf);
        }
    }

    /** A definition version to seed, the signals it declares and whether it becomes the version new starts use. */
    private record Seed(Def<?, ?> definition, List<String> signalNames, boolean activate) {}

    // --- Scenarios ---

    /**
     * A scenario over a workflow whose decision node {@code route} picks the step {@code approve} without changing
     * state.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static DecisionScenario routeDecision(DbPoolConfig db) {
        return new DecisionScenario(db, List.of(new Seed(route(), List.of("noop"), true)), null, Set.of());
    }

    /**
     * A scenario over version 1 of a workflow that waits for a signal, a task decision and a signal, with a fork of
     * two branches and a join reducer between the task and the last signal, and over version 2, to which a handler
     * migrates an instance. Version 2 waits for one signal and completes.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static DecisionScenario flow(DbPoolConfig db) {
        return new DecisionScenario(
                db,
                List.of(new Seed(flowV1(), List.of("pay", "ship"), true), new Seed(flowV2(), List.of("close"), false)),
                null,
                Set.of(handler(
                        FLOW,
                        1,
                        2,
                        Flow.class,
                        Flow.class,
                        (state, ctx) -> MigrationResult.anchorAtInitial(state.add("migrated")))));
    }

    /**
     * A scenario over a workflow that waits for two signals in a row.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static DecisionScenario legacy(DbPoolConfig db) {
        return new DecisionScenario(
                db, List.of(new Seed(legacyFlow(), List.of("first", "second"), true)), null, Set.of());
    }

    /**
     * A scenario over a workflow with a list and a text in its state that four signals in a row edit.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static DecisionScenario blob(DbPoolConfig db) {
        return new DecisionScenario(
                db,
                List.of(new Seed(blobFlow(), List.of("append1", "grow", "shrink", "append2"), true)),
                null,
                Set.of());
    }

    /**
     * A scenario over a workflow that waits for {@code paid} and then for {@code note}, whose {@code note} signal
     * applies and then fails the step after it when it says so.
     *
     * @param db the migrated database
     * @return the scenario
     */
    public static DecisionScenario parked(DbPoolConfig db) {
        return new DecisionScenario(db, List.of(new Seed(parkedFlow(), List.of("paid", "note"), true)), null, Set.of());
    }

    /**
     * A scenario with the workflows {@code payment} and {@code order}, which have the same shape: a card in the start
     * payload and the state, and five signals in a row.
     *
     * @param db       the migrated database
     * @param redactor the history redactor to bind, or null for none
     * @return the scenario
     */
    public static DecisionScenario payment(DbPoolConfig db, @Nullable WorkflowHistoryRedactor redactor) {
        return new DecisionScenario(
                db,
                List.of(
                        new Seed(paymentFlow(PAYMENT), List.of("note", "bulk", "shrink", "cardCleared", "tail"), true),
                        new Seed(paymentFlow(ORDER), List.of("note", "bulk", "shrink", "cardCleared", "tail"), true)),
                redactor,
                Set.of());
    }

    /**
     * A scenario with version 1 of {@code payment}, which holds a card, and version 2, which holds a payer, and a
     * handler that moves the card number into the payer's iban.
     *
     * @param db       the migrated database
     * @param redactor the history redactor to bind, or null for none
     * @return the scenario
     */
    public static DecisionScenario paymentMigration(DbPoolConfig db, @Nullable WorkflowHistoryRedactor redactor) {
        return new DecisionScenario(
                db,
                List.of(
                        new Seed(paymentV1(), List.of("v1note", "hold"), true),
                        new Seed(paymentV2(), List.of("v2note"), false)),
                redactor,
                Set.of(handler(PAYMENT, 1, 2, PayState.class, PayState.class, (state, ctx) -> {
                    String number = state.card() == null ? null : state.card().number();
                    return MigrationResult.anchorAtInitial(new PayState(state.ref(), null, new Payer(number)));
                })));
    }

    /**
     * A scenario with {@code payment}, which waits for {@code cardUpdated} whose payload declares the card number as
     * an integer.
     *
     * @param db       the migrated database
     * @param redactor the history redactor to bind, or null for none
     * @return the scenario
     */
    public static DecisionScenario typedPayment(DbPoolConfig db, @Nullable WorkflowHistoryRedactor redactor) {
        return new DecisionScenario(
                db, List.of(new Seed(typedPayment(), List.of("cardUpdated"), true)), redactor, Set.of());
    }

    // --- Definitions ---

    private static Def<RouteState, RouteContract> route() {
        return new Def<>(ROUTE, 1, RouteContract.class, RouteState.class, wf -> {
            wf.init(Begin.class, begin -> new RouteState(begin.ref()));
            wf.initialStep("route");
            wf.decide("route", state -> "approve");
            wf.complete("approve");
        });
    }

    private static Def<Flow, FlowContract> flowV1() {
        return new Def<>(FLOW, 1, FlowContract.class, Flow.class, wf -> {
            wf.init(Begin.class, begin -> new Flow(begin.ref(), List.of()));
            wf.initialStep("pay");
            wf.waitForSignal("pay", "pay", Pay.class)
                    .onSignal((state, payload) -> state.add("pay:" + payload.value()))
                    .toStepOnSignal("review")
                    .build();
            wf.task("review")
                    .assignToRole("compliance")
                    .decision("approve", Review.class)
                    .onDecision((state, decision) -> state.add("approve:" + decision.note()))
                    .toStep("fork")
                    .build();
            wf.fork("fork")
                    .branch("a", "a-work", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .branch("b", "b-work", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                    .join("join");
            wf.decide("a-work", state -> "a-done");
            wf.complete("a-done");
            wf.decide("b-work", state -> "b-done");
            wf.complete("b-done");
            wf.join("join")
                    .allRequired((state, results) -> state.add("joined:" + results.size()))
                    .toStep("ship")
                    .onFailure("failed")
                    .endJoin();
            wf.waitForSignal("ship", "ship", Pay.class)
                    .onSignal((state, payload) -> state.add("ship:" + payload.value()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
            wf.complete("failed");
        });
    }

    private static Def<Flow, FlowContract> flowV2() {
        return new Def<>(FLOW, 2, FlowContract.class, Flow.class, wf -> {
            wf.init(Begin.class, begin -> new Flow(begin.ref(), List.of()));
            wf.initialStep("close");
            wf.waitForSignal("close", "close", Pay.class)
                    .onSignal((state, payload) -> state.add("close:" + payload.value()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<Flow, LegacyContract> legacyFlow() {
        return new Def<>(LEGACY, 1, LegacyContract.class, Flow.class, wf -> {
            wf.init(Begin.class, begin -> new Flow(begin.ref(), List.of()));
            wf.initialStep("first");
            wf.waitForSignal("first", "first", Pay.class)
                    .onSignal((state, payload) -> state.add("first:" + payload.value()))
                    .toStepOnSignal("second")
                    .build();
            wf.waitForSignal("second", "second", Pay.class)
                    .onSignal((state, payload) -> state.add("second:" + payload.value()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<Blob, BlobContract> blobFlow() {
        return new Def<>(BLOB, 1, BlobContract.class, Blob.class, wf -> {
            wf.init(Begin.class, begin -> new Blob(begin.ref(), List.of(), ""));
            wf.initialStep("append1");
            wf.waitForSignal("append1", "append1", Edit.class)
                    .onSignal(Blob::edit)
                    .toStepOnSignal("grow")
                    .build();
            wf.waitForSignal("grow", "grow", Edit.class)
                    .onSignal(Blob::edit)
                    .toStepOnSignal("shrink")
                    .build();
            wf.waitForSignal("shrink", "shrink", Edit.class)
                    .onSignal(Blob::edit)
                    .toStepOnSignal("append2")
                    .build();
            wf.waitForSignal("append2", "append2", Edit.class)
                    .onSignal(Blob::edit)
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<Parked, ParkedContract> parkedFlow() {
        return new Def<>(PARKED, 1, ParkedContract.class, Parked.class, wf -> {
            wf.init(Begin.class, begin -> new Parked(begin.ref(), List.of(), false));
            wf.initialStep("paid");
            wf.waitForSignal("paid", "paid", Pay.class)
                    .onSignal((state, payload) -> {
                        List<String> next = new ArrayList<>(state.applied());
                        next.add("paid:" + payload.value());
                        return new Parked(state.ref(), next, state.explode());
                    })
                    .toStepOnSignal("note-wait")
                    .build();
            wf.waitForSignal("note-wait", "note", Note.class)
                    .onSignal((state, payload) -> {
                        List<String> next = new ArrayList<>(state.applied());
                        next.add("note");
                        return new Parked(state.ref(), next, payload.explode());
                    })
                    .toStepOnSignal("after-note")
                    .build();
            wf.decide("after-note", state -> {
                if (state.explode()) {
                    throw new IllegalStateException("the step after the note failed");
                }
                return "done";
            });
            wf.complete("done");
        });
    }

    private static Def<PaymentState, PaymentContract> paymentFlow(String id) {
        return new Def<>(id, 1, PaymentContract.class, PaymentState.class, wf -> {
            wf.init(PaymentBegin.class, begin -> new PaymentState(begin.ref(), begin.card(), "", ""));
            wf.initialStep("note");
            wf.waitForSignal("note", "note", Text.class)
                    .onSignal((state, payload) ->
                            new PaymentState(state.ref(), state.card(), payload.text(), state.blob()))
                    .toStepOnSignal("bulk")
                    .build();
            wf.waitForSignal("bulk", "bulk", Text.class)
                    .onSignal((state, payload) ->
                            new PaymentState(state.ref(), state.card(), state.note(), payload.text()))
                    .toStepOnSignal("shrink")
                    .build();
            wf.waitForSignal("shrink", "shrink", Text.class)
                    .onSignal((state, payload) -> new PaymentState(state.ref(), state.card(), state.note(), ""))
                    .toStepOnSignal("clear")
                    .build();
            wf.waitForSignal("clear", "cardCleared", CardPayload.class)
                    .onSignal((state, payload) -> new PaymentState(state.ref(), null, state.note(), state.blob()))
                    .toStepOnSignal("tail")
                    .build();
            wf.waitForSignal("tail", "tail", Text.class)
                    .onSignal((state, payload) ->
                            new PaymentState(state.ref(), state.card(), payload.text(), state.blob()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<PayState, PaymentMigrationContract> paymentV1() {
        return new Def<>(PAYMENT, 1, PaymentMigrationContract.class, PayState.class, wf -> {
            wf.init(PaymentBegin.class, begin -> new PayState(begin.ref(), begin.card(), null));
            wf.initialStep("v1note");
            wf.waitForSignal("v1note", "v1note", CardPayload.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("hold")
                    .build();
            wf.waitForSignal("hold", "hold", CardPayload.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<PayState, PaymentMigrationContract> paymentV2() {
        return new Def<>(PAYMENT, 2, PaymentMigrationContract.class, PayState.class, wf -> {
            wf.init(
                    PaymentBegin.class,
                    begin -> new PayState(
                            begin.ref(),
                            null,
                            new Payer(begin.card() == null ? null : begin.card().number())));
            wf.initialStep("v2note");
            wf.waitForSignal("v2note", "v2note", PayerPayload.class)
                    .onSignal((state, payload) -> new PayState(state.ref(), null, payload.payer()))
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
    }

    private static Def<TypedState, TypedContract> typedPayment() {
        return new Def<>(PAYMENT, 1, TypedContract.class, TypedState.class, wf -> {
            wf.init(Begin.class, begin -> new TypedState(begin.ref()));
            wf.initialStep("card");
            wf.waitForSignal("card", "cardUpdated", CardUpdate.class)
                    .onSignal((state, payload) -> state)
                    .toStepOnSignal("done")
                    .build();
            wf.complete("done");
        });
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

    // --- Instance ---

    private final ExecutorFixture fixture;
    private final DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
    private final Node node;
    private final Map<String, String> contractNames = new LinkedHashMap<>();
    private final Logger workflowLogger = (Logger) LoggerFactory.getLogger("dev.vertique.workflow");
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Level loggerLevelBefore;

    private DecisionScenario(
            DbPoolConfig db,
            List<Seed> seeds,
            @Nullable WorkflowHistoryRedactor redactor,
            Set<WorkflowMigrationHandler<?, ?>> handlers) {
        this.fixture = ExecutorFixture.open(db);
        this.loggerLevelBefore = workflowLogger.getLevel();
        try {
            logged.start();
            workflowLogger.setLevel(Level.TRACE);
            workflowLogger.addAppender(logged);
            PgWorkflowDefinitionRegistryStore store = new PgWorkflowDefinitionRegistryStore(
                    fixture.observer(), new PgDbExceptionMapper(), Optional.empty());
            for (Seed seed : seeds) {
                seed(store, seed);
            }
            ExecutorFixture.NodeSpec spec = fixture.node("engine")
                    .pollIntervalMs(100)
                    .withRegistry(registry)
                    .definitionStore(store);
            if (!handlers.isEmpty()) {
                spec = spec.migrations(new DefaultWorkflowMigrationRegistry(handlers, registry));
            }
            if (redactor != null) {
                spec = spec.historyRedactor(redactor);
            }
            this.node = spec.build();
        } catch (RuntimeException | AssertionError e) {
            close();
            throw e;
        }
    }

    private void seed(PgWorkflowDefinitionRegistryStore store, Seed seed) {
        Def<?, ?> code = seed.definition();
        registry.register(code);
        RuntimeWorkflow runtime = registry.codeDefinitions().stream()
                .filter(candidate -> candidate.plan().definitionId().equals(code.definitionId())
                        && candidate.plan().definitionVersion() == code.definitionVersion())
                .findFirst()
                .orElseThrow();
        contractNames.put(code.definitionId(), runtime.contractName());
        Instant now = Instant.now();
        await(store.upsertCode(
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
                                .put("instance", new JsonArray(seed.signalNames()))
                                .put("branches", new JsonObject()),
                        null),
                seed.activate()));
    }

    // --- Recording and running ---

    /**
     * Records a start in a transaction of its own, under the identity, and commits it. The start payload is
     * {@code {"ref": ref}}.
     *
     * @param caller       the identity the call runs under, or null for none
     * @param definitionId the definition to start
     * @param ref          the label of the instance
     * @return the instance
     */
    public WorkflowInstanceId start(@Nullable SecurityIdentity caller, String definitionId, String ref) {
        return start(caller, definitionId, new JsonObject().put("ref", ref));
    }

    /**
     * Records a start with the payload in a transaction of its own, under the identity, and commits it.
     *
     * @param caller       the identity the call runs under, or null for none
     * @param definitionId the definition to start
     * @param payload      the start payload, which holds the {@code ref}
     * @return the instance
     */
    public WorkflowInstanceId start(@Nullable SecurityIdentity caller, String definitionId, JsonObject payload) {
        StartCommand command = new StartCommand(
                definitionId,
                contractNames.get(definitionId),
                payload,
                payload.getString("ref"),
                null,
                null,
                null,
                null,
                null);
        return await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.start(command, tx))));
    }

    /**
     * Records a signal in a transaction of its own, under the identity, and commits it. No executor runs.
     *
     * @param caller  the identity the call runs under, or null for none
     * @param id      the instance
     * @param name    the signal name
     * @param payload the signal payload
     * @param key     the dedup key
     */
    public void signal(
            @Nullable SecurityIdentity caller, WorkflowInstanceId id, String name, JsonObject payload, String key) {
        await(as(caller, () -> node.pool.withTransaction(tx -> node.engine.signal(id, name, payload, key, tx))));
    }

    /**
     * Completes the instance's open task in a transaction of its own and commits it. No executor runs.
     *
     * @param id       the instance
     * @param decision the decision's name
     * @param payload  the decision payload
     * @param key      the idempotency key
     */
    public void completeTask(WorkflowInstanceId id, String decision, JsonObject payload, String key) {
        UUID taskId = await(fixture.observer()
                        .preparedQuery("SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN'")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getUUID("task_id");
        // The task snapshot of an executor-created task carries no payload schema; a lenient one is stored, as the
        // completion proofs do, so the recording engine accepts the payload.
        JsonArray decisions = (JsonArray) await(fixture.observer()
                        .preparedQuery("SELECT decisions_snapshot_json FROM workflow_tasks WHERE task_id = $1")
                        .execute(Tuple.of(taskId)))
                .iterator()
                .next()
                .getValue("decisions_snapshot_json");
        for (int i = 0; i < decisions.size(); i++) {
            decisions.getJsonObject(i).put("payloadSchema", new JsonObject().put("type", "object"));
        }
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_tasks SET decisions_snapshot_json = $2 WHERE task_id = $1")
                .execute(Tuple.of(taskId, decisions)));
        TaskCompletionCommand command =
                new TaskCompletionCommand(taskId, decision, payload, key, new WorkflowActor.User("reviewer"), null);
        await(as(null, () -> node.pool.withTransaction(tx -> node.engine.taskCompleted(command, tx))));
    }

    /**
     * Records a migration to the version in a transaction of its own and commits it. No executor runs.
     *
     * @param id      the instance
     * @param version the target version
     */
    public void migrate(WorkflowInstanceId id, long version) {
        await(as(null, () -> node.pool.withTransaction(tx -> node.engine.migrate(id, version, tx))));
    }

    /**
     * Starts the executor, waits until the instance is idle and stops it.
     *
     * @param id the instance
     */
    public void runToIdle(WorkflowInstanceId id) {
        node.startExecutor();
        try {
            node.awaitIdle(id);
        } finally {
            node.stopExecutor();
        }
    }

    /**
     * Makes the instance one that existed before the decision log: its baseline mark is cleared and its history rows
     * are deleted.
     *
     * @param id the instance
     */
    public void makeLegacy(WorkflowInstanceId id) {
        await(fixture.observer()
                .preparedQuery("UPDATE workflow_instances SET baseline_logged = FALSE WHERE id = $1")
                .execute(Tuple.of(id.value())));
        await(fixture.observer()
                .preparedQuery("DELETE FROM workflow_history WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())));
    }

    // --- Observation ---

    /**
     * The history entries of the instance in sequence order with their decision columns.
     *
     * @param id the instance
     * @return the entries
     */
    public List<Entry> entries(WorkflowInstanceId id) {
        List<Entry> entries = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT sequence, entry_type, COALESCE(command_id, (payload_json->>'commandId')::bigint)"
                        + " AS command, step_id, outcome, definition_scope_key, definition_id, definition_version,"
                        + " plan_hash, state_version_before, state_version_after, executor_id, patch_json,"
                        + " payload_json, entry_version, actor_kind, actor_id, on_behalf_of, attribution_status,"
                        + " row_to_json(h)::text AS row_text FROM workflow_history h WHERE workflow_id = $1"
                        + " ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            Object payload = row.getValue("payload_json");
            entries.add(new Entry(
                    row.getLong("sequence"),
                    row.getString("entry_type"),
                    row.getLong("command"),
                    row.getString("step_id"),
                    row.getString("outcome"),
                    row.getString("definition_scope_key"),
                    row.getString("definition_id"),
                    row.getLong("definition_version"),
                    row.getString("plan_hash"),
                    row.getLong("state_version_before"),
                    row.getLong("state_version_after"),
                    row.getString("executor_id"),
                    row.getValue("patch_json"),
                    payload instanceof JsonObject json ? json : new JsonObject(),
                    row.getInteger("entry_version"),
                    row.getString("actor_kind"),
                    row.getString("actor_id"),
                    row.getString("on_behalf_of"),
                    row.getString("attribution_status"),
                    row.getString("row_text")));
        }
        return entries;
    }

    /**
     * The highest sequence in the instance's log, or zero when it has none.
     *
     * @param id the instance
     * @return the sequence
     */
    public long lastSequence(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT COALESCE(MAX(sequence), 0) FROM workflow_history WHERE workflow_id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getLong(0);
    }

    /**
     * The stored state of the instance.
     *
     * @param id the instance
     * @return the state
     */
    public JsonObject state(WorkflowInstanceId id) {
        Object state = await(fixture.observer()
                        .preparedQuery("SELECT state_json FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getValue("state_json");
        return state instanceof JsonObject json ? json.copy() : new JsonObject(String.valueOf(state));
    }

    /**
     * The status of the instance.
     *
     * @param id the instance
     * @return the status
     */
    public String status(WorkflowInstanceId id) {
        return fixture.instanceOf(id).status();
    }

    /**
     * The plan hash the instance is pinned to.
     *
     * @param id the instance
     * @return the plan hash
     */
    public String planHash(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT plan_hash FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getString("plan_hash");
    }

    /**
     * Whether the instance has logged its full-state baseline.
     *
     * @param id the instance
     * @return the {@code baseline_logged} flag
     */
    public boolean baselineLogged(WorkflowInstanceId id) {
        return await(fixture.observer()
                        .preparedQuery("SELECT baseline_logged FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value())))
                .iterator()
                .next()
                .getBoolean("baseline_logged");
    }

    /**
     * The commands of the instance in queue order.
     *
     * @param id the instance
     * @return the commands
     */
    public List<Command> commands(WorkflowInstanceId id) {
        List<Command> commands = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT id, kind, state, dedup_key, last_error, claimed_by FROM workflow_commands"
                        + " WHERE workflow_id = $1 ORDER BY seq")
                .execute(Tuple.of(id.value())))) {
            commands.add(new Command(
                    row.getLong("id"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getString("dedup_key"),
                    row.getString("last_error"),
                    row.getString("claimed_by")));
        }
        return commands;
    }

    /**
     * The only command of the kind.
     *
     * @param id   the instance
     * @param kind the command kind
     * @return the command
     */
    public Command commandOf(WorkflowInstanceId id, String kind) {
        List<Command> matching =
                commands(id).stream().filter(c -> c.kind().equals(kind)).toList();
        if (matching.size() != 1) {
            throw new AssertionError("expected one " + kind + " command of " + id + " but found " + matching);
        }
        return matching.getFirst();
    }

    /**
     * The payload stored on the command row, which the executor needs to run the command.
     *
     * @param commandId the command id
     * @return the stored payload
     */
    public JsonObject commandPayload(long commandId) {
        return ((JsonObject) await(fixture.observer()
                                .preparedQuery("SELECT payload_json FROM workflow_commands WHERE id = $1")
                                .execute(Tuple.of(commandId)))
                        .iterator()
                        .next()
                        .getValue("payload_json"))
                .copy();
    }

    /**
     * Every history row of the instance as JSON text, every column of it, one row per line.
     *
     * @param id the instance
     * @return the text
     */
    public String historyText(WorkflowInstanceId id) {
        return String.join("\n", entries(id).stream().map(Entry::rowText).toList());
    }

    /**
     * Every error text stored for the instance: the last error of each of its commands and the error columns of the
     * instance row.
     *
     * @param id the instance
     * @return the text
     */
    public String errorText(WorkflowInstanceId id) {
        List<String> texts = new ArrayList<>();
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT COALESCE(last_error, '') FROM workflow_commands WHERE workflow_id = $1")
                .execute(Tuple.of(id.value())))) {
            texts.add(row.getString(0));
        }
        for (Row row : await(fixture.observer()
                .preparedQuery("SELECT error_type, error_message FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value())))) {
            texts.add(row.getString(0) + " " + row.getString(1));
        }
        return String.join("\n", texts);
    }

    /**
     * Every line the workflow modules logged since the scenario opened, with the text of any exception.
     *
     * @return the text
     */
    public String loggedText() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logged.list)) {
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                line.append('\n').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    @Override
    public void close() {
        try {
            workflowLogger.detachAppender(logged);
            logged.stop();
            workflowLogger.setLevel(loggerLevelBefore);
        } finally {
            fixture.close();
        }
    }

    // --- Plumbing ---

    /** Runs the work on a duplicated context of its own, with the identity bound as the security context. */
    private <T> Future<T> as(@Nullable SecurityIdentity caller, Supplier<Future<T>> work) {
        Promise<T> result = Promise.promise();
        ContextInternal context = ((ContextInternal) fixture.vertx().getOrCreateContext()).duplicate();
        context.runOnContext(ignored -> {
            ContextHolder.Scope scope = caller == null
                    ? null
                    : fixture.holder().bind(SecurityContext.class, SecurityContexts.unauthenticated(caller));
            try {
                work.get().onComplete(outcome -> {
                    if (scope != null) {
                        scope.close();
                    }
                    result.handle(outcome);
                });
            } catch (Throwable e) {
                if (scope != null) {
                    scope.close();
                }
                result.tryFail(e);
            }
        });
        return result.future();
    }
}
