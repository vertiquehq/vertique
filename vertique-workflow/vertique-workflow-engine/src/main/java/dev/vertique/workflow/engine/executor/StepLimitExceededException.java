// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

/**
 * INTERNAL: raised when one segment executes more nodes than {@code workflow.executor.maxStepsPerSegment} without
 * reaching a wait. It rolls the segment back; the executor then fails the instance with the {@code STEP_LIMIT}
 * reason.
 */
public final class StepLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int cap;

    /**
     * Creates the failure.
     *
     * @param cap the step cap that was exceeded
     */
    public StepLimitExceededException(int cap) {
        super("A segment executed more than " + cap + " steps without reaching a wait");
        this.cap = cap;
    }

    /**
     * The step cap that was exceeded.
     *
     * @return the cap
     */
    public int cap() {
        return cap;
    }
}
