// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe store for candidate and active document-backed workflow definitions.
 *
 * <p>The store tracks the lifecycle of each loaded definition using an {@link ActivationStatus}
 * that is separate from the {@link dev.vertique.workflow.registry.WorkflowRegistry}'s version
 * ordering. The store's status is informational — it never overrides registry semantics.
 *
 * <p>Internal storage: a {@link ConcurrentHashMap} keyed on {@link DefinitionKey} (definitionId
 * + definitionVersion). Each entry is a {@link StoredEntry} holding the compiled definition and
 * an {@link AtomicReference} to the current status.
 *
 * <p>Thread-safety contract:
 * <ul>
 *   <li>{@link #storeCandidate} fails atomically on duplicate key — the store either accepts the
 *       entry or throws, with no partial state.</li>
 *   <li>{@link #tryClaimForActivation} atomically CAS-flips {@code CANDIDATE -> ACTIVATING}. Only
 *       one concurrent caller wins the claim; others see the current status and must decide whether
 *       to wait, return idempotently, or throw.</li>
 *   <li>{@link #markActive} atomically transitions {@code ACTIVATING -> ACTIVE} and then marks all
 *       lower-version {@code ACTIVE} entries for the same id as {@code SUPERSEDED}. A post-CAS
 *       re-scan (W2 fix) closes the concurrent race where two threads both pass the pre-CAS check
 *       while their peer is still {@code ACTIVATING}: the lower-version thread self-CASes back to
 *       {@code SUPERSEDED}, ensuring exactly one {@code ACTIVE} entry per definitionId at all
 *       times.</li>
 *   <li>{@link #resetToCandidate} atomically transitions {@code ACTIVATING -> CANDIDATE} so that a
 *       failed activation can be retried.</li>
 *   <li>{@link #list} returns a snapshot; mutations after the call are not reflected.</li>
 * </ul>
 */
@Singleton
public final class WorkflowDefinitionStore {

    // --- Internal key ---

    /**
     * Composite key for the {@code byKey} map: {@code (definitionId, definitionVersion)}.
     *
     * @param definitionId the workflow definition id; non-null
     * @param definitionVersion the definition version
     */
    private record DefinitionKey(String definitionId, long definitionVersion) {}

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

    // --- Test hook (package-private) ---

    /**
     * Test-only hook invoked inside {@link #markActive} between the pre-CAS scan and the CAS step.
     *
     * <p>Used by concurrency tests to deterministically pin the race window between the point where
     * a thread decides "no higher ACTIVE entry exists" and the point where it flips
     * {@code ACTIVATING → ACTIVE}. This allows tests to reproduce the exact interleaving that the
     * post-CAS re-scan (W2 fix) is designed to close.
     *
     * <p>Production code never sets this field. Package-private so only tests in the same package
     * ({@code dev.vertique.workflow.definition.service}) can wire it.
     */
    volatile Runnable preCasHookForTesting = null;

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
     */
    public void storeCandidate(CompiledDefinition compiled) {
        Objects.requireNonNull(compiled, "compiled");
        String id = compiled.definition().definitionId();
        long version = compiled.definition().definitionVersion();
        DefinitionKey key = new DefinitionKey(id, version);
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
        Objects.requireNonNull(definitionId, "definitionId");
        return Optional.ofNullable(byKey.get(new DefinitionKey(definitionId, definitionVersion)));
    }

    /**
     * Atomically tries to claim the entry at {@code (definitionId, definitionVersion)} for
     * activation by CAS-flipping its status from {@link ActivationStatus#CANDIDATE} to
     * {@link ActivationStatus#ACTIVATING}.
     *
     * <p>Only one concurrent caller wins the claim. The winner receives {@code true} and must
     * subsequently call either {@link #markActive} (on successful registry registration) or
     * {@link #resetToCandidate} (if registration fails) to release the claim. Losers receive
     * {@code false} and must inspect the current status to determine the appropriate response.
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to claim
     * @return {@code true} if this caller won the CAS (CANDIDATE to ACTIVATING); {@code false} if
     *     the CAS failed because another thread already changed the status
     * @throws WorkflowDefinitionException if no entry is found for the given id/version
     */
    public boolean tryClaimForActivation(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        DefinitionKey key = new DefinitionKey(definitionId, definitionVersion);
        StoredEntry entry = byKey.get(key);
        if (entry == null) {
            throw new WorkflowDefinitionException("cannot activate definition '" + definitionId + "' v"
                    + definitionVersion + ": not found in the store; call load() first");
        }
        // Attempt atomic CANDIDATE -> ACTIVATING flip; return true only if this caller won
        return entry.status().compareAndSet(ActivationStatus.CANDIDATE, ActivationStatus.ACTIVATING);
    }

    /**
     * Returns the current {@link ActivationStatus} of the entry at
     * {@code (definitionId, definitionVersion)}, or {@code null} if not found.
     *
     * <p>Used by callers after a failed {@link #tryClaimForActivation} to determine why the CAS
     * did not succeed (already ACTIVE, SUPERSEDED, or ACTIVATING by another thread).
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to query
     * @return the current status, or {@code null} if the entry is not found
     */
    public ActivationStatus currentStatus(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        DefinitionKey key = new DefinitionKey(definitionId, definitionVersion);
        StoredEntry entry = byKey.get(key);
        return entry != null ? entry.status().get() : null;
    }

    /**
     * Resets the status of the entry at {@code (definitionId, definitionVersion)} from
     * {@link ActivationStatus#ACTIVATING} back to {@link ActivationStatus#CANDIDATE}, allowing a
     * subsequent activation attempt to retry.
     *
     * <p>Called by {@link DefaultWorkflowDefinitionService} when {@code registry.register(...)}
     * throws after the claim was won, to release the claim so the caller can retry.
     *
     * @param definitionId the definition id; must not be {@code null}
     * @param definitionVersion the version to reset
     */
    public void resetToCandidate(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        DefinitionKey key = new DefinitionKey(definitionId, definitionVersion);
        StoredEntry entry = byKey.get(key);
        if (entry != null) {
            entry.status().compareAndSet(ActivationStatus.ACTIVATING, ActivationStatus.CANDIDATE);
        }
    }

    /**
     * Atomically transitions the status of the entry at {@code (definitionId, definitionVersion)}
     * from {@link ActivationStatus#ACTIVATING} to either {@link ActivationStatus#ACTIVE} or
     * {@link ActivationStatus#SUPERSEDED}, depending on whether a higher-version active entry
     * exists for the same {@code definitionId}.
     *
     * <h2>Activation lifecycle semantics</h2>
     * <ul>
     *   <li>If no other {@code ACTIVE} entry exists for {@code definitionId}, or the existing
     *       {@code ACTIVE} entry has a <em>lower</em> version, this entry is marked
     *       {@code ACTIVE} and all lower-version {@code ACTIVE} entries are superseded.</li>
     *   <li>If an {@code ACTIVE} entry with a <em>higher</em> version already exists (detected
     *       either before or after the CAS), this entry is immediately marked
     *       {@code SUPERSEDED}. The higher-version entry remains {@code ACTIVE}. The entry is
     *       still recorded for audit — the store tracks every activation attempt regardless of
     *       whether it becomes the current active version.</li>
     * </ul>
     *
     * <h2>Race-safety: post-CAS re-scan (W2 fix)</h2>
     * <p>Without additional checks, two threads activating v1 and v2 concurrently could both
     * pass the pre-CAS "no higher ACTIVE" scan (both peers are still ACTIVATING), then both
     * CAS-flip to ACTIVE, leaving two concurrent ACTIVE entries — an invariant violation. The
     * fix adds a <em>post-CAS re-scan</em>:
     * <ol>
     *   <li>Pre-CAS: scan for a higher-version ACTIVE entry. If found, CAS to SUPERSEDED and
     *       return.</li>
     *   <li>CAS ACTIVATING to ACTIVE.</li>
     *   <li>Post-CAS re-scan: scan for any other ACTIVE entry of the same definitionId.
     *       <ul>
     *         <li>If a peer with {@code version > mine} is ACTIVE (won the race): CAS my own
     *             status {@code ACTIVE} to {@code SUPERSEDED} and return {@code false}.</li>
     *         <li>If a peer with {@code version < mine} is ACTIVE (slower concurrent activation):
     *             CAS that peer's status {@code ACTIVE} to {@code SUPERSEDED}.</li>
     *       </ul>
     *   </li>
     * </ol>
     * <p>This protocol is lock-free. Both threads converge to the invariant (exactly one ACTIVE
     * per definitionId) regardless of interleaving order.
     *
     * @param definitionId the definition id to activate; must not be {@code null}
     * @param definitionVersion the version to activate
     * @return {@code true} if the entry became {@code ACTIVE} and remains ACTIVE after the
     *     post-CAS re-scan; {@code false} if the entry was already ACTIVE, was superseded
     *     before the CAS, or was self-superseded in the post-CAS re-scan
     * @throws WorkflowDefinitionException if no entry is found for the given id/version
     */
    public boolean markActive(String definitionId, long definitionVersion) {
        Objects.requireNonNull(definitionId, "definitionId");
        DefinitionKey key = new DefinitionKey(definitionId, definitionVersion);
        StoredEntry entry = byKey.get(key);
        if (entry == null) {
            throw new WorkflowDefinitionException("cannot activate definition '" + definitionId + "' v"
                    + definitionVersion + ": no entry found in the store");
        }

        // Step 1 — Pre-CAS scan: if a higher-version ACTIVE entry already exists, supersede
        // immediately without attempting the CAS.
        boolean higherActiveExists = byKey.entrySet().stream()
                .anyMatch(e -> e.getKey().definitionId().equals(definitionId)
                        && e.getKey().definitionVersion() > definitionVersion
                        && e.getValue().status().get() == ActivationStatus.ACTIVE);

        if (higherActiveExists) {
            // Transition ACTIVATING -> SUPERSEDED: registry resolves the higher-version active.
            entry.status().compareAndSet(ActivationStatus.ACTIVATING, ActivationStatus.SUPERSEDED);
            return false;
        }

        // Test-only hook: invoked after the pre-CAS scan but before the CAS. Production code
        // never sets this; it is used by concurrency tests to pin the race window deterministically.
        Runnable hook = preCasHookForTesting;
        if (hook != null) {
            hook.run();
        }

        // Step 2 — CAS ACTIVATING -> ACTIVE.
        boolean flipped = entry.status().compareAndSet(ActivationStatus.ACTIVATING, ActivationStatus.ACTIVE);
        if (!flipped) {
            return false;
        }

        // Step 3 — Post-CAS re-scan (W2 fix): a concurrent thread for a different version may
        // have raced through steps 1-2 simultaneously (both saw "no higher ACTIVE" before either
        // flipped). Re-scan now that we hold ACTIVE and reconcile.
        for (ConcurrentHashMap.Entry<DefinitionKey, StoredEntry> e : byKey.entrySet()) {
            DefinitionKey k = e.getKey();
            if (!k.definitionId().equals(definitionId) || k.definitionVersion() == definitionVersion) {
                continue;
            }
            ActivationStatus peerStatus = e.getValue().status().get();
            if (peerStatus != ActivationStatus.ACTIVE) {
                continue;
            }
            if (k.definitionVersion() > definitionVersion) {
                // A higher-version peer is also ACTIVE — we lost the race. CAS ourselves back to
                // SUPERSEDED and return false. The higher-version peer will sweep us on its own
                // post-CAS pass (or already has), but we must not leave two ACTIVE entries.
                entry.status().compareAndSet(ActivationStatus.ACTIVE, ActivationStatus.SUPERSEDED);
                return false;
            } else {
                // A lower-version peer is still ACTIVE (its ACTIVATING->ACTIVE CAS beat the
                // supersession sweep from its own markActive call, or it raced in concurrently).
                // CAS it to SUPERSEDED — we are the higher version.
                e.getValue().status().compareAndSet(ActivationStatus.ACTIVE, ActivationStatus.SUPERSEDED);
            }
        }
        return true;
    }

    /**
     * Returns an immutable snapshot of all entries currently in the store, across all statuses.
     *
     * @return an immutable collection of refs; never {@code null}; may be empty
     */
    public Collection<CompiledDefinitionRef> list() {
        return List.copyOf(byKey.values().stream()
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
}
