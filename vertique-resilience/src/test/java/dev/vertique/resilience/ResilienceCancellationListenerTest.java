// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof for the one-shot listener contract the executions rely on to be cancelled and to stop being
 * cancellable: a listener runs exactly once, with the first reason, and never after it unregisters.
 */
class ResilienceCancellationListenerTest {

    /**
     * A listener registered after the cancel runs at once with the reason, and one
     * registered before it does not run until the cancel.
     */
    @Test
    @DisplayName("a listener runs once with the reason, immediately when already cancelled")
    void listenerRunsOnceWithTheReasonImmediatelyWhenAlreadyCancelled() {
        // Given a cancellation with a listener registered before the cancel
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        List<Throwable> early = new ArrayList<>();
        cancellation.onCancel(early::add);

        // Then it has not run yet
        assertEquals(List.of(), early);

        // When the cancellation is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);

        // Then the early listener ran once with the reason
        assertEquals(1, early.size());
        assertSame(reason, early.get(0));

        // And a listener registered after the cancel runs immediately, once, with the same reason
        List<Throwable> late = new ArrayList<>();
        cancellation.onCancel(late::add);
        assertEquals(1, late.size());
        assertSame(reason, late.get(0));

        // And a second cancel runs neither of them again
        cancellation.cancel(new IllegalArgumentException("later"));
        assertEquals(1, early.size());
        assertEquals(1, late.size());
    }

    /** A listener that unregistered never runs, before or after the cancel. */
    @Test
    @DisplayName("an unregistered listener never runs")
    void unregisteredListenerNeverRuns() {
        // Given a listener that is registered and then unregistered
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        List<Throwable> unregistered = new ArrayList<>();
        Runnable unregister = cancellation.onCancel(unregistered::add);
        unregister.run();

        // When the cancellation is cancelled
        cancellation.cancel(new IllegalStateException("stop"));

        // Then the listener did not run
        assertEquals(List.of(), unregistered);

        // And unregistering after the cancel is harmless
        unregister.run();
        assertEquals(List.of(), unregistered);
    }

    /**
     * A listener that an earlier listener unregisters while the cancel is running is
     * skipped, because only the call that removes a listener runs it.
     */
    @Test
    @DisplayName("a listener unregistered by an earlier listener during the cancel is skipped")
    void listenerUnregisteredDuringTheCancelIsSkipped() {
        // Given two listeners where the first unregisters the second
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicReference<Runnable> unregisterSecond = new AtomicReference<>();
        List<String> ran = new ArrayList<>();
        cancellation.onCancel(reason -> {
            ran.add("first");
            unregisterSecond.get().run();
        });
        unregisterSecond.set(cancellation.onCancel(reason -> ran.add("second")));

        // When the cancellation is cancelled
        cancellation.cancel(new IllegalStateException("stop"));

        // Then only the first listener ran
        assertEquals(List.of("first"), ran);
    }

    /**
     * When a listener cancels again while the first cancel is delivering, the
     * listeners that follow still receive the first reason, once.
     */
    @Test
    @DisplayName("a re-entrant cancel does not change the reason later listeners receive")
    void reentrantCancelDoesNotChangeTheReasonLaterListenersReceive() {
        // Given a first listener that cancels again with another reason and a second listener
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        cancellation.onCancel(reason -> cancellation.cancel(second));
        List<Throwable> received = new ArrayList<>();
        cancellation.onCancel(received::add);

        // When the cancellation is cancelled with the first reason
        cancellation.cancel(first);

        // Then the second listener ran once, with the first reason
        assertEquals(1, received.size());
        assertSame(first, received.get(0));
        assertSame(first, cancellation.reason());
    }

    /** A cancellation that was not cancelled has no reason. */
    @Test
    @DisplayName("a cancellation that was not cancelled has no reason")
    void uncancelledCancellationHasNoReason() {
        assertNull(ResilienceCancellation.create().reason());
    }
}
