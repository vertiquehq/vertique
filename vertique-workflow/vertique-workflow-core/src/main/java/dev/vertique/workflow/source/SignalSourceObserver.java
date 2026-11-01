// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.source;

/**
 * Observes the outcome of every signal a correlated signal source records, and of every message that ends before a
 * signal is chosen.
 *
 * <p>Register an observer by contributing it into the {@code Set<SignalSourceObserver>} multibinding.
 * Every observer is told about every outcome, once per signal, or once for an outcome reached before a signal is
 * chosen, in no defined order. An observer never
 * changes an outcome: what it throws is contained and reported by the framework, and a message's fate
 * is the same with or without it.
 */
public interface SignalSourceObserver {

    /**
     * Called once with the outcome of a signal, or of a message that ended before a signal was chosen, after the
     * source has decided it.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation.
     *
     * <p>{@link LinkageError}, {@link AssertionError} and {@link StackOverflowError} are swallowed the
     * same way, and a {@link LinkageError} is reported at error level at a limited rate.
     *
     * <p>The callback runs on the context of the delivery and may run concurrently for different
     * messages, so an implementation must be thread-safe. It must not block.
     *
     * @param event the outcome; carries framework facts only, never a key, a header or a payload
     */
    void onOutcome(SignalSourceEvent event);
}
