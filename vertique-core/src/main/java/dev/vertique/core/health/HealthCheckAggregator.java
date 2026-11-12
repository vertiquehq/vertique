// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import io.vertx.core.Future;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates a set of {@link HealthCheck}s in process and reduces it to one {@link HealthReport}.
 *
 * <p>This is the one place that turns a check set into a verdict. Anything that needs to know
 * whether the process is live or ready — a probe endpoint, a test, a command-line tool, a host
 * bridge, a shutdown hook — evaluates the {@link Liveness @Liveness} or {@link Readiness @Readiness}
 * set through it and, given the same timeout, gets the same answer by the same rules.
 * {@link HealthCheckModule} binds an instance with the {@linkplain #DEFAULT_CHECK_TIMEOUT_SECONDS
 * default} per-check timeout; a consumer with its own timeout constructs one.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Every check runs, every check is reported.</b> All checks are started, in the set's
 *       iteration order, before any of them is waited for; the report holds one {@link
 *       HealthReport.Entry} per check in that order and is produced only once every check has settled,
 *       whatever its outcome. A failing check never hides a slower sibling's result.
 *   <li><b>UP only when all UP.</b> The report's status is {@link HealthStatus#UP} only when every
 *       entry is {@code UP}. An empty set is {@code UP} with no entries, and its report completes
 *       synchronously.
 *   <li><b>Per-check timeout.</b> A check that has not settled within the configured timeout is
 *       reported {@code DOWN}, described by the {@link java.util.concurrent.TimeoutException}.
 *   <li><b>Failure containment.</b> A check that returns a failed future, that throws an
 *       {@link Exception} from {@link HealthCheck#check()}, or that returns {@code null} instead of a
 *       future is reported {@code DOWN} with {@link HealthCheckResult#down(Throwable)} describing the
 *       failure; a future that succeeds with a {@code null} result is {@code DOWN} with no diagnostic.
 *       A check whose {@link HealthCheck#name()} returns {@code null} is reported under its
 *       implementing class name and still runs; one whose {@code name()} throws an {@link Exception}
 *       is reported {@code DOWN} under its class name without being run. {@code name()} is called
 *       exactly once per evaluation.
 *   <li><b>An {@code Error} is not a check's status.</b> An {@link Error} thrown by {@code name()}
 *       or {@code check()} propagates synchronously out of {@link #evaluate(Set)}; checks started
 *       before it are abandoned. An {@code Error} raised while the report is being assembled — for
 *       example by a failure's {@link Throwable#getMessage()} — fails the returned future instead.
 *       Either way the caller, not the aggregator, decides how to answer for a check that is broken
 *       rather than unhealthy.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>No Vert.x context is required. The returned future completes on whichever thread settles the
 * last check — a check's own thread, or the timer thread that fires the timeout — and completes
 * inline, before {@code evaluate} returns, when every check is already settled. Two hazards are
 * not absorbed because they produce no outcome to report: a check that blocks indefinitely inside
 * {@code name()}, {@code check()} or {@code getMessage()} stalls the evaluation with no future left
 * to time out, and a check future that reports itself failed with a {@code null} cause never
 * settles the aggregation.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class HealthCheckAggregator {

    /** The per-check timeout, in seconds, of an aggregator created without one. */
    public static final long DEFAULT_CHECK_TIMEOUT_SECONDS = 5L;

    private final long checkTimeoutSeconds;

    /**
     * One check's in-flight invocation: its name, resolved exactly once up front, paired with the
     * raw result future.
     *
     * <p>The future is kept raw — never recovered per check — so that a failure reaches the report
     * builder as a failure, where it is described by the total {@link HealthCheckResult#down(Throwable)}.
     *
     * @param name   the resolved name
     * @param result the raw, possibly failed, result of invoking the check
     */
    private record Execution(String name, Future<HealthCheckResult> result) {}

    /**
     * Creates an aggregator with the {@linkplain #DEFAULT_CHECK_TIMEOUT_SECONDS default} per-check
     * timeout.
     */
    public HealthCheckAggregator() {
        this(DEFAULT_CHECK_TIMEOUT_SECONDS);
    }

    /**
     * Creates an aggregator with the given per-check timeout.
     *
     * @param checkTimeoutSeconds the per-check timeout in seconds; must be positive
     * @throws IllegalArgumentException if {@code checkTimeoutSeconds} is zero or negative
     */
    public HealthCheckAggregator(long checkTimeoutSeconds) {
        if (checkTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("checkTimeoutSeconds must be positive, got: " + checkTimeoutSeconds);
        }
        this.checkTimeoutSeconds = checkTimeoutSeconds;
    }

    /**
     * Returns the per-check timeout.
     *
     * @return the timeout in seconds; always positive
     */
    public long checkTimeoutSeconds() {
        return checkTimeoutSeconds;
    }

    // --- Evaluation ---

    /**
     * Evaluates every check in the set and reports the verdict, by the rules in the class
     * description.
     *
     * <p>The set is read once, when this method is called; a later change to it is not observed.
     *
     * @param checks the checks to evaluate; must not be {@code null} or hold {@code null}
     * @return a future that completes with the report once every check has settled or timed out,
     *         or fails with an {@link Error} raised while the report was being assembled
     * @throws NullPointerException if {@code checks} is {@code null} or holds a {@code null} element
     * @throws Error                if a check's {@code name()} or {@code check()} throws one
     */
    public Future<HealthReport> evaluate(Set<HealthCheck> checks) {
        List<HealthCheck> snapshot = List.copyOf(Objects.requireNonNull(checks, "checks"));
        if (snapshot.isEmpty()) {
            return Future.succeededFuture(HealthReport.of(List.of()));
        }
        List<Execution> executions = snapshot.stream().map(this::start).toList();
        // join, not all: all is fail-fast and would settle the aggregation while siblings of a failed
        // check are still pending. join waits for every constituent, whatever its outcome, and
        // transform then reads each outcome off its own future regardless of the composite's.
        return Future.join(executions.stream().map(Execution::result).toList())
                .transform(ignored -> Future.succeededFuture(report(executions)));
    }

    /**
     * Starts one check: resolves its name exactly once and converts a synchronous {@link Exception}
     * into a failed future, so that every outcome reaches the report as a settled future.
     *
     * @param check the check to start
     * @return the started execution
     */
    private Execution start(HealthCheck check) {
        String name;
        try {
            name = check.name();
        } catch (Exception e) {
            // A check that cannot even name itself is unusable: report it DOWN under its class name
            // and do not run it. name() is never called again on any path.
            return new Execution(check.getClass().getName(), Future.failedFuture(e));
        }
        if (name == null) {
            // name() is documented non-null; tolerate the violation the same way as a throwing name
            // but still run the check — its own result is meaningful.
            name = check.getClass().getName();
        }
        try {
            // A null future fails here with a NullPointerException and is reported like any other
            // synchronous failure.
            return new Execution(name, check.check().timeout(checkTimeoutSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new Execution(name, Future.failedFuture(e));
        }
    }

    /**
     * Assembles the report from settled executions.
     *
     * @param executions the executions, every one of them settled
     * @return the report
     */
    private static HealthReport report(List<Execution> executions) {
        return HealthReport.of(
                executions.stream().map(HealthCheckAggregator::entry).toList());
    }

    /**
     * Describes one settled execution as its entry.
     *
     * @param execution the settled execution
     * @return the entry: the check's own result when it produced one, otherwise a {@code DOWN} result
     *         describing the failure
     */
    private static HealthReport.Entry entry(Execution execution) {
        Future<HealthCheckResult> result = execution.result();
        if (result.succeeded()) {
            HealthCheckResult value = result.result();
            return new HealthReport.Entry(execution.name(), value != null ? value : HealthCheckResult.down());
        }
        Throwable cause = result.cause();
        // A future that reports itself failed with no cause violates the Future contract and never
        // settles Future.join, so this branch is defensive: it is the seam that would observe the
        // state if that ever changed, and down(Throwable) is strict on null by contract.
        return new HealthReport.Entry(
                execution.name(), cause != null ? HealthCheckResult.down(cause) : HealthCheckResult.down());
    }
}
