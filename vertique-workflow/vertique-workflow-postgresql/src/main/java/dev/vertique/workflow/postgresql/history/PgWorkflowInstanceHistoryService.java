// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.history.WorkflowInstanceHistoryService;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed {@link WorkflowInstanceHistoryService}.
 *
 * <p>Reads from the pool with no lock and outside any caller transaction. Entries come from
 * {@link PgWorkflowHistoryRepository#listPage}; open commands from {@code workflow_commands}, whose {@code last_error}
 * is returned as stored because the writer sanitizes it.
 */
@Singleton
public final class PgWorkflowInstanceHistoryService implements WorkflowInstanceHistoryService {

    private static final String INSTANCE_NAME = "workflow";
    private static final int MAX_LIMIT = 500;

    private final PgWorkflowHistoryRepository history;
    private final Pool pool;
    private final PgDbExceptionMapper exceptionMapper;
    private final String sqlOpenCommands;

    /**
     * Creates the service over the history repository and the command table of the same schema placement.
     *
     * @param history         the history repository that reads the entries
     * @param pool            the PostgreSQL connection pool
     * @param exceptionMapper the exception mapper for translating SQL errors
     * @param schemas         the schema placement; an empty mapping leaves table names unqualified
     */
    public PgWorkflowInstanceHistoryService(
            PgWorkflowHistoryRepository history, Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas) {
        this.history = history;
        this.pool = pool;
        this.exceptionMapper = exceptionMapper;
        String commands = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_commands");
        this.sqlOpenCommands = "SELECT workflow_id, seq, kind, state, available_at, attempts, last_error FROM "
                + commands + " WHERE workflow_id = $1 AND state IN ('PENDING','DEFERRED','CLAIMED','PARKED','DEAD')"
                + " ORDER BY seq ASC";
    }

    @Override
    public Future<List<WorkflowHistoryEntry>> entries(WorkflowInstanceId id, long afterSequence, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + " but was " + limit);
        }
        return history.listPage(id, afterSequence, limit);
    }

    @Override
    public Future<List<PendingCommand>> openCommands(WorkflowInstanceId id) {
        return pool.preparedQuery(sqlOpenCommands)
                .execute(Tuple.of(id.value()))
                .map(rows -> {
                    List<PendingCommand> commands = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        commands.add(new PendingCommand(
                                id,
                                row.getLong("seq"),
                                row.getString("kind"),
                                row.getString("state"),
                                row.getOffsetDateTime("available_at").toInstant(),
                                row.getInteger("attempts"),
                                row.getString("last_error")));
                    }
                    return commands;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_commands openCommands")));
    }
}
