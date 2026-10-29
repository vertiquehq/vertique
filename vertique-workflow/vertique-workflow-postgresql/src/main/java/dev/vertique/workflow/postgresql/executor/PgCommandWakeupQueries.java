// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

/** A single-row scheduling read, separate from the bounded diagnostic queries used by {@code IdleReport}. */
final class PgCommandWakeupQueries {

    final String findEarliestAvailabilityDelayMs;

    PgCommandWakeupQueries(CommandEligibilitySql eligibility) {
        String commands = eligibility.commandsTable();
        this.findEarliestAvailabilityDelayMs = "WITH sampled_clock AS MATERIALIZED ("
                + "SELECT clock_timestamp() AS sampled_at) "
                + "SELECT ceil(extract(epoch FROM (candidate.available_at - sampled_clock.sampled_at)) * 1000)::bigint"
                + " AS delay_ms FROM sampled_clock LEFT JOIN LATERAL (SELECT c.available_at FROM " + commands
                + " c WHERE c.state IN ('PENDING','DEFERRED') AND c.available_at > sampled_clock.sampled_at"
                + " ORDER BY c.available_at LIMIT 1) candidate ON TRUE";
    }
}
