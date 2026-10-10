// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.extension;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * Reports the failure of an observer-style callback — an observer, listener, hook or completion
 * scope whose throwable the framework swallows so that it cannot affect the operation it observes.
 *
 * <p>This is framework-internal support shared by the modules that isolate such callbacks, so that
 * they all report a failure the same way. It is public only because those modules live in other
 * packages; applications have no reason to use it. A caller catches the throwable itself and hands
 * it to {@link #report(Class, String, Throwable)}; this class only decides what is logged.
 *
 * <h2>What is logged</h2>
 *
 * <ul>
 *   <li>A {@link LinkageError} means the callback cannot run at all and would fail the same way on
 *       every call. It is logged at error level, with the throwable, the first time it is seen for
 *       an observer class and callback, and after that at most once per {@link #REPORT_INTERVAL}
 *       for that pair, together with the number of failures that went unreported in between. It is
 *       never silenced for good: a broken audit observer keeps showing up in the log for as long as
 *       it keeps failing.</li>
 *   <li>Any other throwable — an {@link Exception}, an {@link AssertionError}, a
 *       {@link StackOverflowError} — is logged at warn level every time, naming the observer class,
 *       the callback and the throwable's class only. The throwable itself, with its message and
 *       stack, is logged at debug level only. An observer's exception message can contain payload
 *       text, so it is kept out of the log at the levels a production system runs at.</li>
 * </ul>
 *
 * <h2>Thread safety and bounds</h2>
 *
 * <p>Instances are safe for use from any thread. The state kept is one small record per observer
 * class and callback pair that has thrown a {@link LinkageError}; at most
 * {@value #MAX_TRACKED_CALLBACKS} pairs are tracked individually and any further pair shares one
 * record, so the memory used is bounded and a report is still made at the limited rate.
 *
 * <p>One instance is created per isolation site and lives as long as the site does. A site whose
 * owning object is created per request shares one reporter across requests, otherwise every request
 * would report the failure as if it were the first.
 */
public final class ObserverFailureReporter {

    /**
     * The shortest time between two error-level reports of a {@link LinkageError} for one observer
     * class and callback.
     */
    public static final Duration REPORT_INTERVAL = Duration.ofMinutes(5);

    /** The number of observer class and callback pairs whose reports are throttled individually. */
    static final int MAX_TRACKED_CALLBACKS = 1024;

    private final Logger log;
    private final String kind;
    private final LongSupplier nanoTime;
    private final Map<Callback, Throttle> throttles = new ConcurrentHashMap<>();

    /** The throttle shared by every pair beyond {@link #MAX_TRACKED_CALLBACKS}. */
    private final Throttle overflow = new Throttle();

    /**
     * Creates a reporter that reads the time from {@link System#nanoTime()}.
     *
     * @param log  the logger of the isolation site, so its failures appear under the site's own
     *             logger name; must not be {@code null}
     * @param kind what the reported callbacks belong to, used as the start of every message (for
     *             example {@code "Outbox publish observer"}); must not be {@code null}
     */
    public ObserverFailureReporter(Logger log, String kind) {
        this(log, kind, System::nanoTime);
    }

    /**
     * Creates a reporter with its own time source.
     *
     * @param log      the logger of the isolation site; must not be {@code null}
     * @param kind     what the reported callbacks belong to, used as the start of every message;
     *                 must not be {@code null}
     * @param nanoTime a monotonic time source in nanoseconds with the contract of
     *                 {@link System#nanoTime()}; must not be {@code null}
     */
    public ObserverFailureReporter(Logger log, String kind, LongSupplier nanoTime) {
        this.log = Objects.requireNonNull(log, "log");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Reports one swallowed failure of an observer-style callback.
     *
     * <p>A {@link LinkageError} is reported at error level at the limited rate described on the
     * class; any other throwable is reported at warn level by class name, with the throwable itself
     * at debug level. This method never throws because of the failure it is given.
     *
     * @param observerClass the class of the observer, listener, hook or scope that threw; must not
     *                      be {@code null}
     * @param callback      the name of the callback that threw; must not be {@code null}
     * @param failure       the throwable the caller caught and is swallowing; must not be
     *                      {@code null}
     */
    public void report(Class<?> observerClass, String callback, Throwable failure) {
        if (failure instanceof LinkageError) {
            reportUnusable(observerClass, callback, failure);
            return;
        }
        log.warn(
                "{} {} callback {} threw {}; the failure is swallowed",
                kind,
                observerClass.getName(),
                callback,
                failure.getClass().getName());
        if (log.isDebugEnabled()) {
            log.debug("{} {} callback {} failure detail", kind, observerClass.getName(), callback, failure);
        }
    }

    /**
     * Reports a callback that cannot run, unless it was reported within {@link #REPORT_INTERVAL}.
     *
     * @param observerClass the class of the observer that threw
     * @param callback      the name of the callback that threw
     * @param failure       the linkage failure
     */
    private void reportUnusable(Class<?> observerClass, String callback, Throwable failure) {
        long suppressed = throttleFor(observerClass, callback).reportOrSuppress(nanoTime.getAsLong());
        if (suppressed < 0) {
            return;
        }
        log.error(
                "{} {} callback {} is unusable and its notifications are being lost; {} further failure(s) of"
                        + " this callback went unreported since the last report; it is reported at most once"
                        + " every {} minutes",
                kind,
                observerClass.getName(),
                callback,
                suppressed,
                REPORT_INTERVAL.toMinutes(),
                failure);
    }

    /**
     * Returns the throttle of an observer class and callback pair, creating it on first use. Once
     * {@link #MAX_TRACKED_CALLBACKS} pairs are tracked, every new pair shares one throttle.
     *
     * @param observerClass the class of the observer that threw
     * @param callback      the name of the callback that threw
     * @return the throttle to decide the report with
     */
    private Throttle throttleFor(Class<?> observerClass, String callback) {
        Callback key = new Callback(observerClass, callback);
        Throttle existing = throttles.get(key);
        if (existing != null) {
            return existing;
        }
        if (throttles.size() >= MAX_TRACKED_CALLBACKS) {
            return overflow;
        }
        return throttles.computeIfAbsent(key, ignored -> new Throttle());
    }

    /**
     * One observer class and callback pair.
     *
     * @param observerClass the observer's class
     * @param callback      the callback name
     */
    private record Callback(Class<?> observerClass, String callback) {}

    /** The report state of one pair: when it was last reported and how many failures went unreported. */
    private static final class Throttle {

        private boolean reported;
        private long lastReportNanos;
        private long suppressed;

        /**
         * Decides whether a failure seen at {@code now} is reported.
         *
         * @param now the current time in nanoseconds
         * @return the number of failures that went unreported since the last report when this one
         *     is to be reported, or {@code -1} when it is suppressed
         */
        synchronized long reportOrSuppress(long now) {
            if (reported && now - lastReportNanos < REPORT_INTERVAL.toNanos()) {
                suppressed++;
                return -1;
            }
            long unreported = suppressed;
            reported = true;
            lastReportNanos = now;
            suppressed = 0;
            return unreported;
        }
    }
}
