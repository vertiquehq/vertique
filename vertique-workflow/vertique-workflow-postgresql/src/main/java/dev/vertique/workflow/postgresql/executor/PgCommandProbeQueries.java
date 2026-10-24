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
    /** The most characters of a command's last error that a report carries; the writers store at most as many. */
    static final int MAX_LAST_ERROR = 4000;

    private static final String COLUMNS = "SELECT c.workflow_id, c.seq, c.kind, c.state, c.available_at, c.attempts,"
            + " left(c.last_error, " + MAX_LAST_ERROR + ") AS last_error";
    /** The most commands one list of the probe's report holds; further commands are left out of the report. */
    static final int MAX_LISTED = 1000;

    private static final String ORDER = " ORDER BY c.workflow_id, c.seq LIMIT " + MAX_LISTED;

    final String countRunnable;
    final String findDeferred;
    final String findParked;
    final String findNotYetAvailable;
    final String findStuck;
    final String countQueue;

    PgCommandProbeQueries(CommandEligibilitySql eligibility) {
        String from = " FROM " + eligibility.commandsTable() + " c WHERE ";
        this.countRunnable = "SELECT count(*) FROM " + eligibility.commandsTable() + " c WHERE ("
                + eligibility.inFlight("c") + " OR " + eligibility.claimable("c") + ")" + SCOPE;
        this.findDeferred =
                COLUMNS + from + "c.state = 'DEFERRED' AND NOT " + eligibility.claimable("c") + SCOPE + ORDER;
        this.findParked = COLUMNS + from + "c.state = 'PARKED'" + SCOPE + ORDER;
        this.findNotYetAvailable = COLUMNS + from + "c.state IN ('PENDING','CLAIMED') AND NOT "
                + eligibility.claimable("c") + SCOPE + ORDER;
        this.findStuck = COLUMNS + from + stuck("c", "$2") + SCOPE + ORDER;
        String commands = eligibility.commandsTable();
        this.countQueue = "SELECT (SELECT count(*) FROM " + commands + " c WHERE " + eligibility.claimable("c")
                + ") AS available, (SELECT count(*) FROM " + commands + " c WHERE " + stuck("c", "$1")
                + ") AS stuck, (SELECT count(*) FROM " + commands + " c WHERE c.state = 'PARKED') AS parked,"
                + " (SELECT count(*) FROM " + commands + " c WHERE c.state = 'DEAD') AS dead_lettered";
    }

    /**
     * A command is reported stuck: it is dead-lettered, or unsettled and first available longer ago than the age in
     * the given parameter, in seconds. The first availability of a timer command is its fire time and of any other command the time
     * it was recorded, never its current availability, which deferral and transient backoff move forward.
     */
    private static String stuck(String c, String seconds) {
        return "(" + c + ".state = 'DEAD' OR (" + c
                + ".state IN ('PENDING','DEFERRED','CLAIMED','PARKED') AND COALESCE(" + c + ".fire_at, " + c
                + ".recorded_at) <= now() - (" + seconds + "::float8 * interval '1 second')))";
    }
}
