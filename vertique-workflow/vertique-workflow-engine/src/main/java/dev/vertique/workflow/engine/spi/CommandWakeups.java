// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import io.vertx.core.Context;

/**
 * SPI for the source that wakes executor instances when a command becomes pending or deferred.
 *
 * <p>An executor instance registers on start and closes the registration on stop. The source calls the instance's
 * {@link WakeupSink} on the registered context, never on a context of its own, and never blocks. A wake-up is only a
 * hint: the instance peeks globally and claims nothing directly, so a lost, repeated or malformed hint costs at most
 * one extra peek, and polling remains the fallback.
 */
@FunctionalInterface
public interface CommandWakeups {

    /**
     * Registers a sink to be woken on the given context.
     *
     * @param context the context the sink runs on; the executor instance's own verticle context
     * @param sink    the sink to wake
     * @return the registration; closing it ends the wake-ups for the sink
     */
    Registration register(Context context, WakeupSink sink);

    /**
     * A source that never wakes anyone, so executors rely on polling alone.
     *
     * @return the source
     */
    static CommandWakeups none() {
        return (context, sink) -> () -> {};
    }

    /** Receives wake-up hints on the context it was registered with. */
    @FunctionalInterface
    interface WakeupSink {

        /**
         * Called when a command became pending or deferred.
         *
         * @param availableAtEpochMs the command's available time in epoch milliseconds; a time in the past, such as
         *                           {@code 0}, when the source could not read one, which means peek now
         */
        void wake(long availableAtEpochMs);
    }

    /** A live registration of a sink. */
    interface Registration extends AutoCloseable {

        /** Ends the wake-ups for the sink; closing twice has no further effect. */
        @Override
        void close();
    }
}
