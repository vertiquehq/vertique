// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.intake;

import jakarta.annotation.Nullable;

/**
 * INTERNAL: the admission state of one node: a high mark, a low mark and the readings that move between them.
 *
 * <p>The state starts admitting, refuses from a reading at or above the high mark and admits again from a reading at or
 * below the low mark; a reading between the marks keeps the state it is in. A node that cannot read keeps its state
 * through {@value #TOLERATED_FAILED_SAMPLES} failed samples in a row and admits on the next one, because the limit
 * protects the database and must not become an outage of its own. The next good reading puts the node back under the
 * ordinary rules.
 *
 * <p>The readings are fed from one context, the sampler's, and are not synchronised. {@link #admitting()} is safe to
 * read from any thread.
 */
public final class IntakeAdmissionState implements IntakeAdmission {

    /** The failed samples in a row that leave the state as it is; the next one opens intake. */
    public static final int TOLERATED_FAILED_SAMPLES = 2;

    /** A change of the admission state, as the sampler reports it. */
    public record Transition(boolean refusing, long due, boolean readFailed) {}

    private final long highMark;
    private final long lowMark;

    private volatile boolean refusing;
    private long lastDue;
    private int failedInARow;

    /**
     * Creates the state of a node.
     *
     * @param highMark the number of due commands at or above which the node refuses
     * @param lowMark the number of due commands at or below which the node admits again; less than {@code highMark}
     * @throws IllegalArgumentException if {@code lowMark} is not less than {@code highMark}
     */
    public IntakeAdmissionState(long highMark, long lowMark) {
        if (lowMark >= highMark) {
            throw new IllegalArgumentException("lowMark must be less than highMark");
        }
        this.highMark = highMark;
        this.lowMark = lowMark;
    }

    @Override
    public boolean admitting() {
        return !refusing;
    }

    /**
     * Applies a successful reading.
     *
     * @param due the due commands counted; reported capped at one above the high mark
     * @return the transition the reading caused, or {@code null} when the node stays as it was
     */
    @Nullable
    public Transition onReading(long due) {
        failedInARow = 0;
        lastDue = Math.min(due, highMark + 1);
        if (!refusing && due >= highMark) {
            refusing = true;
            return new Transition(true, lastDue, false);
        }
        if (refusing && due <= lowMark) {
            refusing = false;
            return new Transition(false, lastDue, false);
        }
        return null;
    }

    /**
     * Applies a failed or timed-out sample.
     *
     * @return the transition the failure caused, or {@code null} when the node stays as it was; a node that was
     *     refusing opens intake on the failed sample that follows the tolerated ones
     */
    @Nullable
    public Transition onReadFailed() {
        failedInARow++;
        if (failedInARow > TOLERATED_FAILED_SAMPLES && refusing) {
            refusing = false;
            return new Transition(false, lastDue, true);
        }
        return null;
    }

    /**
     * Whether the failed sample just applied is the first that the node reports for an outage: the one that follows
     * the tolerated samples, reported once however long the outage lasts.
     *
     * @return {@code true} exactly on that sample
     */
    public boolean outageReached() {
        return failedInARow == TOLERATED_FAILED_SAMPLES + 1;
    }

    /**
     * The due commands counted by the last successful reading, capped at one above the high mark.
     *
     * @return the last good reading, or 0 before the first
     */
    public long lastDue() {
        return lastDue;
    }
}
