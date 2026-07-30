// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link JobLogFlusher}: batch delivery to the repository, ack on success,
 * nack-and-retain on failure (without failing the caller), containment of every way a public-SPI
 * {@link JobRepository} can misbehave (synchronous throw, {@code null} return, a write that never
 * settles), resolution of the drainable logger from a {@link DefaultJobContext}, and the no-op
 * constructions used for executions that have no {@code job_executions} row to reference or no
 * drainable buffer at all.
 */
@DisplayName("JobLogFlusher")
class JobLogFlusherTest {

    private JobRepository repository;
    private DefaultJobLogger logger;
    private UUID executionId;

    @BeforeEach
    void setUp() {
        repository = mock(JobRepository.class);
        logger = new DefaultJobLogger();
        executionId = UUID.randomUUID();
    }

    @Test
    @DisplayName("flush sends the claimed entries to saveLogs with the execution id")
    void flushSendsClaimedEntries() {
        when(repository.saveLogs(any(), any())).thenReturn(Future.succeededFuture());
        logger.info("first");
        logger.warn("second");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush must succeed when saveLogs succeeds");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLogs(eq(executionId), captor.capture());
        List<LogEntry> sent = captor.getValue();
        assertEquals(2, sent.size(), "both buffered entries must be sent");
        assertEquals("first", sent.get(0).message());
        assertEquals("second", sent.get(1).message());
    }

    @Test
    @DisplayName("flush acks on success so a later flush does not re-send persisted entries")
    void flushAcksOnSuccess() {
        when(repository.saveLogs(any(), any())).thenReturn(Future.succeededFuture());
        logger.info("only once");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        flusher.flush();
        Future<Void> second = flusher.flush();

        assertTrue(second.succeeded(), "the second flush must succeed");
        verify(repository, times(1)).saveLogs(any(), any());
    }

    @Test
    @DisplayName("flush nacks on failure: the future still succeeds and the batch is re-sent next flush")
    void flushNacksOnFailure() {
        when(repository.saveLogs(any(), any()))
                .thenReturn(Future.failedFuture(new RuntimeException("db down")))
                .thenReturn(Future.succeededFuture());
        logger.error("must not be lost");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> first = flusher.flush();

        assertTrue(first.succeeded(), "a failed log flush must never fail the job it belongs to");

        Future<Void> second = flusher.flush();

        assertTrue(second.succeeded(), "the retry flush must succeed");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveLogs(eq(executionId), captor.capture());
        List<LogEntry> retried = captor.getAllValues().get(1);
        assertEquals(1, retried.size(), "the nacked batch must be retried");
        assertEquals("must not be lost", retried.get(0).message());
    }

    @Test
    @DisplayName("flush survives a synchronous throw from saveLogs and retains the batch")
    void flushSurvivesSynchronousThrow() {
        // A closed pool throws from saveLogs rather than returning a failed future.
        when(repository.saveLogs(any(), any()))
                .thenThrow(new IllegalStateException("pool closed"))
                .thenReturn(Future.succeededFuture());
        logger.error("must not be lost");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> first = flusher.flush();

        assertTrue(first.succeeded(), "a synchronous saveLogs throw must not escape flush");
        assertRetriedBatchIsResent(flusher, "must not be lost");
    }

    @Test
    @DisplayName("flush survives a null return from saveLogs and retains the batch")
    void flushSurvivesNullReturn() {
        // JobRepository is public SPI: a third-party implementation may return null.
        when(repository.saveLogs(any(), any())).thenReturn(null).thenReturn(Future.succeededFuture());
        logger.error("must not be lost");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> first = flusher.flush();

        assertTrue(first.succeeded(), "a null saveLogs return must not NPE out of flush");
        assertRetriedBatchIsResent(flusher, "must not be lost");
    }

    @Test
    @DisplayName("flush times out a write that never settles and retains the batch")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void flushTimesOutAWriteThatNeverSettles() throws Exception {
        // A hung write would otherwise leave the single-flight marker set forever: neither ack()
        // nor nack() runs, so no later flush can ever claim again and the buffer grows unbounded.
        Promise<Void> neverSettles = Promise.promise();
        when(repository.saveLogs(any(), any()))
                .thenReturn(neverSettles.future())
                .thenReturn(Future.succeededFuture());
        logger.error("must not be lost");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> first = flusher.flush();

        first.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertTrue(first.succeeded(), "a timed-out write must not fail the job whose logs these are");
        assertRetriedBatchIsResent(flusher, "must not be lost");
    }

    /**
     * Asserts that the next flush re-sends the previously failed batch — proof that the failure
     * path cleared the single-flight marker instead of wedging it.
     *
     * @param flusher         the flusher under test, whose previous flush failed
     * @param expectedMessage the message of the single entry expected in the retried batch
     */
    private void assertRetriedBatchIsResent(JobLogFlusher flusher, String expectedMessage) {
        Future<Void> retry = flusher.flush();

        assertTrue(retry.succeeded(), "the retry flush must succeed");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveLogs(eq(executionId), captor.capture());
        List<LogEntry> retried = captor.getAllValues().get(1);
        assertEquals(1, retried.size(), "the retained batch must be re-sent by the next flush");
        assertEquals(expectedMessage, retried.get(0).message());
    }

    @Test
    @DisplayName("flush is a no-op when nothing is buffered")
    void flushIsNoOpWhenNothingBuffered() {
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, logger);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "an empty flush must succeed");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("flush is a no-op without a repository, even with buffered entries")
    void flushIsNoOpWithoutRepository() {
        logger.info("buffered but unflushable");
        JobLogFlusher flusher = new JobLogFlusher(null, executionId, logger);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush without a repository must succeed");
        assertEquals(1, logger.entries().size(), "entries must stay buffered, not be claimed and dropped");
    }

    @Test
    @DisplayName("flush is a no-op for a foreign JobContext (no drainable buffer to claim)")
    void flushIsNoOpForForeignJobContext() {
        JobContext foreign = mock(JobContext.class);
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, foreign);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush over a foreign JobContext must succeed");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("flush drains the logger of a DefaultJobContext resolved through the JobContext overload")
    void flushDrainsDefaultJobContextLogger() {
        when(repository.saveLogs(any(), any())).thenReturn(Future.succeededFuture());
        DefaultJobContext context = new DefaultJobContext("job-1", executionId, 0, JobType.DELAYED);
        context.logger().info("from the context");
        JobLogFlusher flusher = new JobLogFlusher(repository, executionId, context);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush must succeed when saveLogs succeeds");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LogEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveLogs(eq(executionId), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("from the context", captor.getValue().get(0).message());
    }

    @Test
    @DisplayName("flush is a no-op without an execution id (untracked fire has no job_executions row)")
    void flushIsNoOpWithoutExecutionId() {
        logger.info("untracked cron fire");
        JobLogFlusher flusher = new JobLogFlusher(repository, null, logger);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush without an execution id must succeed");
        verifyNoInteractions(repository);
    }
}
