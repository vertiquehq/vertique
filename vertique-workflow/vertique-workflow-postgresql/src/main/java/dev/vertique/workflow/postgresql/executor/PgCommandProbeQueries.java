// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

/**
 * The statements {@link PgWorkflowCommandRepository} runs for the execution probe. Each takes the instance id as
 * {@code $1}, or {@code NULL} for every instance, and composes its eligibility conditions from
 * {@link CommandEligibilitySql}.
 */
final class PgCommandProbeQueries {

    private static final String SCOPE = " AND ($1::uuid IS NULL OR c.workflow_id = $1::uuid)";
    private static final String COLUMNS =
            "SELECT c.workflow_id, c.seq, c.kind, c.state, c.available_at, c.attempts, c.last_error";
    private static final String ORDER = " ORDER BY c.workflow_id, c.seq";

    final String countRunnable;
    final String findDeferred;
    final String findParked;
    final String findNotYetAvailable;

    PgCommandProbeQueries(CommandEligibilitySql eligibility) {
        String from = " FROM " + eligibility.commandsTable() + " c WHERE ";
        this.countRunnable = "SELECT count(*) FROM " + eligibility.commandsTable() + " c WHERE ("
                + eligibility.inFlight("c") + " OR " + eligibility.claimable("c") + ")" + SCOPE;
        this.findDeferred =
                COLUMNS + from + "c.state = 'DEFERRED' AND NOT " + eligibility.claimable("c") + SCOPE + ORDER;
        this.findParked = COLUMNS + from + "c.state = 'PARKED'" + SCOPE + ORDER;
        this.findNotYetAvailable = COLUMNS + from + "c.state IN ('PENDING','CLAIMED') AND NOT "
                + eligibility.claimable("c") + SCOPE + ORDER;
    }
}
