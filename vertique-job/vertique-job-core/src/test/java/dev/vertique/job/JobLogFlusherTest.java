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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link JobLogFlusher}: batch delivery to the repository, ack on success,
 * nack-and-retain on failure (without failing the caller), and the no-op constructions used for
 * executions that have no {@code job_executions} row to reference.
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
    @DisplayName("flush is a no-op without an execution id (untracked fire has no job_executions row)")
    void flushIsNoOpWithoutExecutionId() {
        logger.info("untracked cron fire");
        JobLogFlusher flusher = new JobLogFlusher(repository, null, logger);

        Future<Void> result = flusher.flush();

        assertTrue(result.succeeded(), "flush without an execution id must succeed");
        verifyNoInteractions(repository);
    }
}
