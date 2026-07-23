// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.postgresql.PgSqlRepository;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxRecord;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.inboxoutbox.RelayCapabilities;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL-backed implementation of both {@link InboxRepository} and {@link OutboxRepository}.
 *
 * <p>All SQL operations use the Vert.x reactive PostgreSQL client. The inbox uses
 * {@code INSERT ... ON CONFLICT DO NOTHING} to provide atomic, transactional deduplication.
 * The outbox claim uses a CTE with {@code FOR UPDATE SKIP LOCKED} inside a short transaction so
 * that no two relay workers claim the same entry and database locks are released before destination
 * I/O begins.
 *
 * <p>Node identity ({@link #nodeId}) is built from the hostname and a random suffix so that
 * multiple JVM instances on the same host are distinguishable in the {@code claimed_by} column.
 *
 * <p>Bind this class through {@link TransactionalMessagingPostgresqlModule} so that both
 * repository interfaces resolve to this implementation in the Dagger component.
 */
@Slf4j
@Singleton
public class PgInboxOutboxRepository extends PgSqlRepository implements InboxRepository, OutboxRepository {

    // --- SQL constants: Inbox ---

    private static final String SQL_INBOX_TRY_INSERT =
            "INSERT INTO inbox (message_id, source) VALUES ($1, $2) ON CONFLICT DO NOTHING";

    private static final String SQL_INBOX_CLEANUP =
            "WITH batch AS (SELECT message_id, source FROM inbox WHERE processed_at < NOW() - ($1 * interval '1 day')"
                    + " LIMIT $2) DELETE FROM inbox USING batch"
                    + " WHERE inbox.message_id = batch.message_id AND inbox.source = batch.source";

    // --- SQL constants: Outbox ---

    private static final String SQL_OUTBOX_INSERT =
            "INSERT INTO outbox (carrier_id, aggregate_type, aggregate_id, event_type, destination,"
                    + " destination_type, payload, headers, metadata, scheduled_at, available_at, max_attempts)"
                    + " VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9::jsonb, $10, COALESCE($11, NOW()), $12)"
                    + " RETURNING id";

    /**
     * Prefix of the dynamic claim query, up to and including the {@code AND (} that opens the
     * destination-type eligibility predicate group. The prefix fixes {@code $1} (batchSize) and
     * {@code $2} (claimedBy); dynamic parameters start at {@code $3}.
     *
     * <p>Eligibility criteria covered by the prefix:
     * <ul>
     *   <li>{@code state = 'PENDING'} and {@code available_at <= NOW()}</li>
     *   <li>{@code scheduled_at} is null or in the past</li>
     *   <li>the opening {@code AND (} for the destination-type disjunction</li>
     * </ul>
     */
    private static final String SQL_OUTBOX_CLAIM_PREFIX = "WITH eligible AS ("
            + "SELECT o.id FROM outbox o"
            + " WHERE o.state = 'PENDING'"
            + "   AND o.available_at <= NOW()"
            + "   AND (o.scheduled_at IS NULL OR o.scheduled_at <= NOW())"
            + "   AND (";

    /**
     * Suffix of the dynamic claim query, from the {@code )} that closes the destination predicate
     * group onward. Includes the head-of-line subquery, ORDER BY, LIMIT $1, FOR UPDATE SKIP LOCKED,
     * and the UPDATE…RETURNING clause.
     */
    private static final String SQL_OUTBOX_CLAIM_SUFFIX = ")"
            + "   AND ("
            + "       o.aggregate_id IS NULL"
            + "       OR o.id = ("
            + "           SELECT MIN(head.id) FROM outbox head"
            + "           WHERE head.aggregate_id = o.aggregate_id"
            + "             AND head.state IN ('PENDING', 'PROCESSING')"
            + "       )"
            + "   )"
            + " ORDER BY o.available_at ASC, o.id ASC"
            + " LIMIT $1"
            + " FOR UPDATE SKIP LOCKED"
            + ")"
            + " UPDATE outbox"
            + " SET state = 'PROCESSING', claimed_at = NOW(), claimed_by = $2, updated_at = NOW()"
            + " FROM eligible WHERE outbox.id = eligible.id"
            + " RETURNING outbox.*";

    // --- Claim-scope validation limits ---

    /**
     * Maximum number of claimable target IDs that a {@link ClaimScope.Destinations} supplier may
     * return in a single claim cycle. Prevents runaway query generation or pathological bind-param
     * lists from mis-configured handlers.
     */
    private static final int MAX_CLAIM_TARGETS = 10_000;

    /**
     * Maximum length of a single destination target string, matching the {@code destination}
     * column definition of {@code VARCHAR(255)}.
     */
    private static final int MAX_TARGET_LENGTH = 255;

    private static final String SQL_MARK_PUBLISHED =
            "UPDATE outbox SET state = 'PUBLISHED', published_at = NOW(), updated_at = NOW()"
                    + " WHERE id = $1 AND state = 'PROCESSING' AND claimed_by = $2";

    private static final String SQL_MARK_RETRY = "UPDATE outbox SET state = 'PENDING', attempt = $3, available_at = $4,"
            + " last_error = $5, error_type = $6, claimed_at = NULL, claimed_by = NULL, updated_at = NOW()"
            + " WHERE id = $1 AND state = 'PROCESSING' AND claimed_by = $2";

    private static final String SQL_MARK_DEAD_LETTER =
            "UPDATE outbox SET state = 'DEAD_LETTER', last_error = $3, error_type = $4, updated_at = NOW()"
                    + " WHERE id = $1 AND state = 'PROCESSING' AND claimed_by = $2";

    private static final String SQL_MARK_UNRESOLVABLE = "UPDATE outbox SET state = 'PENDING',"
            + " available_at = NOW() + ($3 * interval '1 millisecond'),"
            + " claimed_at = NULL, claimed_by = NULL, updated_at = NOW()"
            + " WHERE id = $1 AND state = 'PROCESSING' AND claimed_by = $2";

    private static final String SQL_RECLAIM_STALE =
            "UPDATE outbox SET state = 'PENDING', claimed_at = NULL, claimed_by = NULL, updated_at = NOW()"
                    + " WHERE state = 'PROCESSING' AND claimed_at < NOW() - ($1 * interval '1 millisecond')";

    private static final String SQL_CLEANUP_PUBLISHED = "WITH batch AS (SELECT id FROM outbox WHERE state = 'PUBLISHED'"
            + " AND published_at < NOW() - ($1 * interval '1 day') LIMIT $2)"
            + " DELETE FROM outbox WHERE id IN (SELECT id FROM batch)";

    private static final String SQL_CLEANUP_DEAD_LETTER =
            "WITH batch AS (SELECT id FROM outbox WHERE state = 'DEAD_LETTER'"
                    + " AND updated_at < NOW() - ($1 * interval '1 day') LIMIT $2)"
                    + " DELETE FROM outbox WHERE id IN (SELECT id FROM batch)";

    // --- Node identity ---

    /**
     * Stable identifier for this repository instance, used as {@code claimed_by} during outbox
     * claim. Constructed from the hostname and a random suffix to differentiate multiple JVM
     * instances running on the same host.
     */
    private final String nodeId;

    // --- Constructor ---

    /**
     * Creates a new PostgreSQL inbox/outbox repository.
     *
     * @param pool          the PostgreSQL connection pool
     * @param exceptionMapper the PostgreSQL failure mapper for exception translation
     */
    @Inject
    public PgInboxOutboxRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
        this.nodeId = resolveNodeId();
    }

    /**
     * Returns the stable node identifier for this repository instance.
     *
     * @return the node id string (e.g., {@code "hostname-a1b2c3d4"})
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * Generates a node identifier from the hostname and a short random suffix.
     * Falls back to {@code "unknown"} if hostname resolution fails.
     *
     * @return a stable node identifier for this JVM instance
     */
    private static String resolveNodeId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return hostname + "-" + suffix;
    }

    // --- InboxRepository ---

    /**
     * {@inheritDoc}
     *
     * <p>Executes on the provided {@link SqlClient} so the insert participates in the caller's
     * transaction. Uses {@code ON CONFLICT DO NOTHING} to make the check-and-insert atomic.
     * Returns {@code true} when a new row was inserted, or {@code false} when a conflict
     * was detected (duplicate message).
     */
    @Override
    public Future<Boolean> tryInsert(String messageId, String source, SqlClient tx) {
        return tx.preparedQuery(SQL_INBOX_TRY_INSERT)
                .execute(Tuple.of(messageId, source))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "inbox tryInsert")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes records in a single batched CTE to avoid holding long-running locks.
     */
    @Override
    public Future<Integer> cleanup(int retentionDays, int batchSize) {
        return pool.preparedQuery(SQL_INBOX_CLEANUP)
                .execute(Tuple.of(retentionDays, batchSize))
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "inbox cleanup")));
    }

    // --- OutboxRepository ---

    /**
     * {@inheritDoc}
     *
     * <p>Executes on the provided {@link SqlClient} so the insert participates in the caller's
     * transaction. The {@code metadata} document is serialized via {@link OutboxMetadata#toJson()}
     * and stored in the {@code metadata} JSONB column. {@code carrierId} is persisted into the
     * first-class, {@code NOT NULL UNIQUE} {@code carrier_id} column (PRD identity-002 F3b) — never
     * into {@code metadata} — so it cannot be forged via application-writable input. Returns the
     * surrogate primary key assigned by the database via {@code RETURNING id}.
     */
    @Override
    public Future<Long> insert(OutboxEntry entry, OutboxMetadata metadata, UUID carrierId, SqlClient tx) {
        JsonObject headersJson =
                entry.headers().isEmpty() ? null : new JsonObject(new LinkedHashMap<>(entry.headers()));
        OffsetDateTime scheduledAt = toOffsetDateTime(entry.scheduledAt());
        OffsetDateTime availableAt = toOffsetDateTime(entry.availableAt());

        return tx.preparedQuery(SQL_OUTBOX_INSERT)
                .execute(Tuple.of(
                        carrierId,
                        entry.aggregateType(),
                        entry.aggregateId(),
                        entry.eventType(),
                        entry.destination(),
                        entry.destinationType().id(),
                        PayloadCodec.encode(entry.payload()),
                        headersJson,
                        metadata.toJson(),
                        scheduledAt,
                        availableAt,
                        entry.maxAttempts()))
                .map(rs -> {
                    Row row = rs.iterator().next();
                    return row.getLong("id");
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox insert")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs in a short transaction with {@code FOR UPDATE SKIP LOCKED} to prevent competing
     * relay workers from claiming the same entries. Only entries whose destination type and
     * target identity match the relay's {@link RelayCapabilities} are eligible. Entries sharing
     * the same {@code aggregate_id} are delivered in strict insertion order (head-of-line
     * blocking per aggregate).
     *
     * <p>The destination-eligibility disjunction in the claim query is built dynamically from
     * {@link RelayCapabilities#byType()} via {@link #buildClaimEligibility(RelayCapabilities, Tuple)}.
     * If that method detects an invalid {@link ClaimScope.Destinations} supplier result it returns a
     * failed future wrapping a {@link ClaimScopeException} — that exception is propagated directly
     * without routing through the {@code exceptionMapper}, because it is a configuration fault, not
     * a database error.
     */
    @Override
    public Future<List<OutboxRecord>> claimBatch(int batchSize, String claimedBy, RelayCapabilities capabilities) {
        Tuple params = Tuple.tuple();
        params.addInteger(batchSize);
        params.addString(claimedBy);

        String disjunction;
        try {
            disjunction = buildClaimEligibility(capabilities, params);
        } catch (ClaimScopeException e) {
            return Future.failedFuture(e);
        }

        String sql = SQL_OUTBOX_CLAIM_PREFIX + disjunction + SQL_OUTBOX_CLAIM_SUFFIX;

        return pool.withTransaction(
                        conn -> conn.preparedQuery(sql).execute(params).map(rs -> {
                            List<OutboxRecord> records = new ArrayList<>(rs.size());
                            for (Row row : rs) {
                                records.add(OutboxRecordMapper.fromRow(row));
                            }
                            return records;
                        }))
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox claimBatch")));
    }

    /**
     * Builds the SQL destination-eligibility disjunction fragment and appends the corresponding
     * bind parameters to {@code params}, starting at parameter index {@code $3} (after the fixed
     * {@code $1}=batchSize and {@code $2}=claimedBy parameters).
     *
     * <p>Each entry in {@link RelayCapabilities#byType()} contributes one predicate clause:
     * <ul>
     *   <li>{@link ClaimScope.All} — appends {@code (o.destination_type = $n)} and binds the
     *       type name as a string parameter.</li>
     *   <li>{@link ClaimScope.Destinations} — evaluates the supplier once, validates the snapshot,
     *       and appends {@code (o.destination_type = $n AND o.destination = ANY($m))} with the
     *       type name and target array bound as parameters. An empty snapshot contributes no
     *       predicate (claims nothing for that type). An invalid snapshot throws
     *       {@link ClaimScopeException}.</li>
     * </ul>
     *
     * <p>If no predicate is contributed (empty map, or all {@code Destinations} scopes returned
     * empty sets), the returned fragment is the literal {@code FALSE} so the query claims nothing.
     *
     * <p>All destination-type strings and target values are bound as prepared-statement parameters
     * ({@code $n} / {@code ANY($m)}) — never concatenated into the SQL string.
     *
     * <p><strong>Validation rules for {@link ClaimScope.Destinations} snapshots:</strong>
     * <ul>
     *   <li>Supplier <em>or its returned set</em> throws (during {@code get()}/{@code iterator()}/etc.) →
     *       {@link ClaimScopeException} (message names type + "supplier or its result failed")</li>
     *   <li>Supplier returns {@code null} → {@link ClaimScopeException} (message names type + "produced a null target set")</li>
     *   <li>Any element is {@code null} or blank after trim → {@link ClaimScopeException}</li>
     *   <li>Any element length exceeds {@link #MAX_TARGET_LENGTH} → {@link ClaimScopeException}</li>
     *   <li>More than {@link #MAX_CLAIM_TARGETS} elements → {@link ClaimScopeException}</li>
     *   <li>Empty set → no predicate contributed, no exception</li>
     * </ul>
     *
     * @param capabilities the relay capabilities describing which destination types and scopes apply
     * @param params       the mutable {@link Tuple} to append bind parameters to; must already
     *                     contain {@code $1} (batchSize) and {@code $2} (claimedBy)
     * @return the SQL disjunction fragment (e.g. {@code "(o.destination_type = $3)"}, or
     *         multiple OR-joined clauses, or {@code "FALSE"} when nothing is claimable)
     * @throws ClaimScopeException if a {@link ClaimScope.Destinations} supplier produces an invalid
     *                             snapshot; the message names only the type and reason category
     */
    private String buildClaimEligibility(RelayCapabilities capabilities, Tuple params) {
        // Dynamic parameter index: $1 (batchSize) and $2 (claimedBy) are already bound.
        int paramIdx = 2;
        List<String> predicates = new ArrayList<>();

        for (Map.Entry<DestinationType, ClaimScope> entry :
                capabilities.byType().entrySet()) {
            String typeName = entry.getKey().id();
            switch (entry.getValue()) {
                case ClaimScope.All ignored -> {
                    int n = ++paramIdx;
                    params.addString(typeName);
                    predicates.add("(o.destination_type = $" + n + ")");
                }
                case ClaimScope.Destinations destinations -> {
                    // validateClaimTargets returns a safe defensive copy built inside its fail-closed
                    // guard, so the (possibly hostile) supplier-returned Set is never touched here.
                    String[] targets = validateClaimTargets(typeName, destinations.claimableTargets());
                    if (targets.length == 0) {
                        // Empty set: claims nothing for this type — contribute no predicate.
                        continue;
                    }
                    int n = ++paramIdx;
                    int m = ++paramIdx;
                    params.addString(typeName);
                    // Bind the original (un-trimmed) target values: the `destination` column is stored
                    // verbatim on write, so trimming here would fail to match legitimately-stored targets.
                    params.addArrayOfString(targets);
                    predicates.add("(o.destination_type = $" + n + " AND o.destination = ANY($" + m + "))");
                }
            }
        }

        return predicates.isEmpty() ? "FALSE" : String.join(" OR ", predicates);
    }

    /**
     * Evaluates and validates a {@link ClaimScope.Destinations} target supplier, returning the
     * snapshot to bind into the claim query.
     *
     * <p>Fails closed with a {@link ClaimScopeException} whose message names only the destination
     * type and the reason category — never the offending target value, and with no cause attached —
     * so a misbehaving supplier cannot leak target ids through the relay's failure log. The supplier
     * <em>and</em> its returned {@link Set} are exercised entirely inside one guard: a hostile set
     * whose {@code size()}/{@code iterator()}/{@code toArray()} throws still fails closed (sanitized)
     * rather than escaping {@code claimBatch} as a raw synchronous exception and breaking the relay
     * poll loop's failed-{@link io.vertx.core.Future} contract.
     *
     * @param typeName the destination type id, used only for the sanitized message
     * @param supplier the claim-time target supplier to evaluate
     * @return a defensive copy of the validated targets (possibly empty — an empty array claims
     *         nothing for the type), safe to bind without touching the supplier's set again
     * @throws ClaimScopeException if the supplier or its returned set throws, the set is {@code null},
     *                             or it yields a null/blank/over-{@value #MAX_TARGET_LENGTH}-character
     *                             element or more than {@value #MAX_CLAIM_TARGETS} elements
     */
    private String[] validateClaimTargets(String typeName, Supplier<Set<String>> supplier) {
        try {
            Set<String> snapshot = supplier.get();
            if (snapshot == null) {
                throw new ClaimScopeException(
                        "claim scope for destination type '" + typeName + "' produced a null target set");
            }
            // Validate AND copy in a SINGLE pass over the supplier-returned set, counting independently
            // rather than trusting size(). The set is read exactly once: a second traversal (e.g.
            // toArray()) could yield different, unvalidated values from a hostile or concurrently-mutated
            // set, slipping null/blank/over-length/over-cap targets past these guards.
            List<String> validated = new ArrayList<>();
            for (String target : snapshot) {
                if (target == null || target.isBlank()) {
                    throw new ClaimScopeException(
                            "claim scope for destination type '" + typeName + "' contains a null or blank element");
                }
                if (target.length() > MAX_TARGET_LENGTH) {
                    throw new ClaimScopeException("claim scope for destination type '" + typeName
                            + "' element exceeds maximum length of " + MAX_TARGET_LENGTH);
                }
                if (validated.size() >= MAX_CLAIM_TARGETS) {
                    throw new ClaimScopeException("claim scope for destination type '" + typeName
                            + "' target set exceeds limit of " + MAX_CLAIM_TARGETS);
                }
                validated.add(target);
            }
            // toArray() on our own local list — never on the supplier-returned set.
            return validated.toArray(String[]::new);
        } catch (ClaimScopeException sanitized) {
            throw sanitized;
        } catch (RuntimeException ex) {
            // Supplier threw, or its returned set threw during size()/iteration/toArray(). Fail closed,
            // logging only the destination type + exception CLASS — never the supplier's message/trace
            // or any target id (honours the no-leak contract).
            log.warn(
                    "ClaimScope supplier for destination type '{}' failed with {}; suppressing detail to avoid leaking target ids",
                    typeName,
                    ex.getClass().getName());
            throw new ClaimScopeException(
                    "claim scope for destination type '" + typeName + "' supplier or its result failed");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The update is guarded by both {@code id} and {@code claimed_by} to ensure that only
     * the owning relay worker can mark an entry as published.
     */
    @Override
    public Future<Boolean> markPublished(long entryId, String claimedBy) {
        return pool.preparedQuery(SQL_MARK_PUBLISHED)
                .execute(Tuple.of(entryId, claimedBy))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox markPublished")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The update is guarded by both {@code id} and {@code claimed_by} to ensure that only
     * the owning relay worker can schedule a retry.
     */
    @Override
    public Future<Boolean> markRetry(
            long entryId, String claimedBy, int newAttempt, Instant availableAt, String lastError, String errorType) {
        return pool.preparedQuery(SQL_MARK_RETRY)
                .execute(Tuple.of(entryId, claimedBy, newAttempt, toOffsetDateTime(availableAt), lastError, errorType))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox markRetry")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The update is guarded by both {@code id} and {@code claimed_by} to ensure that only
     * the owning relay worker can dead-letter an entry.
     */
    @Override
    public Future<Boolean> markDeadLetter(long entryId, String claimedBy, String lastError, String errorType) {
        return pool.preparedQuery(SQL_MARK_DEAD_LETTER)
                .execute(Tuple.of(entryId, claimedBy, lastError, errorType))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox markDeadLetter")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The update is guarded by both {@code id} and {@code claimed_by}. The delay is passed
     * in milliseconds so that the SQL expression {@code NOW() + ($3 * interval '1 millisecond')}
     * produces the correct backoff time.
     */
    @Override
    public Future<Boolean> markUnresolvable(long entryId, String claimedBy, Duration delay) {
        return pool.preparedQuery(SQL_MARK_UNRESOLVABLE)
                .execute(Tuple.of(entryId, claimedBy, delay.toMillis()))
                .map(rs -> rs.rowCount() > 0)
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox markUnresolvable")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The lease timeout is passed in milliseconds so that the SQL expression
     * {@code claimed_at < NOW() - ($1 * interval '1 millisecond')} identifies entries that have
     * been in {@code PROCESSING} state for longer than the configured timeout.
     */
    @Override
    public Future<Void> reclaimStale(Duration leaseTimeout) {
        return pool.preparedQuery(SQL_RECLAIM_STALE)
                .execute(Tuple.of(leaseTimeout.toMillis()))
                .map(rs -> {
                    int count = rs.rowCount();
                    if (count > 0) {
                        log.warn("Reclaimed {} stale outbox entries", count);
                    }
                    return (Void) null;
                })
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox reclaimStale")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes published records in a single batched CTE to avoid holding long-running locks.
     */
    @Override
    public Future<Integer> cleanupPublished(int retentionDays, int batchSize) {
        return pool.preparedQuery(SQL_CLEANUP_PUBLISHED)
                .execute(Tuple.of(retentionDays, batchSize))
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox cleanupPublished")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes dead-letter records in a single batched CTE to avoid holding long-running locks.
     */
    @Override
    public Future<Integer> cleanupDeadLetter(int retentionDays, int batchSize) {
        return pool.preparedQuery(SQL_CLEANUP_DEAD_LETTER)
                .execute(Tuple.of(retentionDays, batchSize))
                .map(rs -> rs.rowCount())
                .recover(t -> Future.failedFuture(exceptionMapper.translate(t, "outbox cleanupDeadLetter")));
    }

    // --- Internal helpers ---

    /**
     * Converts an {@link Instant} to an {@link OffsetDateTime} at UTC for use in
     * {@code TIMESTAMPTZ} parameters. The Vert.x pg-client requires {@link OffsetDateTime} for
     * timestamp-with-timezone columns. Returns {@code null} when {@code instant} is {@code null}.
     *
     * @param instant the instant to convert, or {@code null}
     * @return the UTC {@link OffsetDateTime}, or {@code null}
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
