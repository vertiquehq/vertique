// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobExecutionStateTransitionEvent;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The claim-fenced recovery writes pass through {@link NotifyingJobRepository} to the delegate's
 * fenced implementation, carrying the claim owner, and notify listeners only when a row transitioned.
 */
class NotifyingJobRepositoryFenceTest {

    private final JobRepository delegate = mock(JobRepository.class);
    private final UUID executionId = UUID.randomUUID();
    private final List<JobExecutionStateTransitionEvent> events = new ArrayList<>();
    private final NotifyingJobRepository repository = new NotifyingJobRepository(delegate, Set.of(events::add));

    @Test
    @DisplayName("completeClaimedExecution forwards the claim owner to the delegate and notifies once")
    void completeClaimedForwardsAndNotifies() {
        when(delegate.completeClaimedExecution(any(), any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.of(execution(JobState.ABANDONED))));

        Future<Optional<JobExecution>> result = repository.completeClaimedExecution(
                executionId, "dead-node", JobState.ABANDONED, "crashed", "NodeCrashException", ProgressSnapshot.EMPTY);

        assertTrue(result.succeeded());
        verify(delegate)
                .completeClaimedExecution(
                        executionId,
                        "dead-node",
                        JobState.ABANDONED,
                        "crashed",
                        "NodeCrashException",
                        ProgressSnapshot.EMPTY);
        verify(delegate, never()).completeExecution(any(), any(), any(), any(), any());
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("abandonClaimedAndScheduleRetry forwards the claim owner to the delegate and notifies once")
    void abandonClaimedForwardsAndNotifies() {
        Instant when = Instant.now();
        when(delegate.abandonClaimedAndScheduleRetry(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Future.succeededFuture(Optional.of(execution(JobState.ABANDONED))));

        Future<Optional<JobExecution>> result = repository.abandonClaimedAndScheduleRetry(
                executionId, "dead-node", "crashed", "NodeCrashException", ProgressSnapshot.EMPTY, when, 1);

        assertTrue(result.succeeded());
        verify(delegate)
                .abandonClaimedAndScheduleRetry(
                        executionId, "dead-node", "crashed", "NodeCrashException", ProgressSnapshot.EMPTY, when, 1);
        verify(delegate, never()).abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt());
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("a fenced write that matches no row notifies nobody")
    void emptyResultNotifiesNobody() {
        when(delegate.completeClaimedExecution(any(), any(), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.empty()));
        when(delegate.abandonClaimedAndScheduleRetry(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Future.succeededFuture(Optional.empty()));

        repository.completeClaimedExecution(
                executionId,
                "dead-node",
                JobState.DEAD_LETTER,
                "crashed",
                "NodeCrashException",
                ProgressSnapshot.EMPTY);
        repository.abandonClaimedAndScheduleRetry(
                executionId, "dead-node", "crashed", "NodeCrashException", ProgressSnapshot.EMPTY, Instant.now(), 1);

        assertTrue(events.isEmpty());
    }

    private JobExecution execution(JobState state) {
        Instant now = Instant.now();
        return new JobExecution(
                executionId,
                "job-1",
                JobType.DELAYED,
                "handler.address",
                "default",
                state,
                0,
                3,
                null,
                0,
                null,
                now,
                now,
                now,
                now,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                DurableMetadata.empty(),
                null);
    }
}
