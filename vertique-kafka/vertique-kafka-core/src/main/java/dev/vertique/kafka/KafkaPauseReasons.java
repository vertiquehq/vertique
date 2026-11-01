// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.Objects;

/**
 * Tracks why a consumer is paused, so that it resumes only when no reason remains.
 *
 * <p>A consumer can be held for several reasons at once: it has reached its in-flight limit, and a record's retry is
 * waiting out its backoff. With one flag, whichever reason cleared first resumed the consumer while the other still
 * applied, and a pause that outlived the clearing of its own reason stayed on for good. Here each reason is tracked on
 * its own and the consumer is paused on the first reason that is held and resumed on the release of the last.
 *
 * <p>A {@linkplain Reason#IN_FLIGHT level} reason is held or not held, whatever the number of times it is requested. A
 * {@linkplain Reason#ERROR_BACKOFF counted} reason is held once per request, and each request is released by exactly
 * one release, so two retries that overlap keep the consumer paused until both have ended. A release that finds nothing
 * held is ignored, so a surplus release cannot release another reason.
 *
 * <p>The transitions and the calls to the consumer happen under one lock, so the pause and the resume reach the
 * consumer in the order the reasons changed. Neither call blocks.
 */
final class KafkaPauseReasons {

    /** The reasons a consumer is paused. */
    enum Reason {

        /** The consumer holds as many unfinished dispatches as its in-flight limit allows. Held until below it. */
        IN_FLIGHT(false),

        /** A failed record is waiting out its backoff before it is delivered again. One hold per failed record. */
        ERROR_BACKOFF(true);

        private final boolean counted;

        Reason(boolean counted) {
            this.counted = counted;
        }
    }

    private final Runnable pause;
    private final Runnable resume;
    private final int[] holds = new int[Reason.values().length];
    private boolean paused;

    /**
     * Creates the tracker of one consumer.
     *
     * @param pause pauses the consumer; called when the first reason is held
     * @param resume resumes the consumer; called when the last reason is released
     */
    KafkaPauseReasons(Runnable pause, Runnable resume) {
        this.pause = Objects.requireNonNull(pause, "pause");
        this.resume = Objects.requireNonNull(resume, "resume");
    }

    /**
     * Holds a reason, pausing the consumer if it is running.
     *
     * @param reason the reason to hold
     */
    synchronized void hold(Reason reason) {
        int index = reason.ordinal();
        if (reason.counted) {
            holds[index]++;
        } else {
            holds[index] = 1;
        }
        if (!paused) {
            paused = true;
            pause.run();
        }
    }

    /**
     * Releases a reason, resuming the consumer when it was the last one held.
     *
     * @param reason the reason to release
     */
    synchronized void release(Reason reason) {
        int index = reason.ordinal();
        if (holds[index] == 0) {
            return;
        }
        holds[index] = reason.counted ? holds[index] - 1 : 0;
        if (paused && total() == 0) {
            paused = false;
            resume.run();
        }
    }

    /**
     * Whether a reason is held.
     *
     * @param reason the reason
     * @return {@code true} when at least one hold of the reason remains
     */
    synchronized boolean isHeld(Reason reason) {
        return holds[reason.ordinal()] > 0;
    }

    /**
     * Whether the consumer is paused for any reason.
     *
     * @return {@code true} when a reason is held
     */
    synchronized boolean isPaused() {
        return paused;
    }

    private int total() {
        int total = 0;
        for (int count : holds) {
            total += count;
        }
        return total;
    }
}
