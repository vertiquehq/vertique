// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data access contract for the outbox table, covering the full relay lifecycle.
 *
 * <p>Methods follow a claim-and-mark protocol: the relay claims a batch of entries via
 * {@link #claimBatch}, attempts delivery, then marks each entry with the appropriate outcome
 * ({@link #markPublished}, {@link #markRetry}, {@link #markDeadLetter}, or
 * {@link #markUnresolvable}). Stale claims are reclaimed by {@link #reclaimStale} to handle
 * crashed relay workers.
 *
 * <p>Implementations are provided by the persistence-specific sub-modules
 * (e.g., {@code vertique-inbox-outbox-postgresql}).
 */
public interface OutboxRepository {

    /**
     * Inserts a new outbox entry in {@link OutboxEntryState#PENDING} state within the provided
     * transaction.
     *
     * <p>The {@code metadata} document carries the durable propagation context captured at publish
     * time. It is stored in the {@code metadata} JSONB column and restored by relay destination
     * handlers when the entry is delivered.
     *
     * <p>{@code carrierId} is persisted into the first-class, {@code NOT NULL UNIQUE} {@code
     * carrier_id} column — never into the app-writable {@code metadata} JSONB — so a durable identity
     * snapshot signed for this exact row (PRD identity-002 F3b) can be verified against a carrier
     * reproduced from a column the relay controls, not from attacker-writable metadata.
     *
     * @param entry     the outbox entry to insert; caller-supplied headers are stored as-is
     * @param metadata  the structured metadata document (durable context + delivery control) to
     *                  persist alongside the entry; must not be {@code null}
     * @param carrierId the framework-generated per-row durable carrier identity allocated by the
     *                  producer before this call; persisted into the first-class {@code carrier_id}
     *                  column; must not be {@code null}
     * @param tx        open database transaction to use for the insert
     * @return a {@link Future} that completes with the surrogate primary key assigned to the entry
     */
    Future<Long> insert(OutboxEntry entry, OutboxMetadata metadata, UUID carrierId, SqlClient tx);

    /**
     * Atomically claims up to {@code batchSize} eligible pending entries for relay processing.
     *
     * <p>An entry is eligible if its state is {@link OutboxEntryState#PENDING} and its
     * {@code availableAt} timestamp is in the past. Capability filtering is applied as follows:
     * <ul>
     *   <li>Only entries whose {@link DestinationType} has a registered {@link ClaimScope} in
     *       {@link RelayCapabilities#byType()} are considered — destination types with no entry
     *       in the map are never claimed.</li>
     *   <li>For a type mapped to {@link ClaimScope.All}, every eligible row of that type is
     *       admitted.</li>
     *   <li>For a type mapped to {@link ClaimScope.Destinations}, the supplier is evaluated at
     *       claim time and only rows whose {@code destination} column value is in the resulting
     *       set are admitted. An empty set admits nothing for that type.</li>
     * </ul>
     *
     * <p>Claimed entries are transitioned to {@link OutboxEntryState#PROCESSING} and stamped with
     * the relay worker's identity.
     *
     * @param batchSize    maximum number of entries to claim in one operation
     * @param claimedBy    identity of the relay worker claiming the entries
     * @param capabilities mapping of destination types to claim scopes for this relay worker;
     *                     types absent from the map are not claimed
     * @return a {@link Future} that completes with the list of claimed entries
     */
    Future<List<OutboxRecord>> claimBatch(int batchSize, String claimedBy, RelayCapabilities capabilities);

    /**
     * Marks the given entry as successfully published.
     *
     * <p>Transitions the entry from {@link OutboxEntryState#PROCESSING} to
     * {@link OutboxEntryState#PUBLISHED}. The update is guarded by {@code claimedBy} to prevent
     * concurrent workers from marking entries they do not own.
     *
     * @param entryId   surrogate primary key of the outbox entry
     * @param claimedBy identity of the relay worker that published the entry
     * @return a {@link Future} that completes with {@code true} if the entry was updated,
     *         or {@code false} if the entry was not found or not owned by this worker
     */
    Future<Boolean> markPublished(long entryId, String claimedBy);

    /**
     * Marks the given entry for retry after a transient failure.
     *
     * <p>Resets the entry to {@link OutboxEntryState#PENDING}, increments the attempt counter
     * to {@code newAttempt}, sets {@code availableAt} to control backoff, and records the
     * failure details for observability.
     *
     * @param entryId      surrogate primary key of the outbox entry
     * @param claimedBy    identity of the relay worker that attempted delivery
     * @param newAttempt   the updated attempt counter after this failure
     * @param availableAt  earliest time at which this entry may be re-claimed
     * @param lastError    human-readable error message from this failure
     * @param errorType    exception class name from this failure
     * @return a {@link Future} that completes with {@code true} if the entry was updated,
     *         or {@code false} if the entry was not found or not owned by this worker
     */
    Future<Boolean> markRetry(
            long entryId, String claimedBy, int newAttempt, Instant availableAt, String lastError, String errorType);

    /**
     * Marks the given entry as dead-lettered after exhausting all retry attempts or encountering
     * a permanent failure.
     *
     * <p>Transitions the entry to {@link OutboxEntryState#DEAD_LETTER} and records the final
     * error details.
     *
     * @param entryId   surrogate primary key of the outbox entry
     * @param claimedBy identity of the relay worker that made the final attempt
     * @param lastError human-readable error message from the final failure
     * @param errorType exception class name from the final failure
     * @return a {@link Future} that completes with {@code true} if the entry was updated,
     *         or {@code false} if the entry was not found or not owned by this worker
     */
    Future<Boolean> markDeadLetter(long entryId, String claimedBy, String lastError, String errorType);

    /**
     * Marks the given entry as unresolvable and schedules it for a delayed retry.
     *
     * <p>Used when no handler is registered for the entry's destination. Resets the entry to
     * {@link OutboxEntryState#PENDING} with {@code availableAt} set to {@code now + delay}
     * so the relay will not repeatedly attempt an unresolvable entry in a tight loop.
     *
     * @param entryId   surrogate primary key of the outbox entry
     * @param claimedBy identity of the relay worker that found the entry unresolvable
     * @param delay     duration to wait before the entry becomes eligible for re-claiming
     * @return a {@link Future} that completes with {@code true} if the entry was updated,
     *         or {@code false} if the entry was not found or not owned by this worker
     */
    Future<Boolean> markUnresolvable(long entryId, String claimedBy, Duration delay);

    /**
     * Resets stale {@link OutboxEntryState#PROCESSING} entries back to
     * {@link OutboxEntryState#PENDING} so they can be re-claimed by another relay worker.
     *
     * <p>An entry is considered stale if it has been in PROCESSING state for longer than
     * {@code leaseTimeout}. This handles relay worker crashes without leaving entries
     * permanently stuck.
     *
     * @param leaseTimeout maximum duration an entry may remain in PROCESSING before being reset
     * @return a {@link Future} that completes when the reclaim operation finishes
     */
    Future<Void> reclaimStale(Duration leaseTimeout);

    /**
     * Deletes published outbox entries older than the given retention period.
     *
     * <p>Records are deleted in batches to avoid long-running transactions.
     *
     * @param retentionDays number of days to retain published outbox entries
     * @param batchSize     maximum number of records to delete in a single operation
     * @return a {@link Future} that completes with the number of records deleted
     */
    Future<Integer> cleanupPublished(int retentionDays, int batchSize);

    /**
     * Deletes dead-letter outbox entries older than the given retention period.
     *
     * <p>Records are deleted in batches to avoid long-running transactions.
     *
     * @param retentionDays number of days to retain dead-letter outbox entries
     * @param batchSize     maximum number of records to delete in a single operation
     * @return a {@link Future} that completes with the number of records deleted
     */
    Future<Integer> cleanupDeadLetter(int retentionDays, int batchSize);
}
