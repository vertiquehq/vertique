// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;

/**
 * Translates the failures of the outbox query and operations services. The semantic exceptions
 * ({@link ValidationException}, {@link NotFoundException}, {@link ConflictException}) and an
 * {@link InboxOutboxPersistenceException} pass through unchanged; everything else becomes an
 * {@link InboxOutboxPersistenceException} whose message carries no SQL, driver text or request data.
 */
final class PgOutboxFailures {

    private static final PgInboxOutboxExceptionMapper BOUNDARY_MAPPER = new PgInboxOutboxExceptionMapper();

    private PgOutboxFailures() {}

    static Throwable translate(PgDbExceptionMapper exceptionMapper, Throwable failure, String operation) {
        if (failure instanceof ValidationException
                || failure instanceof NotFoundException
                || failure instanceof ConflictException
                || failure instanceof InboxOutboxPersistenceException) {
            return failure;
        }
        Throwable mapped = BOUNDARY_MAPPER.translate(exceptionMapper.translate(failure, operation), operation);
        if (mapped instanceof InboxOutboxPersistenceException) {
            return mapped;
        }
        return new InboxOutboxPersistenceException(
                "Persistence failure during inbox/outbox operation '" + operation + "' ["
                        + failure.getClass().getSimpleName() + "]",
                failure,
                false);
    }
}
