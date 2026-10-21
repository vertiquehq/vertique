// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.workflow.engine.WorkflowExceptionMapper;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * PostgreSQL registry with transaction-owned id locking and fenced publication claims.
 *
 * <p>Every reference to the {@code workflow_definitions}, {@code workflow_definition_activations} and {@code workflow_instances}
 * tables is qualified with the schema mapped to the {@code workflow} instance when the {@link DbSchemas} given at construction holds one; otherwise
 * the tables resolve through the connection's default schema. The statements are built once at
 * construction.
 */
@Singleton
public final class PgWorkflowDefinitionRegistryStore extends PgSqlRepository
        implements WorkflowDefinitionRegistryStore {
    private static final String COLUMNS =
            "scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, name_binding, status, document_format, "
                    + "source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at, created_at, status_changed_at";
    private static final String LOCKED_COLUMNS = COLUMNS + ", document_digest";
    private static final String KEY = "scope_key = $1 AND definition_id = $2 AND definition_version = $3";

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    private final String sqlFind;
    private final String sqlFindDocument;
    private final String sqlList;
    private final String sqlInsert;
    private final String sqlInsertCode;
    private final String sqlUpdateCodeProvenance;
    private final String sqlRehashCode;
    private final String sqlResetStale;
    private final String sqlUpdateProvenance;
    private final String sqlClaim;
    private final String sqlComplete;
    private final String sqlRelease;
    private final String sqlTransition;
    private final String sqlRetire;
    private final String sqlCooling;
    private final String sqlCountNonTerminal;
    private final String sqlLockRows;
    private final String sqlPutActivation;
    private final String sqlRemoveActivation;
    private final String sqlListActivations;
    private final String sqlDefaultCurrent;
    private final String sqlActivateDefault;
    private final WorkflowDefinitionsConfig config;
    private final WorkflowExceptionMapper workflowMapper = new WorkflowExceptionMapper();
    private volatile boolean utf8Confirmed;

    /**
     * Constructs a store using the component's optional shared document policy. The tables are not
     * schema-qualified, so they resolve through the connection's default schema.
     *
     * @param pool the PostgreSQL connection pool
     * @param mapper the PostgreSQL failure mapper
     * @param config the optional shared document policy; defaults apply when absent
     */
    @Inject
    public PgWorkflowDefinitionRegistryStore(
            Pool pool, PgDbExceptionMapper mapper, Optional<WorkflowDefinitionsConfig> config) {
        this(pool, mapper, config, DbSchemas.empty());
    }

    /**
     * Constructs a store that qualifies the definitions and instances tables with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements are
     * identical to those of the three-argument constructor.
     *
     * @param pool the PostgreSQL connection pool
     * @param mapper the PostgreSQL failure mapper
     * @param config the optional shared document policy; defaults apply when absent
     * @param schemas the schema placement; an empty mapping leaves the table names unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *     PostgreSQL identifier
     */
    public PgWorkflowDefinitionRegistryStore(
            Pool pool, PgDbExceptionMapper mapper, Optional<WorkflowDefinitionsConfig> config, DbSchemas schemas) {
        super(pool, mapper);
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);

        String definitions = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_definitions");
        String instances = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_instances");
        String activations = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_definition_activations");

        this.sqlFind = "SELECT " + COLUMNS + " FROM " + definitions + " WHERE " + KEY;

        this.sqlFindDocument = """
                    SELECT scope_key, definition_id, definition_version, document_format, document_digest,
                        octet_length(convert_to(document, 'UTF8')) AS document_size_bytes,
                        char_length(document) AS document_code_points,
                        CASE WHEN octet_length(convert_to(document, 'UTF8')) <= $4 AND char_length(document) <= $5
                             THEN document ELSE NULL END AS document
                    FROM""" + " " + definitions + " WHERE " + KEY + " AND kind = 'DOCUMENT'";

        this.sqlList =
                "SELECT " + COLUMNS + " FROM " + definitions + " ORDER BY definition_id, scope_key, definition_version";

        this.sqlInsert = "INSERT INTO " + definitions
                + " (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, document, document_format, document_digest, source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at, name_binding) VALUES ($1,$2,$3,'DOCUMENT',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17::jsonb)";

        this.sqlInsertCode = "INSERT INTO " + definitions
                + " (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at, name_binding) VALUES ($1,$2,$3,'CODE',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::jsonb)";

        this.sqlUpdateCodeProvenance = "UPDATE " + definitions
                + " SET source_type=$4, source_uri=$5, source_revision=$6, source_author=$7, source_upload_id=$8, loaded_at=$9, contract_name=$10, state_type_name=$11 WHERE "
                + KEY + " AND kind='CODE'";

        // The only statement that replaces a stored plan hash; it replaces the name binding with it.
        this.sqlRehashCode = "UPDATE " + definitions
                + " SET plan_hash=$4, name_binding=$5::jsonb, source_type=$6, source_uri=$7, source_revision=$8, source_author=$9, source_upload_id=$10, loaded_at=$11, contract_name=$12, state_type_name=$13 WHERE "
                + KEY + " AND kind='CODE'";

        this.sqlResetStale = "UPDATE " + definitions
                + " SET status='CANDIDATE', publish_token=NULL, status_changed_at=clock_timestamp() WHERE definition_id=$1 AND status='PUBLISHING' AND extract(epoch from (clock_timestamp() - status_changed_at)) >= ($2::numeric * 2)";

        this.sqlUpdateProvenance = "UPDATE " + definitions
                + " SET source_type=$4, source_uri=$5, source_revision=$6, source_author=$7, source_upload_id=$8, loaded_at=$9 WHERE "
                + KEY;

        this.sqlClaim = "UPDATE " + definitions
                + " SET status='PUBLISHING', publish_token=$4, status_changed_at=clock_timestamp() WHERE " + KEY;

        this.sqlComplete = "UPDATE " + definitions
                + " SET status='AVAILABLE', publish_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='PUBLISHING' AND publish_token=$4";

        this.sqlRelease = "UPDATE " + definitions
                + " SET status='CANDIDATE', publish_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='PUBLISHING' AND publish_token=$4";

        this.sqlTransition = "UPDATE " + definitions + " SET status=$5, status_changed_at=clock_timestamp() WHERE "
                + KEY + " AND status=$4";

        this.sqlRetire = "UPDATE " + definitions + " SET status='RETIRED', status_changed_at=clock_timestamp() WHERE "
                + KEY + " AND status=$4";

        this.sqlCooling =
                "SELECT (clock_timestamp() - status_changed_at) < ($4::bigint * interval '1 second') AS cooling FROM "
                        + definitions + " WHERE " + KEY;

        this.sqlCountNonTerminal = "SELECT count(*) AS total FROM " + instances
                + " WHERE definition_id = $1 AND definition_version = $2 AND definition_scope_key = $3 AND status IN ('RUNNING','WAITING','COMPENSATING')";

        this.sqlLockRows = "SELECT " + LOCKED_COLUMNS
                + ", publish_token, extract(epoch from (clock_timestamp() - status_changed_at)) >= ($2::numeric * 2) AS stale FROM "
                + definitions
                + " WHERE definition_id=$1 ORDER BY scope_key, definition_version FOR UPDATE";

        this.sqlPutActivation = "INSERT INTO " + activations
                + " (scope_key, definition_id, target_scope_key, definition_version, activated_at) VALUES ($1,$2,$3,$4,$5)"
                + " ON CONFLICT (scope_key, definition_id) DO UPDATE SET target_scope_key=EXCLUDED.target_scope_key,"
                + " definition_version=EXCLUDED.definition_version, activated_at=EXCLUDED.activated_at";

        this.sqlRemoveActivation = "DELETE FROM " + activations + " WHERE scope_key=$1 AND definition_id=$2";

        this.sqlListActivations =
                "SELECT scope_key, definition_id, target_scope_key, definition_version, activated_at FROM "
                        + activations
                        + " WHERE ($1::varchar IS NULL OR scope_key=$1) ORDER BY definition_id, scope_key";

        // Writes the default scope's record of the id to the inserted version unless the record names a higher one.
        this.sqlActivateDefault = "INSERT INTO " + activations
                + " AS existing (scope_key, definition_id, target_scope_key, definition_version, activated_at) VALUES ('"
                + WorkflowScopes.DEFAULT + "',$1,'" + WorkflowScopes.DEFAULT + "',$2,clock_timestamp())"
                + " ON CONFLICT (scope_key, definition_id) DO UPDATE SET target_scope_key=EXCLUDED.target_scope_key,"
                + " definition_version=EXCLUDED.definition_version, activated_at=EXCLUDED.activated_at"
                + " WHERE existing.definition_version < EXCLUDED.definition_version";

        this.sqlDefaultCurrent = "SELECT 1 FROM " + activations + " WHERE scope_key='" + WorkflowScopes.DEFAULT
                + "' AND definition_id=$1 AND target_scope_key='" + WorkflowScopes.DEFAULT
                + "' AND definition_version=$2";
    }

    @Override
    public Future<Void> insertCandidate(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        return attempt(() -> {
            validateDocument(record, document);
            if (record.status() != DefinitionStatus.CANDIDATE) throw invalid(record.key(), "insert requires CANDIDATE");
            return mutate(record.key(), (tx, rows) -> encoding(tx).compose(ignored -> {
                if (row(rows, record.key()).isPresent()) throw invalid(record.key(), "definition already exists");
                return insert(tx, record, document, DefinitionStatus.CANDIDATE);
            }));
        });
    }

    @Override
    public Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return transaction(key.definitionId(), tx -> tx.preparedQuery(sqlFind)
                    .execute(parameters(key))
                    .map(rows -> rows.iterator().hasNext()
                            ? Optional.of(metadata(rows.iterator().next()))
                            : Optional.empty()));
        });
    }

    @Override
    public Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return transaction(key.definitionId(), tx -> encoding(tx).compose(ignored -> tx.preparedQuery(
                            sqlFindDocument)
                    .execute(parameters(key)
                            .addInteger(config.maxStoredDocumentSizeBytes())
                            .addInteger(config.maxDocumentSizeBytes()))
                    .map(rows -> {
                        if (!rows.iterator().hasNext()) return Optional.empty();
                        Row r = rows.iterator().next();
                        long bytes = ((Number) r.getValue("document_size_bytes")).longValue();
                        long points = ((Number) r.getValue("document_code_points")).longValue();
                        if (bytes > config.maxStoredDocumentSizeBytes() || points > config.maxDocumentSizeBytes())
                            throw new WorkflowDefinitionException(
                                    "Stored document " + key + " exceeds source envelope: UTF8 bytes " + bytes + "/"
                                            + config.maxStoredDocumentSizeBytes() + ", code points " + points + "/"
                                            + config.maxDocumentSizeBytes());
                        String text = r.getString("document");
                        if (!sha256Hex(text).equals(r.getString("document_digest")))
                            throw new WorkflowDefinitionLifecycleException(
                                    Reason.DRIFT,
                                    key,
                                    "Definition " + key
                                            + ": stored text digest differs from the recorded digest, so the stored text was changed after it was recorded");
                        return Optional.of(new WorkflowDefinitionDocument(key, text, r.getString("document_format")));
                    })));
        });
    }

    @Override
    public Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
        return attempt(() -> {
            Objects.requireNonNull(filter, "filter");
            return transaction(
                    "list", tx -> tx.query(sqlList).execute().map(rows -> java.util.stream.StreamSupport.stream(
                                    rows.spliterator(), false)
                            .map(PgWorkflowDefinitionRegistryStore::metadata)
                            .filter(r -> filter.scopeKey() == null
                                    || filter.scopeKey().equals(r.key().scopeKey()))
                            .filter(r -> filter.definitionId() == null
                                    || filter.definitionId().equals(r.key().definitionId()))
                            .filter(r -> filter.statuses() == null
                                    || filter.statuses().contains(r.status()))
                            .toList()));
        });
    }

    @Override
    public Future<ReconcileOutcome> upsertDocument(
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
        return attempt(() -> {
            validateDocument(record, document);
            return mutate(record.key(), (tx, rows) -> encoding(tx).compose(ignored -> {
                Optional<Row> current = row(rows, record.key());
                if (current.isEmpty())
                    return insert(tx, record, document, DefinitionStatus.AVAILABLE)
                            .compose(inserted -> activateFirstPublication(tx, record.key(), activateDefault))
                            .map(ReconcileOutcome.INSERTED);
                Row old = current.orElseThrow();
                if (!"DOCUMENT".equals(old.getString("kind")))
                    return Future.succeededFuture(ReconcileOutcome.KIND_CONFLICT);
                if (!record.planHash().equals(old.getString("plan_hash")))
                    return Future.succeededFuture(ReconcileOutcome.DRIFT);
                if (!sha256Hex(document.document()).equals(old.getString("document_digest")))
                    return Future.succeededFuture(ReconcileOutcome.DRIFT);
                var p = record.provenance();
                return tx.preparedQuery(sqlUpdateProvenance)
                        .execute(parameters(record.key())
                                .addString(p.sourceType())
                                .addString(p.uri())
                                .addString(p.revision())
                                .addString(p.author())
                                .addString(p.uploadId())
                                .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC)))
                        .map(ReconcileOutcome.UNCHANGED);
            }));
        });
    }

    @Override
    public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
        return attempt(() -> {
            validateCode(record);
            DefinitionKey key = record.key();
            // Read committed, so that the pinned count taken after the lock wait sees everything committed meanwhile.
            return mutate(key.definitionId(), (tx, rows) -> {
                Optional<Row> current = row(rows, key);
                if (current.isEmpty())
                    return insertCode(tx, record, DefinitionStatus.AVAILABLE)
                            .compose(inserted -> activateFirstPublication(tx, key, activateDefault))
                            .map(ReconcileOutcome.INSERTED);
                Row old = current.orElseThrow();
                if (!"CODE".equals(old.getString("kind")))
                    return Future.succeededFuture(ReconcileOutcome.KIND_CONFLICT);
                if (record.planHash().equals(old.getString("plan_hash")))
                    return updateCodeProvenance(tx, record).map(ReconcileOutcome.UNCHANGED);
                // The hash differs: only an exact version without a live pinned instance may take the new hash,
                // and it takes the name binding the new hash was computed with.
                return countNonTerminal(tx, key)
                        .compose(pinned -> pinned > 0
                                ? Future.succeededFuture(ReconcileOutcome.DRIFT)
                                : rehashCode(tx, record).map(ReconcileOutcome.REHASHED));
            });
        });
    }

    @Override
    public Future<Void> resetStalePublishing(String definitionId) {
        return attempt(() -> {
            Objects.requireNonNull(definitionId, "definitionId");
            // DefinitionKey enforces the id constraints; the scope and version are irrelevant here.
            new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
            return mutate(definitionId, (tx, rows) -> tx.preparedQuery(sqlResetStale)
                    .execute(Tuple.of(definitionId, config.maxStalenessSeconds()))
                    .mapEmpty());
        });
    }

    @Override
    public Future<ActivationClaim> claim(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return mutate(key, (tx, rows) -> {
                Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
                String status = current.getString("status");
                if (!"CANDIDATE".equals(status) && !("PUBLISHING".equals(status) && current.getBoolean("stale")))
                    throw invalid(key, "cannot claim status " + status);
                ActivationClaim claim = new ActivationClaim(key, UUID.randomUUID());
                return tx.preparedQuery(sqlClaim)
                        .execute(parameters(key).addUUID(claim.token()))
                        .map(claim);
            });
        });
    }

    @Override
    public Future<Void> complete(ActivationClaim claim) {
        return attempt(() -> {
            Objects.requireNonNull(claim, "claim");
            DefinitionKey key = claim.key();
            return mutate(key, (tx, rows) -> {
                Row current =
                        row(rows, key).orElseThrow(() -> conflict(Reason.STALE_CLAIM, key, "claim no longer exists"));
                if (!"PUBLISHING".equals(current.getString("status"))
                        || !claim.token().equals(current.getUUID("publish_token")))
                    throw conflict(Reason.STALE_CLAIM, key, "publish token is obsolete");
                return tx.preparedQuery(sqlComplete)
                        .execute(parameters(key).addUUID(claim.token()))
                        .mapEmpty();
            });
        });
    }

    @Override
    public Future<Void> release(ActivationClaim claim) {
        return attempt(() -> {
            Objects.requireNonNull(claim, "claim");
            return mutate(claim.key(), (tx, rows) -> tx.preparedQuery(sqlRelease)
                    .execute(parameters(claim.key()).addUUID(claim.token()))
                    .mapEmpty());
        });
    }

    @Override
    public Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            boolean supported = (from == DefinitionStatus.AVAILABLE && to == DefinitionStatus.DEPRECATED)
                    || (to == DefinitionStatus.AVAILABLE
                            && (from == DefinitionStatus.DEPRECATED || from == DefinitionStatus.RETIRED));
            if (!supported) throw invalid(key, "transition " + from + " to " + to + " is not supported");
            return mutate(key, (tx, rows) -> {
                Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
                String status = current.getString("status");
                if (!from.name().equals(status)) throw invalid(key, "expected status " + from + " but found " + status);
                Future<Void> guard =
                        to == DefinitionStatus.DEPRECATED ? guardDefaultCurrent(tx, key) : Future.succeededFuture();
                return guard.compose(ignored -> tx.preparedQuery(sqlTransition)
                        .execute(parameters(key).addString(from.name()).addString(to.name()))
                        .map(updated -> {
                            if (updated.rowCount() != 1)
                                throw invalid(key, "expected status " + from + " but the row changed");
                            return (Void) null;
                        }));
            });
        });
    }

    @Override
    public Future<Void> retire(DefinitionKey key) {
        return attempt(() -> {
            Objects.requireNonNull(key, "key");
            return mutate(key.definitionId(), (tx, rows) -> {
                Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
                String status = current.getString("status");
                if (!"CANDIDATE".equals(status) && !"DEPRECATED".equals(status))
                    throw invalid(key, "cannot retire status " + status);
                // The default-current guard comes first. Only a deprecated version can be pinned by instances, so a
                // candidate skips cooling and the count.
                return guardDefaultCurrent(tx, key)
                        .compose(ignored ->
                                "DEPRECATED".equals(status) ? guardDeprecated(tx, key) : Future.<Void>succeededFuture())
                        .compose(ignored -> tx.preparedQuery(sqlRetire)
                                .execute(parameters(key).addString(status))
                                .map(updated -> {
                                    if (updated.rowCount() != 1)
                                        throw invalid(key, "expected status " + status + " but the row changed");
                                    return (Void) null;
                                }));
            });
        });
    }

    @Override
    public Future<Void> putActivation(DefinitionActivation activation) {
        return attempt(() -> {
            Objects.requireNonNull(activation, "activation");
            DefinitionKey target =
                    new DefinitionKey(activation.targetScopeKey(), activation.definitionId(), activation.version());
            return mutate(target, (tx, rows) -> {
                Row current = row(rows, target).orElseThrow(() -> invalid(target, "definition not found"));
                String status = current.getString("status");
                if (!DefinitionStatus.AVAILABLE.name().equals(status))
                    throw invalid(target, "cannot activate status " + status + ", only AVAILABLE can be activated");
                return tx.preparedQuery(sqlPutActivation)
                        .execute(Tuple.of(
                                activation.scopeKey(),
                                activation.definitionId(),
                                activation.targetScopeKey(),
                                activation.version(),
                                activation.activatedAt().atOffset(java.time.ZoneOffset.UTC)))
                        .mapEmpty();
            });
        });
    }

    @Override
    public Future<Void> removeActivation(String scopeKey, String definitionId) {
        return attempt(() -> {
            // DefinitionKey enforces the scope and id constraints; the version is irrelevant here.
            new DefinitionKey(scopeKey, definitionId, 1);
            return mutate(definitionId, (tx, rows) -> tx.preparedQuery(sqlRemoveActivation)
                    .execute(Tuple.of(scopeKey, definitionId))
                    .mapEmpty());
        });
    }

    @Override
    public Future<List<DefinitionActivation>> listActivations(@Nullable String scopeKey) {
        return attempt(() -> {
            if (scopeKey != null) new DefinitionKey(scopeKey, WorkflowScopes.DEFAULT, 1);
            return transaction("activations", tx -> tx.preparedQuery(sqlListActivations)
                    .execute(Tuple.tuple().addValue(scopeKey))
                    .map(rows -> java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                            .map(PgWorkflowDefinitionRegistryStore::activation)
                            .toList()));
        });
    }

    /**
     * Makes a version that was just inserted the default scope's current version when it is higher than the version the
     * default scope's record already names, or when there is no record. It runs on the transaction of the insert, under
     * the lock of the id, so a failure rolls back the insert with it, and a record committed meanwhile is compared
     * after the lock was granted.
     */
    private Future<Void> activateFirstPublication(SqlConnection tx, DefinitionKey key, boolean activateDefault) {
        if (!activateDefault) return Future.succeededFuture();
        return tx.preparedQuery(sqlActivateDefault)
                .execute(Tuple.of(key.definitionId(), key.definitionVersion()))
                .mapEmpty();
    }

    /**
     * Refuses to take the default scope's current version out of service: while the default scope's activation record
     * of the id names this version, deprecating or retiring it fails as an invalid transition. The record is read on
     * the caller's locked transaction, after the lock was granted, so a record committed while the lock was awaited
     * is seen and one written later waits for this transaction. Rows of other scopes are not guarded.
     */
    private Future<Void> guardDefaultCurrent(SqlConnection tx, DefinitionKey key) {
        if (!WorkflowScopes.DEFAULT.equals(key.scopeKey())) return Future.succeededFuture();
        return tx.preparedQuery(sqlDefaultCurrent)
                .execute(Tuple.of(key.definitionId(), key.definitionVersion()))
                .map(rows -> {
                    if (rows.iterator().hasNext())
                        throw invalid(
                                key,
                                "is the current version of the default scope; activate another version or deactivate the default first");
                    return (Void) null;
                });
    }

    /**
     * Refuses a deprecated row that is still cooling, then one that is still pinned. Both reads run after the
     * locks were acquired, so they see everything committed while the lock was awaited.
     */
    private Future<Void> guardDeprecated(SqlConnection tx, DefinitionKey key) {
        return tx.preparedQuery(sqlCooling)
                .execute(parameters(key).addLong(config.maxStalenessSeconds()))
                .compose(result -> {
                    if (result.iterator().next().getBoolean("cooling"))
                        throw conflict(
                                Reason.COOLING,
                                key,
                                "deprecated less than " + config.maxStalenessSeconds()
                                        + " seconds ago, so it is still cooling");
                    return countNonTerminal(tx, key);
                })
                .map(pinned -> {
                    if (pinned > 0)
                        throw conflict(
                                Reason.PINNED_INSTANCES, key, "pinned by " + pinned + " non-terminal instance(s)");
                    return (Void) null;
                });
    }

    /**
     * Counts the instances pinned to exactly this definition scope, id and version that are still running, waiting or
     * compensating, archived ones included. It runs on the caller's locked transaction, after the lock was
     * granted, so it sees every pin committed while the lock was awaited.
     */
    private Future<Long> countNonTerminal(SqlConnection tx, DefinitionKey key) {
        return tx.preparedQuery(sqlCountNonTerminal)
                .execute(Tuple.of(key.definitionId(), key.definitionVersion(), key.scopeKey()))
                .map(result -> result.iterator().next().getLong("total"));
    }

    private Future<Void> encoding(SqlConnection tx) {
        if (utf8Confirmed) return Future.succeededFuture();
        return tx.query("SELECT current_setting('server_encoding') AS encoding")
                .execute()
                .map(rows -> {
                    if (!"UTF8".equalsIgnoreCase(rows.iterator().next().getString("encoding")))
                        throw new WorkflowDefinitionException(
                                "Durable DOCUMENT source requires PostgreSQL server_encoding UTF8");
                    utf8Confirmed = true;
                    return (Void) null;
                });
    }

    private void validateDocument(WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(document, "document");
        if (record.kind() != DefinitionKind.DOCUMENT
                || !record.key().equals(document.key())
                || !Objects.equals(record.documentFormat(), document.documentFormat()))
            throw new WorkflowDefinitionException(
                    "Document metadata does not match source carrier for " + record.key());
        long bytes = document.document().getBytes(StandardCharsets.UTF_8).length;
        long points = document.document().codePointCount(0, document.document().length());
        if (bytes > config.maxStoredDocumentSizeBytes() || points > config.maxDocumentSizeBytes())
            throw new WorkflowDefinitionException("Stored document " + record.key()
                    + " exceeds source envelope: UTF8 bytes " + bytes + "/" + config.maxStoredDocumentSizeBytes()
                    + ", code points " + points + "/" + config.maxDocumentSizeBytes());
    }

    private static void validateCode(WorkflowDefinitionRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.kind() != DefinitionKind.CODE
                || !WorkflowScopes.DEFAULT.equals(record.key().scopeKey())
                || record.documentFormat() != null)
            throw new WorkflowDefinitionException("Code metadata for " + record.key()
                    + " must be a default-scope CODE record without a document format");
    }

    private Future<Void> insertCode(SqlConnection tx, WorkflowDefinitionRecord record, DefinitionStatus status) {
        var p = record.provenance();
        return tx.preparedQuery(sqlInsertCode)
                .execute(parameters(record.key())
                        .addString(record.contractName())
                        .addString(record.stateTypeName())
                        .addString(record.planHash())
                        .addString(status.name())
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC))
                        .addValue(bindingValue(record)))
                .mapEmpty();
    }

    /** Refreshes the provenance and logical names of a code row; its plan hash and name binding are kept. */
    private Future<Void> updateCodeProvenance(SqlConnection tx, WorkflowDefinitionRecord record) {
        var p = record.provenance();
        return tx.preparedQuery(sqlUpdateCodeProvenance)
                .execute(parameters(record.key())
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC))
                        .addString(record.contractName())
                        .addString(record.stateTypeName()))
                .mapEmpty();
    }

    /** Replaces the plan hash of a code row together with its name binding, and refreshes the rest of the row. */
    private Future<Void> rehashCode(SqlConnection tx, WorkflowDefinitionRecord record) {
        var p = record.provenance();
        return tx.preparedQuery(sqlRehashCode)
                .execute(parameters(record.key())
                        .addString(record.planHash())
                        .addValue(bindingValue(record))
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC))
                        .addString(record.contractName())
                        .addString(record.stateTypeName()))
                .mapEmpty();
    }

    private static JsonObject bindingValue(WorkflowDefinitionRecord record) {
        return new JsonObject(new TreeMap<String, Object>(record.nameBinding()));
    }

    /** Reads a stored binding; a stored value that is not a string maps to nothing and is never echoed. */
    private static Map<String, String> storedBinding(Row r) {
        JsonObject stored = r.getJsonObject("name_binding");
        Map<String, String> binding = new TreeMap<>();
        if (stored != null)
            stored.forEach(entry -> {
                if (entry.getValue() instanceof String name) binding.put(entry.getKey(), name);
            });
        return binding;
    }

    private Future<Void> insert(
            SqlConnection tx,
            WorkflowDefinitionRecord record,
            WorkflowDefinitionDocument document,
            DefinitionStatus status) {
        var p = record.provenance();
        return tx.preparedQuery(sqlInsert)
                .execute(parameters(record.key())
                        .addString(record.contractName())
                        .addString(record.stateTypeName())
                        .addString(record.planHash())
                        .addString(status.name())
                        .addString(document.document())
                        .addString(document.documentFormat())
                        .addString(sha256Hex(document.document()))
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC))
                        .addValue(bindingValue(record)))
                .mapEmpty();
    }

    /** Returns the lowercase hexadecimal SHA-256 of the UTF-8 bytes of the text. */
    private static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private <T> Future<T> mutate(DefinitionKey key, LockedOperation<T> action) {
        return mutate(key.definitionId(), action);
    }

    /**
     * Runs the operation under the exclusive lock of the id. The transaction is first pinned to READ COMMITTED, so
     * the rows read after a lock wait are current whatever the server default isolation is; a snapshot taken before
     * the wait would miss a row that another transaction committed meanwhile. The isolation statement must be the first
     * statement of the transaction, before the lock bound and the advisory lock.
     */
    private <T> Future<T> mutate(String definitionId, LockedOperation<T> action) {
        return transaction(definitionId, tx -> tx.query("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
                .execute()
                .compose(ignored -> lockedRows(tx, definitionId))
                .compose(rows -> action.apply(tx, rows)));
    }

    private Future<RowSet<Row>> lockedRows(SqlConnection tx, String definitionId) {
        return DefinitionIdLock.acquire(tx, definitionId).compose(ignored -> tx.preparedQuery(sqlLockRows)
                .execute(Tuple.of(definitionId, config.maxStalenessSeconds())));
    }

    private <T> Future<T> transaction(String id, Function<SqlConnection, Future<T>> action) {
        return pool.withTransaction(action).recover(failure -> {
            Throwable mapped = failure instanceof VertxException
                    ? exceptionMapper.translate(failure, "definition registry " + id)
                    : failure;
            return Future.failedFuture(workflowMapper.translate(mapped, "definition registry " + id));
        });
    }

    private static <T> Future<T> attempt(Supplier<Future<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    private static Optional<Row> row(RowSet<Row> rows, DefinitionKey key) {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(r -> key.equals(key(r)))
                .findFirst();
    }

    private static DefinitionKey key(Row r) {
        return new DefinitionKey(
                r.getString("scope_key"), r.getString("definition_id"), r.getLong("definition_version"));
    }

    private static Tuple parameters(DefinitionKey key) {
        return Tuple.tuple()
                .addString(key.scopeKey())
                .addString(key.definitionId())
                .addLong(key.definitionVersion());
    }

    private static WorkflowDefinitionRecord metadata(Row r) {
        return new WorkflowDefinitionRecord(
                key(r),
                DefinitionKind.valueOf(r.getString("kind")),
                r.getString("contract_name"),
                r.getString("state_type_name"),
                r.getString("plan_hash"),
                DefinitionStatus.valueOf(r.getString("status")),
                new DefinitionProvenance(
                        r.getString("source_type"),
                        r.getString("source_uri"),
                        r.getString("source_revision"),
                        r.getString("source_author"),
                        r.getString("source_upload_id"),
                        r.getOffsetDateTime("loaded_at").toInstant()),
                r.getString("document_format"),
                r.getOffsetDateTime("created_at").toInstant(),
                r.getOffsetDateTime("status_changed_at").toInstant(),
                storedBinding(r));
    }

    private static DefinitionActivation activation(Row r) {
        return new DefinitionActivation(
                r.getString("scope_key"),
                r.getString("definition_id"),
                r.getString("target_scope_key"),
                r.getLong("definition_version"),
                r.getOffsetDateTime("activated_at").toInstant());
    }

    private static WorkflowDefinitionLifecycleException invalid(DefinitionKey key, String detail) {
        return conflict(Reason.INVALID_TRANSITION, key, detail);
    }

    private static WorkflowDefinitionLifecycleException conflict(Reason reason, DefinitionKey key, String detail) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + detail);
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        Future<T> apply(SqlConnection tx, RowSet<Row> rows);
    }
}
