// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/**
 * The mark of an instance that existed before the decision log and has not logged its full state yet.
 *
 * <p>The upgrade conversion clears {@code workflow_instances.baseline_logged} for every instance it finds; the first
 * segment that claims such an instance consumes the mark here, in the segment's transaction and under the execution
 * lock, and logs the full state as a baseline. An instance created by this version never has the mark cleared.
 */
final class LegacyBaseline {

    private final String consume;

    /**
     * Creates the statement over the placement of the workflow tables.
     *
     * @param schemas the schema placement; an empty mapping leaves table names unqualified
     */
    LegacyBaseline(DbSchemas schemas) {
        String instances = PgTableNames.qualify(schemas, "workflow", "workflow_instances");
        this.consume = "UPDATE " + instances + " SET baseline_logged = TRUE WHERE id = $1 AND baseline_logged = FALSE"
                + " RETURNING id";
    }

    /**
     * Consumes the mark of an instance, if it is set.
     *
     * @param instance the instance
     * @param tx       the segment's transaction, which holds the instance's execution lock
     * @return a future of whether the instance still owed its baseline, that is whether it was a pre-log instance that
     *     had not been claimed yet
     */
    Future<Boolean> consume(WorkflowInstanceId instance, SqlClient tx) {
        return tx.preparedQuery(consume).execute(Tuple.of(instance.value())).map(rows -> rows.size() > 0);
    }
}
