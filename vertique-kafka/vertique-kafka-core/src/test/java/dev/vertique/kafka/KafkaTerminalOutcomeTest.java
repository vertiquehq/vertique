// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.interceptor.KafkaConsumerCaptureHook;
import dev.vertique.kafka.interceptor.KafkaDispatchContext;
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
 *   <li>{@link KafkaConsumerCaptureHook} contract: observer-only SPI, default no-op, extends
 *       {@link dev.vertique.core.extension.OrderedExtension}</li>
 * </ul>
 *
 * <p>The hook invocation from {@link KafkaConsumerVerticle} is covered in
 * {@link KafkaConsumerCaptureHookInvocationTest}.
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

    // --- KafkaConsumerCaptureHook contract ---

    @Nested
    @DisplayName("KafkaConsumerCaptureHook contract")
    class CaptureHookContract {

        @Test
        @DisplayName("default onTerminalOutcome is a no-op — does not throw")
        void defaultNoOp() {
            KafkaConsumerCaptureHook hook = new KafkaConsumerCaptureHook() {
                        // all-defaults
                    };
            KafkaDispatchContext<?> ctx =
                    new KafkaDispatchContext<>("c", "t", 0, 1L, "k", "v", null, Map.of(), 0L, 0, false, Map.of());
            // must not throw
            hook.onTerminalOutcome(ctx, KafkaTerminalOutcome.SUCCESS);
        }

        @Test
        @DisplayName("hook implements OrderedExtension — default phase is APPLICATION")
        void implementsOrderedExtension() {
            KafkaConsumerCaptureHook hook = new KafkaConsumerCaptureHook() {};
            assertEquals(
                    dev.vertique.core.extension.ExtensionPhase.APPLICATION,
                    hook.phase(),
                    "default phase must be APPLICATION");
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

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
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

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, outcome);
                    assertTrue(control.commitCalled, "commitIfManual must be called after successful DLQ publish");
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

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
                ctx.verify(() -> {
                    assertEquals(KafkaTerminalOutcome.DLQ_FAILED, outcome);
                    assertFalse(control.commitCalled, "commitIfManual must NOT be called when DLQ publish fails");
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

            handler.handleError(rec, new byte[0], Map.of(), cause, control).onComplete(ctx.succeeding(outcome -> {
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
}
