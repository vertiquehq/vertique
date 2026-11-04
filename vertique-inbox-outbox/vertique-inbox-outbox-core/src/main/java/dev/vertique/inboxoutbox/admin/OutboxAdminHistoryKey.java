// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * Keyset position in an entry's administration history, ordered by recording time then operation ID,
 * both descending.
 *
 * <p>This is the plain sort tuple. Encoding it into an opaque cursor belongs to the caller.
 *
 * @param recordedAt  time the operation was recorded; never {@code null}
 * @param operationId identifier of the operation; never {@code null}
 */
public record OutboxAdminHistoryKey(Instant recordedAt, UUID operationId) {

    /**
     * Creates a key.
     *
     * @param recordedAt  time the operation was recorded; never {@code null}
     * @param operationId identifier of the operation; never {@code null}
     * @throws dev.vertique.core.exception.ValidationException if a component is absent or {@code recordedAt} is
     *         outside 0001-01-01T00:00:00Z to 9999-12-31T23:59:59Z
     */
    public OutboxAdminHistoryKey {
        AdminChecks.instant(AdminChecks.required(recordedAt, "recordedAt"), "recordedAt");
        AdminChecks.required(operationId, "operationId");
    }
}
