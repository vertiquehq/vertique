// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceScenario.SHIPMENT_DELIVERED;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.workflow.kafka.SourceScenario.CommandRow;
import dev.vertique.workflow.kafka.SourceScenario.Feed;
import dev.vertique.workflow.kafka.SourceScenario.Headers;
import dev.vertique.workflow.kafka.SourceScenario.Identities;
import dev.vertique.workflow.kafka.SourceScenario.OrdersContract;
import dev.vertique.workflow.kafka.SourceScenario.Overlap;
import dev.vertique.workflow.kafka.SourceScenario.Produced;
import dev.vertique.workflow.kafka.SourceScenario.ShipmentDelivered;
import dev.vertique.workflow.kafka.SourceScenario.Stalled;
import dev.vertique.workflow.kafka.SourceScenario.Waiting;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves, on a real broker and a real PostgreSQL engine, whose identity a recorded signal carries and which context it
 * runs in: the source's system identity or the one {@code dispatchAs} names, a refusal that dead-letters once, the
 * lookup timeout, concurrent lookups on a foreign context, and the correlation header that only an opted-in source
 * restores, and only its id.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class KafkaSignalIdentityIT {

    @Test
    void commandNamesTheSystemIdentityAndRecordsTheConsumedPosition(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "identity")) {
            // Given a source named carriers and one waiting instance
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            String ref = scenario.ref("PAY-77");
            Waiting order = scenario.waitingOrder("acme", ref);

            // When the test produces a record and reads its topic, partition and offset from the producer's metadata
            Produced record = carriers.produce(scenario.delivered("1", ref));
            scenario.awaitDelivered(record, order);

            // Then the SIGNAL command's actor is SYSTEM with the reason kafka source: carriers, and is verified
            CommandRow command = scenario.onlyCommand(order);
            assertThat(command.actorKind()).isEqualTo("SYSTEM");
            assertThat(command.actorReason()).isEqualTo("kafka source: carriers");
            assertThat(command.attribution()).isEqualTo("VERIFIED");
            // And the provenance holds the source, the topic, the partition and the offset
            JsonObject provenance = command.provenanceJson();
            assertThat(provenance.getString("source")).isEqualTo("carriers");
            assertThat(provenance.getString("transport")).isEqualTo("kafka");
            JsonObject attributes = provenance.getJsonObject("attributes");
            assertThat(attributes.getString("topic")).isEqualTo(record.topic());
            assertThat(attributes.getString("partition")).isEqualTo(Integer.toString(record.partition()));
            assertThat(attributes.getString("offset")).isEqualTo(Long.toString(record.offset()));
        }
    }

    @Test
    void dispatchAsMapsAHeaderToAUserAndAMissingHeaderKeepsTheSystemIdentity(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "dispatch-as")) {
            // Given a source whose dispatchAs maps the x-user header to a user, and two waiting instances
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .dispatchAs(message -> message.header("x-user")
                                    .map(user -> Identities.answer(Identities.user(user)))
                                    .orElseGet(Identities::none))
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            Waiting named = scenario.waitingOrder("acme", scenario.ref("PAY-1"));
            Waiting unnamed = scenario.waitingOrder("acme", scenario.ref("PAY-2"));

            // When the test produces one record with x-user alice and one without the header
            Produced withUser =
                    carriers.produce(null, scenario.delivered("1", named.key()), Headers.of("x-user", "alice"));
            Produced withoutUser = carriers.produce(scenario.delivered("2", unnamed.key()));
            scenario.awaitDelivered(withUser, named);
            scenario.awaitDelivered(withoutUser, unnamed);

            // Then the first command names user alice and the second names the system identity
            CommandRow first = scenario.onlyCommand(named);
            assertThat(first.actorKind()).isEqualTo("USER");
            assertThat(first.actorId()).isEqualTo("alice");
            CommandRow second = scenario.onlyCommand(unnamed);
            assertThat(second.actorKind()).isEqualTo("SYSTEM");
            assertThat(second.actorReason()).isEqualTo("kafka source: carriers");
        }
    }

    @Test
    void anonymousIdentityOrActorMapperRefusalDeadLettersWithoutRetryOrPause(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "refused-identity")) {
            // Given one source whose dispatchAs answers an anonymous identity for a record that carries x-anon, and a
            // second source whose dispatchAs answers an identity the actor mapper refuses, with a long retry backoff
            Feed<ShipmentDelivered> anonymous = scenario.carriers("anonymous", source -> source.dedupBy(
                                    ShipmentDelivered::eventId)
                            .dispatchAs(message -> message.header("x-anon").isPresent()
                                    ? Identities.answer(Identities.anonymous())
                                    : Identities.none())
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, 30_000);
            Feed<ShipmentDelivered> mapper = scenario.carriers(
                            "mapper", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .dispatchAs(message -> Identities.answer(Identities.refusedByMapper()))
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, 30_000);
            scenario.start();
            Waiting forAnonymous = scenario.waitingOrder("acme", scenario.ref("PAY-1"));
            Waiting forMapper = scenario.waitingOrder("acme", scenario.ref("PAY-2"));

            // When the test produces one record to each source, then a good record to the first
            Produced anonymousRecord =
                    anonymous.produce(null, scenario.delivered("1", forAnonymous.key()), Headers.of("x-anon", "yes"));
            Produced mapperRecord = mapper.produce(scenario.delivered("2", forMapper.key()));
            Produced good = anonymous.produce(scenario.delivered("3", forAnonymous.key()));

            // Then, for each, no command exists, the record is in the dead-letter topic, and it was published there at
            // once, never scheduled for a retry
            scenario.awaitSettled(anonymousRecord, SourceScenario.PROMPT);
            scenario.awaitSettled(mapperRecord, SourceScenario.PROMPT);
            assertThat(scenario.completions().outcomesOf(anonymousRecord))
                    .containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(scenario.completions().outcomesOf(mapperRecord))
                    .containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            assertThat(anonymous.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            assertThat(mapper.dlq().await(1, SourceScenario.PROMPT)).hasSize(1);
            // And the good record is handled within 5 seconds, so no pause occurred
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            scenario.awaitIdle(forAnonymous, forMapper);
            assertThat(scenario.commands(forMapper)).isEmpty();
            assertThat(scenario.commands(forAnonymous)).hasSize(1);
            // And the first source reported one REFUSED_IDENTITY event
            assertThat(scenario.observer().count("anonymous", Outcome.REFUSED_IDENTITY))
                    .isEqualTo(1);
            assertThat(scenario.observer().count("mapper", Outcome.REFUSED_IDENTITY))
                    .isEqualTo(1);
        }
    }

    @Test
    void dispatchAsThatNeverSettlesFailsAtTheLookupTimeoutAndRetries(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "stalled")) {
            // Given a source with a lookup timeout of 200 ms whose dispatchAs never completes for the first record and
            // answers empty for later ones
            Stalled stalled = Stalled.once();
            scenario.onClose(stalled::fail);
            Feed<ShipmentDelivered> carriers = scenario.carriers(
                            "carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                                    .lookupTimeoutMs(200)
                                    .dispatchAs(stalled.dispatchAs())
                                    .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                                    .key(ShipmentDelivered::carrierRef)
                                    .payload(SourceScenario::confirmation)
                                    .build())
                    .retry(2, 50);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces one record
            long startedAt = System.nanoTime();
            Produced record = carriers.produce(scenario.delivered("1", order.key()));

            // Then the first attempt fails after about 200 ms and is scheduled for a retry
            assertThat(scenario.completions().awaitCompletions(record, 1, SourceScenario.PROMPT))
                    .isTrue();
            Duration firstAttempt = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(scenario.completions().outcomesOf(record).get(0))
                    .isEqualTo(KafkaTerminalOutcome.RETRY_SCHEDULED);
            assertThat(firstAttempt).isGreaterThanOrEqualTo(Duration.ofMillis(150));
            // And the retry, whose dispatchAs answers, records one command
            scenario.awaitDelivered(record, order);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.completions().outcomesOf(record))
                    .containsExactly(KafkaTerminalOutcome.RETRY_SCHEDULED, KafkaTerminalOutcome.SUCCESS);
        }
    }

    @Test
    void dispatchAsFuturesCompletedOnAForeignContextKeepEachRecordsIdentityAndScope(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "overlap")) {
            // Given a two-partition topic and a source whose scope is the x-scope header, whose dispatchAs holds each
            // future open until the test completes it from a foreign context, and whose key reads the ambient scope
            Overlap overlap = scenario.new Overlap();
            List<String> scopesSeen = Collections.synchronizedList(new ArrayList<>());
            String base = scenario.ref("PAY");
            Feed<ShipmentDelivered> carriers = scenario.carriers("carriers", source -> source.dedupBy(
                                    ShipmentDelivered::eventId)
                            .scope(message -> message.header("x-scope"))
                            .dispatchAs(overlap.dispatchAs())
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .keyFrom(message -> {
                                Optional<String> scope = scenario.ambientScope();
                                scope.ifPresent(scopesSeen::add);
                                return io.vertx.core.Future.succeededFuture(scope.map(value -> base + "-" + value));
                            })
                            .payload(SourceScenario::confirmation)
                            .build())
                    .partitions(2);
            scenario.start();
            Waiting acme = scenario.waitingOrder("acme", base + "-acme");
            Waiting globex = scenario.waitingOrder("globex", base + "-globex");

            // When the test produces record A (x-scope acme, user alice) and record B (x-scope globex, user bob) and
            // releases the futures
            Produced recordA = carriers.produce(
                    null,
                    scenario.delivered("a", "ignored"),
                    Headers.and(Headers.of("x-scope", "acme"), Headers.of("x-user", "alice")),
                    0);
            Produced recordB = carriers.produce(
                    null,
                    scenario.delivered("b", "ignored"),
                    Headers.and(Headers.of("x-scope", "globex"), Headers.of("x-user", "bob")),
                    1);
            overlap.release();
            scenario.awaitDelivered(recordA, acme);
            scenario.awaitDelivered(recordB, globex);

            // Then the command on the acme instance names alice and the one on the globex instance names bob
            assertThat(scenario.commands(acme)).hasSize(1);
            assertThat(scenario.onlyCommand(acme).actorId()).isEqualTo("alice");
            assertThat(scenario.commands(globex)).hasSize(1);
            assertThat(scenario.onlyCommand(globex).actorId()).isEqualTo("bob");
            // And each lookup saw its own message's scope
            assertThat(scopesSeen).containsExactlyInAnyOrder("acme", "globex");
        }
    }

    @Test
    void correlationHeaderIsRestoredOnlyOnOptInAndAMalformedIdentityHeaderNeverDegradesTheCommand(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "correlation")) {
            // Given two sources, one plain and one with restoreCorrelationFromHeaders, and a waiting instance for each
            Feed<ShipmentDelivered> plain =
                    scenario.carriers("plain", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            Feed<ShipmentDelivered> restoring =
                    scenario.carriers("restoring", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .restoreCorrelationFromHeaders()
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            Waiting forPlain = scenario.waitingOrder("acme", scenario.ref("PAY-1"));
            Waiting forRestoring = scenario.waitingOrder("acme", scenario.ref("PAY-2"));
            Map<String, String> headers =
                    Headers.durable(Headers.correlation("C-77", "R-9"), Headers.malformedIdentitySnapshot());

            // When the test produces one record carrying a valid correlation header and a malformed identity header to
            // each source
            Produced toPlain = plain.produce(null, scenario.delivered("1", forPlain.key()), headers);
            Produced toRestoring = restoring.produce(null, scenario.delivered("2", forRestoring.key()), headers);
            scenario.awaitDelivered(toPlain, forPlain);
            scenario.awaitDelivered(toRestoring, forRestoring);

            // Then the plain source's command holds no C-77
            CommandRow plainCommand = scenario.onlyCommand(forPlain);
            assertThat(plainCommand.context() == null ? "" : plainCommand.context())
                    .doesNotContain("C-77");
            // And the opt-in source's holds correlation C-77 with a request id other than R-9, the id alone restored
            CommandRow restoringCommand = scenario.onlyCommand(forRestoring);
            assertThat(restoringCommand.context()).isNotNull().contains("C-77");
            assertThat(restoringCommand.context()).doesNotContain("R-9");
            // And on both commands the attribution is verified, not degraded, and the malformed identity header was not
            // applied
            assertThat(plainCommand.attribution()).isEqualTo("VERIFIED");
            assertThat(restoringCommand.attribution()).isEqualTo("VERIFIED");
            assertThat(restoringCommand.actorKind()).isEqualTo("SYSTEM");
            assertThat(restoringCommand.context()).doesNotContain("not-base64");
        }
    }

    @Test
    void dispatchAsFailingWithTheRefusalMarkerDeadLettersOnceWithNoRetryOrPauseAndCarriesTheTypeOnly(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "refusal")) {
            // Given a source whose dispatchAs fails with the refusal marker TokenRejected for a record carrying x-user
            // mallory and answers empty for others, and a long retry backoff
            Feed<ShipmentDelivered> carriers = scenario.carriers("carriers", source -> source.dedupBy(
                                    ShipmentDelivered::eventId)
                            .dispatchAs(message -> message.header("x-user").isPresent()
                                    ? Identities.refusing("TokenRejected")
                                    : Identities.none())
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build())
                    .retry(2, 30_000);
            scenario.start();
            Waiting order = scenario.waitingOrder("acme", scenario.ref("PAY-77"));

            // When the test produces the x-user mallory record, then a record without the header
            Produced refused =
                    carriers.produce(null, scenario.delivered("1", order.key()), Headers.of("x-user", "mallory"));
            Produced good = carriers.produce(scenario.delivered("2", order.key()));

            // Then the first record is in the dead-letter topic exactly once with the error type TokenRejected alone
            scenario.awaitSettled(refused, SourceScenario.PROMPT);
            List<DlqReader.Entry> deadLetters = carriers.dlq().await(1, SourceScenario.PROMPT);
            assertThat(deadLetters).hasSize(1);
            assertThat(deadLetters.get(0).errorType()).isEqualTo("TokenRejected");
            assertThat(deadLetters.get(0).frameworkHeaders()).doesNotContain("mallory");
            // And it was published there at once, never scheduled for a retry, and no command exists for it
            assertThat(scenario.completions().outcomesOf(refused)).containsExactly(KafkaTerminalOutcome.DLQ_PUBLISHED);
            // And the observer saw one REFUSED_IDENTITY event
            assertThat(scenario.observer().count("carriers", Outcome.REFUSED_IDENTITY))
                    .isEqualTo(1);
            // And the second record is handled within 5 seconds, so no pause occurred
            scenario.awaitSettled(good, SourceScenario.PROMPT);
            scenario.awaitIdle(order);
            assertThat(scenario.commands(order)).hasSize(1);
            assertThat(scenario.logs()).noneMatch(line -> line.contains("mallory"));
        }
    }

    @Test
    void oversizedMalformedOrIdentityCarryingHeaderRestoresNothingAndDoesNotFailTheRecord(Vertx vertx) {
        try (SourceScenario scenario = SourceScenario.open(vertx, "headers")) {
            // Given one source with restoreCorrelationFromHeaders and waiting instances on four keys
            Feed<ShipmentDelivered> carriers =
                    scenario.carriers("carriers", source -> source.dedupBy(ShipmentDelivered::eventId)
                            .restoreCorrelationFromHeaders()
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(ShipmentDelivered::carrierRef)
                            .payload(SourceScenario::confirmation)
                            .build());
            scenario.start();
            Waiting[] waiting = new Waiting[4];
            for (int index = 0; index < 4; index++) {
                waiting[index] = scenario.waitingOrder("acme", scenario.ref("PAY-" + index));
            }
            String lineBreakId = "C 77\nX";
            List<Map<String, String>> variants = List.of(
                    // a correlation header of 5000 characters
                    Headers.oversized(5000),
                    // a header whose value does not decode
                    Headers.rawCorrelation("{this is not json"),
                    // a header that decodes but whose correlation id holds a line break and a space
                    Headers.rawCorrelation(Headers.correlation(lineBreakId, "R-9")),
                    // no correlation header, but a valid identity snapshot header naming mallory
                    Headers.durable(null, Headers.identitySnapshot("mallory")));

            // When the test produces the four records
            List<Produced> sent = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                sent.add(carriers.produce(
                        null, scenario.delivered("h" + index, waiting[index].key()), variants.get(index)));
            }
            for (int index = 0; index < 4; index++) {
                scenario.awaitDelivered(sent.get(index), waiting[index]);
            }

            // Then all four complete with SUCCESS and each records one command
            for (int index = 0; index < 4; index++) {
                assertThat(scenario.completions().outcomesOf(sent.get(index)))
                        .containsExactly(KafkaTerminalOutcome.SUCCESS);
                assertThat(scenario.commands(waiting[index])).hasSize(1);
                CommandRow command = scenario.onlyCommand(waiting[index]);
                // And none stores the header's correlation id, each stored correlation is freshly seeded, no command
                // names mallory and each attribution is verified
                assertThat(command.context()).isNotNull().doesNotContain("R-9").doesNotContain("C 77");
                assertThat(command.context()).doesNotContain("xxxxxxxx");
                assertThat(command.actorKind()).isEqualTo("SYSTEM");
                assertThat(String.valueOf(command.actorId())).doesNotContain("mallory");
                assertThat(command.context()).doesNotContain("mallory");
                assertThat(command.attribution()).isEqualTo("VERIFIED");
            }
            // And no log record holds a header value
            assertThat(scenario.logs())
                    .noneMatch(line -> line.contains("xxxxxxxxxx")
                            || line.contains("this is not json")
                            || line.contains("C 77")
                            || line.contains("mallory"));
        }
    }
}
