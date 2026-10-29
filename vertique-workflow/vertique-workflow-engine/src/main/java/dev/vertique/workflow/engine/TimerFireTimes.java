// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.TimerCommandSpec.FireTime;
import dev.vertique.workflow.plan.TimerSpec;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Package-private helper that turns a plan's {@link TimerSpec} into the {@link FireTime} a timer command is recorded
 * with. A delay stays a delay, so the database clock that stamps the command also measures the delay; an absolute
 * instant, given or computed from the state, stays an instant.
 */
final class TimerFireTimes {

    private TimerFireTimes() {
        // static helpers only
    }

    /**
     * Resolves a spec to a fire time.
     *
     * @param spec      the plan's timer spec
     * @param fromState computes the instant of a spec that reads the workflow state; called only for such a spec
     * @return the fire time
     */
    static FireTime of(TimerSpec spec, Supplier<Instant> fromState) {
        return switch (spec) {
            case TimerSpec.At at -> new FireTime.At(at.fireAt());
            case TimerSpec.After after -> new FireTime.After(after.delay());
            case TimerSpec.FromState fromStateSpec -> new FireTime.At(fromState.get());
        };
    }
}
