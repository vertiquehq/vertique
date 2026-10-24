// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgTableNames;

/**
 * The single SQL rendering of the executor protocol's eligibility rule over {@code workflow_commands} and
 * {@code workflow_tasks}. Every reader of eligibility composes its statement from these fragments, so the rule has
 * no second rendering.
 *
 * <p>Each method returns a boolean SQL expression over the command row correlated as the given alias. The
 * expressions read the database clock with {@code now()}, which is fixed for the duration of one statement, so a
 * statement judges every row at the same instant:
 *
 * <ul>
 *   <li>{@link #heldBack}: a due-date or reminder timer whose task is completing. A completion that is dead-lettered
 *       still holds its task's timers back, because the task stays {@code COMPLETING} under its reservation.</li>
 *   <li>{@link #blocks}: an unsettled command ({@code PENDING}, {@code DEFERRED} or {@code CLAIMED}) that is not a
 *       timer before its fire time and is not held back. {@code PARKED}, {@code DEAD} and settled commands block
 *       nothing.</li>
 *   <li>{@link #atHead}: not held back, and no earlier command of the same instance blocks.</li>
 *   <li>{@link #claimable}: at the head, available, and either {@code PENDING}, {@code DEFERRED}, or {@code CLAIMED}
 *       under a lease that has expired.</li>
 *   <li>{@link #inFlight}: {@code CLAIMED} under a lease that has not expired.</li>
 * </ul>
 *
 * <p>The hold-back of a timeout timer by an earlier signal or completion for the wait it belongs to is not rendered
 * here yet: it reads the wait from the command's target, which only the timer writer records.
 */
public final class CommandEligibilitySql {

    private static final String INSTANCE_NAME = "workflow";

    private final String commands;
    private final String tasks;

    /**
     * Creates the rendering over tables placed by the schema mapping of the {@code workflow} instance.
     *
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment identifier
     */
    public CommandEligibilitySql(DbSchemas schemas) {
        this.commands = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_commands");
        this.tasks = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_tasks");
    }

    /** The possibly schema-qualified {@code workflow_commands} table name. */
    public String commandsTable() {
        return commands;
    }

    /**
     * The command is a due-date or reminder timer whose task is completing.
     *
     * @param c the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String heldBack(String c) {
        return "(" + c + ".kind = 'TIMER' AND " + c + ".timer_kind IN ('DUE','REMINDER')"
                + " AND EXISTS (SELECT 1 FROM " + tasks + " tk_" + c + " WHERE tk_" + c + ".task_id = " + c
                + ".task_id AND tk_" + c + ".status = 'COMPLETING'))";
    }

    /**
     * The command keeps the commands behind it from running.
     *
     * @param b the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String blocks(String b) {
        return "(" + b + ".state IN ('PENDING','DEFERRED','CLAIMED')"
                + " AND NOT (" + b + ".kind = 'TIMER' AND " + b + ".fire_at IS NOT NULL AND now() < " + b + ".fire_at)"
                + " AND NOT " + heldBack(b) + ")";
    }

    /**
     * The command is not held back and no earlier command of its instance blocks.
     *
     * @param c the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String atHead(String c) {
        String b = "b_" + c;
        return "(NOT " + heldBack(c) + " AND NOT EXISTS (SELECT 1 FROM " + commands + " " + b
                + " WHERE " + b + ".workflow_id = " + c + ".workflow_id AND " + b + ".seq < " + c + ".seq AND "
                + blocks(b) + "))";
    }

    /**
     * An executor could claim the command now.
     *
     * @param c the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String claimable(String c) {
        return "(" + atHead(c) + " AND " + c + ".available_at <= now() AND (" + c + ".state IN ('PENDING','DEFERRED')"
                + " OR (" + c + ".state = 'CLAIMED' AND " + c + ".lease_until <= now())))";
    }

    /**
     * The command is claimed under a lease that has not expired.
     *
     * @param c the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String inFlight(String c) {
        return "(" + c + ".state = 'CLAIMED' AND " + c + ".lease_until > now())";
    }
}
