// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import dev.vertique.workflow.history.PendingCommand;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Waits until recorded work has run. For tests and operational probes.
 *
 * <p>An instance is idle when it has no command in flight (claimed under a live lease) and no command an executor
 * could claim now: one at the head of its queue, not held back and available. A command behind a blocking head, or
 * available only later, does not hold the probe. The probe polls the database and reads its clock, so it observes
 * work recorded by any node.
 *
 * <p>A non-positive timeout is refused with {@link IllegalArgumentException}.
 */
public interface WorkflowExecutionProbe {

    /**
     * Completes when the instance is idle, reporting what the probe stopped on.
     *
     * @param id      the instance to wait for
     * @param timeout how long to wait; must be positive
     * @return a future of the report; failed with {@link TimeoutException} when the instance is not idle after
     *         {@code timeout}, the message naming the instance and the number of runnable commands left
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    Future<IdleReport> awaitIdle(WorkflowInstanceId id, Duration timeout);

    /**
     * Completes when every instance is idle.
     *
     * @param timeout how long to wait; must be positive
     * @return a future of the report across all instances, ordered by instance and sequence; failed with
     *         {@link TimeoutException} when some instance is not idle after {@code timeout}
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    Future<IdleReport> awaitAllIdle(Duration timeout);

    /**
     * What the probe stopped on; each list is ordered by instance and then by sequence.
     *
     * @param deferred       deferred commands that cannot be claimed yet
     * @param stuck          commands reported stuck; empty until stuck detection exists
     * @param parked         parked commands, which wait for an operator or a later state
     * @param notYetAvailable the other pending and claimed commands that cannot be claimed now: timers before their
     *                       fire time, backed-off commands, commands behind a blocking head and held-back timers
     */
    record IdleReport(
            List<PendingCommand> deferred,
            List<PendingCommand> stuck,
            List<PendingCommand> parked,
            List<PendingCommand> notYetAvailable) {}
}
