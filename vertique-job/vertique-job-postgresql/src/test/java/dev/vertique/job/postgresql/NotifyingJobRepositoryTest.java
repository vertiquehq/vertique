// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobExecutionStateTransitionEvent;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link NotifyingJobRepository}.
 *
 * <p>Verifies that the decorator:
 * <ul>
 *   <li>delegates all non-{@code completeExecution} methods verbatim to the raw repository;</li>
 *   <li>fires exactly one {@link JobExecutionStateTransitionEvent} per real persisted transition
 *       on {@code completeExecution}, with fields matching the returned {@link JobExecution};</li>
 *   <li>does not notify listeners when the result is {@link Optional#empty()} (idempotent no-op);</li>
 *   <li>propagates delegate failures without notifying listeners;</li>
 *   <li>isolates listener exceptions — a throwing first listener does not prevent the second from
 *       running, and the returned future still succeeds.</li>
 * </ul>
 */
class NotifyingJobRepositoryTest {

    private JobRepository delegate;

    @BeforeEach
    void setUp() {
        delegate = mock(JobRepository.class);
    }

    // --- completeExecution: present result ---

    @Nested
    @DisplayName("completeExecution with a present result")
    class CompleteExecutionPresent {

        private final UUID execId = UUID.randomUUID();
        private final Instant now = Instant.now();

        /** A minimal valid {@link JobExecution} fixture. */
        private JobExecution makeExecution(JobState state) {
            return new JobExecution(
                    execId,
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
                    DurableMetadata.empty());
        }

        @Test
        @DisplayName("fires exactly one event whose fields match the returned JobExecution")
        void firesEventMatchingExecution() {
            JobExecution exec = makeExecution(JobState.SUCCEEDED);
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result =
                    repo.completeExecution(execId, JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isPresent(), "Optional must be present");

            assertEquals(1, received.size(), "exactly one event must be fired");
            JobExecutionStateTransitionEvent event = received.get(0);

            assertEquals(execId, event.executionId(), "executionId mismatch");
            assertEquals("job-1", event.jobId(), "jobId mismatch");
            assertEquals(JobType.DELAYED, event.jobType(), "jobType mismatch");
            assertEquals("default", event.queue(), "queue mismatch");
            assertEquals(JobState.SUCCEEDED, event.newState(), "newState mismatch");
            assertEquals(0, event.attemptNumber(), "attemptNumber mismatch");
            assertEquals(3, event.maxAttempts(), "maxAttempts mismatch");
            assertNull(event.errorType(), "errorType must be null on success");
            assertEquals(now, event.scheduledAt(), "scheduledAt mismatch");
            assertEquals(now, event.enqueuedAt(), "enqueuedAt mismatch");
            assertEquals(now, event.startedAt(), "startedAt mismatch");
            assertEquals(now, event.completedAt(), "completedAt mismatch");
            assertEquals(DurableMetadata.empty(), event.metadata(), "metadata mismatch");
        }

        @Test
        @DisplayName("second listener also receives the event when first listener throws")
        void isolatesThrowingListener() {
            JobExecution exec = makeExecution(JobState.FAILED);
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            List<JobExecutionStateTransitionEvent> secondReceived = new ArrayList<>();
            JobExecutionStateTransitionListener throwing = e -> {
                throw new RuntimeException("listener boom");
            };
            JobExecutionStateTransitionListener capturing = secondReceived::add;

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(throwing, capturing));

            Future<Optional<JobExecution>> result =
                    repo.completeExecution(execId, JobState.FAILED, "err", "ErrType", ProgressSnapshot.EMPTY);

            assertTrue(result.succeeded(), "Future must still succeed despite listener throwing");
            assertEquals(1, secondReceived.size(), "second listener must still receive the event");
        }
    }

    // --- completeExecution: empty result ---

    @Nested
    @DisplayName("completeExecution with an empty result (idempotent no-op)")
    class CompleteExecutionEmpty {

        @Test
        @DisplayName("fires no events when Optional is empty")
        void noEventWhenEmpty() {
            UUID execId = UUID.randomUUID();
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result =
                    repo.completeExecution(execId, JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isEmpty(), "result Optional must be empty");
            assertTrue(received.isEmpty(), "no events must be fired for an empty result");
        }
    }

    // --- completeExecution: delegate failure ---

    @Nested
    @DisplayName("completeExecution when the delegate fails")
    class CompleteExecutionFailure {

        @Test
        @DisplayName("propagates the delegate failure and fires no events")
        void propagatesFailureAndNoEvents() {
            UUID execId = UUID.randomUUID();
            RuntimeException cause = new RuntimeException("db error");
            when(delegate.completeExecution(any(), any(), any(), any(), any())).thenReturn(Future.failedFuture(cause));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result =
                    repo.completeExecution(execId, JobState.FAILED, null, null, ProgressSnapshot.EMPTY);

            assertTrue(result.failed(), "Future must be failed");
            assertSame(cause, result.cause(), "failure cause must be propagated");
            assertTrue(received.isEmpty(), "no events must be fired when the delegate fails");
        }
    }

    // --- delegation of other methods ---

    @Nested
    @DisplayName("non-completeExecution methods delegate verbatim")
    class DelegationTests {

        @Test
        @DisplayName("findById delegates to the raw repository")
        void findByIdDelegates() {
            UUID id = UUID.randomUUID();
            when(delegate.findById(id)).thenReturn(Future.succeededFuture(Optional.empty()));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of());

            Future<Optional<JobExecution>> result = repo.findById(id);

            assertTrue(result.succeeded(), "Future must succeed");
            verify(delegate).findById(eq(id));
        }

        @Test
        @DisplayName("save delegates to the raw repository")
        void saveDelegates() {
            JobExecution exec = new JobExecution(
                    UUID.randomUUID(),
                    "j",
                    JobType.CRON,
                    "h",
                    "q",
                    JobState.ENQUEUED,
                    0,
                    1,
                    null,
                    0,
                    null,
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    ProgressSnapshot.EMPTY,
                    null,
                    null,
                    DurableMetadata.empty());
            UUID returnedId = exec.id();
            when(delegate.save(exec)).thenReturn(Future.succeededFuture(returnedId));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of());
            Future<UUID> result = repo.save(exec);

            assertTrue(result.succeeded(), "Future must succeed");
            assertEquals(returnedId, result.result(), "returned ID must match delegate result");
            verify(delegate).save(eq(exec));
            // completeExecution must never be called during save
            verify(delegate, never()).completeExecution(any(), any(), any(), any(), any());
        }
    }

    // --- failAndScheduleRetry: present result ---

    @Nested
    @DisplayName("failAndScheduleRetry with a present result (FAILED snapshot)")
    class FailAndScheduleRetryPresent {

        private final UUID execId = UUID.randomUUID();
        private final Instant now = Instant.now();

        /** A minimal valid {@link JobExecution} fixture in the FAILED state. */
        private JobExecution makeFailedExecution() {
            return new JobExecution(
                    execId,
                    "retry-job",
                    JobType.DELAYED,
                    "handler.address",
                    "default",
                    JobState.FAILED,
                    1,
                    3,
                    null,
                    0,
                    null,
                    now,
                    now,
                    now,
                    now,
                    "transient error",
                    "java.io.IOException",
                    ProgressSnapshot.EMPTY,
                    null,
                    null,
                    DurableMetadata.empty());
        }

        @Test
        @DisplayName("fires exactly one event whose fields match the FAILED snapshot")
        void firesEventMatchingFailedSnapshot() {
            JobExecution failedSnapshot = makeFailedExecution();
            Instant nextScheduledAt = now.plusSeconds(30);
            when(delegate.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(failedSnapshot)));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.failAndScheduleRetry(
                    execId, "transient error", "java.io.IOException", ProgressSnapshot.EMPTY, nextScheduledAt, 1);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isPresent(), "Optional must be present (FAILED snapshot)");

            assertEquals(1, received.size(), "exactly one event must be fired");
            JobExecutionStateTransitionEvent event = received.get(0);

            assertEquals(execId, event.executionId(), "executionId mismatch");
            assertEquals("retry-job", event.jobId(), "jobId mismatch");
            assertEquals(JobType.DELAYED, event.jobType(), "jobType mismatch");
            assertEquals("default", event.queue(), "queue mismatch");
            // The event reflects the FAILED snapshot, not the committed ENQUEUED row
            assertEquals(JobState.FAILED, event.newState(), "newState must be FAILED (audit snapshot)");
            assertEquals(1, event.attemptNumber(), "attemptNumber mismatch");
            assertEquals(3, event.maxAttempts(), "maxAttempts mismatch");
            assertEquals("java.io.IOException", event.errorType(), "errorType mismatch");
            assertEquals(DurableMetadata.empty(), event.metadata(), "metadata mismatch");
        }

        @Test
        @DisplayName("delegates to the raw repository with correct arguments")
        void delegatesWithCorrectArgs() {
            JobExecution failedSnapshot = makeFailedExecution();
            Instant nextScheduledAt = now.plusSeconds(60);
            when(delegate.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(failedSnapshot)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of());
            repo.failAndScheduleRetry(
                    execId, "boom", "java.lang.RuntimeException", ProgressSnapshot.EMPTY, nextScheduledAt, 2);

            verify(delegate)
                    .failAndScheduleRetry(
                            eq(execId),
                            eq("boom"),
                            eq("java.lang.RuntimeException"),
                            eq(ProgressSnapshot.EMPTY),
                            eq(nextScheduledAt),
                            eq(2));
        }
    }

    // --- failAndScheduleRetry: empty result ---

    @Nested
    @DisplayName("failAndScheduleRetry with an empty result (idempotent no-op)")
    class FailAndScheduleRetryEmpty {

        @Test
        @DisplayName("fires no events when Optional is empty")
        void noEventWhenEmpty() {
            UUID execId = UUID.randomUUID();
            when(delegate.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.failAndScheduleRetry(
                    execId,
                    "err",
                    "SomeException",
                    ProgressSnapshot.EMPTY,
                    Instant.now().plusSeconds(30),
                    1);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isEmpty(), "result Optional must be empty");
            assertTrue(received.isEmpty(), "no events must be fired for an empty result");
        }
    }

    // --- failAndScheduleRetry: delegate failure ---

    @Nested
    @DisplayName("failAndScheduleRetry when the delegate fails")
    class FailAndScheduleRetryFailure {

        @Test
        @DisplayName("propagates the delegate failure and fires no events")
        void propagatesFailureAndNoEvents() {
            UUID execId = UUID.randomUUID();
            RuntimeException cause = new RuntimeException("db error");
            when(delegate.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.failedFuture(cause));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.failAndScheduleRetry(
                    execId,
                    "err",
                    "SomeException",
                    ProgressSnapshot.EMPTY,
                    Instant.now().plusSeconds(30),
                    1);

            assertTrue(result.failed(), "Future must be failed");
            assertSame(cause, result.cause(), "failure cause must be propagated");
            assertTrue(received.isEmpty(), "no events must be fired when the delegate fails");
        }
    }

    // --- abandonAndScheduleRetry: present result ---

    @Nested
    @DisplayName("abandonAndScheduleRetry with a present result (ABANDONED snapshot)")
    class AbandonAndScheduleRetryPresent {

        private final UUID execId = UUID.randomUUID();
        private final Instant now = Instant.now();

        /** A minimal valid {@link JobExecution} fixture in the ABANDONED state. */
        private JobExecution makeAbandonedExecution() {
            return new JobExecution(
                    execId,
                    "retry-job",
                    JobType.DELAYED,
                    "handler.address",
                    "default",
                    JobState.ABANDONED,
                    1,
                    3,
                    null,
                    0,
                    null,
                    now,
                    now,
                    now,
                    now,
                    "Execution timeout",
                    "ExecutionTimeoutException",
                    ProgressSnapshot.EMPTY,
                    null,
                    null,
                    DurableMetadata.empty());
        }

        @Test
        @DisplayName("fires exactly one event whose fields match the ABANDONED snapshot")
        void firesEventMatchingAbandonedSnapshot() {
            JobExecution abandonedSnapshot = makeAbandonedExecution();
            Instant nextScheduledAt = now.plusSeconds(30);
            when(delegate.abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(abandonedSnapshot)));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.abandonAndScheduleRetry(
                    execId,
                    "Execution timeout",
                    "ExecutionTimeoutException",
                    ProgressSnapshot.EMPTY,
                    nextScheduledAt,
                    1);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isPresent(), "Optional must be present (ABANDONED snapshot)");

            assertEquals(1, received.size(), "exactly one event must be fired");
            JobExecutionStateTransitionEvent event = received.get(0);

            assertEquals(execId, event.executionId(), "executionId mismatch");
            assertEquals("retry-job", event.jobId(), "jobId mismatch");
            assertEquals(JobType.DELAYED, event.jobType(), "jobType mismatch");
            assertEquals("default", event.queue(), "queue mismatch");
            // The event reflects the ABANDONED snapshot, not the committed ENQUEUED row
            assertEquals(JobState.ABANDONED, event.newState(), "newState must be ABANDONED (audit snapshot)");
            assertEquals(1, event.attemptNumber(), "attemptNumber mismatch");
            assertEquals(3, event.maxAttempts(), "maxAttempts mismatch");
            assertEquals("ExecutionTimeoutException", event.errorType(), "errorType mismatch");
            assertEquals(DurableMetadata.empty(), event.metadata(), "metadata mismatch");
        }

        @Test
        @DisplayName("delegates to the raw repository with correct arguments")
        void delegatesWithCorrectArgs() {
            JobExecution abandonedSnapshot = makeAbandonedExecution();
            Instant nextScheduledAt = now.plusSeconds(60);
            when(delegate.abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.of(abandonedSnapshot)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of());
            repo.abandonAndScheduleRetry(
                    execId,
                    "Execution timeout",
                    "ExecutionTimeoutException",
                    ProgressSnapshot.EMPTY,
                    nextScheduledAt,
                    2);

            verify(delegate)
                    .abandonAndScheduleRetry(
                            eq(execId),
                            eq("Execution timeout"),
                            eq("ExecutionTimeoutException"),
                            eq(ProgressSnapshot.EMPTY),
                            eq(nextScheduledAt),
                            eq(2));
        }
    }

    // --- abandonAndScheduleRetry: empty result ---

    @Nested
    @DisplayName("abandonAndScheduleRetry with an empty result (idempotent no-op)")
    class AbandonAndScheduleRetryEmpty {

        @Test
        @DisplayName("fires no events when Optional is empty")
        void noEventWhenEmpty() {
            UUID execId = UUID.randomUUID();
            when(delegate.abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(Optional.empty()));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.abandonAndScheduleRetry(
                    execId,
                    "Execution timeout",
                    "ExecutionTimeoutException",
                    ProgressSnapshot.EMPTY,
                    Instant.now().plusSeconds(30),
                    1);

            assertTrue(result.succeeded(), "Future must succeed");
            assertTrue(result.result().isEmpty(), "result Optional must be empty");
            assertTrue(received.isEmpty(), "no events must be fired for an empty result");
        }
    }

    // --- abandonAndScheduleRetry: delegate failure ---

    @Nested
    @DisplayName("abandonAndScheduleRetry when the delegate fails")
    class AbandonAndScheduleRetryFailure {

        @Test
        @DisplayName("propagates the delegate failure and fires no events")
        void propagatesFailureAndNoEvents() {
            UUID execId = UUID.randomUUID();
            RuntimeException cause = new RuntimeException("db error");
            when(delegate.abandonAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.failedFuture(cause));

            List<JobExecutionStateTransitionEvent> received = new ArrayList<>();
            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(received::add));

            Future<Optional<JobExecution>> result = repo.abandonAndScheduleRetry(
                    execId,
                    "Execution timeout",
                    "ExecutionTimeoutException",
                    ProgressSnapshot.EMPTY,
                    Instant.now().plusSeconds(30),
                    1);

            assertTrue(result.failed(), "Future must be failed");
            assertSame(cause, result.cause(), "failure cause must be propagated");
            assertTrue(received.isEmpty(), "no events must be fired when the delegate fails");
        }
    }

    // --- listener warn-log characterization ---

    /**
     * Characterization tests that pin the EXACT warning log format emitted by
     * {@link NotifyingJobRepository#notifyListeners} when a listener throws a
     * {@link RuntimeException}.
     *
     * <p>These tests capture the Logback appender attached to
     * {@code dev.vertique.job.postgresql.NotifyingJobRepository}, assert on the SLF4J message
     * template, argument array, and throwable proxy, and verify that listeners after a throwing
     * listener still execute. They must stay GREEN against the current implementation; the migration
     * step uses them to prove the migrated code produces an identical log.
     */
    @Nested
    @DisplayName("notifyListeners warn-log characterization (current loop)")
    class ListenerWarnLogCharacterization {

        private ListAppender<ILoggingEvent> appender;
        private Logger repoLogger;

        @BeforeEach
        void attachAppender() {
            repoLogger = (Logger) LoggerFactory.getLogger(NotifyingJobRepository.class);
            appender = new ListAppender<>();
            appender.start();
            repoLogger.addAppender(appender);
            repoLogger.setLevel(Level.WARN);
        }

        @AfterEach
        void detachAppender() {
            repoLogger.detachAppender(appender);
            appender.stop();
        }

        /** A static test-double listener that always throws a fixed {@link RuntimeException}. */
        private static final class ThrowingListener implements JobExecutionStateTransitionListener {
            private final RuntimeException error;

            ThrowingListener(RuntimeException error) {
                this.error = error;
            }

            @Override
            public void onStateTransition(JobExecutionStateTransitionEvent event) {
                throw error;
            }
        }

        /** A static test-double listener that records every event it receives. */
        private static final class RecordingListener implements JobExecutionStateTransitionListener {
            final List<JobExecutionStateTransitionEvent> received = new ArrayList<>();

            @Override
            public void onStateTransition(JobExecutionStateTransitionEvent event) {
                received.add(event);
            }
        }

        private JobExecution minimalExecution() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            return new JobExecution(
                    id,
                    "char-job",
                    JobType.DELAYED,
                    "handler.address",
                    "default",
                    JobState.SUCCEEDED,
                    0,
                    1,
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
                    DurableMetadata.empty());
        }

        @Test
        @DisplayName(
                "warn log fires exactly once with correct message template and argument values when listener throws")
        void warnLogFiredWithExactMessageAndArgs() {
            RuntimeException boom = new RuntimeException("listener boom");
            ThrowingListener throwing = new ThrowingListener(boom);

            JobExecution exec = minimalExecution();
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(throwing));
            repo.completeExecution(exec.id(), JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertEquals(1, appender.list.size(), "exactly one WARN log entry must be emitted");
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel(), "log level must be WARN");
            assertEquals(
                    "JobExecutionStateTransitionListener {} threw during onStateTransition: {}",
                    event.getMessage(),
                    "SLF4J message template must match verbatim");

            Object[] args = event.getArgumentArray();
            // SLF4J receives three args: className, e.toString(), throwable.
            // Logback strips the trailing Throwable from getArgumentArray() — it is available
            // separately via getThrowableProxy(). So the array holds exactly two elements.
            assertEquals(ThrowingListener.class.getName(), args[0], "first arg must be listener class name");
            assertEquals(boom.toString(), args[1], "second arg must be e.toString()");
            // The trailing throwable is accessible via the throwable proxy, not via the arg array.
            assertEquals(
                    boom.getClass().getName(),
                    event.getThrowableProxy().getClassName(),
                    "throwable proxy class must match the thrown exception class");
            assertEquals(
                    boom.getMessage(),
                    event.getThrowableProxy().getMessage(),
                    "throwable proxy message must match the thrown exception message");
        }

        @Test
        @DisplayName("warn log fires once per throwing listener; silent listeners generate no warn")
        void warnLogFiredOncePerThrowingListenerOnly() {
            RuntimeException boom = new RuntimeException("only one throws");
            ThrowingListener throwing = new ThrowingListener(boom);
            RecordingListener silent = new RecordingListener();

            // Use LinkedHashSet to guarantee deterministic iteration order: throwing first.
            Set<JobExecutionStateTransitionListener> ordered = new LinkedHashSet<>();
            ordered.add(throwing);
            ordered.add(silent);

            JobExecution exec = minimalExecution();
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, ordered);
            repo.completeExecution(exec.id(), JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertEquals(1, appender.list.size(), "exactly one WARN must be emitted for the single throwing listener");
            assertEquals(
                    1, silent.received.size(), "the recording listener after the thrower must still receive the event");
        }

        @Test
        @DisplayName("no warn log emitted when all listeners succeed")
        void noWarnLogWhenAllSucceed() {
            RecordingListener first = new RecordingListener();
            RecordingListener second = new RecordingListener();

            JobExecution exec = minimalExecution();
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            // Use LinkedHashSet to guarantee both listeners run and order is deterministic.
            Set<JobExecutionStateTransitionListener> ordered = new LinkedHashSet<>();
            ordered.add(first);
            ordered.add(second);

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, ordered);
            repo.completeExecution(exec.id(), JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertTrue(appender.list.isEmpty(), "no WARN log must be emitted when all listeners succeed");
            assertEquals(1, first.received.size(), "first listener must receive event");
            assertEquals(1, second.received.size(), "second listener must receive event");
        }

        @Test
        @DisplayName("future still succeeds even when a listener throws (synchronous notify does not fail the future)")
        void futureSucceedsEvenWhenListenerThrows() {
            RuntimeException boom = new RuntimeException("throws but future ok");
            ThrowingListener throwing = new ThrowingListener(boom);

            JobExecution exec = minimalExecution();
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of(throwing));
            Future<Optional<JobExecution>> result =
                    repo.completeExecution(exec.id(), JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY);

            assertTrue(result.succeeded(), "future must succeed despite listener throwing");
            assertTrue(result.result().isPresent(), "result must be present");
        }
    }

    // --- empty listener set ---

    @Nested
    @DisplayName("completeExecution with no listeners")
    class NoListeners {

        @Test
        @DisplayName("succeeds without error when listener set is empty")
        void succeedsWithNoListeners() {
            UUID execId = UUID.randomUUID();
            Instant now = Instant.now();
            JobExecution exec = new JobExecution(
                    execId,
                    "j",
                    JobType.DELAYED,
                    "h",
                    "q",
                    JobState.DEAD_LETTER,
                    2,
                    3,
                    null,
                    0,
                    null,
                    now,
                    now,
                    now,
                    now,
                    "fatal",
                    "SomeException",
                    ProgressSnapshot.EMPTY,
                    null,
                    null,
                    DurableMetadata.empty());
            when(delegate.completeExecution(any(), any(), any(), any(), any()))
                    .thenReturn(Future.succeededFuture(Optional.of(exec)));

            NotifyingJobRepository repo = new NotifyingJobRepository(delegate, Set.of());
            Future<Optional<JobExecution>> result = repo.completeExecution(
                    execId, JobState.DEAD_LETTER, "fatal", "SomeException", ProgressSnapshot.EMPTY);

            assertTrue(result.succeeded(), "Future must succeed even with no listeners");
            assertInstanceOf(Optional.class, result.result(), "result type must be Optional<JobExecution>");
        }
    }
}
