// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link OutboxMaintenanceService}:
 * <ul>
 *   <li>{@link OutboxMaintenanceService#recoverStaleLeases()} delegates to
 *       {@link OutboxRepository#reclaimStale(Duration)} with the configured lease timeout.</li>
 *   <li>{@link OutboxMaintenanceService#cleanup()} invokes all four cleanup paths
 *       (published, dead-letter, discarded, inbox) and returns a composite outcome: success iff all four
 *       succeed; otherwise failure carrying every underlying cause as suppressed exceptions.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxMaintenanceServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private InboxRepository inboxRepository;

    private OutboxMaintenanceService service;

    private static final OutboxRelayConfig RELAY_CONFIG =
            OutboxRelayConfig.builder().build();
    private static final InboxOutboxCleanupConfig CLEANUP_CONFIG =
            InboxOutboxCleanupConfig.builder().build();

    @BeforeEach
    void setUp() {
        service = new OutboxMaintenanceService(outboxRepository, inboxRepository, RELAY_CONFIG, CLEANUP_CONFIG);
    }

    @Nested
    @DisplayName("recoverStaleLeases()")
    class RecoverStaleLeases {

        @Test
        @DisplayName("delegates to reclaimStale(leaseTimeout) and propagates success")
        void delegatesAndSucceeds() {
            when(outboxRepository.reclaimStale(any(Duration.class))).thenReturn(Future.succeededFuture());

            Future<Void> result = service.recoverStaleLeases();

            assertTrue(result.succeeded());
            verify(outboxRepository, times(1)).reclaimStale(eq(Duration.ofMillis(RELAY_CONFIG.leaseTimeoutMs())));
        }

        @Test
        @DisplayName("propagates repository failure")
        void propagatesFailure() {
            RuntimeException boom = new RuntimeException("db down");
            when(outboxRepository.reclaimStale(any(Duration.class))).thenReturn(Future.failedFuture(boom));

            Future<Void> result = service.recoverStaleLeases();

            assertTrue(result.failed());
            assertSame(boom, result.cause());
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class Cleanup {

        @Test
        @DisplayName("all four cleanups succeed → returns success")
        void allSucceed() {
            stubAll(
                    Future.succeededFuture(3),
                    Future.succeededFuture(5),
                    Future.succeededFuture(6),
                    Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertTrue(result.succeeded());
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("each cleanup is called with its own retention window and the shared batch size")
        void eachCleanupGetsItsOwnRetention() {
            InboxOutboxCleanupConfig distinct = InboxOutboxCleanupConfig.builder()
                    .publishedRetentionDays(1)
                    .deadLetterRetentionDays(2)
                    .discardedRetentionDays(3)
                    .inboxRetentionDays(4)
                    .cleanupBatchSize(5)
                    .build();
            OutboxMaintenanceService custom =
                    new OutboxMaintenanceService(outboxRepository, inboxRepository, RELAY_CONFIG, distinct);
            when(outboxRepository.cleanupPublished(1, 5)).thenReturn(Future.succeededFuture(0));
            when(outboxRepository.cleanupDeadLetter(2, 5)).thenReturn(Future.succeededFuture(0));
            when(outboxRepository.cleanupDiscarded(3, 5)).thenReturn(Future.succeededFuture(0));
            when(inboxRepository.cleanup(4, 5)).thenReturn(Future.succeededFuture(0));

            Future<Void> result = custom.cleanup();

            assertTrue(result.succeeded());
            verify(outboxRepository).cleanupPublished(1, 5);
            verify(outboxRepository).cleanupDeadLetter(2, 5);
            verify(outboxRepository).cleanupDiscarded(3, 5);
            verify(inboxRepository).cleanup(4, 5);
            verifyNoMoreInteractions(outboxRepository, inboxRepository);
        }

        @Test
        @DisplayName("the default discarded retention is seven days")
        void defaultDiscardedRetention() {
            assertEquals(7, CLEANUP_CONFIG.discardedRetentionDays());
        }

        @Test
        @DisplayName("published cleanup fails — the other three still run and the outcome carries the cause")
        void publishedFails_othersStillRun() {
            RuntimeException boom = new RuntimeException("published failure");
            stubAll(
                    Future.failedFuture(boom),
                    Future.succeededFuture(5),
                    Future.succeededFuture(6),
                    Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            assertNotNull(result.cause());
            assertTrue(allCauses(result.cause()).contains(boom));
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("discarded cleanup fails — the outcome is failure naming it, and the other three still run")
        void onlyDiscardedFails() {
            RuntimeException boom = new RuntimeException("discarded failure");
            stubAll(
                    Future.succeededFuture(3),
                    Future.succeededFuture(5),
                    Future.failedFuture(boom),
                    Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            OutboxMaintenanceException failure = assertInstanceOf(OutboxMaintenanceException.class, result.cause());
            assertEquals("outbox cleanup failed for [discarded] (1 of 4): discarded failure", failure.getMessage());
            assertSame(boom, failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("all four fail — the failure carries every cause and names every operation")
        void allFail_carriesAllCauses() {
            RuntimeException b1 = new RuntimeException("published failure");
            RuntimeException b2 = new RuntimeException("dead-letter failure");
            RuntimeException b3 = new RuntimeException("discarded failure");
            RuntimeException b4 = new RuntimeException("inbox failure");
            stubAll(Future.failedFuture(b1), Future.failedFuture(b2), Future.failedFuture(b3), Future.failedFuture(b4));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            OutboxMaintenanceException failure = assertInstanceOf(OutboxMaintenanceException.class, result.cause());
            assertEquals(
                    "outbox cleanup failed for [published, deadLetter, discarded, inbox] (4 of 4): published failure",
                    failure.getMessage());
            assertSame(b1, failure.getCause(), "the first failure is the primary cause");
            assertEquals(List.of(b2, b3, b4), List.of(failure.getSuppressed()));
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("two failures are reported as two of four, in operation order")
        void twoOfFour() {
            RuntimeException deadLetterFailure = new RuntimeException("dead-letter failure");
            RuntimeException inboxFailure = new RuntimeException("inbox failure");
            stubAll(
                    Future.succeededFuture(3),
                    Future.failedFuture(deadLetterFailure),
                    Future.succeededFuture(6),
                    Future.failedFuture(inboxFailure));

            Future<Void> result = service.cleanup();

            OutboxMaintenanceException failure = assertInstanceOf(OutboxMaintenanceException.class, result.cause());
            assertEquals(
                    "outbox cleanup failed for [deadLetter, inbox] (2 of 4): dead-letter failure",
                    failure.getMessage());
            assertSame(deadLetterFailure, failure.getCause());
            assertEquals(List.of(inboxFailure), List.of(failure.getSuppressed()));
        }

        @Test
        @DisplayName("inbox cleanup fails — outcome is failure even when every outbox cleanup succeeds")
        void onlyInboxFails() {
            RuntimeException boom = new RuntimeException("inbox failure");
            stubAll(
                    Future.succeededFuture(3),
                    Future.succeededFuture(5),
                    Future.succeededFuture(6),
                    Future.failedFuture(boom));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            assertTrue(allCauses(result.cause()).contains(boom));
            assertEquals(
                    "outbox cleanup failed for [inbox] (1 of 4): inbox failure",
                    result.cause().getMessage());
        }

        @Test
        @DisplayName(
                "never short-circuits — when published fails synchronously, the other three still observe their stubs")
        void neverShortCircuits() {
            stubAll(
                    Future.failedFuture(new RuntimeException("published failure")),
                    Future.succeededFuture(5),
                    Future.succeededFuture(6),
                    Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertFalse(result.succeeded());
            verifyAllCleanupsInvoked();
        }

        private void stubAll(
                Future<Integer> publishedOutcome,
                Future<Integer> deadLetterOutcome,
                Future<Integer> discardedOutcome,
                Future<Integer> inboxOutcome) {
            when(outboxRepository.cleanupPublished(
                            CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(publishedOutcome);
            when(outboxRepository.cleanupDeadLetter(
                            CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(deadLetterOutcome);
            when(outboxRepository.cleanupDiscarded(
                            CLEANUP_CONFIG.discardedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(discardedOutcome);
            when(inboxRepository.cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(inboxOutcome);
        }

        private void verifyAllCleanupsInvoked() {
            verify(outboxRepository)
                    .cleanupPublished(CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(outboxRepository)
                    .cleanupDeadLetter(CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(outboxRepository)
                    .cleanupDiscarded(CLEANUP_CONFIG.discardedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(inboxRepository).cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
        }
    }

    /**
     * Walks the failure tree: the composite wrapper, its primary cause, and any suppressed
     * causes. Tests call {@code contains(...)} on the result, so order does not matter.
     *
     * @param top the failure returned by the Future (the composite wrapper)
     * @return all causes carried by the failure
     */
    private static List<Throwable> allCauses(Throwable top) {
        List<Throwable> out = new ArrayList<>();
        out.add(top);
        if (top.getCause() != null) {
            out.add(top.getCause());
        }
        for (Throwable s : top.getSuppressed()) {
            out.add(s);
        }
        return out;
    }
}
