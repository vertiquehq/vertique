// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the retry bookkeeping of {@link KafkaErrorHandler} around a terminal failure.
 *
 * <p>Verified: a record that was retried and then fails terminally leaves no entry in the error
 * handler's retry map, and a terminal failure on a record that never retried creates none. The
 * doubles are those of {@link KafkaTerminalOutcomeTest}; the retry map is read through its
 * package-private field, as {@link KafkaConsumerLifecycleCallbackTest} reads it.
 */
class KafkaTerminalRetryStateTest {

    @Test
    @DisplayName("a terminal failure after a retry leaves no retry count behind")
    void terminalFailureAfterARetryLeavesNoRetryCountBehind() {
        // Given: a RETRY entry with the default retry configuration and a dead-letter send that succeeds
        KafkaProducerFactory factory = mock(KafkaProducerFactory.class);
        when(factory.sendForDlq(any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        ConsumerEntry entry = KafkaTerminalOutcomeTest.entryFor(ErrorStrategy.RETRY, "dead.letter");
        KafkaErrorHandler handler = new KafkaErrorHandler(entry, factory);
        KafkaConsumerRecord<String, byte[]> retried = KafkaTerminalOutcomeTest.fakeRecord("src.topic", 0, 7);
        KafkaConsumerRecord<String, byte[]> neverRetried = KafkaTerminalOutcomeTest.fakeRecord("src.topic", 0, 8);

        // When: the record fails with an ordinary exception, then fails again with a terminal failure
        KafkaTerminalOutcomeTest.CapturingConsumerControl firstControl =
                new KafkaTerminalOutcomeTest.CapturingConsumerControl();
        Future<KafkaTerminalOutcome> first = handler.handleError(
                retried, new byte[0], KafkaRecordHeaders.empty(), new RuntimeException("transient"), firstControl);

        // Then: the first failure is a scheduled retry that counted the record (the precondition)
        assertTrue(first.succeeded(), "the retry outcome should settle at once");
        assertEquals(KafkaTerminalOutcome.RETRY_SCHEDULED, first.result());
        assertEquals(Map.of("src.topic:0:7", 1), Map.copyOf(handler.retryCounts));

        // When: the same record then fails with a terminal failure
        KafkaTerminalOutcomeTest.CapturingConsumerControl secondControl =
                new KafkaTerminalOutcomeTest.CapturingConsumerControl();
        Future<KafkaTerminalOutcome> second = handler.handleError(
                retried,
                new byte[0],
                KafkaRecordHeaders.empty(),
                new KafkaTerminalFailure("AmbiguousSignal"),
                secondControl);

        // Then: it is dead-lettered without a pause or a resume, and the record's count is gone
        assertTrue(second.succeeded(), "the terminal outcome should settle at once");
        assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, second.result());
        assertFalse(secondControl.pauseCalled, "a terminal failure must not pause the consumer");
        assertFalse(secondControl.scheduleResumeCalled, "a terminal failure must not schedule a resume");
        assertEquals(Map.of(), Map.copyOf(handler.retryCounts), "the terminal failure must clear the record's count");

        // When: a record that never retried fails with a terminal failure
        Future<KafkaTerminalOutcome> third = handler.handleError(
                neverRetried,
                new byte[0],
                KafkaRecordHeaders.empty(),
                new KafkaTerminalFailure("AmbiguousSignal"),
                new KafkaTerminalOutcomeTest.CapturingConsumerControl());

        // Then: it is dead-lettered and no entry was created for it
        assertTrue(third.succeeded(), "the terminal outcome should settle at once");
        assertEquals(KafkaTerminalOutcome.DLQ_PUBLISHED, third.result());
        assertEquals(Map.of(), Map.copyOf(handler.retryCounts), "a terminal failure must create no retry count");
    }
}
