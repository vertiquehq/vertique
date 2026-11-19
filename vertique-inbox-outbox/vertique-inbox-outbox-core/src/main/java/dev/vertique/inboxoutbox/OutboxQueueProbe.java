// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;

/**
 * Reads the size of the outbox queue, for monitoring.
 *
 * <p>An implementation answers with one {@link OutboxQueueSample} per call, read from the store
 * the relay claims from. The call is a read: it claims nothing, locks nothing and changes no entry.
 * It is meant to be called on a timer, not once per request, and a caller must not start a new
 * read while the previous one is still running.
 *
 * <p>The PostgreSQL module binds this interface to its repository; an application that implements
 * its own {@link OutboxRepository} binds its own probe.
 *
 * @see OutboxQueueSample
 */
public interface OutboxQueueProbe {

    /**
     * Reads the queue size now.
     *
     * @return a future of the sample; fails when the store cannot be read
     */
    Future<OutboxQueueSample> sample();
}
