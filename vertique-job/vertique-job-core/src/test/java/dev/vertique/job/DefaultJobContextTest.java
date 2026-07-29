// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJobContext}.
 */
@DisplayName("DefaultJobContext")
class DefaultJobContextTest {

    private static final String JOB_ID = "test-job";
    private static final UUID EXECUTION_ID = UUID.randomUUID();

    private DefaultJobContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new DefaultJobContext(JOB_ID, EXECUTION_ID, 0, JobType.CRON);
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("returns the job ID")
        void returnsJobId() {
            assertEquals(JOB_ID, ctx.jobId());
        }

        @Test
        @DisplayName("returns the execution ID")
        void returnsExecutionId() {
            assertEquals(EXECUTION_ID, ctx.executionId());
        }

        @Test
        @DisplayName("returns the attempt number")
        void returnsAttemptNumber() {
            assertEquals(0, ctx.attemptNumber());
        }

        @Test
        @DisplayName("returns the job type")
        void returnsJobType() {
            assertEquals(JobType.CRON, ctx.jobType());
        }
    }

    @Nested
    @DisplayName("progress")
    class Progress {

        @Test
        @DisplayName("returns a non-null progress reporter")
        void returnsNonNullProgressReporter() {
            assertNotNull(ctx.progress());
        }

        @Test
        @DisplayName("progress reporter is usable")
        void progressReporterIsUsable() {
            ctx.progress().setTotal(10);
            ctx.progress().incrementSucceeded(5);
            assertEquals(50, ctx.progress().percentage());
        }
    }

    @Nested
    @DisplayName("logger")
    class Logger {

        @Test
        @DisplayName("returns a non-null logger")
        void returnsNonNullLogger() {
            assertNotNull(ctx.logger());
        }

        @Test
        @DisplayName("logger buffers entries")
        void loggerBuffersEntries() {
            ctx.logger().info("hello");
            assertEquals(1, ctx.logger().entries().size());
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("starts not cancelled")
        void startsNotCancelled() {
            assertFalse(ctx.isCancelled());
        }

        @Test
        @DisplayName("setCancelled true marks as cancelled")
        void setCancelledTrue() {
            ctx.setCancelled(true);
            assertTrue(ctx.isCancelled());
        }

        @Test
        @DisplayName("setCancelled false clears cancellation")
        void setCancelledFalse() {
            ctx.setCancelled(true);
            ctx.setCancelled(false);
            assertFalse(ctx.isCancelled());
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        @DisplayName("saveMetadata then getMetadata returns value")
        void saveAndGetMetadata() {
            ctx.saveMetadata("key", "value");
            assertEquals("value", ctx.getMetadata("key", String.class));
        }

        @Test
        @DisplayName("getMetadata returns null for missing key")
        void getMissingMetadataReturnsNull() {
            assertNull(ctx.getMetadata("missing", String.class));
        }

        @Test
        @DisplayName("saveMetadata with null value removes the key")
        void saveNullRemovesKey() {
            ctx.saveMetadata("key", "value");
            ctx.saveMetadata("key", null);
            assertNull(ctx.getMetadata("key", String.class));
        }
    }

    @Nested
    @DisplayName("step deduplication")
    class StepDeduplication {

        @Test
        @DisplayName("hasCompletedStep returns false initially")
        void hasCompletedStepReturnsFalse() {
            assertFalse(ctx.hasCompletedStep("step-1"));
        }

        @Test
        @DisplayName("runStepOnce executes task and marks step as completed")
        void runStepOnceExecutesTask() {
            AtomicInteger callCount = new AtomicInteger(0);
            Future<String> result = ctx.runStepOnce("step-1", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture("done");
            });
            assertTrue(result.succeeded());
            assertEquals("done", result.result());
            assertEquals(1, callCount.get());
            assertTrue(ctx.hasCompletedStep("step-1"));
        }

        @Test
        @DisplayName("runStepOnce allows retry after task failure")
        void runStepOnceAllowsRetryAfterFailure() {
            AtomicInteger callCount = new AtomicInteger(0);
            RuntimeException error = new RuntimeException("step failed");

            // First call — task fails
            Future<String> first = ctx.runStepOnce("step-fail", () -> {
                callCount.incrementAndGet();
                return Future.failedFuture(error);
            });
            assertTrue(first.failed());
            assertEquals(error, first.cause());
            assertEquals(1, callCount.get());

            // Second call — task succeeds (should run, not return cached failure)
            Future<String> second = ctx.runStepOnce("step-fail", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture("recovered");
            });
            assertTrue(second.succeeded());
            assertEquals("recovered", second.result());
            assertEquals(2, callCount.get());
        }

        @Test
        @DisplayName("hasCompletedStep returns false after task failure")
        void hasCompletedStepReturnsFalseAfterFailure() {
            ctx.runStepOnce("step-err", () -> Future.failedFuture(new RuntimeException("boom")));
            assertFalse(ctx.hasCompletedStep("step-err"));
        }

        @Test
        @DisplayName("runStepOnce caches null result correctly")
        void runStepOnceCachesNullResult() {
            AtomicInteger callCount = new AtomicInteger(0);

            // First call — task returns null
            Future<String> first = ctx.runStepOnce("step-null", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture(null);
            });
            assertTrue(first.succeeded());
            assertNull(first.result());
            assertTrue(ctx.hasCompletedStep("step-null"));
            assertEquals(1, callCount.get());

            // Second call — should skip task and return cached null
            Future<String> second = ctx.runStepOnce("step-null", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture("should not run");
            });
            assertTrue(second.succeeded());
            assertNull(second.result());
            assertEquals(1, callCount.get());
        }

        @Test
        @DisplayName("runStepOnce skips task on second call and returns cached result")
        void runStepOnceSkipsOnSecondCall() {
            AtomicInteger callCount = new AtomicInteger(0);
            // First call
            ctx.runStepOnce("step-1", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture("result");
            });
            // Second call — task must not run again
            Future<String> second = ctx.runStepOnce("step-1", () -> {
                callCount.incrementAndGet();
                return Future.succeededFuture("other");
            });
            assertEquals(1, callCount.get());
            assertEquals("result", second.result());
        }
    }
}
