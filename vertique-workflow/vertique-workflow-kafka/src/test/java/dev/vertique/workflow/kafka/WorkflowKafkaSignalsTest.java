// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import static dev.vertique.workflow.kafka.SourceFixture.DEFAULT_NAME;
import static dev.vertique.workflow.kafka.SourceFixture.GROUP;
import static dev.vertique.workflow.kafka.SourceFixture.SHIPMENT_DELIVERED;
import static dev.vertique.workflow.kafka.SourceFixture.TOPIC;
import static dev.vertique.workflow.kafka.SourceFixture.keyedByCarrier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.kafka.CommitStrategy;
import dev.vertique.kafka.ErrorStrategy;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaDeferredFailure;
import dev.vertique.kafka.KafkaMessage;
import dev.vertique.kafka.KafkaRecordFilter;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.KafkaRegistrationException;
import dev.vertique.kafka.KafkaTerminalFailure;
import dev.vertique.workflow.kafka.SourceFixture.Carriers;
import dev.vertique.workflow.kafka.SourceFixture.DeliveryConfirmed;
import dev.vertique.workflow.kafka.SourceFixture.OrdersContract;
import dev.vertique.workflow.kafka.SourceFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.SourceDisposition;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.services.source.SourceEnvelope;
import dev.vertique.workflow.services.source.WorkflowSignalSourceDelivery;
import dev.vertique.workflow.source.SignalSourceEvent;
import dev.vertique.workflow.source.SignalSourceEvent.Outcome;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Proves the Kafka source builder: what a built source binds, how it is named, which sources it
 * refuses at construction, what the resolved configuration may not change, and what its wrapped
 * filter reports. Nothing connects to a broker.
 */
class WorkflowKafkaSignalsTest {

    private static final String SECRET_KEY = "SECRET-KEY-7";

    /** One configuration of the built consumer, and the option a required strategy must name on it. */
    record Row(String label, JsonObject consumerConfig, String changedOption) {}

    @Test
    void builtSourceIsAManualRetryingRequiredStrategiesBindingWithTheSourceOptions() {
        // Given a source over a topic and group, with a filter, one signal keyed by carrier reference
        // and a payload mapping
        Carriers carriers = SourceFixture.carriers();
        KafkaRecordFilter given = KafkaRecordFilter.headerEquals("type", "delivered");
        KafkaSignals<ShipmentDelivered> source = carriers.signals()
                .on("carrier.events", ShipmentDelivered.class)
                .groupId("logistics")
                .filter(given);

        // When the source is built
        KafkaConsumerBinding<ShipmentDelivered> binding = source.signal(OrdersContract.class, "shipment-delivered")
                .key(ShipmentDelivered::carrierRef)
                .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()))
                .build();

        // Then the binding carries the topic and group, commits manually and retries, requires both
        assertThat(binding.topic()).isEqualTo("carrier.events");
        assertThat(binding.groupId()).isEqualTo("logistics");
        assertThat(binding.commitStrategy()).isEqualTo(CommitStrategy.MANUAL);
        assertThat(binding.errorStrategy()).isEqualTo(ErrorStrategy.RETRY);
        assertThat(binding.requireBindingStrategies()).isTrue();
        // And it installs no header context and ignores undecodable records
        assertThat(binding.noHeaderContext()).isTrue();
        assertThat(binding.ignoreUndecodable()).isNotNull();
        // And it has a handler and no dispatchTo target
        assertThat(binding.handler()).isNotNull();
        assertThat(binding.targetService()).isNull();
        assertThat(binding.targetOperation()).isNull();
        // And its value type is the source's message type
        assertThat(binding.valueType()).isEqualTo(ShipmentDelivered.class);
        // And its filter accepts exactly the records the given filter accepts
        assertThat(binding.filter()).isNotNull();
        for (Map<String, String> headers : List.of(
                Map.of("type", "delivered"),
                Map.of("type", "other"),
                Map.of("type", "Delivered"),
                Map.<String, String>of())) {
            KafkaRecordHeaders recordHeaders = KafkaRecordHeaders.of(headers);
            assertThat(binding.filter().accept("key-1", recordHeaders))
                    .as("headers %s", headers)
                    .isEqualTo(given.accept("key-1", recordHeaders));
        }
    }

    @Test
    void nameDefaultsToGroupIdAndTopicAndNameReplacesIt() {
        // Given the source of the first proof, without and with .name("carriers")
        Carriers carriers = SourceFixture.carriers();

        // When each is built
        KafkaConsumerBinding<ShipmentDelivered> unnamed = keyedByCarrier(carriers.source());
        KafkaConsumerBinding<ShipmentDelivered> named =
                keyedByCarrier(carriers.source().name("carriers"));

        // Then the first is named group-topic and the second by the replacement
        assertThat(unnamed.name()).isEqualTo("logistics-carrier.events");
        assertThat(named.name()).isEqualTo("carriers");

        // And a blank topic or group fails at construction
        assertThatThrownBy(() -> keyedByCarrier(
                        carriers.signals().on(" ", ShipmentDelivered.class).groupId(GROUP)))
                .isInstanceOf(RuntimeException.class)
                .message()
                .containsIgnoringCase("topic");
        assertThatThrownBy(() -> keyedByCarrier(
                        carriers.signals().on(TOPIC, ShipmentDelivered.class).groupId(" ")))
                .isInstanceOf(RuntimeException.class)
                .message()
                .containsIgnoringCase("groupId");
    }

    @Test
    void signalUndeclaredByTheContractFailsAtBuildNamingTheSignal() {
        // Given the source naming shipment-deliverd (misspelled) on OrdersContract
        Carriers carriers = SourceFixture.carriers();
        KafkaSignals<ShipmentDelivered> source = carriers.source();

        // When it is built
        Throwable failure = catchThrowable(() -> source.signal(OrdersContract.class, "shipment-deliverd")
                .key(ShipmentDelivered::carrierRef)
                .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()))
                .build());

        // Then construction fails naming the source, the contract and the signal
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DEFAULT_NAME)
                .hasMessageContaining("OrdersContract")
                .hasMessageContaining("shipment-deliverd");
    }

    @Test
    void businessKeySourceWithoutDedupByFailsAtBuildNamingTheSource() {
        // Given a source named carriers with a business key and no dedupBy
        Carriers carriers = SourceFixture.carriers();

        // When it is built
        Throwable withoutDedup = catchThrowable(() -> carriers.source()
                .name("carriers")
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .businessKey(ShipmentDelivered::orderNumber)
                .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()))
                .build());

        // Then it fails, naming the source
        assertThat(withoutDedup).isInstanceOf(IllegalStateException.class).hasMessageContaining("carriers");

        // And the same source with a dedupBy builds
        assertThatCode(() -> carriers.source()
                        .name("carriers")
                        .dedupBy(ShipmentDelivered::carrierRef)
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .businessKey(ShipmentDelivered::orderNumber)
                        .payload(message -> new DeliveryConfirmed(message.carrierRef(), message.note()))
                        .build())
                .doesNotThrowAnyException();

        // And a source addressing by the wait's key needs no dedupBy
        assertThatCode(() -> keyedByCarrier(carriers.source().name("carriers"))).doesNotThrowAnyException();
    }

    @Test
    void configurationRestoringAutoOrSkipFailsStartupNamingTheOption() {
        // Given the binding built by the first proof's source, named logistics-carrier.events
        KafkaConsumerBinding<ShipmentDelivered> binding =
                keyedByCarrier(SourceFixture.carriers().source());
        assertThat(binding.name()).isEqualTo("logistics-carrier.events");
        List<Row> rows = List.of(
                new Row("no configuration", new JsonObject(), null),
                new Row("commitStrategy=AUTO", new JsonObject().put("commitStrategy", "AUTO"), "commitStrategy"),
                new Row("errorStrategy=SKIP", new JsonObject().put("errorStrategy", "SKIP"), "errorStrategy"),
                new Row(
                        "retry.exhaustedStrategy=SKIP",
                        new JsonObject().put("retry", new JsonObject().put("exhaustedStrategy", "SKIP")),
                        "retry.exhaustedStrategy"));

        for (Row row : rows) {
            // When the registry is built for the binding under the row's configuration
            Throwable failure = catchThrowable(() -> SourceFixture.register(binding, row.consumerConfig()));

            // Then none registers, and each other configuration is refused naming the consumer and the option
            if (row.changedOption() == null) {
                assertThat(failure).as(row.label()).isNull();
            } else {
                assertThat(failure).as(row.label()).isInstanceOf(KafkaRegistrationException.class);
                assertThat(((KafkaRegistrationException) failure).violations())
                        .as(row.label())
                        .anyMatch(violation ->
                                violation.contains(binding.name()) && violation.contains(row.changedOption()));
            }
        }
    }

    @Test
    void sourceBuilderOffersNoCommitOrErrorStrategySetter() {
        // Given the public interface KafkaSignals
        // When the test lists its methods
        List<Method> methods = Arrays.asList(KafkaSignals.class.getMethods());

        // Then its own stages are listed, so the list is not vacuous
        assertThat(methods)
                .extracting(Method::getName)
                .contains("topic", "groupId", "name", "filter", "dlqReplay", "signal", "build");

        // And none takes a CommitStrategy or an ErrorStrategy, and none is named for either or for retry
        assertThat(methods).noneMatch(method -> Arrays.stream(method.getParameterTypes())
                .anyMatch(type -> type == CommitStrategy.class || type == ErrorStrategy.class));
        assertThat(methods).extracting(Method::getName).doesNotContain("commitStrategy", "errorStrategy", "retry");
    }

    @Test
    void kafkaBuilderRefusesScopeAndAnyScopeTogether() {
        // Given a Kafka source naming .scope(fn) and .anyScope(); one naming .anyScope() only; one naming neither
        Carriers carriers = SourceFixture.carriers();

        // When the first is built
        Throwable both = catchThrowable(() -> keyedByCarrier(carriers.source()
                .name("carriers")
                .scope(message -> Optional.of("tenant-1"))
                .anyScope()));

        // Then it fails at construction naming the source
        assertThat(both).isInstanceOf(IllegalStateException.class).hasMessageContaining("carriers");

        // And the other two build
        assertThatCode(() -> keyedByCarrier(carriers.source().name("carriers").anyScope()))
                .doesNotThrowAnyException();
        assertThatCode(() -> keyedByCarrier(carriers.source().name("carriers"))).doesNotThrowAnyException();
    }

    @Test
    void recordRejectedBySourceFilterIsReportedIgnoredThroughTheDelivery() {
        // Given a source with a header filter over a delivery that records its events, and the same source with no
        // filter
        Carriers filtered = SourceFixture.recordingDelivery();
        KafkaConsumerBinding<ShipmentDelivered> binding =
                keyedByCarrier(filtered.source().filter(KafkaRecordFilter.headerEquals("type", "delivered")));
        KafkaConsumerBinding<ShipmentDelivered> unfiltered =
                keyedByCarrier(SourceFixture.recordingDelivery().source());

        // When the built filter sees headers type=delivered
        boolean accepted = binding.filter().accept("key-1", KafkaRecordHeaders.of(Map.of("type", "delivered")));

        // Then it accepts and reports nothing
        assertThat(accepted).isTrue();
        assertThat(filtered.events()).isEmpty();

        // When it sees headers type=other and the record key SECRET-KEY-7
        boolean rejected = binding.filter().accept(SECRET_KEY, KafkaRecordHeaders.of(Map.of("type", "other")));

        // Then it rejects and report received exactly one IGNORED event naming the source and transport
        assertThat(rejected).isFalse();
        assertThat(filtered.events()).hasSize(1);
        SignalSourceEvent event = filtered.events().get(0);
        assertThat(event.source()).isEqualTo("logistics-carrier.events").isEqualTo(binding.name());
        assertThat(event.transport()).isEqualTo("kafka");
        assertThat(event.outcome()).isEqualTo(Outcome.IGNORED);
        assertThat(event.contractName()).isNull();
        assertThat(event.signalName()).isNull();
        assertThat(event.definitionId()).isNull();
        // And no event field holds the key or a header value
        assertThat(event.toString())
                .doesNotContain(SECRET_KEY)
                .doesNotContain("other")
                .doesNotContain("delivered");

        // And the binding built without a filter has none
        assertThat(unfiltered.filter()).isNull();
    }

    @Test
    void filterThatThrowsRaisesATerminalFailureCarryingTheExceptionClassOnly() {
        // Given a source with a filter that throws and one that accepts, and one that rejects
        Carriers throwing = SourceFixture.recordingDelivery();
        KafkaConsumerBinding<ShipmentDelivered> throwingBinding =
                keyedByCarrier(throwing.source().filter((key, headers) -> {
                    throw new IllegalStateException(SECRET_KEY + " rejected");
                }));
        Carriers accepting = SourceFixture.recordingDelivery();
        KafkaConsumerBinding<ShipmentDelivered> acceptingBinding =
                keyedByCarrier(accepting.source().filter((key, headers) -> true));
        Carriers rejecting = SourceFixture.recordingDelivery();
        KafkaConsumerBinding<ShipmentDelivered> rejectingBinding =
                keyedByCarrier(rejecting.source().filter((key, headers) -> false));

        // When the built filter is called with the record key SECRET-KEY-7 and no headers
        Throwable failure =
                catchThrowable(() -> throwingBinding.filter().accept(SECRET_KEY, KafkaRecordHeaders.empty()));

        // Then it throws KafkaTerminalFailure whose errorType is the exception class, with no cause and no key
        assertThat(failure).isInstanceOf(KafkaTerminalFailure.class);
        KafkaTerminalFailure terminal = (KafkaTerminalFailure) failure;
        assertThat(terminal.errorType()).isEqualTo("IllegalStateException");
        assertThat(terminal).hasNoCause();
        assertThat(terminal.getMessage()).doesNotContain(SECRET_KEY);
        assertThat(terminal.errorType()).doesNotContain(SECRET_KEY);

        // And one STAGE_FAILED event is reported
        assertThat(throwing.events()).hasSize(1);
        assertThat(throwing.events().get(0).outcome()).isEqualTo(Outcome.STAGE_FAILED);
        assertThat(throwing.events().get(0).toString()).doesNotContain(SECRET_KEY);

        // And the accepting filter still accepts and a rejecting filter still rejects without throwing
        assertThat(acceptingBinding.filter().accept(SECRET_KEY, KafkaRecordHeaders.empty()))
                .isTrue();
        assertThat(accepting.events()).isEmpty();
        assertThat(rejectingBinding.filter().accept(SECRET_KEY, KafkaRecordHeaders.empty()))
                .isFalse();
        assertThat(rejecting.events()).extracting(SignalSourceEvent::outcome).containsExactly(Outcome.IGNORED);
    }

    @Test
    void waitFateFailsTheRecordWithARedeliverFailureCarryingTheErrorType() {
        // Given a source whose delivery answers DEFER with an error type, RETRY and TERMINAL
        for (Fate fate : List.of(Fate.DEFER, Fate.RETRY, Fate.TERMINAL)) {
            Carriers carriers = SourceFixture.carriers();
            doReturn(Future.succeededFuture(new SourceDisposition(fate, "WorkflowIntakeRefusedException")))
                    .when(carriers.delivery())
                    .deliver(any(), any(), any());
            KafkaConsumerBinding<ShipmentDelivered> binding = keyedByCarrier(carriers.source());

            // When a record is handled
            Throwable failure = catchThrowable(() -> binding.handler()
                    .handle(new KafkaMessage<>(
                            new ShipmentDelivered("ref-1", "order-1", "note"),
                            "key-1",
                            TOPIC,
                            0,
                            1L,
                            0L,
                            KafkaRecordHeaders.empty()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get());

            // Then DEFER fails it with KafkaDeferredFailure carrying the error type and nothing more, and the other
            // fates keep their own failures
            Throwable cause = failure.getCause();
            if (fate == Fate.DEFER) {
                assertThat(cause).isInstanceOf(KafkaDeferredFailure.class).hasNoCause();
                assertThat(((KafkaDeferredFailure) cause).errorType()).isEqualTo("WorkflowIntakeRefusedException");
            } else if (fate == Fate.TERMINAL) {
                assertThat(cause).isInstanceOf(KafkaTerminalFailure.class);
            } else {
                assertThat(cause)
                        .isNotInstanceOf(KafkaDeferredFailure.class)
                        .isNotInstanceOf(KafkaTerminalFailure.class);
            }
        }
    }

    @Test
    void builtSourceDispatchesOnlyWhileTheNodeAdmitsIntake() {
        // Given a source whose delivery reports the node's intake, first admitting and then refusing
        Carriers carriers = SourceFixture.carriers();
        doReturn(true, false, true).when(carriers.delivery()).admitting();
        KafkaConsumerBinding<ShipmentDelivered> binding = keyedByCarrier(carriers.source());

        // When the binding's gate is asked three times
        // Then it follows the node
        assertThat(binding.dispatchGate()).isNotNull();
        assertThat(binding.dispatchGate().open()).isTrue();
        assertThat(binding.dispatchGate().open()).isFalse();
        assertThat(binding.dispatchGate().open()).isTrue();
    }

    @Test
    void aRecordReplayedAnyNumberOfTimesKeepsTheDedupKeyOfTheRecordItStartedAs() throws Exception {
        // Given a replay source with no dedupBy, and a delivery double that keeps the envelope of each record
        Carriers carriers = SourceFixture.carriers();
        ArgumentCaptor<SourceEnvelope> envelopes = ArgumentCaptor.forClass(SourceEnvelope.class);
        doReturn(Future.succeededFuture(new SourceDisposition(Fate.DONE, null)))
                .when(carriers.delivery())
                .deliver(any(), any(), envelopes.capture());
        KafkaConsumerBinding<ShipmentDelivered> binding =
                keyedByCarrier(carriers.source().dlqReplay());
        KafkaRecordHeaders original = KafkaRecordHeaders.of(new java.util.LinkedHashMap<>(
                Map.of("x-dlq-source-topic", TOPIC, "x-dlq-source-partition", "3", "x-dlq-source-offset", "42")));

        // When the dead letter is replayed from its own position, fails again, and its next dead letter, which keeps
        // the headers of the original, is replayed from another position, and so is the one after that
        for (long dlqOffset : List.of(7L, 19L, 31L)) {
            binding.handler()
                    .handle(new KafkaMessage<>(
                            new ShipmentDelivered("ref-1", "order-1", "note"),
                            "key-1",
                            TOPIC + ".dlq",
                            0,
                            dlqOffset,
                            0L,
                            original))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        }

        // Then every replay computes the dedup key of the original record
        assertThat(envelopes.getAllValues())
                .extracting(SourceEnvelope::defaultDedupKey)
                .containsExactly(TOPIC + ":3:42", TOPIC + ":3:42", TOPIC + ":3:42");
    }

    @Test
    void injectedCorrelationFactorySeedsTheCorrelationOfAnOptInSource() throws Exception {
        // Given a delivery double that keeps the envelope it is handed, and a correlation factory over an
        // application-supplied id generator
        Carriers carriers = SourceFixture.carriers();
        ArgumentCaptor<SourceEnvelope> envelopes = ArgumentCaptor.forClass(SourceEnvelope.class);
        doReturn(Future.succeededFuture(new SourceDisposition(Fate.DONE, null)))
                .when(carriers.delivery())
                .deliver(any(), any(), envelopes.capture());
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.of(() -> "application-id"));

        // When a source from the injected entry point restores correlation from headers and handles a record without
        // a correlation header, and so does one over a factory with the default generator
        KafkaConsumerBinding<ShipmentDelivered> injected =
                keyedByCarrier(new WorkflowKafkaSignals(carriers.delivery(), factory)
                        .on(TOPIC, ShipmentDelivered.class)
                        .groupId(GROUP)
                        .restoreCorrelationFromHeaders());
        KafkaConsumerBinding<ShipmentDelivered> byDefault =
                keyedByCarrier(carriers.source().restoreCorrelationFromHeaders());
        for (KafkaConsumerBinding<ShipmentDelivered> binding : List.of(injected, byDefault)) {
            binding.handler()
                    .handle(new KafkaMessage<>(
                            new ShipmentDelivered("ref-1", "order-1", "note"),
                            "key-1",
                            TOPIC,
                            0,
                            1L,
                            0L,
                            KafkaRecordHeaders.empty()))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get();
        }

        // Then the first record's correlation and request ids come from the application's generator
        CorrelationContext seeded = envelopes.getAllValues().get(0).correlation();
        assertThat(seeded.correlationId().value()).isEqualTo("application-id");
        assertThat(seeded.requestId().value()).isEqualTo("application-id");

        // And the factory with the default generator keeps it
        assertThat(envelopes.getAllValues().get(1).correlation().correlationId().value())
                .isNotEqualTo("application-id");

        // And the injectable constructor takes the delivery bean and the correlation factory
        assertThat(Arrays.stream(WorkflowKafkaSignals.class.getConstructors())
                        .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                        .map(constructor -> List.of(constructor.getParameterTypes()))
                        .toList())
                .containsExactly(List.of(WorkflowSignalSourceDelivery.class, CorrelationContextFactory.class));
    }
}
