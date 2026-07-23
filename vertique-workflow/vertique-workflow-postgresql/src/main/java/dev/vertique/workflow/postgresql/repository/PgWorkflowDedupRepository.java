// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.workflow.dedup.WorkflowDedupScopes;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL-backed repository for workflow deduplication records.
 *
 * <p>The {@code workflow_dedup} table has a composite primary key {@code (kind, scope, key)} and
 * covers two deduplication concerns:
 *
 * <ul>
 *   <li>{@code kind='start'}, {@code scope=definitionId}, {@code key=idempotencyKey} — prevents
 *       duplicate workflow starts for the same idempotency key within a definition.
 *   <li>{@code kind='signal'}, {@code scope=workflowInstanceId::text}, {@code key=signalDedupKey}
 *       — prevents the same signal from being applied twice to the same instance.
 * </ul>
 *
 * <p>The {@link #claimOrResolveStart} method uses an {@code INSERT ... ON CONFLICT DO UPDATE
 * RETURNING} pattern (§3.7) that is race-safe under {@code READ COMMITTED}. {@code DO UPDATE}
 * serializes with concurrent inserters: if a concurrent transaction has an uncommitted conflicting
 * row, PostgreSQL waits for that transaction to commit or roll back before the {@code UPDATE}
 * branch executes, so {@code RETURNING} always observes the committed winner. This is safer than
 * {@code DO NOTHING + UNION ALL SELECT} which takes the same snapshot for both arms and may not
 * see a concurrent committer's row.
 *
 * <p>SQL follows the direct {@code tx.preparedQuery(SQL).execute(Tuple)} style used by the
 * reference {@code PgInboxOutboxRepository} implementation.
 */
@Singleton
public final class PgWorkflowDedupRepository extends PgSqlRepository implements WorkflowDedupRepository<SqlClient> {

    // --- Dedup kind constants ---

    /** Kind value for workflow start dedup rows: {@code (kind='start', scope=definitionId, key=idempotencyKey)}. */
    private static final String DEDUP_KIND_START = "start";

    /** Kind value for signal dedup rows: {@code (kind='signal', scope=instanceId::text, key=signalDedupKey)}. */
    private static final String DEDUP_KIND_SIGNAL = "signal";

    /** Kind value for task-completion dedup rows: {@code (kind='task-complete', scope=taskId::text, key=idempotencyKey)}. */
    private static final String DEDUP_KIND_TASK_COMPLETE = "task-complete";

    /** Kind value for task-reassignment dedup rows: {@code (kind='task-reassign', scope=taskId::text, key=idempotencyKey)}. */
    private static final String DEDUP_KIND_TASK_REASSIGN = "task-reassign";

    // --- SQL constants ---

    /**
     * Race-safe upsert for start dedup. Includes the {@code fingerprint} column so the engine can
     * detect version-pinning conflicts (FR-WF-DEF-070): when the caller supplies an explicit
     * {@code requestedDefinitionVersion}, a conflict row whose fingerprint differs from the incoming
     * one is rejected with {@link dev.vertique.workflow.exception.WorkflowIdempotencyConflictException}.
     *
     * <p>The {@code DO UPDATE SET workflow_id = workflow_dedup.workflow_id} is a no-op write whose
     * sole purpose is to make {@code RETURNING} fire on conflict. {@code (xmax = 0)} is {@code true}
     * iff this transaction inserted (won the race); {@code false} iff a prior committed transaction
     * already held the row.
     *
     * <p>Parameters: $1=scope (definitionId), $2=key (idempotencyKey), $3=workflow_id, $4=fingerprint.
     */
    private static final String SQL_CLAIM_OR_RESOLVE_START =
            "INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)"
                    + " VALUES ('" + DEDUP_KIND_START + "', $1, $2, $3, $4)"
                    + " ON CONFLICT (kind, scope, key) DO UPDATE"
                    + " SET workflow_id = workflow_dedup.workflow_id"
                    + " RETURNING workflow_id, (xmax = 0) AS inserted, fingerprint AS existing_fingerprint";

    private static final String SQL_FIND_SIGNAL = "SELECT workflow_id FROM workflow_dedup" + " WHERE kind = '"
            + DEDUP_KIND_SIGNAL + "' AND scope = $1 AND key = $2";

    private static final String SQL_INSERT_SIGNAL = "INSERT INTO workflow_dedup (kind, scope, key, workflow_id)"
            + " VALUES ('" + DEDUP_KIND_SIGNAL + "', $1, $2, $3)";

    /**
     * Race-safe upsert for signal dedup. Same shape as {@link #SQL_CLAIM_OR_RESOLVE_START}: the
     * {@code DO UPDATE} no-op write makes {@code RETURNING} fire on conflict so the caller always
     * gets back {@code (xmax = 0)} which is {@code true} iff this transaction won the race.
     */
    private static final String SQL_CLAIM_OR_RESOLVE_SIGNAL =
            "INSERT INTO workflow_dedup (kind, scope, key, workflow_id)"
                    + " VALUES ('" + DEDUP_KIND_SIGNAL + "', $1, $2, $3)"
                    + " ON CONFLICT (kind, scope, key) DO UPDATE"
                    + " SET workflow_id = workflow_dedup.workflow_id"
                    + " RETURNING (xmax = 0) AS inserted";

    /**
     * Race-safe upsert for task-completion dedup. Includes the {@code fingerprint} column so the
     * caller can detect idempotent retries (same key + same fingerprint) versus conflicts (same key
     * + different fingerprint).
     *
     * <p>Parameters: $1=scope (taskId::text), $2=key (idempotencyKey), $3=workflow_id, $4=fingerprint.
     */
    private static final String SQL_CLAIM_OR_RESOLVE_TASK_COMPLETE =
            "INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)"
                    + " VALUES ('" + DEDUP_KIND_TASK_COMPLETE + "', $1, $2, $3, $4)"
                    + " ON CONFLICT (kind, scope, key) DO UPDATE"
                    + " SET workflow_id = workflow_dedup.workflow_id"
                    + " RETURNING (xmax = 0) AS inserted, fingerprint AS existing_fingerprint";

    /**
     * Race-safe upsert for task-reassignment dedup. Mirror of
     * {@link #SQL_CLAIM_OR_RESOLVE_TASK_COMPLETE} with a different {@code kind} literal.
     *
     * <p>Parameters: $1=scope (taskId::text), $2=key (idempotencyKey), $3=workflow_id, $4=fingerprint.
     */
    private static final String SQL_CLAIM_OR_RESOLVE_TASK_REASSIGN =
            "INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)"
                    + " VALUES ('" + DEDUP_KIND_TASK_REASSIGN + "', $1, $2, $3, $4)"
                    + " ON CONFLICT (kind, scope, key) DO UPDATE"
                    + " SET workflow_id = workflow_dedup.workflow_id"
                    + " RETURNING (xmax = 0) AS inserted, fingerprint AS existing_fingerprint";

    // --- Constructor ---

    /**
     * Creates a new repository with the given connection pool and exception mapper.
     *
     * @param pool   the PostgreSQL connection pool
     * @param mapper the exception mapper for translating SQL errors to domain exceptions
     */
    @Inject
    public PgWorkflowDedupRepository(Pool pool, PgDbExceptionMapper mapper) {
        super(pool, mapper);
    }

    // --- Start dedup ---

    /**
     * Atomically claims a start dedup slot or resolves an existing one, recording the definition
     * version fingerprint for idempotency-conflict detection (FR-WF-DEF-070).
     *
     * <p>Executes:
     *
     * <pre>{@code
     * INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)
     * VALUES ('start', $definitionId, $idempotencyKey, $proposedNewId, $fingerprint)
     * ON CONFLICT (kind, scope, key) DO UPDATE
     *   SET workflow_id = workflow_dedup.workflow_id
     * RETURNING workflow_id, (xmax = 0) AS inserted, fingerprint AS existing_fingerprint;
     * }</pre>
     *
     * <p>The {@code DO UPDATE} is a no-op write (sets the column to its current value); its purpose
     * is to make {@code RETURNING} fire on conflict so the caller always gets back the winning row
     * in a single statement. {@code (xmax = 0)} is {@code true} iff this caller inserted the row
     * (and therefore won the race). PostgreSQL's {@code ON CONFLICT} semantics wait for a
     * concurrent conflicting transaction to commit or roll back before the UPDATE branch executes,
     * eliminating snapshot-visibility races under {@code READ COMMITTED}.
     *
     * <p>The {@code fingerprint} carries {@code "definitionVersion=N"} where {@code N} is the
     * resolved version at first claim. On a conflict row, {@code existingFingerprint} lets the
     * engine distinguish an idempotent retry (fingerprints equal) from a version conflict
     * (fingerprints differ). Existing rows with a {@code NULL} fingerprint (written before
     * version-pinning was introduced) are treated as "no version recorded" and never trigger the
     * conflict path.
     *
     * @param definitionId   the workflow definition id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param proposedNewId  the workflow instance id this caller proposes to use if it wins
     * @param fingerprint    the version fingerprint to record, e.g. {@code "definitionVersion=1"}
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the result; {@code didInsert=true} when this caller won
     */
    @Override
    public Future<StartDedupResult> claimOrResolveStart(
            String definitionId,
            String idempotencyKey,
            WorkflowInstanceId proposedNewId,
            String fingerprint,
            SqlClient tx) {
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_START)
                .execute(Tuple.of(definitionId, idempotencyKey, proposedNewId.value(), fingerprint))
                .map(rs -> {
                    var row = rs.iterator().next();
                    UUID winningId = row.getUUID("workflow_id");
                    boolean inserted = Boolean.TRUE.equals(row.getBoolean("inserted"));
                    String existingFp = row.getString("existing_fingerprint");
                    return new StartDedupResult(new WorkflowInstanceId(winningId), inserted, existingFp);
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup claimOrResolveStart")));
    }

    // --- Signal dedup ---

    /**
     * Looks up an existing signal dedup record for the given workflow instance and dedup key.
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the persisted {@link UUID} if the signal was already
     *         applied, or {@link Optional#empty()} if not
     */
    @Override
    public Future<Optional<UUID>> findSignal(WorkflowInstanceId workflowId, String signalDedupKey, SqlClient tx) {
        String scope = workflowId.value().toString();
        return tx.preparedQuery(SQL_FIND_SIGNAL)
                .execute(Tuple.of(scope, signalDedupKey))
                .map(rs -> {
                    var it = rs.iterator();
                    if (it.hasNext()) {
                        return Optional.of(it.next().getUUID("workflow_id"));
                    }
                    return Optional.<UUID>empty();
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup findSignal")));
    }

    /**
     * Records that a signal has been applied to the given workflow instance.
     *
     * <p>Inserts a row with {@code kind='signal'}, {@code scope=workflowId.value().toString()},
     * {@code key=signalDedupKey}, {@code workflow_id=workflowId.value()}. Fails with a
     * unique-constraint violation if the same {@code (workflowId, signalDedupKey)} was already
     * inserted (which is always a programming error — callers must check {@link #findSignal} first).
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} that completes when the row is inserted
     */
    @Override
    public Future<Void> insertSignal(WorkflowInstanceId workflowId, String signalDedupKey, SqlClient tx) {
        String scope = workflowId.value().toString();
        return tx.preparedQuery(SQL_INSERT_SIGNAL)
                .execute(Tuple.of(scope, signalDedupKey, workflowId.value()))
                .<Void>mapEmpty()
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup insertSignal")));
    }

    // --- Task dedup ---

    /**
     * Atomically claims or resolves a task-completion dedup slot, including fingerprint comparison.
     *
     * <p>Executes:
     *
     * <pre>{@code
     * INSERT INTO workflow_dedup (kind, scope, key, workflow_id, fingerprint)
     * VALUES ('task-complete', $taskId, $idempotencyKey, $workflowId, $fingerprint)
     * ON CONFLICT (kind, scope, key) DO UPDATE
     *   SET workflow_id = workflow_dedup.workflow_id
     * RETURNING (xmax = 0) AS inserted, fingerprint AS existing_fingerprint;
     * }</pre>
     *
     * <p>The {@code DO UPDATE} is a no-op write whose purpose is to make {@code RETURNING} fire on
     * conflict. On insert ({@code inserted=true}), {@code existingFingerprint} equals the
     * just-written fingerprint. On conflict ({@code inserted=false}), it equals the fingerprint
     * committed by the winning transaction.
     *
     * @param taskId         the task id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param workflowId     the workflow instance id; stored as the FK reference
     * @param fingerprint    the SHA-256 hex fingerprint of the completion command
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the dedup claim result
     */
    @Override
    public Future<DedupClaim> claimOrResolveTaskCompletion(
            UUID taskId, String idempotencyKey, WorkflowInstanceId workflowId, String fingerprint, SqlClient tx) {
        String scope = taskId.toString();
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_TASK_COMPLETE)
                .execute(Tuple.of(scope, idempotencyKey, workflowId.value(), fingerprint))
                .map(rs -> {
                    var row = rs.iterator().next();
                    boolean inserted = Boolean.TRUE.equals(row.getBoolean("inserted"));
                    String existingFp = row.getString("existing_fingerprint");
                    return new DedupClaim(inserted, existingFp);
                })
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_dedup claimOrResolveTaskCompletion")));
    }

    /**
     * Atomically claims or resolves a task-reassignment dedup slot, including fingerprint
     * comparison.
     *
     * <p>Identical to {@link #claimOrResolveTaskCompletion} but uses {@code kind='task-reassign'}
     * so that completion and reassignment dedup rows for the same task id never collide.
     *
     * @param taskId         the task id; used as the dedup scope
     * @param idempotencyKey the caller-supplied idempotency key
     * @param workflowId     the workflow instance id; stored as the FK reference
     * @param fingerprint    the SHA-256 hex fingerprint of the reassignment command
     * @param tx             the active transaction to use
     * @return a {@link Future} containing the dedup claim result
     */
    @Override
    public Future<DedupClaim> claimOrResolveTaskReassignment(
            UUID taskId, String idempotencyKey, WorkflowInstanceId workflowId, String fingerprint, SqlClient tx) {
        String scope = taskId.toString();
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_TASK_REASSIGN)
                .execute(Tuple.of(scope, idempotencyKey, workflowId.value(), fingerprint))
                .map(rs -> {
                    var row = rs.iterator().next();
                    boolean inserted = Boolean.TRUE.equals(row.getBoolean("inserted"));
                    String existingFp = row.getString("existing_fingerprint");
                    return new DedupClaim(inserted, existingFp);
                })
                .recover(t -> Future.failedFuture(
                        exceptionMapper.translate(t, "workflow_dedup claimOrResolveTaskReassignment")));
    }

    /**
     * Atomically claims a signal dedup slot or resolves an existing one. Race-safe replacement for
     * the {@link #findSignal} + {@link #insertSignal} two-step.
     *
     * <p>Returns {@code true} iff this caller inserted the row (won the race) and should proceed
     * with applying the signal; {@code false} iff a prior committed transaction already held the
     * row and the caller should treat the signal as an idempotent no-op.
     *
     * <p>Eliminates the snapshot-visibility race that the prior two-step pattern allowed: under
     * READ COMMITTED two concurrent same-key callers could both observe an empty {@code findSignal}
     * result, both proceed to apply the signal, and the second would then collide on the unique
     * constraint or on optimistic-version on {@code workflow_instances}. Postgres
     * {@code ON CONFLICT} semantics wait for a conflicting transaction to commit/roll back before
     * the UPDATE branch executes, so exactly one caller observes {@code inserted=true}.
     *
     * @param workflowId     the workflow instance id (used as the dedup scope)
     * @param signalDedupKey the caller-supplied signal dedup key
     * @param tx             the active transaction to use
     * @return a {@link Future} of {@code true} when this caller won the race, {@code false}
     *     when the dedup row already existed
     */
    @Override
    public Future<Boolean> claimOrResolveSignal(WorkflowInstanceId workflowId, String signalDedupKey, SqlClient tx) {
        String scope = workflowId.value().toString();
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_SIGNAL)
                .execute(Tuple.of(scope, signalDedupKey, workflowId.value()))
                .map(rs -> Boolean.TRUE.equals(rs.iterator().next().getBoolean("inserted")))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup claimOrResolveSignal")));
    }

    /**
     * Variant of {@link #claimOrResolveSignal} that accepts a caller-supplied {@code (scope, key)}
     * pair directly. Used by the branch-aware signal path (PRD-WF-002 §D11/§FR-WF-PAR-026): the
     * branch path uses {@link dev.vertique.workflow.dedup.WorkflowDedupScopes#signalKey} which
     * folds the branch identity into the dedup key so two sibling branches sharing the same
     * caller-supplied {@code dedupKey} cannot collide.
     *
     * @param scope dedup scope (typically the workflow id string)
     * @param key dedup key (typically a SHA-256 hex digest combining branch identity + dedup key)
     * @param workflowId workflow instance id (for the FK column)
     * @param tx the active transaction
     * @return a {@link Future} of {@code true} when this caller won the race, {@code false} when
     *     the dedup row already existed
     */
    @Override
    public Future<Boolean> claimOrResolveSignalScoped(
            String scope, String key, WorkflowInstanceId workflowId, SqlClient tx) {
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_SIGNAL)
                .execute(Tuple.of(scope, key, workflowId.value()))
                .map(rs -> Boolean.TRUE.equals(rs.iterator().next().getBoolean("inserted")))
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup claimOrResolveSignalScoped")));
    }

    // --- Dispatch dedup (PRD-WF-002) ---

    /**
     * Race-safe upsert for branch service-dispatch idempotency. Mirrors the signal-dedup pattern:
     * inserts {@code (kind='dispatch', scope=workflowId, key=<SHA-256 hex>, workflow_id)}; the
     * {@code DO UPDATE} no-op makes {@code RETURNING} fire on conflict so the caller always learns
     * whether it inserted (won the race) or found a prior committed row.
     *
     * <p>Parameters: $1=scope (workflowId::text), $2=key (SHA-256 hex), $3=workflow_id.
     */
    private static final String SQL_CLAIM_OR_RESOLVE_DISPATCH =
            "INSERT INTO workflow_dedup (kind, scope, key, workflow_id)"
                    + " VALUES ('" + WorkflowDedupScopes.DISPATCH_KIND + "', $1, $2, $3)"
                    + " ON CONFLICT (kind, scope, key) DO UPDATE"
                    + " SET workflow_id = workflow_dedup.workflow_id"
                    + " RETURNING (xmax = 0) AS inserted";

    /**
     * Atomically claims a branch service-dispatch dedup slot or resolves an existing one
     * (PRD-WF-002 §D6). Returns {@code true} iff this caller inserted the row and should proceed
     * with routing the side-effect intent; {@code false} iff a prior committed transaction
     * already held the row and the caller should skip routing (the dispatch was already
     * recorded — FR-WF-PAR-038).
     *
     * <p>Use {@link WorkflowDedupScopes#dispatchScope(WorkflowInstanceId)} and
     * {@link WorkflowDedupScopes#dispatchKey(String, String, String, String, String)} to build the
     * canonical scope/key strings before calling.
     *
     * @param workflowId the parent workflow instance id
     * @param scope the canonical dispatch scope ({@link WorkflowDedupScopes#dispatchScope})
     * @param key the canonical dispatch key ({@link WorkflowDedupScopes#dispatchKey})
     * @param tx the active transaction
     * @return a {@link Future} of {@code true} when this caller won the race
     */
    @Override
    public Future<Boolean> claimOrResolveDispatch(
            WorkflowInstanceId workflowId, String scope, String key, SqlClient tx) {
        return tx.preparedQuery(SQL_CLAIM_OR_RESOLVE_DISPATCH)
                .execute(Tuple.of(scope, key, workflowId.value()))
                .map(rs -> Boolean.TRUE.equals(rs.iterator().next().getBoolean("inserted")))
                .recover(t ->
                        Future.failedFuture(exceptionMapper.translate(t, "workflow_dedup claimOrResolveDispatch")));
    }
}
