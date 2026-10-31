// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for cluster-singleton job table maintenance.
 *
 * <p>One operation, scheduled as a {@code SINGLE_INSTANCE} cron job by {@link JobMaintenanceCron}.
 * {@code @ServiceOperation} stable ids are required by the cron registrar; without them a cron job
 * on this contract cannot resolve to a durable service target.
 *
 * <p><b>Internal contract:</b> this interface exists to satisfy the cron registrar's stable-target-id
 * requirement. Applications should not invoke it directly: fires are owned by the cron dispatcher on
 * the leader node, and a direct invocation would race with that leader.
 */
@ServiceContract(namespace = "job", value = "job-maintenance")
public interface JobMaintenanceContract {

    /**
     * Deletes finished job executions, and their logs, that are older than the configured retention.
     *
     * @return a future that completes when the purge is done
     */
    @ServiceOperation("purgeFinishedExecutions")
    Future<Void> purgeFinishedExecutions();
}
