// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static java.time.Instant.EPOCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.vertique.core.eventbus.Result;
import dev.vertique.core.extension.ExtensionPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * Characterization tests for {@link JobInterceptors}: pins observable behavior of
 * {@code fireOnDispatch} and {@code fireOnComplete} so migration to
 * {@link dev.vertique.core.async.Combinators#forEachSwallowSync} preserves every invariant.
 *
 * <p>Covers:
 * <ul>
 *   <li>Interceptors are called in list order.</li>
 *   <li>A throwing interceptor does not prevent later interceptors from running.</li>
 *   <li>The exact WARN message template and arguments are preserved.</li>
 *   <li>Non-throwing interceptors produce no warning log.</li>
 * </ul>
 */
class JobInterceptorsTest {

    // --- Test doubles ---

    /**
     * Minimal {@link JobInterceptor} that records call order and optionally throws on invocation.
     */
    private static final class RecordingInterceptor implements JobInterceptor {

        private final String name;
        private final List<String> callLog;
        private final RuntimeException onDispatchThrow;
        private final RuntimeException onCompleteThrow;

        RecordingInterceptor(String name, List<String> callLog) {
            this(name, callLog, null, null);
        }

        RecordingInterceptor(
                String name, List<String> callLog, RuntimeException onDispatchThrow, RuntimeException onCompleteThrow) {
            this.name = name;
            this.callLog = callLog;
            this.onDispatchThrow = onDispatchThrow;
            this.onCompleteThrow = onCompleteThrow;
        }

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public void onDispatch(JobDispatchContext ctx) {
            callLog.add(name + ".onDispatch");
            if (onDispatchThrow != null) {
                throw onDispatchThrow;
            }
        }

        @Override
        public void onComplete(JobDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
            callLog.add(name + ".onComplete");
            if (onCompleteThrow != null) {
                throw onCompleteThrow;
            }
        }
    }

    // --- Shared test fixture ---

    private static JobDispatchContext sampleCtx() {
        return new JobDispatchContext(
                "test-job", UUID.randomUUID(), JobType.CRON, 0, 3, "default", EPOCH, EPOCH, Map.of(), Map.of());
    }

    // --- fireOnDispatch ---

    @Nested
    @DisplayName("fireOnDispatch")
    class FireOnDispatch {

        @Test
        @DisplayName("calls all interceptors in list order when none throw")
        void callsAllInOrder() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);

            RecordingInterceptor first = new RecordingInterceptor("first", log);
            RecordingInterceptor second = new RecordingInterceptor("second", log);
            RecordingInterceptor third = new RecordingInterceptor("third", log);
            List<JobInterceptor> interceptors = List.of(first, second, third);

            JobInterceptors.fireOnDispatch(interceptors, ctx, logger);

            assertEquals(List.of("first.onDispatch", "second.onDispatch", "third.onDispatch"), log);
            verify(logger, never()).warn(anyString(), any(), any(), any(Throwable.class));
        }

        @Test
        @DisplayName("continues past a throwing interceptor and calls later interceptors")
        void continuesPastThrowingInterceptor() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("dispatch-bang");

            RecordingInterceptor first = new RecordingInterceptor("first", log);
            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, bang, null);
            RecordingInterceptor third = new RecordingInterceptor("third", log);
            List<JobInterceptor> interceptors = List.of(first, thrower, third);

            JobInterceptors.fireOnDispatch(interceptors, ctx, logger);

            assertEquals(
                    List.of("first.onDispatch", "thrower.onDispatch", "third.onDispatch"),
                    log,
                    "all three interceptors must be called regardless of the throw");
        }

        @Test
        @DisplayName("logs exact WARN template with interceptor simple name and jobId when an interceptor throws")
        void logsExactWarnTemplate() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("dispatch-bang");

            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, bang, null);
            List<JobInterceptor> interceptors = List.of(thrower);

            JobInterceptors.fireOnDispatch(interceptors, ctx, logger);

            verify(logger)
                    .warn(
                            eq("onDispatch interceptor {} threw for job '{}'"),
                            eq("RecordingInterceptor"),
                            eq(ctx.jobId()),
                            eq(bang));
        }

        @Test
        @DisplayName("does nothing when the interceptor list is empty")
        void emptyListNoOp() {
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);

            JobInterceptors.fireOnDispatch(List.of(), ctx, logger);

            verify(logger, never()).warn(anyString(), any(), any(), any(Throwable.class));
        }

        @Test
        @DisplayName("warns once per throwing interceptor, not for non-throwing ones")
        void warnsOnlyForThrowers() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("single-throw");

            RecordingInterceptor ok = new RecordingInterceptor("ok", log);
            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, bang, null);
            List<JobInterceptor> interceptors = List.of(ok, thrower);

            JobInterceptors.fireOnDispatch(interceptors, ctx, logger);

            verify(logger)
                    .warn(
                            eq("onDispatch interceptor {} threw for job '{}'"),
                            eq("RecordingInterceptor"),
                            eq(ctx.jobId()),
                            eq(bang));
            // Exactly one warn call: for the thrower only
            verify(logger).warn(anyString(), any(), any(), any(Throwable.class));
        }

        @Test
        @DisplayName("calls interceptors in the exact sequence supplied by the caller (preserves order)")
        void preservesCallerOrder() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);

            RecordingInterceptor a = new RecordingInterceptor("a", log);
            RecordingInterceptor b = new RecordingInterceptor("b", log);
            // Provide in reverse to confirm the combinator does not reorder
            List<JobInterceptor> interceptors = List.of(b, a);

            JobInterceptors.fireOnDispatch(interceptors, ctx, logger);

            assertEquals(List.of("b.onDispatch", "a.onDispatch"), log);
        }
    }

    // --- fireOnComplete ---

    @Nested
    @DisplayName("fireOnComplete")
    class FireOnComplete {

        private final Result<?> RESULT = Result.success("done");

        @Test
        @DisplayName("calls all interceptors in list order when none throw")
        void callsAllInOrder() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);

            RecordingInterceptor first = new RecordingInterceptor("first", log);
            RecordingInterceptor second = new RecordingInterceptor("second", log);
            List<JobInterceptor> interceptors = List.of(first, second);

            JobInterceptors.fireOnComplete(interceptors, ctx, RESULT, EPOCH, EPOCH.plusSeconds(1), logger);

            assertEquals(List.of("first.onComplete", "second.onComplete"), log);
            verify(logger, never()).warn(anyString(), any(), any(), any(Throwable.class));
        }

        @Test
        @DisplayName("continues past a throwing interceptor and calls later interceptors")
        void continuesPastThrowingInterceptor() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("complete-bang");

            RecordingInterceptor first = new RecordingInterceptor("first", log);
            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, null, bang);
            RecordingInterceptor third = new RecordingInterceptor("third", log);
            List<JobInterceptor> interceptors = List.of(first, thrower, third);

            JobInterceptors.fireOnComplete(interceptors, ctx, RESULT, EPOCH, EPOCH.plusSeconds(1), logger);

            assertEquals(
                    List.of("first.onComplete", "thrower.onComplete", "third.onComplete"),
                    log,
                    "all three must run despite the throw");
        }

        @Test
        @DisplayName("logs exact WARN template with interceptor simple name and jobId when an interceptor throws")
        void logsExactWarnTemplate() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("complete-bang");

            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, null, bang);
            List<JobInterceptor> interceptors = List.of(thrower);

            JobInterceptors.fireOnComplete(interceptors, ctx, RESULT, EPOCH, EPOCH.plusSeconds(1), logger);

            verify(logger)
                    .warn(
                            eq("onComplete interceptor {} threw for job '{}'"),
                            eq("RecordingInterceptor"),
                            eq(ctx.jobId()),
                            eq(bang));
        }

        @Test
        @DisplayName("does nothing when the interceptor list is empty")
        void emptyListNoOp() {
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);

            JobInterceptors.fireOnComplete(List.of(), ctx, RESULT, EPOCH, EPOCH.plusSeconds(1), logger);

            verify(logger, never()).warn(anyString(), any(), any(), any(Throwable.class));
        }

        @Test
        @DisplayName("passes result, startTime, and endTime verbatim to each onComplete")
        void forwardsArgumentsVerbatim() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            Instant start = Instant.ofEpochSecond(100);
            Instant end = Instant.ofEpochSecond(200);

            // Use a spy-style interceptor that captures its args
            List<Object[]> captured = new ArrayList<>();
            JobInterceptor capturer = new JobInterceptor() {
                @Override
                public ExtensionPhase phase() {
                    return ExtensionPhase.APPLICATION;
                }

                @Override
                public void onComplete(JobDispatchContext ctx2, Result<?> result, Instant startTime, Instant endTime) {
                    captured.add(new Object[] {ctx2, result, startTime, endTime});
                }
            };

            JobInterceptors.fireOnComplete(List.of(capturer), ctx, RESULT, start, end, logger);

            assertEquals(1, captured.size());
            assertSame(ctx, captured.get(0)[0]);
            assertSame(RESULT, captured.get(0)[1]);
            assertSame(start, captured.get(0)[2]);
            assertSame(end, captured.get(0)[3]);
        }

        @Test
        @DisplayName("warns once per throwing interceptor, not for non-throwing ones")
        void warnsOnlyForThrowers() {
            List<String> log = new ArrayList<>();
            JobDispatchContext ctx = sampleCtx();
            Logger logger = mock(Logger.class);
            RuntimeException bang = new RuntimeException("single-throw");

            RecordingInterceptor ok = new RecordingInterceptor("ok", log);
            RecordingInterceptor thrower = new RecordingInterceptor("thrower", log, null, bang);
            List<JobInterceptor> interceptors = List.of(ok, thrower);

            JobInterceptors.fireOnComplete(interceptors, ctx, RESULT, EPOCH, EPOCH.plusSeconds(1), logger);

            verify(logger)
                    .warn(
                            eq("onComplete interceptor {} threw for job '{}'"),
                            eq("RecordingInterceptor"),
                            eq(ctx.jobId()),
                            eq(bang));
            verify(logger).warn(anyString(), any(), any(), any(Throwable.class));
        }
    }
}
