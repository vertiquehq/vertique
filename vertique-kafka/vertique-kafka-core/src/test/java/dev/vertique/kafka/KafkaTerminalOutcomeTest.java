// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the terminal-outcome plumbing introduced in the consumer pipeline:
 *
 * <ul>
 *   <li>{@link KafkaTerminalOutcome} enum constants exist and are correctly named</li>
 *   <li>the error type of a terminal failure is kept when it is a token, and reaches the dead-letter
 *       header as a fixed name when it is not</li>
 * </ul>
 *
 * <p>How {@link KafkaConsumerVerticle} reports each outcome to interceptors is covered in
 * {@link KafkaConsumerLifecycleCallbackTest}.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaTerminalOutcomeTest {

    // --- Test doubles ---

    /** Builds a minimal {@link ConsumerEntry} for the given strategy. */
    static ConsumerEntry entryFor(ErrorStrategy strategy, String dlqTopic) {
        return entryFor(strategy, dlqTopic, CommitStrategy.MANUAL);
    }

    /** Builds a minimal {@link ConsumerEntry} for the given strategy and commit strategy. */
    static ConsumerEntry entryFor(ErrorStrategy strategy, String dlqTopic, CommitStrategy commitStrategy) {
        JsonObject cfg = new JsonObject()
                .put(
                        "consumers",
                        new JsonObject()
                                .put(
                                        "test",
                                        new JsonObject()
                                                .put("topic", "src.topic")
                                                .put("groupId", "grp")
                                                .put("enabled", true)
                                                .put(
                                                        "retry",
                                                        new JsonObject()
                                                                .put("backoffMs", 10)
                                                                .put("maxBackoffMs", 10))));
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", cfg), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ResolvedKafkaConsumerConfig consumerConfig = ResolvedKafkaConsumerConfig.resolve(
                "test",
                "src.topic",
                "grp",
                true,
                commitStrategy,
                strategy,
                dlqTopic != null ? dlqTopic : "",
                0L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get("test"));
        return new ConsumerEntry(
                "test",
                consumerConfig,
                ConsumerEntry.Kind.BINDING,
                Object.class,
                "addr",
                null,
                true,
                List.of(),
                null,
                null,
                false,
                null,
                dev.vertique.kafka.serialization.KafkaSerdeRegistry.DEFAULT_FORMAT);
    }

    /** Minimal fake {@link KafkaConsumerRecord} backed by simple fields. */
    @SuppressWarnings("unchecked")
    static KafkaConsumerRecord<String, byte[]> fakeRecord(String topic, int partition, long offset) {
        KafkaConsumerRecord<String, byte[]> rec = mock(KafkaConsumerRecord.class);
        when(rec.topic()).thenReturn(topic);
        when(rec.partition()).thenReturn(partition);
        when(rec.offset()).thenReturn(offset);
        return rec;
    }

    // --- KafkaTerminalOutcome enum ---

    @Nested
    @DisplayName("KafkaTerminalOutcome enum")
    class TerminalOutcomeEnum {

        @Test
        @DisplayName("all seven constants are defined")
        void allConstantsDefined() {
            KafkaTerminalOutcome[] values = KafkaTerminalOutcome.values();
            assertEquals(7, values.length, "Expected exactly 7 terminal-outcome constants");
        }

        @Test
        @DisplayName(
                "SUCCESS, SKIP, RECOVERED, RETRY_SCHEDULED, DLQ_PUBLISHED, DLQ_FAILED, ERROR_HANDLER_FAILED are present")
        void namedConstantsPresent() {
            Set<String> names = new java.util.HashSet<>();
            for (KafkaTerminalOutcome v : KafkaTerminalOutcome.values()) {
                names.add(v.name());
            }
            assertTrue(names.contains("SUCCESS"), "SUCCESS missing");
            assertTrue(names.contains("SKIP"), "SKIP missing");
            assertTrue(names.contains("RECOVERED"), "RECOVERED missing");
            assertTrue(names.contains("RETRY_SCHEDULED"), "RETRY_SCHEDULED missing");
            assertTrue(names.contains("DLQ_PUBLISHED"), "DLQ_PUBLISHED missing");
            assertTrue(names.contains("DLQ_FAILED"), "DLQ_FAILED missing");
            assertTrue(names.contains("ERROR_HANDLER_FAILED"), "ERROR_HANDLER_FAILED missing");
        }
    }

    // --- KafkaErrorHandler outcome values ---

    @Nested
    @DisplayName("the error type of a terminal failure")
    class TerminalFailureErrorType {

        @Test
        @DisplayName("is kept when it is a simple token and replaced when it is not")
        void keptWhenATokenAndReplacedOtherwise() {
            // Given / When / Then: tokens are kept as they are
            for (String token : List.of("AmbiguousSignal", "a.b-c_d$e", "_x", "$y", "a".repeat(128))) {
                assertEquals(token, new KafkaTerminalFailure(token).errorType());
                assertEquals(token, new KafkaTerminalFailure(token).getMessage());
            }

            // And: anything else is replaced by a fixed name, in the type and in the message
            for (String other :
                    List.of("", "has space", "line\nbreak", "1starts-with-digit", "a".repeat(129), "caf\u00e9")) {
                KafkaTerminalFailure failure = new KafkaTerminalFailure(other);
                assertEquals("InvalidErrorType", failure.errorType(), "for '" + other + "'");
                assertEquals("InvalidErrorType", failure.getMessage(), "for '" + other + "'");
            }
        }

        @Test
        @DisplayName("reaches the x-dlq-error header as the fixed name when it is not a token")
        void reachesTheHeaderAsTheFixedNameWhenNotAToken() {
            // Given: an entry that dead-letters and a send that captures the headers
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            ArgumentCaptor<KafkaRecordHeaders> headers = ArgumentCaptor.forClass(KafkaRecordHeaders.class);
            when(factory.sendForDlq(any(), any(), any(), headers.capture())).thenReturn(Future.succeededFuture());
            KafkaErrorHandler handler = new KafkaErrorHandler(entryFor(ErrorStrategy.DEAD_LETTER, "dlq"), factory);

            // When: a record fails with an error type that carries a line break
            handler.publishToDlq(
                    fakeRecord("t", 0, 1L),
                    new byte[0],
                    KafkaRecordHeaders.empty(),
                    new KafkaTerminalFailure("evil\nx-dlq-error: forged"));

            // Then: the header holds the fixed name alone
            assertEquals(
                    "InvalidErrorType",
                    headers.getValue()
                            .lastHeader(KafkaDlqHeaders.ERROR)
                            .orElseThrow()
                            .valueAsUtf8());
        }
    }
}
