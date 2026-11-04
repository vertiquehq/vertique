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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
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
 *   <li>{@link OutboxMaintenanceService#cleanup()} invokes all three cleanup paths
 *       (published, dead-letter, inbox) and returns a composite outcome: success iff all three
 *       succeed; otherwise failure carrying every underlying cause as suppressed exceptions.
 *       Built with the receipt cleanup, it runs a fourth {@code operations} outcome under the same
 *       contract.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OutboxMaintenanceServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private InboxRepository inboxRepository;

    @Mock
    private OutboxAdminOperationCleanup operationCleanup;

    private OutboxMaintenanceService service;

    private static final OutboxRelayConfig RELAY_CONFIG =
            OutboxRelayConfig.builder().build();
    private static final InboxOutboxCleanupConfig CLEANUP_CONFIG =
            InboxOutboxCleanupConfig.builder().build();
    private static final InboxOutboxAdminConfig ADMIN_CONFIG =
            InboxOutboxAdminConfig.builder().operationRetentionDays(45).build();

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
        @DisplayName("all three cleanups succeed → returns success")
        void allSucceed() {
            stubAll(Future.succeededFuture(3), Future.succeededFuture(5), Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertTrue(result.succeeded());
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("published cleanup fails — other two still run and outcome carries the cause")
        void publishedFails_othersStillRun() {
            RuntimeException boom = new RuntimeException("published failure");
            stubAll(Future.failedFuture(boom), Future.succeededFuture(5), Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            assertNotNull(result.cause());
            assertTrue(allCauses(result.cause()).contains(boom));
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("all three fail — returned failure carries every underlying cause")
        void allFail_carriesAllCauses() {
            RuntimeException b1 = new RuntimeException("published failure");
            RuntimeException b2 = new RuntimeException("dead-letter failure");
            RuntimeException b3 = new RuntimeException("inbox failure");
            stubAll(Future.failedFuture(b1), Future.failedFuture(b2), Future.failedFuture(b3));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            List<Throwable> causes = allCauses(result.cause());
            assertTrue(causes.contains(b1));
            assertTrue(causes.contains(b2));
            assertTrue(causes.contains(b3));
            verifyAllCleanupsInvoked();
        }

        @Test
        @DisplayName("inbox cleanup fails — outcome is failure even when both outbox cleanups succeed")
        void onlyInboxFails() {
            RuntimeException boom = new RuntimeException("inbox failure");
            stubAll(Future.succeededFuture(3), Future.succeededFuture(5), Future.failedFuture(boom));

            Future<Void> result = service.cleanup();

            assertTrue(result.failed());
            assertTrue(allCauses(result.cause()).contains(boom));
        }

        @Test
        @DisplayName(
                "never short-circuits — when published fails synchronously, dead-letter and inbox still observe their stubs")
        void neverShortCircuits() {
            stubAll(
                    Future.failedFuture(new RuntimeException("published failure")),
                    Future.succeededFuture(5),
                    Future.succeededFuture(7));

            Future<Void> result = service.cleanup();

            assertFalse(result.succeeded());
            verifyAllCleanupsInvoked();
        }

        private void stubAll(
                Future<Integer> publishedOutcome, Future<Integer> deadLetterOutcome, Future<Integer> inboxOutcome) {
            when(outboxRepository.cleanupPublished(
                            CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(publishedOutcome);
            when(outboxRepository.cleanupDeadLetter(
                            CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(deadLetterOutcome);
            when(inboxRepository.cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(inboxOutcome);
        }

        private void verifyAllCleanupsInvoked() {
            verify(outboxRepository)
                    .cleanupPublished(CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(outboxRepository)
                    .cleanupDeadLetter(CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(inboxRepository).cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
        }
    }

    @Nested
    @DisplayName("cleanup() with receipt retention")
    class CleanupWithOperations {

        private OutboxMaintenanceService fourOutcomes;

        @BeforeEach
        void build() {
            fourOutcomes = new OutboxMaintenanceService(
                    outboxRepository, inboxRepository, RELAY_CONFIG, CLEANUP_CONFIG, operationCleanup, ADMIN_CONFIG);
        }

        @Test
        @DisplayName("all four cleanups succeed and receipts use the admin retention with the shared batch size")
        void allSucceed() {
            stubAll(
                    Future.succeededFuture(1),
                    Future.succeededFuture(2),
                    Future.succeededFuture(3),
                    Future.succeededFuture(4));

            Future<Void> result = fourOutcomes.cleanup();

            assertTrue(result.succeeded());
            verifyAllInvoked();
        }

        @Test
        @DisplayName("operations cleanup fails alone — outcome is labelled 'operations' and counted of 4")
        void operationsFailsAlone() {
            RuntimeException boom = new RuntimeException("operations failure");
            stubAll(
                    Future.succeededFuture(1),
                    Future.succeededFuture(2),
                    Future.succeededFuture(3),
                    Future.failedFuture(boom));

            Future<Void> result = fourOutcomes.cleanup();

            assertTrue(result.failed());
            assertInstanceOf(OutboxMaintenanceException.class, result.cause());
            assertSame(boom, result.cause().getCause());
            assertEquals(0, result.cause().getSuppressed().length);
            assertEquals(
                    "outbox cleanup failed for [operations] (1 of 4): operations failure",
                    result.cause().getMessage());
            verifyAllInvoked();
        }

        @Test
        @DisplayName("operations fails together with others — every cause reported, first is primary")
        void operationsFailsWithOthers() {
            RuntimeException published = new RuntimeException("published failure");
            RuntimeException inbox = new RuntimeException("inbox failure");
            RuntimeException operations = new RuntimeException("operations failure");
            stubAll(
                    Future.failedFuture(published),
                    Future.succeededFuture(2),
                    Future.failedFuture(inbox),
                    Future.failedFuture(operations));

            Future<Void> result = fourOutcomes.cleanup();

            assertTrue(result.failed());
            assertSame(published, result.cause().getCause());
            assertEquals(List.of(inbox, operations), List.of(result.cause().getSuppressed()));
            assertEquals(
                    "outbox cleanup failed for [published, inbox, operations] (3 of 4): published failure",
                    result.cause().getMessage());
            verifyAllInvoked();
        }

        @Test
        @DisplayName("all four fail — all causes reported")
        void allFour() {
            RuntimeException b1 = new RuntimeException("1");
            RuntimeException b2 = new RuntimeException("2");
            RuntimeException b3 = new RuntimeException("3");
            RuntimeException b4 = new RuntimeException("4");
            stubAll(Future.failedFuture(b1), Future.failedFuture(b2), Future.failedFuture(b3), Future.failedFuture(b4));

            Future<Void> result = fourOutcomes.cleanup();

            assertSame(b1, result.cause().getCause());
            assertEquals(List.of(b2, b3, b4), List.of(result.cause().getSuppressed()));
            assertTrue(result.cause()
                    .getMessage()
                    .startsWith(
                            "outbox cleanup failed for [published, deadLetter, " + "inbox, operations] (4 of 4): 1"));
        }

        @Test
        @DisplayName("the four-argument constructor keeps three outcomes and never touches receipts")
        void legacyConstructorKeepsThreeOutcomes() {
            RuntimeException boom = new RuntimeException("inbox failure");
            when(outboxRepository.cleanupPublished(anyInt(), anyInt())).thenReturn(Future.succeededFuture(1));
            when(outboxRepository.cleanupDeadLetter(anyInt(), anyInt())).thenReturn(Future.succeededFuture(1));
            when(inboxRepository.cleanup(anyInt(), anyInt())).thenReturn(Future.failedFuture(boom));

            Future<Void> result = service.cleanup();

            assertEquals(
                    "outbox cleanup failed for [inbox] (1 of 3): inbox failure",
                    result.cause().getMessage());
            verify(operationCleanup, never()).cleanupOperations(anyInt(), anyInt());
        }

        private void stubAll(
                Future<Integer> published,
                Future<Integer> deadLetter,
                Future<Integer> inbox,
                Future<Integer> operations) {
            when(outboxRepository.cleanupPublished(
                            CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(published);
            when(outboxRepository.cleanupDeadLetter(
                            CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(deadLetter);
            when(inboxRepository.cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(inbox);
            when(operationCleanup.cleanupOperations(
                            ADMIN_CONFIG.operationRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize()))
                    .thenReturn(operations);
        }

        private void verifyAllInvoked() {
            verify(outboxRepository)
                    .cleanupPublished(CLEANUP_CONFIG.publishedRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(outboxRepository)
                    .cleanupDeadLetter(CLEANUP_CONFIG.deadLetterRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(inboxRepository).cleanup(CLEANUP_CONFIG.inboxRetentionDays(), CLEANUP_CONFIG.cleanupBatchSize());
            verify(operationCleanup).cleanupOperations(45, CLEANUP_CONFIG.cleanupBatchSize());
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
