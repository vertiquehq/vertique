// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.eventbus.Result;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;

/**
 * Shared utility for invoking {@link JobInterceptor} chains in
 * {@link dev.vertique.core.extension.OrderedExtension} order (phase → priority → orderKey).
 *
 * <p>Used by both {@code CronScheduler} and {@code DelayedJobPoller} to avoid duplicating
 * the exception-swallowing loop pattern. Each method delegates to
 * {@link Combinators#forEachSwallowSync} so the swallow-and-continue contract lives in one place.
 */
public final class JobInterceptors {

    private JobInterceptors() {}

    /**
     * Fires {@link JobInterceptor#onDispatch} for all interceptors. Exceptions are swallowed and
     * logged — they do not prevent dispatch of the remaining interceptors.
     *
     * @param interceptors the sorted interceptor list
     * @param ctx          the dispatch context
     * @param log          the caller's logger for warning messages
     */
    public static void fireOnDispatch(List<JobInterceptor> interceptors, JobDispatchContext ctx, Logger log) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onDispatch(ctx),
                (i, e) -> log.warn(
                        "onDispatch interceptor {} threw for job '{}'",
                        i.getClass().getSimpleName(),
                        ctx.jobId(),
                        e));
    }

    /**
     * Fires {@link JobInterceptor#onComplete} for all interceptors. Exceptions are swallowed and
     * logged.
     *
     * @param interceptors the sorted interceptor list
     * @param ctx          the dispatch context
     * @param result       the dispatch result, or {@code null} if no reply body was received
     * @param startTime    when dispatch started
     * @param endTime      when the completion reply was received
     * @param log          the caller's logger
     */
    public static void fireOnComplete(
            List<JobInterceptor> interceptors,
            JobDispatchContext ctx,
            Result<?> result,
            Instant startTime,
            Instant endTime,
            Logger log) {
        Combinators.forEachSwallowSync(
                interceptors,
                i -> i.onComplete(ctx, result, startTime, endTime),
                (i, e) -> log.warn(
                        "onComplete interceptor {} threw for job '{}'",
                        i.getClass().getSimpleName(),
                        ctx.jobId(),
                        e));
    }
}
