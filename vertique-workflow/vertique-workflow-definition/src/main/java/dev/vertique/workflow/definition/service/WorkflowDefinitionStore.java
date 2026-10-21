// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe store for candidate and available document-backed workflow definitions.
 *
 * <p>The store tracks the lifecycle of each loaded definition using an {@link ActivationStatus}
 * that is separate from the {@link dev.vertique.workflow.registry.WorkflowRegistry}'s version
 * ordering. The store's status is informational: it never overrides registry semantics, and any
 * number of versions of one id are available at once.
 *
 * <p>Internal storage: a {@link ConcurrentHashMap} keyed on {@link DefinitionKey} (scope, id
 * and version). Public legacy methods address only default-scope entries; internal paths retain the scope. Each entry is a {@link StoredEntry} holding the compiled definition and
 * an {@link AtomicReference} to the current status.
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #storeCandidate} fails atomically on duplicate key — the store either accepts the
 *       entry or throws, with no partial state.</li>
 *   <li>{@link #tryClaimForActivation} atomically CAS-flips {@code CANDIDATE -> PUBLISHING}. Only
 *       one concurrent caller wins the claim; others see the current status and must decide whether
 *       to wait, return idempotently, or throw.</li>
 *   <li>{@link #markActive} atomically transitions {@code PUBLISHING -> AVAILABLE}. It changes no
 *       other entry.</li>
 *   <li>{@link #resetToCandidate} atomically transitions {@code PUBLISHING -> CANDIDATE} so that a
 *       failed publication can be retried.</li>
 *   <li>{@link #list} returns a snapshot; mutations after the call are not reflected.</li>
 * </ul>
 */
@Singleton
public final class WorkflowDefinitionStore {

    // --- Internal entry ---

    /**
     * Internal storage entry: the compiled definition plus a mutable status reference.
     *
     * @param compiled the compiled definition; non-null
     * @param status atomic reference to the current lifecycle status; non-null
     */
    record StoredEntry(CompiledDefinition compiled, AtomicReference<ActivationStatus> status) {

        /**
         * Constructs a stored entry in {@link ActivationStatus#CANDIDATE} status.
         *
         * @param compiled the compiled definition; must not be {@code null}
         */
        StoredEntry(CompiledDefinition compiled) {
            this(compiled, new AtomicReference<>(ActivationStatus.CANDIDATE));
        }
    }

    // --- State ---

    /** Keyed by (definitionId, definitionVersion). */
    private final ConcurrentHashMap<DefinitionKey, StoredEntry> byKey = new ConcurrentHashMap<>();

    // --- Construction ---

    /**
     * Constructs an empty store. Called by Dagger.
     */
    @Inject
    public WorkflowDefinitionStore() {}

    // --- Public API ---

    /**
     * Stores a compiled definition as a {@link ActivationStatus#CANDIDATE}.
     *
     * <p>If an entry for {@code (definitionId, definitionVersion)} already exists (regardless of
     * status), this method throws rather than overwriting it.
     *
     * @param compiled the compiled definition to store; must not be {@code null}
     * @throws WorkflowDefinitionException if the {@code (definitionId, definitionVersion)} pair
     *     is already present in the store
     * @throws IllegalArgumentException if the definition id exceeds 255 code points or the
     *     version is not positive
     */
    public void storeCandidate(CompiledDefinition compiled) {
        storeCandidate(
                new DefinitionKey(
                        WorkflowScopes.DEFAULT,
                        compiled.definition().definitionId(),
                        compiled.definition().definitionVersion()),
                compiled);
    }

    void storeCandidate(DefinitionKey key, CompiledDefinition compiled) {
        Objects.requireNonNull(compiled, "compiled");
        String id = key.definitionId();
        long version = key.definitionVersion();
        StoredEntry existing = byKey.putIfAbsent(key, new StoredEntry(compiled));
        if (existing != null) {
            throw new WorkflowDefinitionException("definition '" + id + "' v" + version
                    + " is already stored (status=" + existing.status().get()
                    + "); cannot overwrite with a new candidate");
        }
    }

    /**
     * Returns the stored entry for the given {@code (definitionId, definitionVersion)}, or
     * {@link Optional#empty()} if not found.
     *
     * @param definitionId the definition id to look up; must not be {@code null}
     * @param definitionVersion the version to look up
     * @return the stored entry, or empty if not found
     */
    public Optional<StoredEntry> get(String definitionId, long definitionVersion) {
        return legacyKey(definitionId, definitionVersion).flatMap(this::get);
    }

    /**
     * Maps legacy id/version arguments to a default-scope key; input the key rejects maps to empty so each
     * legacy method can keep its original outcome for ids and versions that can never be stored.
     */
    private static Optional<DefinitionKey> legacyKey(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        try {
            return Optional.of(new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, definitionVersion));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    Optional<StoredEntry> get(DefinitionKey key) {
        String definitionId = key.definitionId();
        Objects.requireNonNull(definitionId, "definitionId");
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * Atomically tries to claim the entry at {@code (definitionId, definitionVersion)} for
     * publication by CAS-flipping its status from {@link ActivationStatus#CANDIDATE} to
     * {@link ActivationStatus#PUBLISHING}.
     *
     * <p>Only one concurrent caller wins the claim. The winner receives {@code true} and must
     * subsequently call either {@link #markActive} (on successful registry registration) or
     * {@link #resetToCandidate} (if registration fails) to release the claim. Losers receive
     * {@code false} and must inspect the current status to determine the appropriate response.
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to claim
     * @return {@code true} if this caller won the CAS (CANDIDATE to PUBLISHING); {@code false} if
     *     the CAS failed because another thread already changed the status
     * @throws WorkflowDefinitionException if no entry is found for the given id/version
     */
    public boolean tryClaimForActivation(String definitionId, long definitionVersion) {
        Optional<DefinitionKey> key = legacyKey(definitionId, definitionVersion);
        if (key.isEmpty()) {
            throw new WorkflowDefinitionException("cannot publish definition '" + definitionId + "' v"
                    + definitionVersion + ": not found in the store; call load() first");
        }
        return tryClaimForActivation(key.get());
    }

    boolean tryClaimForActivation(DefinitionKey key) {
        String definitionId = key.definitionId();
        long definitionVersion = key.definitionVersion();
        Objects.requireNonNull(definitionId, "definitionId");
        StoredEntry entry = byKey.get(key);
        if (entry == null) {
            throw new WorkflowDefinitionException("cannot publish definition '" + definitionId + "' v"
                    + definitionVersion + ": not found in the store; call load() first");
        }
        // Attempt atomic CANDIDATE -> PUBLISHING flip; return true only if this caller won
        boolean claimed = entry.status().compareAndSet(ActivationStatus.CANDIDATE, ActivationStatus.PUBLISHING);
        if (claimed) refreshCachedStatus(key);
        return claimed;
    }

    /**
     * Returns the current {@link ActivationStatus} of the entry at
     * {@code (definitionId, definitionVersion)}, or {@code null} if not found.
     *
     * <p>Used by callers after a failed {@link #tryClaimForActivation} to determine why the CAS
     * did not succeed (already AVAILABLE, or PUBLISHING by another thread). A version that
     * the store holds only as persisted metadata, because it was not compiled on this node, reports
     * its persisted status.
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to query
     * @return the current status, or {@code null} if the entry is not found
     */
    public ActivationStatus currentStatus(String definitionId, long definitionVersion) {
        return legacyKey(definitionId, definitionVersion)
                .map(this::currentStatus)
                .orElse(null);
    }

    ActivationStatus currentStatus(DefinitionKey key) {
        String definitionId = key.definitionId();
        Objects.requireNonNull(definitionId, "definitionId");
        StoredEntry entry = byKey.get(key);
        if (entry != null) return entry.status().get();
        // A row the node holds only as metadata, such as a candidate that was never compiled here.
        WorkflowDefinitionRecord metadataOnly = metadataByKey.get(key);
        return metadataOnly != null
                ? ActivationStatus.valueOf(metadataOnly.status().name())
                : null;
    }

    /**
     * Resets the status of the entry at {@code (definitionId, definitionVersion)} from
     * {@link ActivationStatus#PUBLISHING} back to {@link ActivationStatus#CANDIDATE}, allowing a
     * subsequent publication attempt to retry.
     *
     * <p>Called by {@link DefaultWorkflowDefinitionService} when {@code registry.register(...)}
     * throws after the claim was won, to release the claim so the caller can retry.
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to reset
     */
    public void resetToCandidate(String definitionId, long definitionVersion) {
        legacyKey(definitionId, definitionVersion).ifPresent(this::resetToCandidate);
    }

    void resetToCandidate(DefinitionKey key) {
        String definitionId = key.definitionId();
        Objects.requireNonNull(definitionId, "definitionId");
        StoredEntry entry = byKey.get(key);
        if (entry != null) {
            entry.status().compareAndSet(ActivationStatus.PUBLISHING, ActivationStatus.CANDIDATE);
            refreshCachedStatus(key);
        }
    }

    /**
     * Atomically transitions the status of the entry at {@code (definitionId, definitionVersion)}
     * from {@link ActivationStatus#PUBLISHING} to {@link ActivationStatus#AVAILABLE}.
     *
     * <p>The name is kept from the first release. The call changes no other entry: any number of
     * versions of one id are available at once, and which of them starts by default is not decided
     * here.
     *
     * @param definitionId the definition id to mark available; must not be {@code null}
     * @param definitionVersion the version to mark available
     * @return {@code true} if the entry became {@code AVAILABLE}; {@code false} if it was not
     *     {@code PUBLISHING}, for instance because it was already available
     * @throws WorkflowDefinitionException if no entry is found for the given id/version
     */
    public boolean markActive(String definitionId, long definitionVersion) {
        Optional<DefinitionKey> key = legacyKey(definitionId, definitionVersion);
        if (key.isEmpty()) {
            throw new WorkflowDefinitionException("cannot publish definition '" + definitionId + "' v"
                    + definitionVersion + ": no entry found in the store");
        }
        return markActive(key.get());
    }

    boolean markActive(DefinitionKey key) {
        String definitionId = key.definitionId();
        long definitionVersion = key.definitionVersion();
        Objects.requireNonNull(definitionId, "definitionId");
        StoredEntry entry = byKey.get(key);
        if (entry == null) {
            throw new WorkflowDefinitionException("cannot publish definition '" + definitionId + "' v"
                    + definitionVersion + ": no entry found in the store");
        }
        boolean flipped = entry.status().compareAndSet(ActivationStatus.PUBLISHING, ActivationStatus.AVAILABLE);
        if (flipped) refreshCachedStatus(key);
        return flipped;
    }

    /**
     * Returns an immutable snapshot of all entries currently in the store, across all statuses.
     *
     * @return an immutable collection of refs; never {@code null}; may be empty
     */
    public Collection<CompiledDefinitionRef> list() {
        return List.copyOf(byKey.entrySet().stream()
                .filter(e -> WorkflowScopes.DEFAULT.equals(e.getKey().scopeKey()))
                .map(java.util.Map.Entry::getValue)
                .map(entry -> new CompiledDefinitionRef(
                        entry.compiled().definition().definitionId(),
                        entry.compiled().definition().definitionVersion(),
                        entry.compiled().planHash(),
                        entry.status().get(),
                        entry.compiled().source()))
                .toList());
    }

    /**
     * Returns the provenance metadata for the stored entry with the given id and version, or
     * {@link Optional#empty()} if not found.
     *
     * @param definitionId the definition id to look up; must not be {@code null}
     * @param definitionVersion the version to look up
     * @return the stored metadata, or empty if not found
     */
    public Optional<SourceMetadata> sourceMetadata(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        return get(definitionId, definitionVersion)
                .map(entry -> entry.compiled().source());
    }

    private final ConcurrentHashMap<DefinitionKey, WorkflowDefinitionRecord> metadataByKey = new ConcurrentHashMap<>();

    void metadata(WorkflowDefinitionRecord record) {
        metadataByKey.put(record.key(), record);
        get(record.key()).ifPresent(entry -> entry.status()
                .set(ActivationStatus.valueOf(record.status().name())));
    }

    void candidate(DefinitionKey key, CompiledDefinition compiled, String format) {
        candidate(DefaultWorkflowDefinitionService.record(key, compiled, format, Map.of()), compiled);
    }

    void candidate(WorkflowDefinitionRecord record, CompiledDefinition compiled) {
        DefinitionKey key = record.key();
        // Validate all metadata before publishing the candidate; metadata must not reset a concurrent claim.
        storeCandidate(key, compiled);
        metadataByKey.put(key, record);
        refreshCachedStatus(key);
    }

    private void refreshCachedStatus(DefinitionKey key) {
        metadataByKey.computeIfPresent(key, (ignored, previous) -> {
            DefinitionStatus status =
                    DefinitionStatus.valueOf(currentStatus(key).name());
            return previous.status() == status
                    ? previous
                    : new WorkflowDefinitionRecord(
                            previous.key(),
                            previous.kind(),
                            previous.contractName(),
                            previous.stateTypeName(),
                            previous.planHash(),
                            status,
                            previous.provenance(),
                            previous.documentFormat(),
                            previous.createdAt(),
                            Instant.now(),
                            previous.nameBinding());
        });
    }

    List<WorkflowDefinitionRecord> records(DefinitionFilter filter) {
        return metadataByKey.values().stream()
                .filter(r -> filter.scopeKey() == null
                        || filter.scopeKey().equals(r.key().scopeKey()))
                .filter(r -> filter.definitionId() == null
                        || filter.definitionId().equals(r.key().definitionId()))
                .map(r -> get(r.key())
                        .map(e -> new WorkflowDefinitionRecord(
                                r.key(),
                                r.kind(),
                                r.contractName(),
                                r.stateTypeName(),
                                r.planHash(),
                                DefinitionStatus.valueOf(e.status().get().name()),
                                r.provenance(),
                                r.documentFormat(),
                                r.createdAt(),
                                r.statusChangedAt(),
                                r.nameBinding()))
                        .orElse(r))
                .filter(r -> filter.statuses() == null || filter.statuses().contains(r.status()))
                .sorted(java.util.Comparator.comparing(
                                (WorkflowDefinitionRecord r) -> r.key().definitionId())
                        .thenComparingLong(r -> r.key().definitionVersion()))
                .toList();
    }

    Optional<SourceMetadata> sourceMetadata(DefinitionKey key) {
        return Optional.ofNullable(metadataByKey.get(key))
                .map(r -> {
                    var p = r.provenance();
                    return new SourceMetadata(
                            p.sourceType(), p.uri(), p.revision(), p.author(), p.uploadId(), p.loadedAt());
                })
                .or(() -> get(key).map(e -> e.compiled().source()));
    }
}
