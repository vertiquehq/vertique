// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.kafka.SourceScenario.CommandRow;
import dev.vertique.workflow.kafka.SourceScenario.DeliveryConfirmed;
import dev.vertique.workflow.kafka.SourceScenario.Feed;
import dev.vertique.workflow.kafka.SourceScenario.Headers;
import dev.vertique.workflow.kafka.SourceScenario.LookupScript;
import dev.vertique.workflow.kafka.SourceScenario.OrdersContract;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import dev.vertique.workflow.kafka.SourceScenario.Waiting;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, on a real broker and a real PostgreSQL engine, that a Kafka record becomes one recorded signal: it finds the
 * one waiting instance in its scope, a message delivered twice or after a crash is recorded once, the dedup key comes
 * from the source, the record's position or the replay headers, and the payload is the one the source maps.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaSignalDeliveryIT {

    @Test
    void keyRecordResumesTheOneWaitingInstanceInItsScope(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "key-record")) {
            // Given one OrderShipping instance in scope acme waiting on a carrier reference, and a source keyed by it
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces a ShipmentDelivered record for the reference
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);

            // Then the instance has applied the signal in scope acme and its history holds the mapped payload
            assertThat(scenario.instance(order).scope()).isEqualTo("acme");
            assertThat(scenario.instance(order).state().getString("deliveredAt"))
                    .isEqualTo(ShipmentDelivered.DELIVERED_AT);
            assertThat(scenario.history(order))
                    .anyMatch(entry -> entry.type().equals("SIGNAL_RECEIVED")
                            && entry.payload().contains(ShipmentDelivered.DELIVERED_AT));
        }
    }

    @Test
    void sameMessageDeliveredTwiceRecordsOneCommand(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "twice")) {
            // Given one waiting instance and a source that deduplicates on the event id
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces the same event, with the same event id, twice, at two offsets
            ShipmentDelivered event = scenario.delivered("1", ref);
            Produced first = carriers.produce(event);
            Produced second = carriers.produce(event);
            scenario.awaitDelivered(first, order);
            scenario.awaitDelivered(second, order);

            // Then one SIGNAL command and one correlated dedup row exist, and the instance resumed once
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.dedupRows(order)).hasSize(1);
            assertThat(scenario.signalsApplied(order)).isEqualTo(1);
            // And both records were completed with SUCCESS
            assertThat(scenario.completions().outcomesOf(first)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.completions().outcomesOf(second)).containsExactly(KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void ignoredRecordReplayedAfterTheWaitOpensIsStillIgnored(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "ignored")) {
            // Given a source that deduplicates on the event id and ignores a no-match
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");

            // When an event arrives before any instance waits for it, and is ignored
            Produced early = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitSettled(early, java.time.Duration.ofSeconds(30));

            // And: the instance then reaches its wait, and the same event is replayed at a new offset
            Waiting order = scenario.waitingOrder("acme", ref);
            Produced replay = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitSettled(replay, java.time.Duration.ofSeconds(30));

            // Then the replay records nothing, though the wait matches it now
            assertThat(scenario.commands(order)).isEmpty();
            assertThat(scenario.observer().events())
                    .extracting(event -> event.outcome())
                    .containsExactly(
                            dev.vertique.workflow.source.SignalSourceEvent.Outcome.NO_MATCH,
                            dev.vertique.workflow.source.SignalSourceEvent.Outcome.DEDUPLICATED);

            // And: a new event for the same reference does resume the instance
            Produced fresh = carriers.produce(scenario.delivered("2", ref));
            scenario.awaitDelivered(fresh, order);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    @Test
    void redeliveredOffsetRecordsOneCommandWhenTheSourceNamesNoDedupKey(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "offset")) {
            // Given a source with a key and no dedupBy, and one waiting instance
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces one record, waits for it to complete, resets the group's committed offset to that
            // record through the admin client and restarts the consumer
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);
            carriers.rewindTo(record);
            scenario.completions().awaitCompletions(record, 2, SourceScenario.PROMPT.multipliedBy(4));
            scenario.awaitIdle(order);

            // Then the record was consumed a second time at the same topic, partition and offset, and completed with
            // SUCCESS again
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.SUCCESS, KafkaTerminalOutcome.SUCCESS);
            // And one command and one dedup row exist
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.dedupRows(order)).hasSize(1);
        }
    }

    @Test
    void dlqSourceHeadersCountOnlyOnADlqReplaySource(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "dlq-headers")) {
            // Given two sources on separate topics, both keyed with no dedupBy: a plain one, and one declaring
            // dlqReplay
            Feed<ShipmentDelivered> plain =
                    scenario.carriers("plain", source -> source.signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            Feed<ShipmentDelivered> replay = scenario.carriers("replay", source -> source.dlqReplay()
                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                    .key(ShipmentDelivered::carrierRef)
                    .payload(SourceScenario::confirmation)
                    .build());
            scenario.start();
            // And, on each topic, three instances waiting on keys K1, K2 and K3
            Waiting[] onPlain = waitingOn(scenario, "PK");
            Waiting[] onReplay = waitingOn(scenario, "RK");

            // When, to each topic, the test produces a plain record for K1 at offset 0, a record for K2 at offset 1
            // that
            // names offset 0 of the same topic in the dead-letter source headers, and a record for K3 at offset 2
            // whose headers name offset 7
            Produced[] sentToPlain = produceThree(scenario, plain, onPlain);
            Produced[] sentToReplay = produceThree(scenario, replay, onReplay);
            for (int index = 0; index < 3; index++) {
                scenario.awaitDelivered(sentToPlain[index]);
                scenario.awaitDelivered(sentToReplay[index]);
            }
            scenario.awaitIdle(onPlain);
            scenario.awaitIdle(onReplay);

            // Then on the plain source all three are recorded, each with a dedup key built from its own offset
            for (int index = 0; index < 3; index++) {
                assertThat(scenario.commands(onPlain[index])).hasSize(1);
                assertThat(scenario.dedupRows(onPlain[index]))
                        .extracting(SourceScenario.DedupRow::key)
                        .containsExactly(WorkflowDedupScopes.correlatedKey(positionKey(sentToPlain[index])));
            }
            // And on the replay source the first two records claim the same dedup key and run concurrently: exactly one
            // of their instances has one command, recorded under offset 0, and the other is unchanged and still waiting
            List<Integer> winners = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                if (!scenario.commands(onReplay[index]).isEmpty()) {
                    winners.add(index);
                }
            }
            assertThat(winners).hasSize(1);
            int winner = winners.get(0);
            int loser = 1 - winner;
            assertThat(scenario.commands(onReplay[winner])).hasSize(1);
            assertThat(scenario.dedupRows(onReplay[winner]))
                    .extracting(SourceScenario.DedupRow::key)
                    .containsExactly(WorkflowDedupScopes.correlatedKey(replay.topic() + ":0:0"));
            assertThat(scenario.commands(onReplay[loser])).isEmpty();
            assertThat(scenario.instance(onReplay[loser]).status()).isEqualTo("WAITING");
            assertThat(scenario.observer().outcomesOf("replay"))
                    .containsExactlyInAnyOrder(Outcome.APPLIED, Outcome.DEDUPLICATED, Outcome.APPLIED);
            // And the third is recorded with a dedup key built from offset 7
            assertThat(scenario.dedupRows(onReplay[2]))
                    .extracting(SourceScenario.DedupRow::key)
                    .containsExactly(WorkflowDedupScopes.correlatedKey(replay.topic() + ":0:7"));
            // And on both sources every provenance names the consumed record's own topic, partition and offset
            for (int index = 0; index < 3; index++) {
                assertProvenance(scenario.onlyCommand(onPlain[index]), "plain", sentToPlain[index]);
            }
            assertProvenance(scenario.onlyCommand(onReplay[winner]), "replay", sentToReplay[winner]);
            assertProvenance(scenario.onlyCommand(onReplay[2]), "replay", sentToReplay[2]);
            assertThat(sentToReplay[2].offset()).isEqualTo(2);
        }
    }

    @Test
    void crashAfterRecordingBeforeCommitResumesTheInstanceOnce(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "crash").losingAcknowledgements()) {
            // Given one waiting instance, and a pool that completes a transaction and then fails its future once
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);
            scenario.loseOneAcknowledgement();

            // When the test produces one record
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);

            // Then the first attempt fails after its commit and is retried, and the redelivery answers a duplicate
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.observer().outcomesOf("carriers")).last().isEqualTo(Outcome.DEDUPLICATED);
            // And exactly one command exists for the event across all instances, the instance resumed once, and the
            // record is committed
            assertThat(scenario.commandCount()).isEqualTo(1);
            assertThat(scenario.signalsApplied(order)).isEqualTo(1);
            assertThat(carriers.committed(record)).isTrue();
        }
    }

    @Test
    void consumerRestartedAfterRecordingBeforeCommitResumesTheInstanceOnce(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "restart").losingAcknowledgements()) {
            // Given one waiting instance, a source that deduplicates on the event id with a retry far longer than the
            // test, and a pool that completes a transaction and then fails its future once
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, 600_000);
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);
            scenario.loseOneAcknowledgement();

            // When the test produces one record, the signal is recorded and the offset is not committed, and the
            // consumers are stopped and started again
            Produced record = carriers.produce(scenario.delivered("1", ref));
            assertThat(scenario.completions().awaitCompletions(record, 1, SourceScenario.PROMPT))
                    .as("the first attempt ended")
                    .isTrue();
            assertThat(scenario.completions().outcomesOf(record)).containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED);
            assertThat(scenario.commands(order)).hasSize(1);
            carriers.restartConsumers();
            scenario.awaitDelivered(record, order);

            // Then the new consumer was handed the record again, answered a duplicate before any match and committed
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.observer().outcomesOf("carriers")).last().isEqualTo(Outcome.DEDUPLICATED);
            assertThat(carriers.committed(record)).isTrue();

            // And exactly one command exists for the event and the instance resumed once
            assertThat(scenario.commandCount()).isEqualTo(1);
            assertThat(scenario.signalsApplied(order)).isEqualTo(1);
        }
    }

    @Test
    void lookupAnsweringDifferentlyOnRedeliveryStillRecordsOnce(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "lookup").losingAcknowledgements()) {
            // Given two live instances with business keys A and B, and a business-key lookup that answers A on its
            // first
            // call and B on later calls
            String keyA = scenario.ref("A");
            String keyB = scenario.ref("B");
            LookupScript lookup = LookupScript.answers(keyA, keyB);
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .businessKeyFrom(lookup.lookup())
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            Waiting orderA = scenario.waitingOrder("acme", scenario.ref("REF-A"), keyA);
            Waiting orderB = scenario.waitingOrder("acme", scenario.ref("REF-B"), keyB);
            scenario.loseOneAcknowledgement();

            // When the test produces one record
            Produced record = carriers.produce(scenario.delivered("1", scenario.ref("ANY")));
            scenario.awaitDelivered(record, orderA, orderB);

            // Then the first attempt records for A and loses its acknowledgement, and the redelivery answers a
            // duplicate
            // before any match
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.observer().outcomesOf("carriers")).last().isEqualTo(Outcome.DEDUPLICATED);
            // And one command exists, on A, and B is unchanged
            assertThat(scenario.commands(orderA)).hasSize(1);
            assertThat(scenario.commands(orderB)).isEmpty();
            assertThat(scenario.instance(orderB).status()).isEqualTo("WAITING");
        }
    }

    @Test
    void payloadMappingHandsTheWaitTheMappedPayload(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "payload")) {
            // Given a message type with extra fields and a payload mapping that keeps only the delivery time
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(event -> new DeliveryConfirmed(event.deliveredAt(), null))
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces a record with the extra fields set
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);

            // Then the instance's state holds the delivery time of the mapped payload and no other field of the message
            JsonObject state = scenario.instance(order).state();
            assertThat(state.getString("deliveredAt")).isEqualTo(ShipmentDelivered.DELIVERED_AT);
            assertThat(state.fieldNames()).containsExactlyInAnyOrder("carrierRef", "deliveredAt");
            // And the stored command payload holds only the mapped fields
            CommandRow command = scenario.onlyCommand(order);
            assertThat(command.payload())
                    .contains(ShipmentDelivered.DELIVERED_AT)
                    .doesNotContain("weightGrams")
                    .doesNotContain(String.valueOf(ShipmentDelivered.WEIGHT))
                    .doesNotContain(ShipmentDelivered.CARRIER)
                    .doesNotContain("left at the door");
        }
    }

    @Test
    void nonNumericReplayHeaderDeadLettersAtOnceOnADlqReplaySource(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "replay-header")) {
            // Given a source declaring dlqReplay with a key and no dedupBy, a waiting instance, and a long retry
            // backoff
            Feed<ShipmentDelivered> replay = scenario.carriers("replay", source -> source.dlqReplay()
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, 30_000);
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces a record carrying a non-numeric source offset, then a plain good record
            Produced bad =
                    replay.produce(null, scenario.delivered("1", ref), Headers.dlqRecord(replay.topic(), 0, "abc"));
            Produced good = replay.produce(scenario.delivered("2", ref));

            // Then the first record has no default dedup key, so it is in the dead-letter topic with the error type
            // BlankDedupKey, was published there at once and never scheduled for a retry
            scenario.awaitSettled(bad, SourceScenario.PROMPT);
            assertThat(scenario.completions().outcomesOf(bad)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            List<DlqReader.Entry> deadLetters = replay.dlq().await(1, SourceScenario.PROMPT);
            assertThat(deadLetters).hasSize(1);
            assertThat(deadLetters.get(0).errorType()).isEqualTo("BlankDedupKey");
            // And the observer saw one INVALID_INPUT event, and the good record is handled within 5 seconds
            assertThat(scenario.observer().count("replay", Outcome.INVALID_INPUT))
                    .isEqualTo(1);
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    @Test
    void badReplayHeaderFailsOnlyARecordThatNeedsTheDefaultKey(io.vertx.core.Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "replay-header-kept")) {
            // Given two sources declaring dlqReplay, one gated by a when stage and one naming its dedupBy, a waiting
            // instance, and a long retry backoff
            Feed<ShipmentDelivered> gated = scenario.carriers("gated", source -> source.dlqReplay()
                            .when(event -> event.type().equals("delivered"))
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, 30_000);
            Feed<ShipmentDelivered> named = scenario.carriers("named", source -> source.dlqReplay()
                            .dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, 30_000);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-78"));

            // When the test produces, to each, a record whose source offset header is not a number: to the gated source
            // one of a type its when stage rejects, to the other one for the waiting instance
            Produced rejected = gated.produce(
                    null,
                    scenario.delivered("1", order.key()).withType("shipped"),
                    Headers.dlqRecord(gated.topic(), 0, "abc"));
            Produced recorded = named.produce(
                    null, scenario.delivered("2", order.key()), Headers.dlqRecord(named.topic(), 0, "abc"));

            // Then both succeed, nothing is dead-lettered, and neither is reported as invalid input
            scenario.awaitSettled(rejected, SourceScenario.PROMPT);
            scenario.awaitDelivered(recorded, order);
            assertThat(scenario.completions().outcomesOf(rejected)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            assertThat(scenario.completions().outcomesOf(recorded)).containsExactly(KafkaTerminalOutcome.SUCCESS);
            assertThat(gated.dlq().read()).isEmpty();
            assertThat(named.dlq().read()).isEmpty();
            assertThat(scenario.observer().count("gated", Outcome.INVALID_INPUT))
                    .isZero();
            assertThat(scenario.observer().count("named", Outcome.INVALID_INPUT))
                    .isZero();
            // And the rejected record is reported as ignored, and the other recorded its command once
            assertThat(scenario.observer().count("gated", Outcome.IGNORED)).isEqualTo(1);
            assertThat(scenario.commands(order)).hasSize(1);
        }
    }

    private static Waiting[] waitingOn(SourceScenario scenario, String prefix) {
        Waiting[] waiting = new Waiting[3];
        for (int index = 0; index < 3; index++) {
            waiting[index] = scenario.waitingOrder("acme", scenario.ref(prefix + (index + 1)));
        }
        return waiting;
    }

    private static Produced[] produceThree(SourceScenario scenario, Feed<ShipmentDelivered> feed, Waiting[] waiting) {
        Produced[] sent = new Produced[3];
        sent[0] = feed.produce(scenario.delivered("1", waiting[0].key()));
        sent[1] = feed.produce(
                null,
                scenario.delivered("2", waiting[1].key()),
                Headers.dlqRecord(feed.topic(), sent[0].partition(), sent[0].offset()));
        sent[2] = feed.produce(null, scenario.delivered("3", waiting[2].key()), Headers.dlqRecord(feed.topic(), 0, 7));
        return sent;
    }

    private static String positionKey(Produced position) {
        return position.topic() + ":" + position.partition() + ":" + position.offset();
    }

    private static void assertProvenance(CommandRow command, String source, Produced position) {
        JsonObject provenance = command.provenanceJson();
        assertThat(provenance.getString("transport")).isEqualTo("kafka");
        assertThat(provenance.getString("source")).isEqualTo(source);
        JsonObject attributes = provenance.getJsonObject("attributes");
        assertThat(attributes.getString("topic")).isEqualTo(position.topic());
        assertThat(attributes.getString("partition")).isEqualTo(Integer.toString(position.partition()));
        assertThat(attributes.getString("offset")).isEqualTo(Long.toString(position.offset()));
    }
}
