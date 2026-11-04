// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import io.vertx.core.Future;

/**
 * Retention cleanup for the receipts of outbox dead-letter administration commands.
 *
 * <p>{@link OutboxMaintenanceService#cleanup()} calls this as the fourth of its independent cleanup
 * outcomes. The PostgreSQL implementation is bound by the module's Dagger wiring next to the
 * administration service.
 */
public interface OutboxAdminOperationCleanup {

    /**
     * Deletes administration receipts older than the retention window, in one bounded batch.
     *
     * @param retentionDays the retention window in days; receipts recorded earlier than that are removed
     * @param batchSize     the maximum number of receipts to delete in this call
     * @return a future of the number of receipts deleted; fails with an inbox/outbox persistence
     *         exception on a database failure
     */
    Future<Integer> cleanupOperations(int retentionDays, int batchSize);
}
