// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cancels calls from any thread. One signal may be shared by any number of calls: each call
 * registers with it when it starts and deregisters when it settles, so a long-lived signal holds
 * nothing for settled calls. {@link #cancel()} affects only calls that have not settled, and a call
 * started with a signal that is already cancelled fails without sending.
 */
public final class CancellationSignal {

    /** A call the signal can cancel. */
    interface Registration {

        /** Cancels the call; may be invoked from any thread. */
        void cancel();
    }

    private final Set<Registration> registrations = new LinkedHashSet<>();
    private boolean cancelled;

    private CancellationSignal() {}

    /**
     * Creates a signal that is not cancelled.
     *
     * @return a new signal
     */
    public static CancellationSignal create() {
        return new CancellationSignal();
    }

    /** Cancels every call registered with this signal. Safe from any thread; repeated calls do nothing. */
    public void cancel() {
        List<Registration> toCancel;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            toCancel = new ArrayList<>(registrations);
            registrations.clear();
        }
        for (Registration registration : toCancel) {
            registration.cancel();
        }
    }

    /**
     * Returns whether {@link #cancel()} was called.
     *
     * @return {@code true} once cancelled
     */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * Registers a call.
     *
     * @return {@code false} when the signal is already cancelled, so the call must not start
     */
    synchronized boolean register(Registration registration) {
        if (cancelled) {
            return false;
        }
        registrations.add(registration);
        return true;
    }

    synchronized void deregister(Registration registration) {
        registrations.remove(registration);
    }

    /** The number of calls currently registered. */
    synchronized int registrationCount() {
        return registrations.size();
    }
}
