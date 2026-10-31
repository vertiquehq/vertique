// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the batching of {@link JobRetentionService}: it deletes batch after batch until one comes
 * back short, stops at the per-run cap, and reports a failed batch.
 */
@DisplayName("JobRetentionService")
class JobRetentionServiceTest {

    private final PgJobRepository repository = mock(PgJobRepository.class);

    private JobRetentionService service(int batchSize) {
        return new JobRetentionService(
                repository,
                JobRetentionConfig.builder()
                        .retentionSeconds(3_600)
                        .batchSize(batchSize)
                        .build());
    }

    @Test
    @DisplayName("passes the retention age and batch size to the repository")
    void passesConfiguration() {
        when(repository.purgeTerminalExecutions(any(), anyInt())).thenReturn(Future.succeededFuture(0));

        assertTrue(service(250).purgeFinishedExecutions().succeeded());

        verify(repository).purgeTerminalExecutions(Duration.ofHours(1), 250);
    }

    @Test
    @DisplayName("stops after the first batch that comes back short")
    void stopsAtAShortBatch() {
        when(repository.purgeTerminalExecutions(any(), anyInt()))
                .thenReturn(Future.succeededFuture(100), Future.succeededFuture(100), Future.succeededFuture(37));

        assertTrue(service(100).purgeFinishedExecutions().succeeded());

        verify(repository, times(3)).purgeTerminalExecutions(any(), anyInt());
    }

    @Test
    @DisplayName("an empty first batch is one call")
    void emptyFirstBatch() {
        when(repository.purgeTerminalExecutions(any(), anyInt())).thenReturn(Future.succeededFuture(0));

        assertTrue(service(100).purgeFinishedExecutions().succeeded());

        verify(repository, times(1)).purgeTerminalExecutions(any(), anyInt());
    }

    @Test
    @DisplayName("stops at the per-run cap even when every batch is full")
    void stopsAtTheRunCap() {
        when(repository.purgeTerminalExecutions(any(), anyInt())).thenReturn(Future.succeededFuture(100));

        assertTrue(service(100).purgeFinishedExecutions().succeeded());

        verify(repository, times(JobRetentionService.MAX_BATCHES_PER_RUN)).purgeTerminalExecutions(any(), anyInt());
    }

    @Test
    @DisplayName("a failed batch fails the run with the repository's cause and runs no further batch")
    void failedBatchFailsTheRun() {
        IllegalStateException cause = new IllegalStateException("pool closed");
        when(repository.purgeTerminalExecutions(any(), anyInt()))
                .thenReturn(Future.succeededFuture(100), Future.failedFuture(cause));

        Future<Void> result = service(100).purgeFinishedExecutions();

        assertTrue(result.failed());
        assertSame(cause, result.cause());
        verify(repository, times(2)).purgeTerminalExecutions(any(), anyInt());
    }

    @Test
    @DisplayName("a repository that throws synchronously fails the run instead of throwing")
    void synchronousThrowFailsTheRun() {
        IllegalStateException cause = new IllegalStateException("not connected");
        when(repository.purgeTerminalExecutions(any(), anyInt())).thenThrow(cause);

        Future<Void> result = service(100).purgeFinishedExecutions();

        assertTrue(result.failed());
        assertEquals("not connected", result.cause().getMessage());
    }

    @Test
    @DisplayName("a null batch count is an empty batch")
    void nullCountIsAnEmptyBatch() {
        when(repository.purgeTerminalExecutions(any(), anyInt())).thenReturn(Future.succeededFuture(null));

        assertTrue(service(100).purgeFinishedExecutions().succeeded());

        verify(repository, times(1)).purgeTerminalExecutions(any(), anyInt());
    }
}
