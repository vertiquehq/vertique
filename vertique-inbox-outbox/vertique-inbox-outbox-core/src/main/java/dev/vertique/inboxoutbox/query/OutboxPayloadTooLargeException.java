// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.exception.ConflictException;

/**
 * Thrown by {@link OutboxQueryService#getPayload(long)} when the stored payload and headers together exceed
 * {@link OutboxQueryConfig#maxPayloadBytes()}. The entry is never transferred.
 *
 * <p>The message is fixed text: it names neither the entry nor its content.
 */
public class OutboxPayloadTooLargeException extends ConflictException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public OutboxPayloadTooLargeException() {
        super("The outbox entry content exceeds the configured limit");
    }
}
