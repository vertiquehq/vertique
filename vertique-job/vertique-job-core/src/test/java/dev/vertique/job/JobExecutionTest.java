// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.context.DurableMetadata;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobExecution} record: new fields, copy-on-write methods,
 * and compact constructor behaviour.
 */
@DisplayName("JobExecution")
class JobExecutionTest {

    /** Builds a minimal {@link JobExecution} with all nullable fields set to {@code null}. */
    private JobExecution buildExecution() {
        return new JobExecution(
                UUID.randomUUID(),
                "test-job",
                JobType.CRON,
                "handler.address",
                "default",
                JobState.ENQUEUED,
                0,
                3,
                null,
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
    }

    // --- New fields ---

    @Nested
    @DisplayName("new fields")
    class NewFields {

        @Test
        @DisplayName("payload defaults to null")
        void payloadDefaultsToNull() {
            assertNull(buildExecution().payload());
        }

        @Test
        @DisplayName("priority defaults to 0")
        void priorityDefaultsToZero() {
            assertEquals(0, buildExecution().priority());
        }

        @Test
        @DisplayName("lockedBy defaults to null")
        void lockedByDefaultsToNull() {
            assertNull(buildExecution().lockedBy());
        }

        @Test
        @DisplayName("record stores payload value")
        void recordStoresPayload() {
            Object payload = Map.of("key", "value");
            JobExecution exec = new JobExecution(
                    UUID.randomUUID(),
                    "job",
                    JobType.CRON,
                    "addr",
                    "q",
                    JobState.ENQUEUED,
                    0,
                    3,
                    payload,
                    5,
                    "node-1",
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    ProgressSnapshot.EMPTY,
                    Map.of(),
                    Map.of(),
                    DurableMetadata.empty());
            assertSame(payload, exec.payload());
            assertEquals(5, exec.priority());
            assertEquals("node-1", exec.lockedBy());
        }
    }

    // --- withPayload ---

    @Nested
    @DisplayName("withPayload")
    class WithPayload {

        @Test
        @DisplayName("returns a new instance")
        void returnsNewInstance() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withPayload("data");
            assertNotSame(exec, updated);
        }

        @Test
        @DisplayName("returns copy with new payload")
        void returnsCopyWithNewPayload() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withPayload("data");
            assertEquals("data", updated.payload());
        }

        @Test
        @DisplayName("does not mutate the original")
        void doesNotMutateOriginal() {
            JobExecution exec = buildExecution();
            exec.withPayload("data");
            assertNull(exec.payload());
        }

        @Test
        @DisplayName("preserves other fields")
        void preservesOtherFields() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withPayload("data");
            assertEquals(exec.id(), updated.id());
            assertEquals(exec.jobId(), updated.jobId());
            assertEquals(exec.state(), updated.state());
        }
    }

    // --- withLockedBy ---

    @Nested
    @DisplayName("withLockedBy")
    class WithLockedBy {

        @Test
        @DisplayName("returns a new instance")
        void returnsNewInstance() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withLockedBy("node-1");
            assertNotSame(exec, updated);
        }

        @Test
        @DisplayName("returns copy with new lockedBy")
        void returnsCopyWithNewLockedBy() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withLockedBy("node-1");
            assertEquals("node-1", updated.lockedBy());
        }

        @Test
        @DisplayName("does not mutate the original")
        void doesNotMutateOriginal() {
            JobExecution exec = buildExecution();
            exec.withLockedBy("node-1");
            assertNull(exec.lockedBy());
        }

        @Test
        @DisplayName("can clear lockedBy with null")
        void canClearWithNull() {
            JobExecution exec = buildExecution().withLockedBy("node-1");
            JobExecution cleared = exec.withLockedBy(null);
            assertNull(cleared.lockedBy());
        }
    }

    // --- withError ---

    @Nested
    @DisplayName("withError")
    class WithError {

        @Test
        @DisplayName("returns a new instance")
        void returnsNewInstance() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withError("boom", "java.lang.RuntimeException");
            assertNotSame(exec, updated);
        }

        @Test
        @DisplayName("sets errorMessage and errorType")
        void setsErrorMessageAndType() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withError("Something went wrong", "java.io.IOException");
            assertEquals("Something went wrong", updated.errorMessage());
            assertEquals("java.io.IOException", updated.errorType());
        }

        @Test
        @DisplayName("does not mutate the original")
        void doesNotMutateOriginal() {
            JobExecution exec = buildExecution();
            exec.withError("boom", "Ex");
            assertNull(exec.errorMessage());
            assertNull(exec.errorType());
        }
    }

    // --- withStarted ---

    @Nested
    @DisplayName("withStarted")
    class WithStarted {

        @Test
        @DisplayName("returns a new instance")
        void returnsNewInstance() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withStarted(Instant.now(), "node-1");
            assertNotSame(exec, updated);
        }

        @Test
        @DisplayName("sets startedAt and lockedBy")
        void setsStartedAtAndLockedBy() {
            Instant start = Instant.parse("2024-01-15T10:00:00Z");
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withStarted(start, "node-1");
            assertEquals(start, updated.startedAt());
            assertEquals("node-1", updated.lockedBy());
        }

        @Test
        @DisplayName("does not mutate the original")
        void doesNotMutateOriginal() {
            JobExecution exec = buildExecution();
            exec.withStarted(Instant.now(), "node-1");
            assertNull(exec.startedAt());
            assertNull(exec.lockedBy());
        }
    }

    // --- withCompleted ---

    @Nested
    @DisplayName("withCompleted")
    class WithCompleted {

        @Test
        @DisplayName("returns a new instance")
        void returnsNewInstance() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withCompleted(Instant.now());
            assertNotSame(exec, updated);
        }

        @Test
        @DisplayName("sets completedAt")
        void setsCompletedAt() {
            Instant completion = Instant.parse("2024-01-15T11:00:00Z");
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withCompleted(completion);
            assertEquals(completion, updated.completedAt());
        }

        @Test
        @DisplayName("does not mutate the original")
        void doesNotMutateOriginal() {
            JobExecution exec = buildExecution();
            exec.withCompleted(Instant.now());
            assertNull(exec.completedAt());
        }

        @Test
        @DisplayName("preserves other fields")
        void preservesOtherFields() {
            JobExecution exec = buildExecution();
            JobExecution updated = exec.withCompleted(Instant.now());
            assertEquals(exec.id(), updated.id());
            assertEquals(exec.jobId(), updated.jobId());
            assertEquals(exec.state(), updated.state());
            assertEquals(exec.lockedBy(), updated.lockedBy());
        }
    }

    // --- Existing withState and withProgress include new fields ---

    @Nested
    @DisplayName("withState preserves new fields")
    class WithStatePreservesNewFields {

        @Test
        @DisplayName("withState preserves payload, priority, and lockedBy")
        void withStatePreservesNewFields() {
            Object payload = "some-payload";
            JobExecution exec = new JobExecution(
                    UUID.randomUUID(),
                    "job",
                    JobType.CRON,
                    "addr",
                    "q",
                    JobState.ENQUEUED,
                    0,
                    3,
                    payload,
                    7,
                    "node-99",
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    ProgressSnapshot.EMPTY,
                    Map.of(),
                    Map.of(),
                    DurableMetadata.empty());
            JobExecution updated = exec.withState(JobState.PROCESSING);
            assertEquals(JobState.PROCESSING, updated.state());
            assertSame(payload, updated.payload());
            assertEquals(7, updated.priority());
            assertEquals("node-99", updated.lockedBy());
        }
    }

    @Nested
    @DisplayName("withProgress preserves new fields")
    class WithProgressPreservesNewFields {

        @Test
        @DisplayName("withProgress preserves payload, priority, and lockedBy")
        void withProgressPreservesNewFields() {
            Object payload = "some-payload";
            JobExecution exec = new JobExecution(
                    UUID.randomUUID(),
                    "job",
                    JobType.CRON,
                    "addr",
                    "q",
                    JobState.PROCESSING,
                    0,
                    3,
                    payload,
                    2,
                    "node-42",
                    Instant.now(),
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    ProgressSnapshot.EMPTY,
                    Map.of(),
                    Map.of(),
                    DurableMetadata.empty());
            ProgressSnapshot snap = new ProgressSnapshot(10, 5, 0, "half done");
            JobExecution updated = exec.withProgress(snap);
            assertEquals(snap, updated.progress());
            assertSame(payload, updated.payload());
            assertEquals(2, updated.priority());
            assertEquals("node-42", updated.lockedBy());
        }
    }
}
