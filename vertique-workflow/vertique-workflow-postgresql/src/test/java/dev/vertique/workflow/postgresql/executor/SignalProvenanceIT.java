// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SystemIdentities;
import dev.vertique.workflow.exception.WorkflowSignalActorException;
import dev.vertique.workflow.exception.WorkflowSignalAmbiguousException;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.history.WorkflowHistoryFailure;
import dev.vertique.workflow.ops.CorrelatedSignal;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt;
import dev.vertique.workflow.ops.SignalProvenance;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ActorScenario.Attributed;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.ApprovalContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Delivery;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.OrderContract;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Payment;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.SignalRow;
import dev.vertique.workflow.postgresql.executor.KeyedWaitFixture.Written;
import dev.vertique.workflow.postgresql.history.RecordedHistory;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that both correlated delivery methods store the optional provenance of a signal in a column of its own and
 * never in the command's target; that a delivery carrying provenance refuses to record a command with no actor,
 * because none was captured or because the actor mapper refused the identity, writing nothing and reporting the
 * refusal to a bound history listener once; and that a delivery without provenance records as before.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SignalProvenanceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_signal_provenance_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String PAYMENT_CAPTURED = "payment-captured";
    private static final String APPROVED = "approved";

    private static final String KAFKA_REASON = "kafka source: carrier-events";

    private static final SignalProvenance KAFKA = SignalProvenance.kafka("carrier-events", "carrier.events", 3, 42L);
    private static final SignalProvenance CAMEL = SignalProvenance.camel("payments-in", "trigger-7", "a1".repeat(32));

    /**
     * A named non-anonymous identity that the configured actor mapper refuses: a system principal whose reason is
     * blank, which the mapper cannot turn into a workflow actor.
     */
    private static final SecurityIdentity REFUSED_BY_THE_MAPPER = new SecurityIdentity(
            new PrincipalRef(PrincipalType.SYSTEM, "system:refused", Map.of("system.reason", " ")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    /** The two delivery methods, each with an instance that a signal of the route would match. */
    enum Route {
        KEY(PAYMENT_CAPTURED, KeyedWaitFixture.ORDER, "PAY-77"),
        BUSINESS_KEY(APPROVED, KeyedWaitFixture.APPROVAL, "PO-42");

        private final String signalName;
        private final String definitionId;
        private final String key;

        Route(String signalName, String definitionId, String key) {
            this.signalName = signalName;
            this.definitionId = definitionId;
            this.key = key;
        }

        /** Records the start of an instance that the route's signal matches, and runs it to its wait. */
        WorkflowInstanceId open(KeyedWaitFixture f, Node node) {
            WorkflowInstanceId id = this == KEY ? f.startKeyed(node, key) : f.startPending(node, key);
            f.idle(node, id);
            return id;
        }

        /** The signal of the route for its key, with the provenance when there is one. */
        CorrelatedSignal signal(@Nullable SignalProvenance provenance, String dedupKey) {
            CorrelatedSignal signal = this == KEY
                    ? CorrelatedSignal.of(OrderContract.class, signalName, key, new Payment("txn-1"), dedupKey)
                    : CorrelatedSignal.of(ApprovalContract.class, signalName, key, new Payment("a-1"), dedupKey);
            return provenance == null ? signal : signal.withProvenance(provenance);
        }

        /** Delivers the signal by the route's method in a caller transaction that commits whatever the outcome was. */
        Delivery deliver(KeyedWaitFixture f, Node node, @Nullable SecurityIdentity who, CorrelatedSignal signal) {
            return this == KEY ? f.deliverAs(node, who, signal) : f.deliverByBusinessKeyAs(node, who, signal);
        }

        String contractName(KeyedWaitFixture f) {
            return f.contractNameOf(definitionId);
        }
    }

    /** The two ways a caller has no actor: no identity is bound, or an anonymous one is. */
    enum NoActor {
        NONE_BOUND(null),
        ANONYMOUS_BOUND(SecurityIdentity.anonymous());

        private final SecurityIdentity identity;

        NoActor(@Nullable SecurityIdentity identity) {
            this.identity = identity;
        }

        @Nullable
        SecurityIdentity identity() {
            return identity;
        }
    }

    static Stream<Arguments> noActorCases() {
        return Stream.of(Route.values())
                .flatMap(route -> Stream.of(NoActor.values()).map(who -> Arguments.of(route, who)));
    }

    // --- Stored provenance ---

    @Test
    @DisplayName("a key delivery stores its provenance with a system actor")
    void keyDeliveryStoresItsProvenanceWithTheSystemActor() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance waiting on PAY-77, an ambient system identity, and a signal carrying Kafka provenance
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startKeyed(node, "PAY-77");
            f.idle(node, id);
            String waitId = f.waitRecords(id).get(0).waitId().toString();
            SecurityIdentity carrier = SystemIdentities.internal(KAFKA_REASON);
            CorrelatedSignal signal = CorrelatedSignal.of(
                            OrderContract.class, PAYMENT_CAPTURED, "PAY-77", new Payment("txn-1"), "d-1")
                    .withProvenance(KAFKA);

            // When signalByKey records it under that identity and the executor applies it
            Delivery delivery = f.deliverAs(node, carrier, signal);
            f.idle(node, id);

            // Then the delivery is recorded
            assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            SignalRow command = f.signalRows(id).get(0);

            // And the command's provenance holds transport kafka, source carrier-events and exactly the topic,
            // partition and offset as attributes
            JsonObject provenance = f.provenanceOf(command.id());
            assertThat(provenance).isNotNull();
            assertThat(provenance.getString("transport")).isEqualTo("kafka");
            assertThat(provenance.getString("source")).isEqualTo("carrier-events");
            JsonObject attributes = provenance.getJsonObject("attributes");
            assertThat(attributes.getString("topic")).isEqualTo("carrier.events");
            assertThat(attributes.getString("partition")).isEqualTo("3");
            assertThat(attributes.getString("offset")).isEqualTo("42");
            assertThat(attributes.fieldNames()).containsExactlyInAnyOrder("topic", "partition", "offset");
            assertThat(attributes.containsKey("triggerId")).isFalse();
            assertThat(attributes.containsKey("messageHash")).isFalse();
            assertThat(provenance.fieldNames()).containsExactlyInAnyOrder("transport", "source", "attributes");

            // And the actor columns name the system identity
            Attributed actor = f.actorOf(command.id());
            assertThat(actor.actorKind()).isEqualTo("SYSTEM");
            assertThat(actor.actorId()).isNull();
            assertThat(actor.actorReason()).isEqualTo(KAFKA_REASON);
            assertThat(actor.attribution()).isEqualTo("VERIFIED");

            // And the target holds the signal name and the wait id and no provenance key
            assertThat(command.target().getString("name")).isEqualTo(PAYMENT_CAPTURED);
            assertThat(command.target().getString("waitId")).isEqualTo(waitId);
            assertThat(command.target().fieldNames())
                    .doesNotContain("provenance", "provenanceJson", "transport", "source", "attributes");

            // And the command is complete and the instance resumed
            assertThat(command.state()).isEqualTo("COMPLETE");
            assertThat(f.fixture.instanceOf(id).status()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("a business-key delivery stores its provenance and keeps today's target shape")
    void businessKeyDeliveryStoresItsProvenanceAndKeepsTodaysTargetShape() {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a live instance with business key PO-42 at its second wait, an ambient system identity, and a
            // signal carrying Camel provenance
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.startPending(node, "PO-42");
            f.idle(node, id);
            f.signalInstance(node, id, "received", new Payment("r-1"), "r-1");
            f.idle(node, id);
            SecurityIdentity carrier = SystemIdentities.internal("camel trigger: payments-in");
            CorrelatedSignal signal = CorrelatedSignal.of(
                            ApprovalContract.class, APPROVED, "PO-42", new Payment("a-1"), "d-1")
                    .withProvenance(CAMEL);

            // When signalByBusinessKey records it under that identity and the executor applies it
            Delivery delivery = f.deliverByBusinessKeyAs(node, carrier, signal);
            f.idle(node, id);

            // Then the delivery is recorded
            assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            SignalRow command = f.signalRows(id).stream()
                    .filter(row -> APPROVED.equals(row.target().getString("name")))
                    .findFirst()
                    .orElseThrow();

            // And the command's provenance holds transport camel, source payments-in and exactly the trigger id and
            // the message hash as attributes
            JsonObject provenance = f.provenanceOf(command.id());
            assertThat(provenance).isNotNull();
            assertThat(provenance.getString("transport")).isEqualTo("camel");
            assertThat(provenance.getString("source")).isEqualTo("payments-in");
            JsonObject attributes = provenance.getJsonObject("attributes");
            assertThat(attributes.getString("triggerId")).isEqualTo("trigger-7");
            assertThat(attributes.getString("messageHash")).isEqualTo("a1".repeat(32));
            assertThat(attributes.fieldNames()).containsExactlyInAnyOrder("triggerId", "messageHash");
            assertThat(attributes.containsKey("topic")).isFalse();
            assertThat(attributes.containsKey("partition")).isFalse();
            assertThat(attributes.containsKey("offset")).isFalse();

            // And the target is exactly the signal name
            assertThat(command.target()).isEqualTo(new JsonObject().put("name", APPROVED));

            // And the command is complete
            assertThat(command.state()).isEqualTo("COMPLETE");
        }
    }

    // --- Refusals ---

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("noActorCases")
    @DisplayName("a delivery with provenance and no actor fails and writes nothing")
    void deliveryWithProvenanceAndNoActorFailsAndWritesNothing(Route route, NoActor who) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given an instance that the signal would match, no actor captured (nothing bound, or an anonymous
            // principal bound), and a signal carrying provenance
            Node node = f.node("engine").build();
            WorkflowInstanceId id = route.open(f, node);
            Written before = f.written();

            // When the delivery is attempted and the caller commits afterwards
            Delivery delivery = deliveryAttempt(f, node, route, who.identity());

            // Then the future fails with the actor exception, neither of the two typed match exceptions, naming the
            // contract and the signal and no key
            assertThat(delivery.failureOrFail())
                    .isExactlyInstanceOf(WorkflowSignalActorException.class)
                    .isNotInstanceOf(WorkflowSignalNoMatchException.class)
                    .isNotInstanceOf(WorkflowSignalAmbiguousException.class)
                    .isInstanceOfSatisfying(WorkflowSignalActorException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(route.contractName(f));
                        assertThat(failure.signalName()).isEqualTo(route.signalName);
                        assertThat(failure.getMessage())
                                .contains(EchoedNames.bounded(route.contractName(f)), route.signalName)
                                .doesNotContain(route.key);
                    });

            // And no command, dedup, history or provenance row exists, and the open signals are unchanged
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.signalCommands(id)).isEmpty();
            assertThat(f.dedupRows()).isEmpty();
            assertThat(f.provenanceRows()).isZero();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    @DisplayName("an actor-mapper refusal fails with the actor exception and writes nothing")
    void actorMapperRefusalFailsWithTheActorExceptionAndWritesNothing(Route route) {
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a bound non-anonymous identity that the configured actor mapper refuses, an instance that the
            // signal would match, and a signal carrying provenance
            Node node = f.node("engine").build();
            WorkflowInstanceId id = route.open(f, node);
            Written before = f.written();

            // When the delivery is attempted and the caller commits afterwards
            Delivery delivery = deliveryAttempt(f, node, route, REFUSED_BY_THE_MAPPER);

            // Then the future fails with the actor exception naming the contract and the signal, and no key
            assertThat(delivery.failureOrFail())
                    .isExactlyInstanceOf(WorkflowSignalActorException.class)
                    .isInstanceOfSatisfying(WorkflowSignalActorException.class, failure -> {
                        assertThat(failure.contractName()).isEqualTo(route.contractName(f));
                        assertThat(failure.signalName()).isEqualTo(route.signalName);
                        assertThat(failure.getMessage())
                                .contains(EchoedNames.bounded(route.contractName(f)), route.signalName)
                                .doesNotContain(route.key);
                    });

            // And no command, dedup, history or provenance row exists, and the open signals are unchanged
            assertThat(f.written()).isEqualTo(before);
            assertThat(f.signalCommands(id)).isEmpty();
            assertThat(f.dedupRows()).isEmpty();
            assertThat(f.provenanceRows()).isZero();
        }
    }

    @Test
    @DisplayName("a refusal for a missing actor is reported to the history listener once")
    void refusalForAMissingActorIsReportedToTheHistoryListenerOnce() {
        RecordedHistory recorded = new RecordedHistory();
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given a history listener bound to the engine, an instance waiting on PAY-77, a live instance with
            // business key PO-42, and no identity bound
            Node node = f.node("engine").historyListeners(Set.of(recorded)).build();
            WorkflowInstanceId waiting = f.startKeyed(node, "PAY-77");
            WorkflowInstanceId keyed = f.startPending(node, "PO-42");
            f.idle(node, waiting, keyed);
            awaitCondition("the start announcements to catch up", IDLE, () -> caughtUp(f, recorded, waiting, keyed));
            assertThat(recorded.failures()).isEmpty();
            int announcedToWaiting = recorded.eventsOf(waiting).size();
            int announcedToKeyed = recorded.eventsOf(keyed).size();
            int changesBefore = recorded.changes().size();

            // When a delivery carrying provenance is made by key and another by business key, each in its own caller
            // transaction
            Delivery byKey = deliveryAttempt(f, node, Route.KEY, null);
            Delivery byBusinessKey = deliveryAttempt(f, node, Route.BUSINESS_KEY, null);

            // Then both futures fail with the actor exception
            assertThat(byKey.failureOrFail()).isExactlyInstanceOf(WorkflowSignalActorException.class);
            assertThat(byBusinessKey.failureOrFail()).isExactlyInstanceOf(WorkflowSignalActorException.class);

            // And the listener holds exactly two failures, both of action SIGNAL and of error type
            // WorkflowSignalActorException, each with its own attempt id
            awaitCondition(
                    "both refusals to be reported",
                    IDLE,
                    () -> recorded.failures().size() >= 2);
            List<WorkflowHistoryFailure> failures = recorded.failures();
            assertThat(failures).hasSize(2);
            assertThat(failures).allSatisfy(failure -> assertThat(failure.action())
                    .isEqualTo(WorkflowHistoryFailure.Action.SIGNAL));
            assertThat(failures)
                    .extracting(WorkflowHistoryFailure::errorType)
                    .containsExactly("WorkflowSignalActorException", "WorkflowSignalActorException");
            assertThat(failures.stream().map(WorkflowHistoryFailure::attemptId).distinct())
                    .hasSize(2);

            // And the text of neither contains a key
            for (WorkflowHistoryFailure failure : failures) {
                assertThat(failure.toString()).doesNotContain("PAY-77", "PO-42");
            }

            // And nothing is announced for the instances
            assertThat(recorded.eventsOf(waiting)).hasSize(announcedToWaiting);
            assertThat(recorded.eventsOf(keyed)).hasSize(announcedToKeyed);
            assertThat(recorded.changes()).hasSize(changesBefore);
        }
    }

    // --- Without provenance ---

    @Test
    @DisplayName("a delivery without provenance records as before and leaves the column null")
    void deliveryWithoutProvenanceRecordsAsBeforeAndLeavesTheColumnNull() {
        RecordedSignalIT.RecordingObserver observer = new RecordedSignalIT.RecordingObserver();
        try (KeyedWaitFixture f = KeyedWaitFixture.open(db.toPoolConfig())) {
            // Given instances waiting on PAY-77 and PAY-78, and live instances with business keys PO-42 and PO-43,
            // and signals carrying no provenance
            Node node = f.node("engine").observers(observer).build();
            WorkflowInstanceId payment77 = f.startKeyed(node, "PAY-77");
            WorkflowInstanceId payment78 = f.startKeyed(node, "PAY-78");
            WorkflowInstanceId order42 = f.startPending(node, "PO-42");
            WorkflowInstanceId order43 = f.startPending(node, "PO-43");
            f.idle(node, payment77, payment78, order42, order43);
            SecurityIdentity system = SystemIdentities.internal("batch import");

            // When each method records one with no ambient identity, and one with a system identity, plus an
            // instance-id signal with no identity
            Delivery keyBare = Route.KEY.deliver(f, node, null, plain(Route.KEY, "PAY-77", "d-1"));
            Delivery keySystem = Route.KEY.deliver(f, node, system, plain(Route.KEY, "PAY-78", "d-2"));
            Delivery businessKeyBare =
                    Route.BUSINESS_KEY.deliver(f, node, null, plain(Route.BUSINESS_KEY, "PO-42", "d-3"));
            Delivery businessKeySystem =
                    Route.BUSINESS_KEY.deliver(f, node, system, plain(Route.BUSINESS_KEY, "PO-43", "d-4"));
            f.signalInstance(node, order42, "received", new Payment("r-1"), "r-1");

            // Then every call succeeds
            for (Delivery delivery : List.of(keyBare, keySystem, businessKeyBare, businessKeySystem)) {
                assertThat(delivery.receiptOrFail().outcome()).isEqualTo(CorrelatedSignalReceipt.Outcome.RECORDED);
            }

            // And each command's provenance is null
            List<SignalRow> commands = new ArrayList<>();
            for (WorkflowInstanceId id : List.of(payment77, payment78, order42, order43)) {
                commands.addAll(f.signalRows(id));
            }
            assertThat(commands).hasSize(5);
            for (SignalRow command : commands) {
                assertThat(f.provenanceOf(command.id())).isNull();
            }
            assertThat(f.provenanceRows()).isZero();

            // And the calls with no identity recorded an absent actor, and those with the system identity a verified
            // system actor
            for (WorkflowInstanceId bare : List.of(payment77, order42)) {
                assertThat(f.signalRows(bare)).isNotEmpty().allSatisfy(command -> {
                    Attributed actor = f.actorOf(command.id());
                    assertThat(actor.actorKind()).isEqualTo("NONE");
                    assertThat(actor.attribution()).isEqualTo("ABSENT");
                });
            }
            for (WorkflowInstanceId carried : List.of(payment78, order43)) {
                assertThat(f.signalRows(carried)).singleElement().satisfies(command -> {
                    Attributed actor = f.actorOf(command.id());
                    assertThat(actor.actorKind()).isEqualTo("SYSTEM");
                    assertThat(actor.attribution()).isEqualTo("VERIFIED");
                });
            }

            // And the existing absence report was made for each of the three signals recorded with no actor
            awaitCondition("the absence reports to be delivered", IDLE, () -> absences(observer) >= 3);
            assertThat(absences(observer)).isEqualTo(3);
        }
    }

    // --- Helpers ---

    /** A delivery of the route's signal that carries provenance, under the identity, committed whatever happens. */
    private static Delivery deliveryAttempt(
            KeyedWaitFixture f, Node node, Route route, @Nullable SecurityIdentity who) {
        SignalProvenance provenance = route == Route.KEY ? KAFKA : CAMEL;
        return route.deliver(f, node, who, route.signal(provenance, "d-1"));
    }

    /** The route's signal for a key, with no provenance. */
    private static CorrelatedSignal plain(Route route, String key, String dedupKey) {
        return route == Route.KEY
                ? CorrelatedSignal.of(OrderContract.class, route.signalName, key, new Payment("txn-1"), dedupKey)
                : CorrelatedSignal.of(ApprovalContract.class, route.signalName, key, new Payment("a-1"), dedupKey);
    }

    /** How many actor-absent reports for a signal the observer has received. */
    private static long absences(RecordedSignalIT.RecordingObserver observer) {
        return observer.events.stream()
                .filter(event -> event instanceof WorkflowExecutorEvent.ActorAbsent absent
                        && absent.kind().equals("SIGNAL"))
                .count();
    }

    /** Whether the listener has been told every stored entry of each instance. */
    private static boolean caughtUp(KeyedWaitFixture f, RecordedHistory recorded, WorkflowInstanceId... ids) {
        for (WorkflowInstanceId id : ids) {
            if (!announced(recorded, id).equals(stored(f, id))) {
                return false;
            }
        }
        return true;
    }

    private static List<Long> announced(RecordedHistory recorded, WorkflowInstanceId id) {
        return recorded.eventsOf(id).stream()
                .map(event -> event.entryInfo().sequence())
                .toList();
    }

    /** The sequences of the instance's history, without the state baselines that are not announced. */
    private static List<Long> stored(KeyedWaitFixture f, WorkflowInstanceId id) {
        List<Long> sequences = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .preparedQuery("SELECT sequence FROM workflow_history WHERE workflow_id = $1"
                        + " AND entry_type <> 'STATE_BASELINE' ORDER BY sequence")
                .execute(Tuple.of(id.value())))) {
            sequences.add(row.getLong("sequence"));
        }
        return sequences;
    }
}
