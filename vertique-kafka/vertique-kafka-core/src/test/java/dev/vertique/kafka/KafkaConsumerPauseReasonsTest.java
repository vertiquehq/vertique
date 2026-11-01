// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import dev.vertique.services.ServiceRequestSender;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a consumer is paused for as long as any reason holds it: the in-flight limit, and the backoff of each
 * retried record. The reasons are tracked on their own in {@link KafkaPauseReasons}, and the verticle's in-flight
 * accounting and its {@link KafkaErrorHandler.ConsumerControl} use them, so the clearing of one reason can neither
 * resume a consumer another reason still holds nor leave a pause behind when the last reason clears.
 */
@ExtendWith(VertxExtension.class)
class KafkaConsumerPauseReasonsTest {

    @Nested
    @DisplayName("the tracker")
    class Tracker {

        @Test
        @DisplayName("pauses on the first reason held and resumes when the last one is released")
        void pausesOnFirstAndResumesOnLast() {
            Calls calls = new Calls();
            KafkaPauseReasons reasons = calls.reasons();

            reasons.hold(KafkaPauseReasons.Reason.IN_FLIGHT);
            reasons.hold(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            assertEquals(1, calls.pauses.get(), "the consumer is paused once, by the first reason");

            reasons.release(KafkaPauseReasons.Reason.IN_FLIGHT);
            assertEquals(0, calls.resumes.get(), "the backoff still holds the consumer");
            assertTrue(reasons.isPaused());

            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            assertEquals(1, calls.resumes.get(), "the last release resumes the consumer");
            assertFalse(reasons.isPaused());
        }

        @Test
        @DisplayName("a level reason is held once however often it is requested, and released once")
        void levelReasonIsHeldOnce() {
            Calls calls = new Calls();
            KafkaPauseReasons reasons = calls.reasons();

            reasons.hold(KafkaPauseReasons.Reason.IN_FLIGHT);
            reasons.hold(KafkaPauseReasons.Reason.IN_FLIGHT);
            reasons.release(KafkaPauseReasons.Reason.IN_FLIGHT);

            assertFalse(reasons.isPaused(), "one release clears a level reason");
            assertEquals(1, calls.pauses.get());
            assertEquals(1, calls.resumes.get());
        }

        @Test
        @DisplayName("a counted reason needs one release per hold")
        void countedReasonNeedsOneReleasePerHold() {
            Calls calls = new Calls();
            KafkaPauseReasons reasons = calls.reasons();

            reasons.hold(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            reasons.hold(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            assertTrue(reasons.isPaused(), "the second backoff still holds the consumer");

            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            assertFalse(reasons.isPaused());
            assertEquals(1, calls.resumes.get());
        }

        @Test
        @DisplayName("a release with nothing held is ignored and releases no other reason")
        void surplusReleaseIsIgnored() {
            Calls calls = new Calls();
            KafkaPauseReasons reasons = calls.reasons();

            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            reasons.hold(KafkaPauseReasons.Reason.IN_FLIGHT);
            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);

            assertTrue(reasons.isPaused(), "a surplus release of another reason leaves the hold alone");
            assertEquals(0, calls.resumes.get());
        }

        @Test
        @DisplayName("a consumer can be paused again after it resumed")
        void pausesAgainAfterResume() {
            Calls calls = new Calls();
            KafkaPauseReasons reasons = calls.reasons();

            reasons.hold(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            reasons.release(KafkaPauseReasons.Reason.ERROR_BACKOFF);
            reasons.hold(KafkaPauseReasons.Reason.IN_FLIGHT);

            assertEquals(2, calls.pauses.get());
            assertEquals(1, calls.resumes.get());
            assertTrue(reasons.isPaused());
        }
    }

    @Nested
    @DisplayName("the verticle")
    class Verticle {

        @Test
        @DisplayName("a retry's resume does not resume a consumer that is at its in-flight limit")
        void retryResumeLeavesTheInFlightPause(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);
            fillInFlight(wired);
            verify(wired.consumer(), times(1)).pause();

            // A retry pauses (already paused), then its scheduled resume fires.
            wired.verticle().consumerControl().pause();
            wired.verticle().consumerControl().scheduleResume(0);

            verify(wired.consumer(), never()).resume();

            // Only the in-flight pause remains, and dropping below the limit releases it.
            wired.verticle().decrementInFlight();
            verify(wired.consumer(), times(1)).resume();
        }

        @Test
        @DisplayName("finishing a dispatch does not resume a consumer that is waiting out a retry's backoff")
        void decrementLeavesTheBackoffPause(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);
            fillInFlight(wired);

            // The retry pause overlaps the in-flight pause; the dispatch that finishes frees an in-flight slot.
            wired.verticle().consumerControl().pause();
            wired.verticle().decrementInFlight();

            verify(wired.consumer(), never()).resume();

            wired.verticle().consumerControl().scheduleResume(0);
            verify(wired.consumer(), times(1)).resume();
        }

        @Test
        @DisplayName("a retry's backoff timer releases only the backoff, and the consumer resumes when it fires")
        void backoffTimerResumesWhenNothingElseHolds(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);

            wired.verticle().consumerControl().pause();
            wired.verticle().consumerControl().scheduleResume(20);

            verify(wired.consumer(), timeout(5_000)).resume();
            verify(wired.consumer(), times(1)).pause();
        }

        @Test
        @DisplayName("a backoff timer that fires while the in-flight limit holds leaves the consumer paused")
        void backoffTimerLeavesTheInFlightPause(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);
            fillInFlight(wired);
            wired.verticle().consumerControl().pause();
            wired.verticle().consumerControl().scheduleResume(20);

            // A later timer on the same context fires after the backoff timer has.
            io.vertx.core.Promise<Void> after = io.vertx.core.Promise.promise();
            vertx.setTimer(200, id -> after.complete());
            after.future().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            verify(wired.consumer(), never()).resume();
            wired.verticle().decrementInFlight();
            verify(wired.consumer(), times(1)).resume();
        }

        @Test
        @DisplayName("a retry through the error handler leaves no pause behind, also when the seek fails")
        void retryThroughTheErrorHandlerLeavesNoPause(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);

            // The seek fails, as it does when a rebalance has taken the partition away: error handling resumes at once.
            when(wired.consumer().seek(any(), anyLong())).thenReturn(Future.failedFuture(new IllegalStateException()));
            KafkaConsumerRecord<String, byte[]> record = record();
            KafkaTerminalOutcome outcome = await(wired.errors()
                    .handleError(
                            record,
                            new byte[0],
                            KafkaRecordHeaders.empty(),
                            new IllegalStateException("handler failed"),
                            wired.verticle().consumerControl()));

            assertEquals(KafkaTerminalOutcome.RETRY_SCHEDULED, outcome);
            verify(wired.consumer(), times(1)).pause();
            verify(wired.consumer(), times(1)).resume();
        }

        @Test
        @DisplayName("two overlapping retries keep the consumer paused until both backoffs have ended")
        void overlappingRetries(Vertx vertx) throws Exception {
            Wired wired = wire(vertx, ErrorStrategy.RETRY);

            wired.verticle().consumerControl().pause();
            wired.verticle().consumerControl().pause();
            wired.verticle().consumerControl().scheduleResume(0);

            verify(wired.consumer(), never()).resume();

            wired.verticle().consumerControl().scheduleResume(0);
            verify(wired.consumer(), times(1)).resume();
        }
    }

    // --- Helpers ---

    private static void fillInFlight(Wired wired) {
        for (int i = 0; i < wired.maxInFlight(); i++) {
            wired.verticle().incrementInFlight();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static KafkaConsumerRecord<String, byte[]> record() {
        return KafkaTerminalOutcomeTest.fakeRecord("t", 0, 42L);
    }

    /** The calls a tracker makes to its consumer. */
    private static final class Calls {

        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();

        KafkaPauseReasons reasons() {
            return new KafkaPauseReasons(pauses::incrementAndGet, resumes::incrementAndGet);
        }
    }

    private record Wired(
            KafkaConsumerVerticle verticle,
            KafkaConsumer<String, byte[]> consumer,
            KafkaErrorHandler errors,
            int maxInFlight) {}

    @SuppressWarnings("unchecked")
    private static Wired wire(Vertx vertx, ErrorStrategy strategy) throws ReflectiveOperationException {
        ConsumerEntry entry = KafkaTerminalOutcomeTest.entryFor(strategy, null);
        KafkaProducerFactory producerFactory = mock(KafkaProducerFactory.class);
        KafkaConsumerVerticle verticle = new KafkaConsumerVerticle(
                entry,
                List.of(),
                producerFactory,
                mock(ServiceRequestSender.class),
                KafkaTestSupport.noOpTargetResolver(),
                KafkaTestSupport.eventBusClient(vertx),
                KafkaTestSupport.noOpInboundExecutionContextScope(),
                KafkaTestSupport.noOpEnvelopeBuilder(),
                KafkaTestSupport.jsonSerdeRegistry());
        verticle.init(vertx, vertx.getOrCreateContext());
        KafkaConsumer<String, byte[]> consumer = mock(KafkaConsumer.class);
        when(consumer.seek(any(), anyLong())).thenReturn(Future.succeededFuture());
        Field field = KafkaConsumerVerticle.class.getDeclaredField("consumer");
        field.setAccessible(true);
        field.set(verticle, consumer);
        return new Wired(
                verticle,
                consumer,
                new KafkaErrorHandler(entry, producerFactory),
                entry.config().maxInFlight());
    }
}
