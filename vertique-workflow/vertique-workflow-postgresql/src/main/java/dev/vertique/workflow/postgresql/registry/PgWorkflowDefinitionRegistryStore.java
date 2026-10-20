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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * PostgreSQL registry with transaction-owned id locking and fenced activation claims.
 *
 * <p>Every reference to the {@code workflow_definitions} table is qualified with the schema mapped to
 * the {@code workflow} instance when the {@link DbSchemas} given at construction holds one; otherwise
 * the table resolves through the connection's default schema. The statements are built once at
 * construction.
 */
@Singleton
public final class PgWorkflowDefinitionRegistryStore extends PgSqlRepository
        implements WorkflowDefinitionRegistryStore {
    private static final String COLUMNS =
            "tenant_key, definition_id, definition_version, kind, plan_hash, status, document_format, "
                    + "source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at, created_at, status_changed_at";
    private static final String KEY =
            "tenant_key IS NOT DISTINCT FROM $1 AND definition_id = $2 AND definition_version = $3";

    /** Name of the migration instance whose schema mapping places the workflow tables. */
    private static final String INSTANCE_NAME = "workflow";

    private final String sqlFind;
    private final String sqlFindDocument;
    private final String sqlList;
    private final String sqlInsert;
    private final String sqlUpdateProvenance;
    private final String sqlClaim;
    private final String sqlDemoteOlder;
    private final String sqlComplete;
    private final String sqlRelease;
    private final String sqlTransition;
    private final String sqlLockRows;
    private final String sqlRecomputeDemote;
    private final String sqlRecomputePromote;
    private final WorkflowDefinitionsConfig config;
    private final WorkflowExceptionMapper workflowMapper = new WorkflowExceptionMapper();
    private volatile boolean utf8Confirmed;

    /**
     * Constructs a store using the component's optional shared document policy. The table is not
     * schema-qualified, so it resolves through the connection's default schema.
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
     * Constructs a store that qualifies the definitions table with the schema mapped to the
     * {@code workflow} instance, when {@code schemas} holds one. Without a mapping the statements are
     * identical to those of the three-argument constructor.
     *
     * @param pool the PostgreSQL connection pool
     * @param mapper the PostgreSQL failure mapper
     * @param config the optional shared document policy; defaults apply when absent
     * @param schemas the schema placement; an empty mapping leaves the table name unqualified
     * @throws IllegalArgumentException if the mapped schema name is not a valid single-segment
     *     PostgreSQL identifier
     */
    public PgWorkflowDefinitionRegistryStore(
            Pool pool, PgDbExceptionMapper mapper, Optional<WorkflowDefinitionsConfig> config, DbSchemas schemas) {
        super(pool, mapper);
        this.config = config.orElseGet(WorkflowDefinitionsConfig::defaults);

        String definitions = PgTableNames.qualify(schemas, INSTANCE_NAME, "workflow_definitions");

        this.sqlFind = "SELECT " + COLUMNS + " FROM " + definitions + " WHERE " + KEY;

        this.sqlFindDocument = """
                    SELECT tenant_key, definition_id, definition_version, document_format,
                        octet_length(convert_to(document, 'UTF8')) AS document_size_bytes,
                        char_length(document) AS document_code_points,
                        CASE WHEN octet_length(convert_to(document, 'UTF8')) <= $4 AND char_length(document) <= $5
                             THEN document ELSE NULL END AS document
                    FROM""" + " " + definitions + " WHERE " + KEY + " AND kind = 'DOCUMENT'";

        this.sqlList = "SELECT " + COLUMNS + " FROM " + definitions
                + " ORDER BY definition_id, tenant_key NULLS FIRST, definition_version";

        this.sqlInsert = "INSERT INTO " + definitions
                + " (tenant_key, definition_id, definition_version, kind, plan_hash, status, document, document_format, source_type, source_uri, source_revision, source_author, source_upload_id, loaded_at) VALUES ($1,$2,$3,'DOCUMENT',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)";

        this.sqlUpdateProvenance = "UPDATE " + definitions
                + " SET source_type=$4, source_uri=$5, source_revision=$6, source_author=$7, source_upload_id=$8, loaded_at=$9 WHERE "
                + KEY;

        this.sqlClaim = "UPDATE " + definitions
                + " SET status='ACTIVATING', activation_token=$4, status_changed_at=clock_timestamp() WHERE " + KEY;

        this.sqlDemoteOlder = "UPDATE " + definitions
                + " SET status='SUPERSEDED', status_changed_at=clock_timestamp() WHERE tenant_key IS NOT DISTINCT FROM $1 AND definition_id=$2 AND definition_version<$3 AND status='ACTIVE'";

        this.sqlComplete = "UPDATE " + definitions
                + " SET status=$4, activation_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='ACTIVATING' AND activation_token=$5";

        this.sqlRelease = "UPDATE " + definitions
                + " SET status='CANDIDATE', activation_token=NULL, status_changed_at=clock_timestamp() WHERE " + KEY
                + " AND status='ACTIVATING' AND activation_token=$4";

        this.sqlTransition = "UPDATE " + definitions + " SET status=$5, status_changed_at=clock_timestamp() WHERE "
                + KEY + " AND status=$4";

        this.sqlLockRows = "SELECT " + COLUMNS
                + ", activation_token, extract(epoch from (clock_timestamp() - status_changed_at)) >= ($2::numeric * 2) AS stale FROM "
                + definitions
                + " WHERE definition_id=$1 ORDER BY tenant_key NULLS FIRST, definition_version FOR UPDATE";

        this.sqlRecomputeDemote = "UPDATE " + definitions
                + " SET status='SUPERSEDED', status_changed_at=clock_timestamp() WHERE tenant_key IS NOT DISTINCT FROM $1 AND definition_id=$2 AND definition_version<>$3 AND status='ACTIVE'";

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
                        return Optional.of(new WorkflowDefinitionDocument(
                                key, r.getString("document"), r.getString("document_format")));
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
                            .filter(r -> filter.scope() == DefinitionFilter.TenantScope.ALL
                                    || Objects.equals(
                                            filter.tenantKey(), r.key().tenantKey()))
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
                        .anyMatch(r -> Objects.equals(key.tenantKey(), r.getString("tenant_key"))
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

    @Override
    public Future<Void> recomputeActive(@jakarta.annotation.Nullable String tenantKey, String definitionId) {
        return attempt(() -> {
            Objects.requireNonNull(definitionId, "definitionId");
            // DefinitionKey enforces the key constraints; the version is irrelevant here.
            new DefinitionKey(tenantKey, definitionId, 1);
            return mutate(definitionId, (tx, rows) -> {
                List<Row> eligible = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .filter(row -> Objects.equals(tenantKey, row.getString("tenant_key")))
                        .filter(row -> "ACTIVE".equals(row.getString("status"))
                                || "SUPERSEDED".equals(row.getString("status")))
                        .toList();
                if (eligible.isEmpty()) return Future.succeededFuture();

                Row highest = eligible.stream()
                        .max(java.util.Comparator.comparingLong(row -> row.getLong("definition_version")))
                        .orElseThrow();
                long highestVersion = highest.getLong("definition_version");
                Future<Void> demote = eligible.stream()
                                .anyMatch(row -> "ACTIVE".equals(row.getString("status"))
                                        && row.getLong("definition_version") != highestVersion)
                        ? tx.preparedQuery(sqlRecomputeDemote)
                                .execute(Tuple.of(tenantKey, definitionId, highestVersion))
                                .mapEmpty()
                        : Future.succeededFuture();

                if ("ACTIVE".equals(highest.getString("status"))) return demote;
                DefinitionKey highestKey = new DefinitionKey(tenantKey, definitionId, highestVersion);
                return demote.compose(ignored -> tx.preparedQuery(sqlRecomputePromote)
                        .execute(parameters(highestKey))
                        .mapEmpty());
            });
        });
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

    private Future<Void> insert(
            SqlConnection tx,
            WorkflowDefinitionRecord record,
            WorkflowDefinitionDocument document,
            DefinitionStatus status) {
        var p = record.provenance();
        return tx.preparedQuery(sqlInsert)
                .execute(parameters(record.key())
                        .addString(record.planHash())
                        .addString(status.name())
                        .addString(document.document())
                        .addString(document.documentFormat())
                        .addString(p.sourceType())
                        .addString(p.uri())
                        .addString(p.revision())
                        .addString(p.author())
                        .addString(p.uploadId())
                        .addValue(p.loadedAt().atOffset(java.time.ZoneOffset.UTC)))
                .mapEmpty();
    }

    private <T> Future<T> mutate(DefinitionKey key, LockedOperation<T> action) {
        return mutate(key.definitionId(), action);
    }

    private <T> Future<T> mutate(String definitionId, LockedOperation<T> action) {
        return transaction(definitionId, tx -> DefinitionIdLock.acquire(tx, definitionId)
                .compose(ignored ->
                        tx.preparedQuery(sqlLockRows).execute(Tuple.of(definitionId, config.maxStalenessSeconds())))
                .compose(rows -> action.apply(tx, rows)));
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
                .map(r -> r.getString("tenant_key") == null ? "global" : r.getString("tenant_key"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean isHeldByAnotherOwner(Row row, DefinitionKey key) {
        return !Objects.equals(key.tenantKey(), row.getString("tenant_key"))
                && (List.of("ACTIVE", "SUPERSEDED", "DEPRECATED").contains(row.getString("status"))
                        || ("ACTIVATING".equals(row.getString("status")) && !row.getBoolean("stale")));
    }

    private static DefinitionKey key(Row r) {
        return new DefinitionKey(
                r.getString("tenant_key"), r.getString("definition_id"), r.getLong("definition_version"));
    }

    private static Tuple parameters(DefinitionKey key) {
        return Tuple.tuple()
                .addString(key.tenantKey())
                .addString(key.definitionId())
                .addLong(key.definitionVersion());
    }

    private static WorkflowDefinitionRecord metadata(Row r) {
        return new WorkflowDefinitionRecord(
                key(r),
                DefinitionKind.valueOf(r.getString("kind")),
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
