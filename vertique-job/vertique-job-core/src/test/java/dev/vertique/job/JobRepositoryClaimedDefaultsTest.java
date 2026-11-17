// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.vertx.core.Future;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A repository that cannot test the claim keeps working: the claim-fenced recovery methods default to
 * the unfenced writes.
 */
class JobRepositoryClaimedDefaultsTest {

    private final JobRepository repository =
            mock(JobRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("completeClaimedExecution defaults to completeExecution")
    void completeClaimedDefaultsToUnfenced() {
        Future<Optional<JobExecution>> expected = Future.succeededFuture(Optional.empty());
        when(repository.completeExecution(any(), any(), any(), any(), any())).thenReturn(expected);

        Future<Optional<JobExecution>> actual = repository.completeClaimedExecution(
                id, "dead-node", JobState.ABANDONED, "crashed", "NodeCrashException", ProgressSnapshot.EMPTY);

        assertSame(expected, actual);
        verify(repository)
                .completeExecution(id, JobState.ABANDONED, "crashed", "NodeCrashException", ProgressSnapshot.EMPTY);
    }

    @Test
    @DisplayName("abandonClaimedAndScheduleRetry defaults to abandonAndScheduleRetry")
    void abandonClaimedDefaultsToUnfenced() {
        Instant when = Instant.now();
        Future<Optional<JobExecution>> expected = Future.succeededFuture(Optional.empty());
        when(repository.abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(expected);

        Future<Optional<JobExecution>> actual = repository.abandonClaimedAndScheduleRetry(
                id, "dead-node", "crashed", "NodeCrashException", ProgressSnapshot.EMPTY, when, 1);

        assertSame(expected, actual);
        verify(repository)
                .abandonAndScheduleRetry(id, "crashed", "NodeCrashException", ProgressSnapshot.EMPTY, when, 1);
    }
}
