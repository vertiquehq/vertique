// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every event a resilience runtime publishes, so a test can tell whether the runtime was reached. */
final class ObservedResilienceEvents implements ResilienceObserver {

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(ResilienceEvent event) {
        events.add(event);
    }

    /**
     * Returns every event published so far.
     *
     * @return an immutable snapshot
     */
    List<ResilienceEvent> all() {
        return List.copyOf(events);
    }

    /**
     * Counts completed executions that ended in a timeout, waiting up to {@code waitMs} for the first.
     *
     * @param waitMs the longest to wait, in milliseconds
     * @return the number of timed-out executions observed
     * @throws InterruptedException if the wait is interrupted
     */
    long timeouts(long waitMs) throws InterruptedException {
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (countTimeouts() > 0) {
                break;
            }
            Thread.sleep(10L);
        }
        return countTimeouts();
    }

    private long countTimeouts() {
        return events.stream()
                .filter(ExecutionCompleted.class::isInstance)
                .map(ExecutionCompleted.class::cast)
                .filter(e -> e.outcome() == ResilienceOutcomeCategory.TIMEOUT)
                .count();
    }
}
