// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the terminal-outcome plumbing introduced in the consumer pipeline:
 *
 * <ul>
 *   <li>{@link KafkaTerminalOutcome} enum constants exist and are correctly named</li>
 *   <li>{@link KafkaErrorHandler#handleError} returns the correct {@link KafkaTerminalOutcome} for
 *       each {@link ErrorStrategy} — and all existing side-effects (commit / seek / resume / DLQ
 *       publish) are still invoked exactly as before</li>
 * </ul>
 *
 * <p>How {@link KafkaConsumerVerticle} reports each outcome to interceptors is covered in
 * {@link KafkaConsumerLifecycleCallbackTest}.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaTerminalOutcomeTest {

    // --- Test doubles ---

    /**
     * Minimal {@link KafkaErrorHandler.ConsumerControl} that records whether
     * {@code commitIfManual} and {@code scheduleResume} were called.
     */
    static final class CapturingConsumerControl implements KafkaErrorHandler.ConsumerControl {

        boolean commitCalled = false;
        boolean pauseCalled = false;
        boolean scheduleResumeCalled = false;
        long lastDelayMs = -1;
        List<long[]> seekCalls = new ArrayList<>();

        @Override
        public void pause() {
            pauseCalled = true;
        }

        @Override
        public void scheduleResume(long delayMs) {
            scheduleResumeCalled = true;
            lastDelayMs = delayMs;
        }

        @Override
        public Future<Void> seekToOffset(String topic, int partition, long offset) {
            seekCalls.add(new long[] {partition, offset});
            return Future.succeededFuture();
        }

        @Override
        public void commitIfManual(KafkaConsumerRecord<String, byte[]> record) {
            commitCalled = true;
        }
    }

    /** Builds a minimal {@link ConsumerEntry} for the given strategy. */
    static ConsumerEntry entryFor(ErrorStrategy strategy, String dlqTopic) {
        JsonObject cfg = new JsonObject()
                .put(
                        "consumers",
                        new JsonObject()
                                .put(
                                        "test",
                                        new JsonObject()
                                                .put("topic", "src.topic")
                                                .put("groupId", "grp")
                                                .put("enabled", true)));
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", cfg), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ResolvedKafkaConsumerConfig consumerConfig = ResolvedKafkaConsumerConfig.resolve(
                "test",
                "src.topic",
                "grp",
                true,
                CommitStrategy.MANUAL,
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
    @DisplayName("KafkaErrorHandler.handleError outcomes")
    class HandleErrorOutcomes {

        private final RuntimeException cause = new RuntimeException("test-error");

        @Test
        @DisplayName("SKIP strategy → SKIP outcome and commitIfManual is called")
        void skipStrategyOutcome(VertxTestContext ctx) {
            ConsumerEntry entry = entryFor(ErrorStrategy.SKIP, null);
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 0L);
            CapturingConsumerControl control = new CapturingConsumerControl();

            handler.handleError(rec, new byte[0], KafkaRecordHeaders.empty(), cause, control)
                    .onComplete(ctx.succeeding(outcome -> {
                        ctx.verify(() -> {
                            assertEquals(KafkaTerminalOutcome.SKIP, outcome);
                            assertTrue(control.commitCalled, "commitIfManual must be called on SKIP");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("DEAD_LETTER strategy + successful DLQ publish → DLQ_PUBLISHED outcome and commit called")
        void dlqSuccessOutcome(VertxTestContext ctx, @Mock KafkaProducerFactory factory) {
            when(factory.sendForDlq(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.succeededFuture());

            ConsumerEntry entry = entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic");
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 0L);
            CapturingConsumerControl control = new CapturingConsumerControl();

            handler.handleError(rec, new byte[0], KafkaRecordHeaders.empty(), cause, control)
                    .onComplete(ctx.succeeding(outcome -> {
                        ctx.verify(() -> {
                            assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, outcome);
                            assertTrue(
                                    control.commitCalled, "commitIfManual must be called after successful DLQ publish");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("DEAD_LETTER strategy + failed DLQ publish → DLQ_FAILED outcome and commit NOT called")
        void dlqFailureOutcome(VertxTestContext ctx, @Mock KafkaProducerFactory factory) {
            when(factory.sendForDlq(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Future.failedFuture(new RuntimeException("kafka-unavailable")));

            ConsumerEntry entry = entryFor(ErrorStrategy.DEAD_LETTER, "dlq-topic");
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 0L);
            CapturingConsumerControl control = new CapturingConsumerControl();

            handler.handleError(rec, new byte[0], KafkaRecordHeaders.empty(), cause, control)
                    .onComplete(ctx.succeeding(outcome -> {
                        ctx.verify(() -> {
                            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, outcome);
                            assertFalse(
                                    control.commitCalled, "commitIfManual must NOT be called when DLQ publish fails");
                        });
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName(
                "RETRY strategy (within max retries) → RETRY_SCHEDULED outcome, pause + seek + scheduleResume called")
        void retryStrategyOutcome(VertxTestContext ctx) {
            ConsumerEntry entry = entryFor(ErrorStrategy.RETRY, null);
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);

            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 42L);
            CapturingConsumerControl control = new CapturingConsumerControl();

            handler.handleError(rec, new byte[0], KafkaRecordHeaders.empty(), cause, control)
                    .onComplete(ctx.succeeding(outcome -> {
                        ctx.verify(() -> {
                            assertEquals(KafkaTerminalOutcome.RETRY_SCHEDULED, outcome);
                            assertTrue(control.pauseCalled, "pause must be called for RETRY");
                            assertFalse(control.seekCalls.isEmpty(), "seekToOffset must be called for RETRY");
                            assertTrue(control.scheduleResumeCalled, "scheduleResume must be called for RETRY");
                        });
                        ctx.completeNow();
                    }));
        }
    }
    // --- Failed dead-letter publish ---

    /** Returns the entry as registration builds it for a binding that forces its strategies. */
    static ConsumerEntry forced(ConsumerEntry e) {
        return new ConsumerEntry(
                e.name(),
                e.config(),
                e.kind(),
                e.valueType(),
                e.targetAddress(),
                e.stableTargetId(),
                e.targetOneWay(),
                e.routes(),
                e.handler(),
                e.deserializer(),
                e.frameworkOwnedDeserializer(),
                e.filter(),
                e.valueFormat(),
                e.ignoreUndecodable(),
                e.noHeaderContext(),
                true);
    }

    @Nested
    @DisplayName("a failed dead-letter publish")
    class FailedDeadLetterPublish {

        private final RuntimeException ordinary = new RuntimeException("test-error");

        private KafkaProducerFactory failingFactory() {
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            when(factory.sendForDlq(any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new IllegalStateException("broker down")));
            return factory;
        }

        private KafkaTerminalOutcome settle(
                KafkaErrorHandler handler,
                KafkaConsumerRecord<String, byte[]> rec,
                Throwable failure,
                CapturingConsumerControl control) {
            Future<KafkaTerminalOutcome> outcome =
                    handler.handleError(rec, new byte[0], KafkaRecordHeaders.empty(), failure, control);
            assertTrue(outcome.succeeded(), "the outcome should settle at once");
            return outcome.result();
        }

        @Test
        @DisplayName("on a forcing entry seeks back, backs off with a growing delay and commits nothing")
        void forcedEntrySeeksBackAndBacksOff() {
            // Given: a forcing DEAD_LETTER entry on the default backoff and a dead-letter topic that is down
            KafkaErrorHandler handler =
                    new KafkaErrorHandler(forced(entryFor(ErrorStrategy.DEAD_LETTER, "dlq")), failingFactory());
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 2, 5L);

            // When: the record fails, and fails again after it is delivered again
            CapturingConsumerControl first = new CapturingConsumerControl();
            KafkaTerminalOutcome firstOutcome = settle(handler, rec, ordinary, first);
            CapturingConsumerControl second = new CapturingConsumerControl();
            KafkaTerminalOutcome secondOutcome = settle(handler, rec, ordinary, second);

            // Then: the record is not committed, the consumer pauses and seeks back to it, and resumes after a backoff
            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, firstOutcome);
            assertFalse(first.commitCalled, "a record that was not dead-lettered must not be committed");
            assertTrue(first.pauseCalled);
            assertEquals(1, first.seekCalls.size());
            assertArrayEquals(new long[] {2, 5L}, first.seekCalls.get(0));
            assertTrue(first.scheduleResumeCalled);
            assertEquals(1000L, first.lastDelayMs);

            // And: the second failure of the same record backs off longer
            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, secondOutcome);
            assertFalse(second.commitCalled);
            assertEquals(2000L, second.lastDelayMs);
        }

        @Test
        @DisplayName("on a forcing entry is published at last, then committed, and the next failure starts over")
        void forcedEntryCommitsOncePublished() {
            // Given: a forcing entry whose dead-letter topic fails once and then recovers
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            when(factory.sendForDlq(any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new IllegalStateException("broker down")))
                    .thenReturn(Future.succeededFuture())
                    .thenReturn(Future.failedFuture(new IllegalStateException("broker down")));
            KafkaErrorHandler handler =
                    new KafkaErrorHandler(forced(entryFor(ErrorStrategy.DEAD_LETTER, "dlq")), factory);
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 9L);

            // When: the record fails twice, the second time with the topic back
            settle(handler, rec, ordinary, new CapturingConsumerControl());
            CapturingConsumerControl published = new CapturingConsumerControl();
            KafkaTerminalOutcome outcome = settle(handler, rec, ordinary, published);

            // Then: it is dead-lettered and committed with no pause
            assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, outcome);
            assertTrue(published.commitCalled);
            assertFalse(published.pauseCalled);

            // And: a later failure of the same position backs off from the first delay again
            CapturingConsumerControl again = new CapturingConsumerControl();
            settle(handler, rec, ordinary, again);
            assertEquals(1000L, again.lastDelayMs);
        }

        @Test
        @DisplayName("of a terminal failure on a forcing entry seeks back and commits nothing")
        void forcedTerminalFailureSeeksBack() {
            // Given: a forcing RETRY entry that dead-letters on exhaustion and a dead-letter topic that is down
            KafkaErrorHandler handler =
                    new KafkaErrorHandler(forced(entryFor(ErrorStrategy.RETRY, "dlq")), failingFactory());
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 1, 7L);
            CapturingConsumerControl control = new CapturingConsumerControl();

            // When: the record fails terminally
            KafkaTerminalOutcome outcome = settle(handler, rec, new KafkaTerminalFailure("Ambiguous"), control);

            // Then: it is not committed, and the consumer seeks back to it after pausing
            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, outcome);
            assertFalse(control.commitCalled);
            assertTrue(control.pauseCalled);
            assertArrayEquals(new long[] {1, 7L}, control.seekCalls.get(0));
            assertTrue(control.scheduleResumeCalled);
        }

        @Test
        @DisplayName("of an exhausted retry on a forcing entry seeks back and is redelivered straight to the publish")
        void forcedExhaustedRetrySeeksBackAndSkipsTheRetries() {
            // Given: a forcing RETRY entry (three retries, dead-letter on exhaustion) and a topic that is down
            KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
            when(factory.sendForDlq(any(), any(), any(), any()))
                    .thenReturn(Future.failedFuture(new IllegalStateException("broker down")))
                    .thenReturn(Future.succeededFuture());
            KafkaErrorHandler handler = new KafkaErrorHandler(forced(entryFor(ErrorStrategy.RETRY, "dlq")), factory);
            KafkaConsumerRecord<String, byte[]> rec = fakeRecord("t", 0, 3L);

            // When: the record fails through its three retries and then once more
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertEquals(
                        KafkaTerminalOutcome.RETRY_SCHEDULED,
                        settle(handler, rec, ordinary, new CapturingConsumerControl()));
            }
            CapturingConsumerControl exhausted = new CapturingConsumerControl();
            KafkaTerminalOutcome outcome = settle(handler, rec, ordinary, exhausted);

            // Then: the failed publish seeks back to the record and does not commit
            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, outcome);
            assertFalse(exhausted.commitCalled);
            assertTrue(exhausted.pauseCalled);
            assertArrayEquals(new long[] {0, 3L}, exhausted.seekCalls.get(0));

            // And: the next failure goes straight to the publish rather than through the retries again
            CapturingConsumerControl published = new CapturingConsumerControl();
            assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, settle(handler, rec, ordinary, published));
            assertTrue(published.commitCalled);
            assertFalse(published.pauseCalled);
            assertEquals(Map.of(), Map.copyOf(handler.retryCounts));
        }

        @Test
        @DisplayName("on an entry that does not force its strategies leaves the record uncommitted and goes on")
        void unforcedEntryGoesOn() {
            // Given: an unforced DEAD_LETTER entry and a dead-letter topic that is down
            KafkaErrorHandler handler =
                    new KafkaErrorHandler(entryFor(ErrorStrategy.DEAD_LETTER, "dlq"), failingFactory());
            CapturingConsumerControl control = new CapturingConsumerControl();

            // When: a record fails
            KafkaTerminalOutcome outcome = settle(handler, fakeRecord("t", 0, 1L), ordinary, control);

            // Then: it is not committed, and the consumer neither pauses nor seeks, as before
            assertEquals(KafkaTerminalOutcome.DLQ_FAILED, outcome);
            assertFalse(control.commitCalled);
            assertFalse(control.pauseCalled);
            assertTrue(control.seekCalls.isEmpty());
            assertFalse(control.scheduleResumeCalled);
        }
    }

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
            handler.handleError(
                    fakeRecord("t", 0, 1L),
                    new byte[0],
                    KafkaRecordHeaders.empty(),
                    new KafkaTerminalFailure("evil\nx-dlq-error: forged"),
                    new CapturingConsumerControl());

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
