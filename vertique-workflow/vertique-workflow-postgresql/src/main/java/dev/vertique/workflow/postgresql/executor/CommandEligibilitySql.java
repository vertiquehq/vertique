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
 *   <li>{@link #heldBack}: a timeout timer while an earlier-recorded signal or completion for the wait it belongs to
 *       is still pending, deferred or claimed; a due-date or reminder timer whose task is completing. A completion that
 *       is dead-lettered still holds its task's timers back, because the task stays {@code COMPLETING} under its
 *       reservation, whereas a dead-lettered signal holds no timeout back.</li>
 *   <li>{@link #blocks}: an unsettled command ({@code PENDING}, {@code DEFERRED} or {@code CLAIMED}) that is not a
 *       timer before its fire time and is not held back. {@code PARKED}, {@code DEAD} and settled commands block
 *       nothing.</li>
 *   <li>{@link #atHead}: not held back, and no earlier command of the same instance blocks.</li>
 *   <li>{@link #claimable}: at the head, available, and either {@code PENDING}, {@code DEFERRED}, or {@code CLAIMED}
 *       under a lease that has expired.</li>
 *   <li>{@link #inFlight}: {@code CLAIMED} under a lease that has not expired.</li>
 * </ul>
 *
 * <p>The wait a timeout belongs to is read from the timeout command's target (the signal names the wait accepts and its
 * branch address) and from its task; a signal targets the wait when its name is among those names and it carries the
 * same branch address, a completion when it reserves the wait's task. A signal recorded before the wait's timeout
 * existed was recorded before the timeout's fire time, which is taken later, so it holds the timeout back too.
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
     * The command is held back: a timeout timer while an operation recorded before its fire time that targets its wait
     * is pending, deferred or claimed, or a due-date or reminder timer whose task is completing.
     *
     * @param c the correlation name of the command row
     * @return a boolean SQL expression
     */
    public String heldBack(String c) {
        // The task clause comes first so that a statement takes its lock on workflow_tasks as early in its analysis as
        // it did before the timeout clause existed; a statement then holds its other table locks no longer while it
        // waits for that one, which keeps the window small in which a TRUNCATE of the workflow tables can deadlock
        // with a poll of the executor.
        return "(" + taskTimerHeldBack(c) + " OR " + timeoutHeldBack(c) + ")";
    }

    private String timeoutHeldBack(String c) {
        String o = "o_" + c;
        return "(" + c + ".kind = 'TIMER' AND " + c + ".timer_kind = 'TIMEOUT' AND EXISTS (SELECT 1 FROM " + commands
                + " " + o + " WHERE " + o + ".workflow_id = " + c + ".workflow_id AND " + o
                + ".state IN ('PENDING','DEFERRED','CLAIMED') AND " + o + ".recorded_at < " + c + ".fire_at AND (("
                + o + ".kind = 'SIGNAL' AND jsonb_exists(" + c + ".target_json->'signalNames', " + o
                + ".target_json->>'name') AND " + o + ".target_json->>'branchId' IS NOT DISTINCT FROM " + c
                + ".target_json->>'branchId' AND " + o + ".target_json->>'forkStepId' IS NOT DISTINCT FROM " + c
                + ".target_json->>'forkStepId') OR (" + o + ".kind = 'COMPLETION' AND " + c
                + ".task_id IS NOT NULL AND " + o + ".task_id = " + c + ".task_id))))";
    }

    private String taskTimerHeldBack(String c) {
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
