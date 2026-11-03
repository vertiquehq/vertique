// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.inboxoutbox;

import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * Completes a service call whose work finishes after the service returns.
 *
 * <p>The service obtains a handle from {@link TransactionalMessageContext#replyHandle()} and may
 * store it across restarts. A migration can issue a new handle, so store the latest one. The handle
 * is a bearer value: anyone holding it can complete that one wait, and the application must protect it.
 */
public interface OutboxReplies {
    /** The result of attempting to record an outcome. */
    enum Completion {
        /** The outcome was recorded; application may happen later. */
        RECORDED,
        /** The same outcome name was already recorded. */
        DUPLICATE,
        /** The wait closed, a different outcome won, or the submitted value was refused. */
        STALE
    }

    /**
     * Records a successful completion. A refused value records a failure when possible and answers stale.
     *
     * @param handle the opaque bearer handle
     * @param value the reply value, or null
     * @return the completion result; an invalid handle fails with an IllegalArgumentException that does not echo it
     */
    Future<Completion> complete(String handle, @Nullable Object value);

    /**
     * Records a reported failure. A repeated failure is duplicate; a recorded success makes it stale.
     *
     * @param handle the opaque bearer handle
     * @return the completion result; an invalid handle fails with an IllegalArgumentException that does not echo it
     */
    Future<Completion> fail(String handle);
}
