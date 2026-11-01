// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.PARCEL_DELIVERED;
import static dev.vertique.workflow.kafka.SourceScenario.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.kafka.KafkaRecordFilter;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.workflow.kafka.SourceScenario.DeliveryConfirmed;
import dev.vertique.workflow.kafka.SourceScenario.Feed;
import dev.vertique.workflow.kafka.SourceScenario.Headers;
import dev.vertique.workflow.kafka.SourceScenario.LookupScript;
import dev.vertique.workflow.kafka.SourceScenario.OrdersContract;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
import dev.vertique.workflow.kafka.SourceScenario.ReturnsContract;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import dev.vertique.workflow.kafka.SourceScenario.Waiting;
import dev.vertique.workflow.source.SignalNoMatch;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves, on a real broker and a real PostgreSQL engine, what becomes of a record that does not become a signal:
 * events that concern no workflow commit and never pause the consumer, input the type or the database refuses
 * dead-letters at once with its error type alone, a retryable failure is retried and then dead-lettered, a message
 * with several signals settles each signal once, and the limits on open signals and payload hold.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaSignalFailureHandlingIT {

    private static final long LONG_BACKOFF_MS = 30_000;

    @Test
    void unrelatedEventsCommitWithNoRetryNoDeadLetterEntryAndNoPause(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "unrelated")) {
            // Given a source for delivered events only, keyed by the carrier reference, with the default onNoMatch and
            // a
            // long retry backoff, and no instance waiting on the key of the third event
            Feed<ShipmentDelivered> carriers = scenario.carriers("carriers", source -> source.when(
                                    event -> event.type().equals("delivered"))
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces an event of another type, an event of the right type with an empty key, an event
            // for a key nobody waits on, and then a matching event
            Produced otherType =
                    carriers.produce(scenario.delivered("1", order.key()).withType("shipped"));
            Produced emptyKey = carriers.produce(scenario.delivered("2", ""));
            Produced noOne = carriers.produce(scenario.delivered("3", scenario.ref("PAY-99")));
            Produced matching = carriers.produce(scenario.delivered("4", order.key()));

            // Then all three unrelated records complete, are committed, and were never scheduled for a retry
            for (Produced unrelated : List.of(otherType, emptyKey, noOne)) {
                scenario.awaitSettled(unrelated, SourceScenario.PROMPT);
                assertThat(scenario.completions().outcomesOf(unrelated)).containsExactly(KafkaTerminalOutcome.SUCCESS);
                assertThat(carriers.committed(unrelated)).isTrue();
            }
            // And no dead-letter record exists
            assertThat(carriers.dlq().read()).isEmpty();
            // And the matching event is handled within 5 seconds
            scenario.awaitSettled(matching, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
            // And the observer saw IGNORED, IGNORED and NO_MATCH, and then applied the matching event
            assertThat(scenario.observer().outcomesOf("carriers"))
                    .containsExactlyInAnyOrder(Outcome.IGNORED, Outcome.IGNORED, Outcome.NO_MATCH, Outcome.APPLIED);
        }
    }

    @Test
    void otherSchemaOrNullValueCommitsAsUndecodable(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "undecodable")) {
            // Given a source over ShipmentDelivered with a long retry backoff
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces a JSON record of another schema, a record with a null value, and then a matching
            // record
            Produced otherSchema = carriers.produceRaw(
                    null,
                    "{\"carrierRef\":[\"not\",\"a\",\"string\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.util.Map.of());
            Produced nullValue = carriers.produceNull(null);
            Produced matching = carriers.produce(scenario.delivered("1", order.key()));

            // Then the first two are committed with no retry and no dead-letter entry
            for (Produced undecodable : List.of(otherSchema, nullValue)) {
                RecordingInterceptor.Completion settled = scenario.awaitSettled(undecodable, SourceScenario.PROMPT);
                assertThat(SourceScenario.COMMITTED_WITHOUT_RETRY).contains(settled.outcome());
                assertThat(scenario.completions().outcomesOf(undecodable)).hasSize(1);
                assertThat(carriers.committed(undecodable)).isTrue();
            }
            assertThat(carriers.dlq().read()).isEmpty();
            // And the observer saw two UNDECODABLE events
            assertThat(scenario.observer().count("carriers", Outcome.UNDECODABLE))
                    .isEqualTo(2);
            // And the matching record is handled within 5 seconds
            scenario.awaitSettled(matching, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    @Test
    void throwingKeyOrNullDedupKeyDeadLettersAtOnceWithoutTheMessage(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "throwing")) {
            // Given one source whose key throws for one reference, naming it, and one whose dedupBy answers null, with
            // a
            // long retry backoff
            String secret = scenario.ref("PAY-77");
            Feed<ShipmentDelivered> throwingKey = scenario.carriers(
                            "throwing-key", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(event -> {
                                        if (event.carrierRef().equals(secret)) {
                                            throw new IllegalStateException(secret + " is secret");
                                        }
                                        return event.carrierRef();
                                    })
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            Feed<ShipmentDelivered> nullDedup = scenario.carriers("null-dedup", source -> source.dedupBy(event -> null)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-1"));

            // When the test produces one record to each, then a good record to the first source's topic
            Produced throwing = throwingKey.produce(scenario.delivered("1", secret));
            Produced nullKey = nullDedup.produce(scenario.delivered("2", secret));
            Produced good = throwingKey.produce(scenario.delivered("3", order.key()));

            // Then each record is in its dead-letter topic with an error type that holds the class name only
            scenario.awaitSettled(throwing, SourceScenario.PROMPT);
            scenario.awaitSettled(nullKey, SourceScenario.PROMPT);
            List<DlqReader.Entry> throwingLetters = throwingKey.dlq().await(1, SourceScenario.PROMPT);
            List<DlqReader.Entry> nullLetters = nullDedup.dlq().await(1, SourceScenario.PROMPT);
            assertThat(throwingLetters).hasSize(1);
            assertThat(throwingLetters.get(0).errorType()).isEqualTo("IllegalStateException");
            assertThat(nullLetters).hasSize(1);
            assertThat(nullLetters.get(0).errorType())
                    .matches("[A-Za-z0-9_.$]+")
                    .doesNotContain("PAY-77");
            for (DlqReader.Entry entry : List.of(throwingLetters.get(0), nullLetters.get(0))) {
                assertThat(entry.frameworkHeaders()).doesNotContain("PAY-77").doesNotContain(secret);
            }
            // And the consumer published each at once and never scheduled a retry
            assertThat(scenario.completions().outcomesOf(throwing)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(scenario.completions().outcomesOf(nullKey)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            // And no log record from the source holds the secret
            assertThat(scenario.logs()).noneMatch(line -> line.contains("PAY-77"));
            // And the good record is handled within 5 seconds
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    @Test
    void ambiguousDeliveryDeadLettersWithNoRetryAndNoPause(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "ambiguous")) {
            // Given instances in scopes acme and globex both waiting on one key, a source with no scope narrowing and a
            // long retry backoff
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            String shared = scenario.ref("PAY-77");
            Waiting acme = scenario.waitingOrder("acme", shared);
            Waiting globex = scenario.waitingOrder("globex", shared);
            Waiting single = scenario.waitingOrder("acme", scenario.ref("PAY-1"));

            // When the test produces a record for the shared key, then a record for a key with one match
            Produced ambiguous = carriers.produce(scenario.delivered("1", shared));
            Produced matching = carriers.produce(scenario.delivered("2", single.key()));

            // Then the first is in the dead-letter topic, published at once, with no command and an AMBIGUOUS event
            scenario.awaitSettled(ambiguous, SourceScenario.PROMPT);
            assertThat(carriers.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            assertThat(scenario.completions().outcomesOf(ambiguous))
                    .containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(scenario.observer().count("carriers", Outcome.AMBIGUOUS)).isEqualTo(1);
            // And the second is handled within 5 seconds
            scenario.awaitSettled(matching, SourceScenario.PROMPT);
            scenario.awaitIdle(acme, globex, single);
            assertThat(scenario.commands(acme)).isEmpty();
            assertThat(scenario.commands(globex)).isEmpty();
            assertThat(scenario.commands(single)).hasSize(1);
        }
    }

    @Test
    void noMatchUnderRetryIsRetriedThenDeadLetteredAndCommitted(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "no-match-retry")) {
            // Given a source with onNoMatch RETRY, two retries 50 ms apart, then the dead-letter topic, and no instance
            // waiting on the key of the first record
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.onNoMatch(SignalNoMatch.RETRY)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, 50);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces one record that matches nothing, then a record that matches
            Produced unmatched = carriers.produce(scenario.delivered("1", scenario.ref("PAY-99")));
            Produced matching = carriers.produce(scenario.delivered("2", order.key()));

            // Then the consumer scheduled a retry twice and then published to the dead-letter topic
            scenario.awaitSettled(unmatched, SourceScenario.PROMPT.multipliedBy(2));
            assertThat(scenario.completions().outcomesOf(unmatched))
                    .containsExactly(
                            KafkaTerminalOutcome.RETRY_SCHEDULED,
                            KafkaTerminalOutcome.RETRY_SCHEDULED,
                            KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(carriers.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            // And its offset is committed, and the second record is handled
            assertThat(carriers.committed(unmatched)).isTrue();
            scenario.awaitDelivered(matching, order);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    @Test
    void openSignalLimitRetriesAndOversizedPayloadDeadLettersAtOnce(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "limits").limits(1, 1024)) {
            // Given a limit of one open signal per instance and a payload limit of 1024 bytes, a source that retries
            // every 50 ms for the limit case, and a source with a long backoff for the payload case
            Feed<ShipmentDelivered> limited = scenario.carriers(
                            "limited", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(event -> DeliveryConfirmed.noting(event.note()))
                                    .build())
                    .steadyRetry(60, 50);
            Feed<ShipmentDelivered> oversized = scenario.carriers(
                            "oversized", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(event -> DeliveryConfirmed.noting(event.note()))
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting atLimit = scenario.waitingOrder("acme", scenario.ref("PAY-1"));
            Waiting forPayload = scenario.waitingOrder("acme", scenario.ref("PAY-2"));
            Waiting next = scenario.waitingOrder("acme", scenario.ref("PAY-3"));
            scenario.fillOpenSignals(atLimit, 1);

            // When the test produces a record for the instance at its limit, then lifts the limit, and produces a
            // record
            // whose mapped payload is 4 KiB
            Produced limitedRecord = produceNoting(limited, scenario, atLimit.key(), "small");
            assertThat(scenario.observer().await("limited", Outcome.LIMITED, 1, SourceScenario.PROMPT))
                    .isTrue();
            scenario.liftOpenSignals(atLimit);
            Produced oversizedRecord =
                    oversized.produce(scenario.delivered("2", forPayload.key()).withNote(SourceScenario.ofSize(4096)));
            Produced after =
                    oversized.produce(scenario.delivered("3", next.key()).withNote("small"));

            // Then the first record's first outcome is a scheduled retry, and once the limit lifts it ends with SUCCESS
            // and one command
            scenario.awaitDelivered(limitedRecord, atLimit);
            assertThat(scenario.completions().outcomesOf(limitedRecord).get(0))
                    .isEqualTo(KafkaTerminalOutcome.RETRY_SCHEDULED);
            assertThat(scenario.completions().outcomesOf(limitedRecord)).last().isEqualTo(KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.commands(atLimit)).hasSize(1);
            // And the oversized record is in the dead-letter topic, published at once, with no command
            scenario.awaitSettled(oversizedRecord, SourceScenario.PROMPT);
            assertThat(oversized.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            assertThat(scenario.completions().outcomesOf(oversizedRecord))
                    .containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(scenario.observer().count("oversized", Outcome.OVERSIZED))
                    .isEqualTo(1);
            assertThat(scenario.commands(forPayload)).isEmpty();
            // And the next record is handled within 5 seconds
            scenario.awaitSettled(after, SourceScenario.PROMPT);
            scenario.awaitIdle(next);
            assertThat(scenario.commands(next)).hasSize(1);
        }
    }

    @Test
    void intakeRefusalHoldsTheRecordBackAndNeverReachesTheDeadLetterTopic(Vertx vertx) throws InterruptedException {
        try (SourceScenario scenario = SourceScenario.open(vertx, "intake-wait").intakeLimit(3, 1, 100)) {
            // Given a source with a single retry and a steady 50 ms wait, an order waiting for a delivery, and a node
            // that refuses intake because the executor is stopped while three orders are due
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .steadyRetry(1, 50);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));
            scenario.pauseExecutor();
            scenario.fillIntake();

            // When the test produces a matching record and the node keeps refusing intake for longer than the retry
            // budget would last
            Produced record = carriers.produce(scenario.delivered("1", order.key()));
            Thread.sleep(3_000);

            // Then the consumer has held the record back: it was never attempted, so no retry was spent, nothing was
            // reported, dead-lettered or recorded
            assertThat(scenario.completions().outcomesOf(record)).isEmpty();
            assertThat(scenario.observer().count("carriers", Outcome.LIMITED)).isZero();
            assertThat(carriers.dlq().read()).isEmpty();
            assertThat(scenario.commands(order)).isEmpty();

            // When the executor runs again and the node admits intake
            scenario.resumeExecutor();

            // Then the same record is recorded once and committed on its first attempt, and the dead-letter topic
            // stays empty
            scenario.awaitDelivered(record, order);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(carriers.committed(record)).isTrue();
            assertThat(scenario.completions().outcomesOf(record)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            assertThat(carriers.dlq().read()).isEmpty();
            assertThat(scenario.observer().count("carriers", Outcome.APPLIED)).isEqualTo(1);
        }
    }

    @Test
    void failedBusinessKeyLookupRetriesAndRecordsNothing(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "lookup-fails")) {
            // Given a source whose business-key lookup fails on its first call with a message that holds the order
            // number, then answers the business key of a live instance
            String orderNumber = scenario.ref("PO-42");
            LookupScript lookup =
                    LookupScript.failsOnceThenHolds("lookup failed for order " + orderNumber, orderNumber);
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .businessKeyFrom(lookup.lookup())
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, 50);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-1"), orderNumber);

            // When the test produces one record
            Produced record = carriers.produce(scenario.delivered("1", scenario.ref("ANY")));

            // Then the first attempt completes with a scheduled retry and no command exists at that point
            assertThat(scenario.completions().awaitCompletions(record, 1, SourceScenario.PROMPT))
                    .isTrue();
            assertThat(scenario.completions().outcomesOf(record).get(0))
                    .isEqualTo(KafkaTerminalOutcome.RETRY_SCHEDULED);
            assertThat(scenario.commands(order)).isEmpty();
            // And the retry records one command
            lookup.release();
            scenario.awaitDelivered(record, order);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.SUCCESS);
            // And the exception message appears in no dead-letter record and no log record
            assertThat(carriers.dlq().read()).isEmpty();
            assertThat(scenario.logs()).noneMatch(line -> line.contains(orderNumber));
        }
    }

    @Test
    void secondSignalFailingRetriesAndEachSignalIsRecordedOnce(Vertx vertx) {
        try (SourceScenario scenario =
                SourceScenario.open(vertx, "second-signal").limits(1, 262_144)) {
            // Given a source naming the orders signal and the returns signal, both keyed by the carrier reference, with
            // a dedup key, one waiting instance for each contract, and the returns instance at its limit
            Feed<ShipmentDelivered> carriers =
                    twoContracts(scenario, "carriers", null).steadyRetry(60, 50);
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);
            Waiting returned = scenario.waitingReturn("acme", ref);
            scenario.fillOpenSignals(returned, 1);

            // When the test produces one record, then lifts the returns instance's limit
            Produced record = carriers.produce(scenario.delivered("1", ref));

            // Then the first attempt records the orders signal and fails the returns signal, and the consumer scheduled
            // a retry
            assertThat(scenario.observer().await("carriers", Outcome.LIMITED, 1, SourceScenario.PROMPT))
                    .isTrue();
            assertThat(scenario.completions().awaitCompletions(record, 1, SourceScenario.PROMPT))
                    .isTrue();
            assertThat(scenario.completions().outcomesOf(record).get(0))
                    .isEqualTo(KafkaTerminalOutcome.RETRY_SCHEDULED);
            scenario.liftOpenSignals(returned);
            scenario.awaitDelivered(record, order, returned);
            // And the retry answers a duplicate for the orders signal and records the returns signal, so that each
            // contract has exactly one command
            assertThat(scenario.observer().outcomesOf("carriers")).contains(Outcome.DEDUPLICATED, Outcome.APPLIED);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.commands(returned)).hasSize(1);
            assertThat(scenario.completions().outcomesOf(record)).last().isEqualTo(KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void twoSignalsWithOneMatchSignalsThatInstanceAndCountsTheOtherAsNoMatch(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "one-match")) {
            // Given the two-signal source with the default onNoMatch, a waiting orders instance and no returns instance
            Feed<ShipmentDelivered> carriers = twoContracts(scenario, "carriers", null);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces one record
            Produced record = carriers.produce(scenario.delivered("1", order.key()));
            scenario.awaitDelivered(record, order);

            // Then the orders instance resumed and the observer saw one APPLIED and one NO_MATCH
            assertThat(scenario.signalsApplied(order)).isEqualTo(1);
            assertThat(scenario.observer().count("carriers", Outcome.APPLIED)).isEqualTo(1);
            assertThat(scenario.observer().count("carriers", Outcome.NO_MATCH)).isEqualTo(1);
            assertThat(scenario.commandCount()).isEqualTo(1);
            // And the record completes with SUCCESS
            assertThat(scenario.completions().outcomesOf(record)).containsExactly(KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void retryingNoMatchBesideAnAmbiguityDeadLettersWithNoRetry(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "no-match-beside-ambiguity")) {
            // Given the two-signal source with onNoMatch RETRY, no returns instance, and two orders instances on the
            // same
            // key in two scopes
            Feed<ShipmentDelivered> carriers =
                    twoContracts(scenario, "carriers", SignalNoMatch.RETRY).retry(2, LONG_BACKOFF_MS);
            scenario.start();
            String shared = scenario.ref("PAY-77");
            Waiting acme = scenario.waitingOrder("acme", shared);
            Waiting globex = scenario.waitingOrder("globex", shared);

            // When the test produces one record
            Produced record = carriers.produce(scenario.delivered("1", shared));

            // Then the record is in the dead-letter topic, published at once and never scheduled for a retry
            scenario.awaitSettled(record, SourceScenario.PROMPT);
            assertThat(scenario.completions().outcomesOf(record)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(carriers.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            // And the observer saw the ambiguity and the no-match, and no command exists for either contract
            assertThat(scenario.observer().outcomesOf("carriers")).contains(Outcome.AMBIGUOUS, Outcome.NO_MATCH);
            scenario.awaitIdle(acme, globex);
            assertThat(scenario.commandCount()).isZero();
        }
    }

    @Test
    void scopeFunctionDeliversOnlyToItsScopeAndAnEmptyAnswerAttemptsNoMatch(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "scope")) {
            // Given instances in acme and globex waiting on one key, and a source whose scope is the x-scope header
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .scope(message -> message.header("x-scope"))
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String shared = scenario.ref("PAY-77");
            Waiting acme = scenario.waitingOrder("acme", shared);
            Waiting globex = scenario.waitingOrder("globex", shared);

            // When the test produces a record with x-scope acme, then a record without the header
            Produced scoped = carriers.produce(null, scenario.delivered("1", shared), Headers.of("x-scope", "acme"));
            Produced unscoped = carriers.produce(scenario.delivered("2", shared));
            scenario.awaitDelivered(scoped, acme, globex);
            scenario.awaitDelivered(unscoped, acme, globex);

            // Then only the acme instance received a command
            assertThat(scenario.commands(acme)).hasSize(1);
            assertThat(scenario.commands(globex)).isEmpty();
            // And the second record created no command, attempted no match, and the observer saw one SCOPE_EMPTY
            assertThat(scenario.observer().outcomesOf("carriers"))
                    .containsExactlyInAnyOrder(Outcome.APPLIED, Outcome.SCOPE_EMPTY);
            // And both records completed with SUCCESS
            assertThat(scenario.completions().outcomesOf(scoped)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.completions().outcomesOf(unscoped)).containsExactly(KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void recordRejectedByTheFilterCommitsWithNoRetryAndIsReportedAsIgnored(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "filter")) {
            // Given a source whose filter keeps the records with x-type delivered, keyed, with the default onNoMatch
            // and a long retry backoff, and a waiting instance
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.filter(KafkaRecordFilter.headerEquals("x-type", "delivered"))
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces a record with x-type other, then a record with x-type delivered
            Produced rejected =
                    carriers.produce(null, scenario.delivered("1", order.key()), Headers.of("x-type", "other"));
            Produced accepted =
                    carriers.produce(null, scenario.delivered("2", order.key()), Headers.of("x-type", "delivered"));

            // Then the first record is committed with no retry and no dead-letter entry
            RecordingInterceptor.Completion settled = scenario.awaitSettled(rejected, SourceScenario.PROMPT);
            assertThat(SourceScenario.COMMITTED_WITHOUT_RETRY).contains(settled.outcome());
            assertThat(scenario.completions().outcomesOf(rejected)).hasSize(1);
            assertThat(carriers.committed(rejected)).isTrue();
            assertThat(carriers.dlq().read()).isEmpty();
            // And the observer saw one IGNORED event carrying the source name and transport kafka, with no contract and
            // no signal name, and made no delivery for the rejected record
            assertThat(scenario.observer().eventsOf("carriers"))
                    .contains(new SignalSourceEvent("carriers", "kafka", null, null, null, Outcome.IGNORED));
            // And the second record is handled within 5 seconds and resumes the instance
            scenario.awaitSettled(accepted, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.observer().outcomesOf("carriers"))
                    .containsExactlyInAnyOrder(Outcome.IGNORED, Outcome.APPLIED);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    /** A row of the table of refused input: the record to produce and the text that must not leak. */
    record Refused(String label, java.util.function.BiFunction<SourceScenario, String, Refusal> input) {

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The record a row produces.
     *
     * @param message the message
     * @param headers the headers
     * @param leak a fragment of the input that must appear in no dead-letter header and no log record
     * @param accepted whether the source accepts the input and records a command
     */
    record Refusal(
            ShipmentDelivered message,
            java.util.Map<String, String> headers,
            @Nullable String leak,
            boolean accepted) {}

    static Stream<Arguments> refusedRows() {
        return Stream.of(
                Arguments.of(new Refused(
                        "a carrierRef of 256 characters",
                        (scenario, ref) -> new Refusal(
                                scenario.delivered("1", "K".repeat(256)),
                                Headers.of("x-scope", "acme"),
                                "KKKKKKKKKK",
                                false))),
                Arguments.of(new Refused(
                        "a whitespace-only carrierRef",
                        (scenario, ref) -> new Refusal(
                                scenario.delivered("1", "   "), Headers.of("x-scope", "acme"), null, false))),
                Arguments.of(new Refused(
                        "a carrierRef with a NUL character",
                        (scenario, ref) -> new Refusal(
                                scenario.delivered("1", "PAY-\u0000-7"),
                                Headers.of("x-scope", "acme"),
                                "PAY-\u0000",
                                false))),
                Arguments.of(new Refused(
                        "a note with a NUL character mapped into the payload",
                        (scenario, ref) -> new Refusal(
                                scenario.delivered("1", ref).withNote("SECRET-\u0000-NOTE"),
                                Headers.of("x-scope", "acme"),
                                "SECRET-",
                                false))),
                Arguments.of(new Refused(
                        "an x-scope header with a control character",
                        (scenario, ref) -> new Refusal(
                                scenario.delivered("1", ref), Headers.of("x-scope", "ac\u0007me"), "\u0007", false))),
                Arguments.of(new Refused(
                        "a carrierRef of exactly 255 characters",
                        (scenario, ref) ->
                                new Refusal(scenario.delivered("1", ref), Headers.of("x-scope", "acme"), null, true))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedRows")
    void refusedKeyScopeOrPayloadDeadLettersAtOnceWithNoRetryAndNoPause(Refused row, Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "refused")) {
            // Given one source shape, a long retry backoff, and one waiting instance for the row
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .scope(message -> message.header("x-scope"))
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(event -> DeliveryConfirmed.noting(event.note()))
                                    .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            String ref = scenario.ref("C").concat("-").concat("X".repeat(255)).substring(0, 255);
            Waiting waiting = scenario.waitingOrder("acme", ref);
            Refusal refusal = row.input().apply(scenario, ref);

            // When the test produces the row's record, then a good record on the same topic
            Produced produced = carriers.produce(null, refusal.message(), refusal.headers());
            Produced good = carriers.produce(
                    null, scenario.delivered("2", scenario.ref("PAY-GOOD")), Headers.of("x-scope", "acme"));

            if (refusal.accepted()) {
                // Then the control row records one command and completes with SUCCESS
                scenario.awaitDelivered(produced, waiting);
                assertThat(scenario.commands(waiting)).hasSize(1);
                assertThat(scenario.completions().outcomesOf(produced)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            } else {
                // Then the record is in the dead-letter topic, published at once, never scheduled for a retry, and no
                // command exists
                scenario.awaitSettled(produced, SourceScenario.PROMPT);
                assertThat(scenario.completions().outcomesOf(produced))
                        .containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
                scenario.awaitIdle(waiting);
                assertThat(scenario.commands(waiting)).isEmpty();
                assertThat(scenario.observer().count("carriers", Outcome.INVALID_INPUT))
                        .isEqualTo(1);
                // And the error header holds an exception class name only, and no part of the key, scope or payload
                List<DlqReader.Entry> deadLetters = carriers.dlq().await(1, SourceScenario.PROMPT);
                assertThat(deadLetters).hasSize(1);
                String errorType = deadLetters.get(0).errorType();
                assertThat(errorType).matches("[A-Za-z0-9_.$]+");
                if (refusal.leak() != null) {
                    assertThat(deadLetters.get(0).frameworkHeaders()).doesNotContain(refusal.leak());
                    assertThat(scenario.logs()).noneMatch(line -> line.contains(refusal.leak()));
                }
            }
            // And the good record is handled within 5 seconds, so no pause occurred
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            assertThat(scenario.completions().outcomesOf(good)).containsExactly(KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void filterThatThrowsDeadLettersAtOnceWithNoRetryAndNoPause(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "filter-throws")) {
            // Given a source whose filter throws for the key poison, naming a secret, keyed, with the default
            // onNoMatch,
            // a long retry backoff and a waiting instance
            String secret = scenario.ref("PAY-77");
            KafkaRecordFilter poisonKeyFilter = (key, headers) -> {
                if ("poison".equals(key)) {
                    throw new IllegalStateException(secret + " is secret");
                }
                return true;
            };
            Feed<ShipmentDelivered> carriers = scenario.carriers("carriers", source -> source.filter(poisonKeyFilter)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, LONG_BACKOFF_MS);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-1"));

            // When the test produces a record with the key poison, then a record with another key for the waiting
            // instance
            Produced poison = carriers.produce("poison", scenario.delivered("1", order.key()), java.util.Map.of());
            Produced good = carriers.produce("fine", scenario.delivered("2", order.key()), java.util.Map.of());

            // Then the first record is in the dead-letter topic exactly once, with the class name alone
            scenario.awaitSettled(poison, SourceScenario.PROMPT);
            List<DlqReader.Entry> deadLetters = carriers.dlq().await(1, SourceScenario.PROMPT);
            assertThat(deadLetters).hasSize(1);
            assertThat(deadLetters.get(0).errorType()).isEqualTo("IllegalStateException");
            assertThat(deadLetters.get(0).frameworkHeaders()).doesNotContain("PAY-77");
            // And the consumer published it at once, never scheduled a retry, and no delivery call was made for it
            assertThat(scenario.completions().outcomesOf(poison)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(scenario.observer().outcomesOf("carriers")).contains(Outcome.STAGE_FAILED);
            // And no log record holds the secret
            assertThat(scenario.logs()).noneMatch(line -> line.contains("PAY-77"));
            // And the second record is handled within 5 seconds and resumes the instance, so no pause occurred
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.observer().outcomesOf("carriers"))
                    .containsExactlyInAnyOrder(Outcome.STAGE_FAILED, Outcome.APPLIED);
        }
    }

    // --- the Given of several tests ---

    /** A source that names the orders signal and the returns signal, both keyed by the carrier reference. */
    private static Feed<ShipmentDelivered> twoContracts(
            SourceScenario scenario, String name, @Nullable SignalNoMatch onNoMatch) {
        return scenario.carriers(name, source -> {
            KafkaSignals<ShipmentDelivered> stages = onNoMatch == null ? source : source.onNoMatch(onNoMatch);
            return stages.dedupBy(ShipmentDelivered::eventId)
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .key(ShipmentDelivered::carrierRef)
                    .payload(SourceScenario::confirmation)
                    .signal(ReturnsContract.class, PARCEL_DELIVERED)
                    .key(ShipmentDelivered::carrierRef)
                    .payload(SourceScenario::confirmation)
                    .build();
        });
    }

    private static Produced produceNoting(
            Feed<ShipmentDelivered> feed, SourceScenario scenario, String ref, String note) {
        return feed.produce(scenario.delivered("1", ref).withNote(note));
    }
}
