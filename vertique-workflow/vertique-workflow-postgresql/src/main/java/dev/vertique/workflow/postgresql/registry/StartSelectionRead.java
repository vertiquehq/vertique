// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartSelection;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartVersion;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;

/**
 * The one statement through which a start reads what it selects its version from: the stored versions of a definition
 * id in the start scope and the default scope, and the activation records of the id in the same two scopes.
 *
 * <p>The statement is a plain read that runs on the caller's transaction handle. It takes no lock, sets no timeout
 * and reads no document text, provenance or name binding. Under {@code READ COMMITTED} it sees every activation
 * committed before it runs; under {@code REPEATABLE READ} or {@code SERIALIZABLE} it sees the caller's snapshot.
 * Pin admission, not this read, is the fence for the status of the version a start pins.
 */
final class StartSelectionRead {

    private static final String VERSION = "V";
    private static final String ACTIVATION = "A";

    private final String sql;

    /**
     * Builds the statement over the two tables.
     *
     * @param definitions the {@code workflow_definitions} table name as the statements address it, already qualified
     * @param activations the {@code workflow_definition_activations} table name, already qualified
     */
    StartSelectionRead(String definitions, String activations) {
        String scopes =
                " WHERE definition_id = $2::varchar AND scope_key IN ($1::varchar, '" + WorkflowScopes.DEFAULT + "')";
        this.sql = "SELECT '" + VERSION + "' AS row_kind, scope_key, definition_id, definition_version, kind,"
                + " contract_name, state_type_name, plan_hash, status, failure_reason, signal_names_json,"
                + " start_schema_json, NULL::varchar AS target_scope_key, NULL::timestamptz AS activated_at FROM "
                + definitions + scopes
                + " UNION ALL SELECT '" + ACTIVATION + "', scope_key, definition_id, definition_version,"
                + " NULL::varchar, NULL::varchar, NULL::varchar, NULL::varchar, NULL::varchar, NULL::varchar,"
                + " NULL::jsonb, NULL::jsonb, target_scope_key, activated_at FROM " + activations + scopes;
    }

    /**
     * Runs the read on the caller's handle.
     *
     * @param tx           the caller's transaction handle
     * @param startScope   the scope the start runs in
     * @param definitionId the definition id the start names
     * @return the versions and the activation records
     */
    Future<StartSelection> read(SqlClient tx, String startScope, String definitionId) {
        return tx.preparedQuery(sql).execute(Tuple.of(startScope, definitionId)).map(rows -> {
            List<StartVersion> versions = new ArrayList<>();
            List<DefinitionActivation> records = new ArrayList<>();
            for (Row row : rows) {
                if (ACTIVATION.equals(row.getString("row_kind"))) {
                    records.add(PgWorkflowDefinitionRegistryStore.activation(row));
                } else {
                    versions.add(version(row));
                }
            }
            return new StartSelection(versions, records);
        });
    }

    private static StartVersion version(Row row) {
        return new StartVersion(
                new DefinitionKey(
                        row.getString("scope_key"), row.getString("definition_id"), row.getLong("definition_version")),
                DefinitionKind.valueOf(row.getString("kind")),
                row.getString("contract_name"),
                row.getString("state_type_name"),
                row.getString("plan_hash"),
                DefinitionStatus.valueOf(row.getString("status")),
                PgWorkflowDefinitionRegistryStore.storedFailure(row),
                PgWorkflowDefinitionRegistryStore.storedObject(row, "signal_names_json"),
                PgWorkflowDefinitionRegistryStore.storedObject(row, "start_schema_json"));
    }
}
