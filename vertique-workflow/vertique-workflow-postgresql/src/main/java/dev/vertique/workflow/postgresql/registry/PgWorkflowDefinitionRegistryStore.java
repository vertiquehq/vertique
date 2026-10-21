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
import dev.vertique.workflow.registry.store.ActivationOutcome;
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
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * PostgreSQL registry with transaction-owned id locking and fenced activation claims.
 *
 * <p>Every reference to the {@code workflow_definitions} and {@code workflow_instances} tables is qualified with the schema mapped to
 * the {@code workflow} instance when the {@link DbSchemas} given at construction holds one; otherwise
 * the tables resolve through the connection's default schema. The statements are built once at
 * construction.
 */
@Singleton
public final class PgWorkflowDefinitionRegistryStore extends PgSqlRepository
        implements WorkflowDefinitionRegistryStore {
    private static final String COLUMNS =
            "scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, document_format, "
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
    private final String sqlResetStale;
    private final String sqlUpdateProvenance;
    private final String sqlClaim;
    private final String sqlDemoteOlder;
    private final String sqlComplete;
    private final String sqlRelease;
    private final String sqlTransition;
    private final String sqlReinstate;
    private final String sqlRetire;
    private final String sqlCooling;
    private final String sqlCountNonTerminal;
    private final String sqlLockRows;
    private final String sqlRecomputeDemote;
    private final String sqlRecomputePromote;
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
                + " (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, document, document_format, document_digest, source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at) VALUES ($1,$2,$3,'DOCUMENT',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)";

        this.sqlInsertCode = "INSERT INTO " + definitions
                + " (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash, status, source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at) VALUES ($1,$2,$3,'CODE',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)";

        this.sqlUpdateCodeProvenance = "UPDATE " + definitions
                + " SET plan_hash=CASE WHEN $9::boolean THEN $10 ELSE plan_hash END, source_type=$4, source_uri=$5, source_revision=$6, source_author=$7, source_upload_id=$8, loaded_at=$11, contract_name=$12, state_type_name=$13 WHERE "
                + KEY + " AND kind='CODE'";

        this.sqlResetStale = "UPDATE " + definitions
                + " SET status='CANDIDATE', activation_token=NULL, status_changed_at=clock_timestamp() WHERE definition_id=$1 AND status='ACTIVATING' AND extract(epoch from (clock_timestamp() - status_changed_at)) >= ($2::numeric * 2)";

        this.sqlUpdateProvenance = "UPDATE " + definitions
                + " SET source_type=$4, source_uri=$5, source_revision=$6, source_author=$7, source_upload_id=$8, loaded_at=$9 WHERE "
                + KEY;

        this.sqlClaim = "UPDATE " + definitions
                + " SET status='ACTIVATING', activation_token=$4, status_changed_at=clock_timestamp() WHERE " + KEY;

        this.sqlDemoteOlder = "UPDATE " + definitions
                + " SET status='SUPERSEDED', status_changed_at=clock_timestamp() WHERE scope_key=$1 AND definition_id=$2 AND definition_version<$3 AND status='ACTIVE'";

        this.sqlComplete = "UPDATE " + definitions
                + " SET status=$4, activation_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='ACTIVATING' AND activation_token=$5";

        this.sqlRelease = "UPDATE " + definitions
                + " SET status='CANDIDATE', activation_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='ACTIVATING' AND activation_token=$4";

        this.sqlTransition = "UPDATE " + definitions + " SET status=$5, status_changed_at=clock_timestamp() WHERE "
                + KEY + " AND status=$4";

        this.sqlReinstate =
                "UPDATE " + definitions + " SET status='SUPERSEDED', status_changed_at=clock_timestamp() WHERE " + KEY
                        + " AND status='RETIRED'";

        this.sqlRetire = "UPDATE " + definitions + " SET status='RETIRED', status_changed_at=clock_timestamp() WHERE "
                + KEY + " AND status=$4";

        this.sqlCooling =
                "SELECT (clock_timestamp() - status_changed_at) < ($4::bigint * interval '1 second') AS cooling FROM "
                        + definitions + " WHERE " + KEY;

        this.sqlCountNonTerminal = "SELECT count(*) AS total FROM " + instances
                + " WHERE definition_id = $1 AND definition_version = $2 AND definition_scope_key = $3 AND status IN ('RUNNING','WAITING','COMPENSATING')";

        this.sqlLockRows = "SELECT " + LOCKED_COLUMNS
                + ", activation_token, extract(epoch from (clock_timestamp() - status_changed_at)) >= ($2::numeric * 2) AS stale FROM "
                + definitions
                + " WHERE definition_id=$1 ORDER BY scope_key, definition_version FOR UPDATE";

        this.sqlRecomputeDemote = "UPDATE " + definitions
                + " SET status='SUPERSEDED', status_changed_at=clock_timestamp() WHERE scope_key=$1 AND definition_id=$2 AND definition_version<>$3 AND status='ACTIVE'";

        this.sqlRecomputePromote = "UPDATE " + definitions
                + " SET status='ACTIVE', status_changed_at=clock_timestamp() WHERE " + KEY + " AND status='SUPERSEDED'";
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
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        return attempt(() -> {
            validateDocument(record, document);
            return mutate(record.key(), (tx, rows) -> encoding(tx).compose(ignored -> {
                if (ownerConflict(rows, record.key())) return Future.succeededFuture(ReconcileOutcome.OWNER_CONFLICT);
                Optional<Row> current = row(rows, record.key());
                if (current.isEmpty())
                    return insert(tx, record, document, DefinitionStatus.SUPERSEDED)
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
    public Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record) {
        return attempt(() -> {
            validateCode(record);
            DefinitionKey key = record.key();
            // Read committed, so that the pinned count taken after the lock wait sees everything committed meanwhile.
            return mutate(key.definitionId(), (tx, rows) -> {
                if (ownerConflict(rows, key)) return Future.succeededFuture(ReconcileOutcome.OWNER_CONFLICT);
                Optional<Row> current = row(rows, key);
                if (current.isEmpty())
                    return insertCode(tx, record, DefinitionStatus.SUPERSEDED).map(ReconcileOutcome.INSERTED);
                Row old = current.orElseThrow();
                if (!"CODE".equals(old.getString("kind")))
                    return Future.succeededFuture(ReconcileOutcome.KIND_CONFLICT);
                if (record.planHash().equals(old.getString("plan_hash")))
                    return updateCodeProvenance(tx, record, false).map(ReconcileOutcome.UNCHANGED);
                // The hash differs: only an exact version without a live pinned instance may take the new hash.
                return countNonTerminal(tx, key)
                        .compose(pinned -> pinned > 0
                                ? Future.succeededFuture(ReconcileOutcome.DRIFT)
                                : updateCodeProvenance(tx, record, true).map(ReconcileOutcome.REHASHED));
            });
        });
    }

    @Override
    public Future<Void> resetStaleActivating(String definitionId) {
        return attempt(() -> {
            Objects.requireNonNull(definitionId, "definitionId");
            // DefinitionKey enforces the id constraints; the owner and version are irrelevant here.
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
                if (ownerConflict(rows, key))
                    throw conflict(
                            Reason.OWNER_CONFLICT,
                            key,
                            "another owner holds this definition id: " + heldOwners(rows, key));
                Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
                String status = current.getString("status");
                if (!"CANDIDATE".equals(status) && !("ACTIVATING".equals(status) && current.getBoolean("stale")))
                    throw invalid(key, "cannot claim status " + status);
                ActivationClaim claim = new ActivationClaim(key, UUID.randomUUID());
                return tx.preparedQuery(sqlClaim)
                        .execute(parameters(key).addUUID(claim.token()))
                        .map(claim);
            });
        });
    }

    @Override
    public Future<ActivationOutcome> complete(ActivationClaim claim) {
        return attempt(() -> {
            Objects.requireNonNull(claim, "claim");
            DefinitionKey key = claim.key();
            return mutate(key, (tx, rows) -> {
                Row current =
                        row(rows, key).orElseThrow(() -> conflict(Reason.STALE_CLAIM, key, "claim no longer exists"));
                if (!"ACTIVATING".equals(current.getString("status"))
                        || !claim.token().equals(current.getUUID("activation_token")))
                    throw conflict(Reason.STALE_CLAIM, key, "activation token is obsolete");
                if (ownerConflict(rows, key))
                    throw conflict(
                            Reason.OWNER_CONFLICT,
                            key,
                            "another owner holds this definition id: " + heldOwners(rows, key));
                boolean higher = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .anyMatch(r -> Objects.equals(key.scopeKey(), r.getString("scope_key"))
                                && ("ACTIVE".equals(r.getString("status"))
                                        || "SUPERSEDED".equals(r.getString("status")))
                                && r.getLong("definition_version") > key.definitionVersion());
                Future<Void> demote = higher
                        ? Future.succeededFuture()
                        : tx.preparedQuery(sqlDemoteOlder)
                                .execute(parameters(key))
                                .mapEmpty();
                ActivationOutcome outcome = higher ? ActivationOutcome.SUPERSEDED : ActivationOutcome.ACTIVE;
                return demote.compose(ignored -> tx.preparedQuery(sqlComplete)
                        .execute(parameters(key).addString(outcome.name()).addUUID(claim.token()))
                        .map(outcome));
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
            if (from == DefinitionStatus.RETIRED && to == DefinitionStatus.SUPERSEDED) return reinstate(key);
            if (to != DefinitionStatus.DEPRECATED
                    || (from != DefinitionStatus.ACTIVE && from != DefinitionStatus.SUPERSEDED))
                throw invalid(key, "transition " + from + " to " + to + " is not supported");
            return mutate(key, (tx, rows) -> {
                Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
                if (ownerConflict(rows, key))
                    throw conflict(
                            Reason.OWNER_CONFLICT,
                            key,
                            "another owner holds this definition id: " + heldOwners(rows, key));
                String status = current.getString("status");
                if (!from.name().equals(status)) throw invalid(key, "expected status " + from + " but found " + status);
                return tx.preparedQuery(sqlTransition)
                        .execute(parameters(key).addString(from.name()).addString(to.name()))
                        .map(updated -> {
                            if (updated.rowCount() != 1)
                                throw invalid(key, "expected status " + from + " but the row changed");
                            return (Void) null;
                        });
            });
        });
    }

    /**
     * Returns a retired version to service in one exclusive transaction: it checks the row and the owner, marks
     * the row superseded, then settles which version of this owner and id is active. Any failure rolls back the
     * whole change. The transaction reads committed rows, so that the owner check after the lock wait sees every
     * row another owner committed meanwhile, whatever the server default isolation is.
     */
    private Future<Void> reinstate(DefinitionKey key) {
        return mutate(key.definitionId(), (tx, rows) -> {
            Row current = row(rows, key).orElseThrow(() -> invalid(key, "definition not found"));
            String status = current.getString("status");
            if (!"RETIRED".equals(status)) throw invalid(key, "expected status RETIRED but found " + status);
            if (ownerConflict(rows, key))
                throw conflict(
                        Reason.OWNER_CONFLICT, key, "another owner holds this definition id: " + heldOwners(rows, key));
            return tx.preparedQuery(sqlReinstate).execute(parameters(key)).compose(updated -> {
                if (updated.rowCount() != 1) throw invalid(key, "expected status RETIRED but the row changed");
                List<Member> members = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .filter(r -> Objects.equals(key.scopeKey(), r.getString("scope_key")))
                        .map(r -> new Member(
                                r.getLong("definition_version"),
                                r.getLong("definition_version") == key.definitionVersion()
                                        ? "SUPERSEDED"
                                        : r.getString("status")))
                        .toList();
                return recomputeMembership(tx, key.scopeKey(), key.definitionId(), members);
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
                // Only a deprecated version can be pinned by instances, so a candidate skips cooling and the count.
                Future<Void> guard = "DEPRECATED".equals(status) ? guardDeprecated(tx, key) : Future.succeededFuture();
                return guard.compose(ignored -> tx.preparedQuery(sqlRetire)
                        .execute(parameters(key).addString(status))
                        .map(updated -> {
                            if (updated.rowCount() != 1)
                                throw invalid(key, "expected status " + status + " but the row changed");
                            return (Void) null;
                        }));
            });
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

    @Override
    public Future<Void> recomputeActive(String scopeKey, String definitionId) {
        return attempt(() -> {
            Objects.requireNonNull(definitionId, "definitionId");
            // DefinitionKey enforces the key constraints; the version is irrelevant here.
            new DefinitionKey(scopeKey, definitionId, 1);
            return mutate(definitionId, (tx, rows) -> {
                List<Member> members = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .filter(row -> Objects.equals(scopeKey, row.getString("scope_key")))
                        .map(row -> new Member(row.getLong("definition_version"), row.getString("status")))
                        .toList();
                return recomputeMembership(tx, scopeKey, definitionId, members);
            });
        });
    }

    /**
     * Settles which version of one owner and id is active, on the caller's locked transaction: the highest
     * version among the active and superseded ones wins. Lower active versions are demoted first, then the
     * winner is promoted, so the one-active-version rule holds at every statement. Rows that are already correct
     * keep their timestamps.
     */
    private Future<Void> recomputeMembership(
            SqlConnection tx, String scopeKey, String definitionId, List<Member> members) {
        List<Member> eligible = members.stream()
                .filter(member -> "ACTIVE".equals(member.status()) || "SUPERSEDED".equals(member.status()))
                .toList();
        if (eligible.isEmpty()) return Future.succeededFuture();

        Member highest = eligible.stream()
                .max(java.util.Comparator.comparingLong(Member::version))
                .orElseThrow();
        long highestVersion = highest.version();
        Future<Void> demote = eligible.stream()
                        .anyMatch(member -> "ACTIVE".equals(member.status()) && member.version() != highestVersion)
                ? tx.preparedQuery(sqlRecomputeDemote)
                        .execute(Tuple.of(scopeKey, definitionId, highestVersion))
                        .mapEmpty()
                : Future.succeededFuture();

        if ("ACTIVE".equals(highest.status())) return demote;
        DefinitionKey highestKey = new DefinitionKey(scopeKey, definitionId, highestVersion);
        return demote.compose(ignored -> tx.preparedQuery(sqlRecomputePromote)
                .execute(parameters(highestKey))
                .mapEmpty());
    }

    /** One version of an owner and id, with the status it has at this point of the transaction. */
    private record Member(long version, String status) {}

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
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC)))
                .mapEmpty();
    }

    /** Refreshes the provenance of a code row and, when asked, replaces its plan hash; the status is kept. */
    private Future<Void> updateCodeProvenance(SqlConnection tx, WorkflowDefinitionRecord record, boolean replaceHash) {
        var p = record.provenance();
        return tx.preparedQuery(sqlUpdateCodeProvenance)
                .execute(parameters(record.key())
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addBoolean(replaceHash)
                        .addString(record.planHash())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC))
                        .addString(record.contractName())
                        .addString(record.stateTypeName()))
                .mapEmpty();
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
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC)))
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
     * the wait would miss a row that another owner committed meanwhile. The isolation statement must be the first
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

    private static boolean ownerConflict(RowSet<Row> rows, DefinitionKey key) {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .anyMatch(r -> isHeldByAnotherOwner(r, key));
    }

    private static String heldOwners(RowSet<Row> rows, DefinitionKey key) {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(r -> isHeldByAnotherOwner(r, key))
                .map(r -> r.getString("scope_key"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean isHeldByAnotherOwner(Row row, DefinitionKey key) {
        return !Objects.equals(key.scopeKey(), row.getString("scope_key"))
                && (List.of("ACTIVE", "SUPERSEDED", "DEPRECATED").contains(row.getString("status"))
                        || ("ACTIVATING".equals(row.getString("status")) && !row.getBoolean("stale")));
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
                r.getOffsetDateTime("status_changed_at").toInstant());
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
