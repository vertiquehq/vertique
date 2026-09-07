// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Snapshot-and-restore scope for direct SLF4J MDC enrichment on scheduler threads.
 *
 * <p>Scheduler-side dispatch code (cron job dispatcher, delayed-job poller) cannot use the
 * framework's {@code MDCContexts} facade because the scheduler thread is not on a duplicated
 * Vert.x context (the facade's write-side guard would reject the writes). The naive alternative —
 * {@code MDC.put(key, value)} for each enrichment key, then {@code MDC.remove(key)} on cleanup —
 * is wrong when the scheduler thread already had a value bound under the same key: cleanup
 * removes the key entirely instead of restoring the prior value, silently dropping the caller's
 * MDC state.
 *
 * <p>This class captures the prior MDC value (or absence) for each key at install time and
 * restores it on {@link #close()}. Closing is idempotent.
 *
 * <p>Use with try-with-resources:
 *
 * <pre>{@code
 * try (SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of("jobId", id, "execId", eid))) {
 *     log.info("Dispatching job {}", id);
 * }
 * }</pre>
 *
 * <p>This is the scheduler-thread complement of the holder-backed
 * {@code MDCContexts.bindAll(...)} pattern, with the same snapshot/restore contract.
 */
public final class SchedulerMdcScope implements AutoCloseable {

    private final Map<String, String> priorValues;
    private final Map<String, Boolean> priorPresent;
    private volatile boolean closed = false;

    private SchedulerMdcScope(Map<String, String> priorValues, Map<String, Boolean> priorPresent) {
        this.priorValues = priorValues;
        this.priorPresent = priorPresent;
    }

    /**
     * Captures the current MDC state for each key in {@code enrichment}, then installs the
     * enrichment values. The returned scope restores the captured state on close.
     *
     * <p>If {@code enrichment} is empty, returns a no-op scope.
     *
     * @param enrichment the MDC keys and values to install for the scope's lifetime
     * @return a scope that restores prior MDC state on close
     */
    public static SchedulerMdcScope install(Map<String, String> enrichment) {
        if (enrichment.isEmpty()) {
            return new SchedulerMdcScope(Map.of(), Map.of());
        }
        Map<String, String> priorValues = new HashMap<>(enrichment.size());
        Map<String, Boolean> priorPresent = new HashMap<>(enrichment.size());
        for (Map.Entry<String, String> entry : enrichment.entrySet()) {
            String key = entry.getKey();
            String prior = MDC.get(key);
            priorPresent.put(key, prior != null);
            if (prior != null) {
                priorValues.put(key, prior);
            }
            MDC.put(key, entry.getValue());
        }
        return new SchedulerMdcScope(priorValues, priorPresent);
    }

    /**
     * Restores prior MDC state for each enriched key. Keys that were absent before install are
     * removed; keys that had a prior value are restored to that value. Idempotent: subsequent
     * calls after the first are no-ops.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Map.Entry<String, Boolean> entry : priorPresent.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue()) {
                MDC.put(key, priorValues.get(key));
            } else {
                MDC.remove(key);
            }
        }
    }
}
