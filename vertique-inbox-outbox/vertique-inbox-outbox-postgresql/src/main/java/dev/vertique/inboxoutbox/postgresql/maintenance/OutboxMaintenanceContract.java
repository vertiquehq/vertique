// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for cluster-singleton outbox/inbox maintenance.
 *
 * <p>Two operations, each scheduled as a {@code SINGLE_INSTANCE} cron job:
 * <ul>
 *   <li>{@link #recoverStaleLeases()} — resets entries stuck in {@code PROCESSING} beyond the
 *       configured lease timeout.</li>
 *   <li>{@link #cleanup()} — deletes old published, dead-letter, and inbox rows.</li>
 * </ul>
 *
 * <p>{@code @ServiceOperation} stable ids are required by the cron registrar; without them a cron
 * job on this contract cannot resolve to a durable service target.
 *
 * <p><b>Internal contract:</b> this interface exists to satisfy the cron registrar's
 * stable-target-id requirement. Applications should not invoke these operations directly —
 * fires are owned by the cron dispatcher on the leader node, and a direct invocation would race
 * with that leader.
 */
@ServiceContract(namespace = "inboxoutbox", value = "outbox-maintenance")
public interface OutboxMaintenanceContract {

    /**
     * Resets outbox entries stuck in {@code PROCESSING} state beyond the configured lease timeout.
     *
     * @return a future that completes when the reclaim is done
     */
    @ServiceOperation("recoverStaleLeases")
    Future<Void> recoverStaleLeases();

    /**
     * Runs one cleanup cycle across published, dead-letter, and inbox tables. Reports a composite
     * outcome: success iff all three succeed; otherwise failure carrying every underlying cause.
     *
     * @return a future that completes when the cleanup cycle is done
     */
    @ServiceOperation("cleanup")
    Future<Void> cleanup();
}
