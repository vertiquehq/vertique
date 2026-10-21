// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.db.DbExceptionMapper;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Admits an instance pin against the stored state of its definition, inside the caller's transaction.
 *
 * <p><strong>Module-internal.</strong> This class is public only because the instance repository lives in a
 * different package of this module. Applications must not call it; it is not part of the supported API.
 *
 * <p>Each admission runs three statements on the caller's connection, one after the other:
 *
 * <ol>
 *   <li>{@code SET LOCAL lock_timeout = '30000ms'}, the same bound the store applies to its own
 *       definition-id locks;
 *   <li>{@code pg_advisory_xact_lock_shared} on the same key as the exclusive lifecycle mutations, together
 *       with the transaction's isolation level. Many admissions hold the shared lock at once, while a
 *       lifecycle mutation (exclusive) waits for all of them to commit or roll back;
 *   <li>a separate read of every row of the definition id. It starts only after the lock was granted, so
 *       under READ COMMITTED it sees what the exclusive holder committed. Under REPEATABLE READ or
 *       SERIALIZABLE the read is {@code FOR SHARE}, so a visible row changed since the snapshot fails with
 *       a serialization failure.
 * </ol>
 *
 * <p>The id is judged as follows. Under READ COMMITTED an id without any row is unmanaged and admitted, which
 * keeps definitions registered only in code working. Under stronger isolation an empty read cannot be told
 * from a newly managed id that the snapshot predates, so it is refused. Otherwise exactly one owner must hold
 * the id (rows ACTIVE, SUPERSEDED or DEPRECATED), and the row of the pin's scope for the pinned version must
 * carry the pinned plan hash and an allowed status. New pins accept ACTIVE and SUPERSEDED; the resurrection of a
 * finished instance additionally accepts DEPRECATED. Refusals fail the returned future with
 * {@link WorkflowDefinitionLifecycleException} so that the caller's transaction can be rolled back; they are
 * never swallowed. A pinned plan hash that differs from the stored one is reason {@code DRIFT}, because the
 * node's compiled definition no longer matches the committed one; every other refusal is reason
 * {@code INVALID_TRANSITION}.
 *
 * <p>The client must be a connection whose transaction was begun through Vert.x and has not completed. Any
 * other client (a pool, a bare or completed connection, a connection with a raw SQL {@code BEGIN}, or a
 * wrapper) fails the returned future with {@link IllegalArgumentException} before any SQL is issued.
 *
 * <p>The shared lock and the lock-timeout setting stay in force until the caller's transaction ends. A
 * caller must therefore not wait for a separate, store-owned lifecycle transaction on the same definition id
 * while its own transaction holds the admission: that transaction waits for this one, so the two would block
 * each other until the lock timeout fails the lifecycle transaction.
 */
public final class DefinitionPinAdmission {

    private static final Set<String> NEW_PIN = Set.of("ACTIVE", "SUPERSEDED");
    private static final Set<String> RESURRECTION = Set.of("ACTIVE", "SUPERSEDED", "DEPRECATED");
    private static final Set<String> HOLDING = Set.of("ACTIVE", "SUPERSEDED", "DEPRECATED");

    private DefinitionPinAdmission() {}

    /**
     * Checks that the client is a connection with a pending transaction begun through Vert.x.
     *
     * @param client the client a pin write was given
     * @return a succeeded future, or a future failed with {@link IllegalArgumentException}; no SQL is issued
     */
    public static Future<Void> requireTransaction(SqlClient client) {
        try {
            if (client instanceof SqlConnection connection) {
                Transaction transaction = connection.transaction();
                if (transaction != null && !transaction.completion().isComplete()) {
                    return Future.succeededFuture();
                }
            }
        } catch (RuntimeException inspectionFailure) {
            // A client that cannot be inspected is not a client that can be trusted.
        }
        return Future.failedFuture(new IllegalArgumentException(
                "A workflow pin write requires a connection with a pending transaction begun through Vert.x"
                        + " (not a pool, a bare or completed connection, a raw BEGIN, or a wrapper)"));
    }

    /**
     * Admits a new pin, or the move of a pin onto another version, to the given definition version.
     *
     * @param client the caller's transaction connection
     * @param definitionsTable the {@code workflow_definitions} table name as the caller's statements address it,
     *     already qualified with the mapped schema when there is one; it is placed in SQL as given, so it must
     *     come from {@code PgTableNames.qualify}
     * @param key the definition scope, id and version the instance is being pinned to
     * @param planHash the plan hash the instance carries, or {@code null} when it carries none
     * @param mapper translates database failures such as lock timeouts and serialization failures
     * @return a future completed when admitted, or failed when refused or when the database failed
     */
    public static Future<Void> admitPin(
            SqlClient client, String definitionsTable, DefinitionKey key, String planHash, DbExceptionMapper mapper) {
        return admit(client, definitionsTable, key, planHash, NEW_PIN, mapper);
    }

    /**
     * Admits the resurrection of a finished instance onto its existing pin.
     *
     * @param client the caller's transaction connection
     * @param definitionsTable the {@code workflow_definitions} table name as the caller's statements address it,
     *     already qualified with the mapped schema when there is one; it is placed in SQL as given, so it must
     *     come from {@code PgTableNames.qualify}
     * @param key the definition scope, id and version the instance is pinned to
     * @param planHash the plan hash the instance carries, or {@code null} when it carries none
     * @param mapper translates database failures such as lock timeouts and serialization failures
     * @return a future completed when admitted, or failed when refused or when the database failed
     */
    public static Future<Void> admitResurrection(
            SqlClient client, String definitionsTable, DefinitionKey key, String planHash, DbExceptionMapper mapper) {
        return admit(client, definitionsTable, key, planHash, RESURRECTION, mapper);
    }

    private static Future<Void> admit(
            SqlClient client,
            String definitionsTable,
            DefinitionKey key,
            String planHash,
            Set<String> allowed,
            DbExceptionMapper mapper) {
        Future<Void> valid = requireTransaction(client);
        if (valid.failed()) return valid;
        Objects.requireNonNull(definitionsTable, "definitionsTable");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        String definitionId = key.definitionId();
        SqlConnection tx = (SqlConnection) client;
        String context = "workflow_definitions pin admission " + definitionId;

        Future<Void> bounded = translated(
                        tx.query("SET LOCAL lock_timeout = '30000ms'").execute(), mapper, context)
                .mapEmpty();
        Future<String> locked = bounded.compose(ignored -> translated(
                        tx.preparedQuery(
                                        "SELECT pg_advisory_xact_lock_shared($1), current_setting('transaction_isolation') AS isolation")
                                .execute(Tuple.of(DefinitionIdLock.lockKey(definitionId))),
                        mapper,
                        context))
                .map(rows -> rows.iterator().next().getString("isolation"));
        return locked.compose(isolation -> {
            boolean snapshot = !"read committed".equals(isolation) && !"read uncommitted".equals(isolation);
            String read = "SELECT scope_key, definition_version, plan_hash, status FROM " + definitionsTable
                    + " WHERE definition_id = $1" + (snapshot ? " FOR SHARE" : "");
            return translated(tx.preparedQuery(read).execute(Tuple.of(definitionId)), mapper, context)
                    .compose(rows -> {
                        List<Row> found = new ArrayList<>();
                        rows.forEach(found::add);
                        return judge(key, planHash, allowed, snapshot, found);
                    });
        });
    }

    private static <T> Future<T> translated(Future<T> statement, DbExceptionMapper mapper, String context) {
        return statement.recover(failure -> Future.failedFuture(mapper.translate(failure, context)));
    }

    private static Future<Void> judge(
            DefinitionKey key, String planHash, Set<String> allowed, boolean snapshot, List<Row> rows) {
        String definitionId = key.definitionId();
        long version = key.definitionVersion();
        if (rows.isEmpty()) {
            if (!snapshot) return Future.succeededFuture();
            return refused(
                    Reason.INVALID_TRANSITION,
                    WorkflowScopes.DEFAULT,
                    definitionId,
                    version,
                    "no row of this definition id is visible to the transaction snapshot, which cannot tell an"
                            + " unmanaged id from a newly managed one, so the pin is not admitted under"
                            + " REPEATABLE READ or SERIALIZABLE isolation");
        }
        Set<String> holders = new LinkedHashSet<>();
        for (Row row : rows) {
            if (HOLDING.contains(row.getString("status"))) holders.add(row.getString("scope_key"));
        }
        if (holders.size() != 1) {
            return refused(
                    Reason.INVALID_TRANSITION,
                    WorkflowScopes.DEFAULT,
                    definitionId,
                    version,
                    holders.isEmpty()
                            ? "no version of the definition is held by an owner that admits instances"
                            : "the definition is held by more than one owner");
        }
        String owner = holders.iterator().next();
        for (Row row : rows) {
            if (row.getLong("definition_version") != version || !key.scopeKey().equals(row.getString("scope_key"))) {
                continue;
            }
            if (!allowed.contains(row.getString("status"))) {
                return refused(
                        Reason.INVALID_TRANSITION,
                        key.scopeKey(),
                        definitionId,
                        version,
                        "the version is " + row.getString("status"));
            }
            if (planHash != null && !planHash.equals(row.getString("plan_hash"))) {
                return refused(
                        Reason.DRIFT,
                        key.scopeKey(),
                        definitionId,
                        version,
                        "the pinned plan hash " + planHash + " is not the stored plan hash "
                                + row.getString("plan_hash")
                                + ", so the code or document of this node differs from the committed definition");
            }
            return Future.succeededFuture();
        }
        return refused(
                Reason.INVALID_TRANSITION,
                key.scopeKey(),
                definitionId,
                version,
                "the version is not held by the owner of the definition");
    }

    private static Future<Void> refused(
            Reason reason, String scopeKey, String definitionId, long version, String detail) {
        try {
            DefinitionKey key = new DefinitionKey(scopeKey, definitionId, version);
            return Future.failedFuture(new WorkflowDefinitionLifecycleException(
                    reason, key, "Definition " + key + " does not admit this instance: " + detail));
        } catch (IllegalArgumentException invalidKey) {
            return Future.failedFuture(invalidKey);
        }
    }
}
