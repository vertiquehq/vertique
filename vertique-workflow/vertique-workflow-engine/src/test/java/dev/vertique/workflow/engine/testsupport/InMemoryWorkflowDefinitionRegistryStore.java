// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionFailure;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link WorkflowDefinitionRegistryStore} that keeps its rows in memory, for tests that need a store but no database.
 *
 * <p>Every operation completes before it returns and is serialized on the store. The store keeps the rows, the
 * document texts, the publication tokens and the activation records, and applies the status rules of the interface.
 * It holds no instances, so it never reports pinned instances, and it has no staleness bound, so a deprecated row
 * retires at once and {@link #resetStalePublishing(String)} changes nothing. Code rows are seeded through
 * {@link #upsertCode(WorkflowDefinitionRecord)}, or for a whole registry through {@link #seedCodeFrom}.
 */
public class InMemoryWorkflowDefinitionRegistryStore implements WorkflowDefinitionRegistryStore {
    private final Map<DefinitionKey, WorkflowDefinitionRecord> rows = new LinkedHashMap<>();
    private final Map<DefinitionKey, WorkflowDefinitionDocument> documents = new LinkedHashMap<>();
    private final Map<DefinitionKey, UUID> tokens = new LinkedHashMap<>();
    private final Map<ActivationSlot, DefinitionActivation> activations = new LinkedHashMap<>();

    private record ActivationSlot(String scopeKey, String definitionId) {}

    /** Creates an empty store. */
    public InMemoryWorkflowDefinitionRegistryStore() {}

    /**
     * Seeds a code row for every default-scope definition the registry holds and makes the highest version of each id
     * the default scope's current version, as the startup of an engine over a durable registry does for the code it
     * deploys. Rows that exist already are reconciled like any other code row.
     *
     * @param registry the registry whose default-scope definitions are the deployed code
     * @return this store
     */
    public InMemoryWorkflowDefinitionRegistryStore seedCodeFrom(WorkflowRegistry registry) {
        for (RuntimeWorkflow runtime : registry.allRegistered()) seedCode(runtime);
        return this;
    }

    /**
     * Seeds the code row of one default-scope runtime and makes its version the default scope's current version unless
     * the default scope already names a higher one. A runtime of another scope is ignored.
     *
     * @param runtime the deployed code definition
     * @return this store
     */
    public InMemoryWorkflowDefinitionRegistryStore seedCode(RuntimeWorkflow runtime) {
        if (!WorkflowScopes.DEFAULT.equals(runtime.definitionScopeKey())) return this;
        Instant now = Instant.now();
        DefinitionKey key = new DefinitionKey(
                WorkflowScopes.DEFAULT,
                runtime.plan().definitionId(),
                runtime.plan().definitionVersion());
        upsertCode(
                new WorkflowDefinitionRecord(
                        key,
                        DefinitionKind.CODE,
                        runtime.contractName(),
                        runtime.stateType().getName(),
                        storableHash(runtime.plan().planHash()),
                        DefinitionStatus.AVAILABLE,
                        new DefinitionProvenance("code", null, null, null, null, now),
                        null,
                        now,
                        now,
                        Map.of(),
                        null),
                true);
        return this;
    }

    @Override
    public synchronized Future<Void> insertCandidate(
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(document, "document");
        if (record.status() != DefinitionStatus.CANDIDATE)
            return Future.failedFuture(invalid(record.key(), "insert requires CANDIDATE"));
        if (rows.containsKey(record.key()))
            return Future.failedFuture(invalid(record.key(), "definition already exists"));
        WorkflowDefinitionContractMismatchException mismatch = defaultContractRefusal(record);
        if (mismatch != null) return Future.failedFuture(mismatch);
        rows.put(record.key(), record);
        documents.put(record.key(), document);
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Optional<WorkflowDefinitionRecord>> find(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return Future.succeededFuture(Optional.ofNullable(rows.get(key)));
    }

    @Override
    public synchronized Future<Optional<WorkflowDefinitionDocument>> findDocument(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        return Future.succeededFuture(Optional.ofNullable(documents.get(key)));
    }

    @Override
    public synchronized Future<List<WorkflowDefinitionRecord>> list(DefinitionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        List<WorkflowDefinitionRecord> selected = rows.values().stream()
                .filter(row -> filter.scopeKey() == null
                        || filter.scopeKey().equals(row.key().scopeKey()))
                .filter(row -> filter.definitionId() == null
                        || filter.definitionId().equals(row.key().definitionId()))
                .filter(row -> filter.statuses() == null || filter.statuses().contains(row.status()))
                .sorted(Comparator.comparing(
                                (WorkflowDefinitionRecord row) -> row.key().definitionId())
                        .thenComparing(row -> row.key().scopeKey())
                        .thenComparingLong(row -> row.key().definitionVersion()))
                .toList();
        return Future.succeededFuture(selected);
    }

    @Override
    public synchronized Future<ReconcileOutcome> upsertDocument(
            WorkflowDefinitionRecord record, WorkflowDefinitionDocument document, boolean activateDefault) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(document, "document");
        WorkflowDefinitionRecord old = rows.get(record.key());
        if (old == null) {
            rows.put(record.key(), withStatus(record, DefinitionStatus.AVAILABLE));
            documents.put(record.key(), document);
            activateFirstPublication(
                    record.key(), activateDefault, record.provenance().loadedAt());
            return Future.succeededFuture(ReconcileOutcome.INSERTED);
        }
        if (old.kind() != DefinitionKind.DOCUMENT) return Future.succeededFuture(ReconcileOutcome.KIND_CONFLICT);
        if (!old.planHash().equals(record.planHash()) || !document.equals(documents.get(record.key())))
            return Future.succeededFuture(ReconcileOutcome.DRIFT);
        rows.put(record.key(), withProvenance(old, record));
        return Future.succeededFuture(ReconcileOutcome.UNCHANGED);
    }

    @Override
    public synchronized Future<ReconcileOutcome> upsertCode(WorkflowDefinitionRecord record, boolean activateDefault) {
        Objects.requireNonNull(record, "record");
        if (record.kind() != DefinitionKind.CODE)
            return Future.failedFuture(invalid(record.key(), "upsertCode requires a CODE record"));
        WorkflowDefinitionRecord old = rows.get(record.key());
        if (old == null) {
            rows.put(record.key(), withStatus(record, DefinitionStatus.AVAILABLE));
            activateFirstPublication(
                    record.key(), activateDefault, record.provenance().loadedAt());
            return Future.succeededFuture(ReconcileOutcome.INSERTED);
        }
        if (old.kind() != DefinitionKind.CODE) return Future.succeededFuture(ReconcileOutcome.KIND_CONFLICT);
        if (old.planHash().equals(record.planHash())) {
            rows.put(record.key(), withProvenance(old, record));
            return Future.succeededFuture(ReconcileOutcome.UNCHANGED);
        }
        // No instance is held here, so the stored hash is always free to be replaced.
        rows.put(
                record.key(),
                new WorkflowDefinitionRecord(
                        old.key(),
                        old.kind(),
                        record.contractName(),
                        record.stateTypeName(),
                        record.planHash(),
                        old.status(),
                        record.provenance(),
                        old.documentFormat(),
                        old.createdAt(),
                        old.statusChangedAt(),
                        record.nameBinding(),
                        old.failure()));
        return Future.succeededFuture(ReconcileOutcome.REHASHED);
    }

    @Override
    public synchronized Future<ActivationClaim> claim(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        WorkflowDefinitionRecord row = rows.get(key);
        if (row == null) return Future.failedFuture(invalid(key, "definition not found"));
        if (row.status() != DefinitionStatus.CANDIDATE)
            return Future.failedFuture(invalid(key, "cannot claim status " + row.status()));
        ActivationClaim claim = new ActivationClaim(key, UUID.randomUUID());
        tokens.put(key, claim.token());
        rows.put(key, withStatus(row, DefinitionStatus.PUBLISHING));
        return Future.succeededFuture(claim);
    }

    @Override
    public synchronized Future<Void> complete(ActivationClaim claim) {
        Objects.requireNonNull(claim, "claim");
        WorkflowDefinitionRecord row = rows.get(claim.key());
        if (row == null
                || row.status() != DefinitionStatus.PUBLISHING
                || !claim.token().equals(tokens.get(claim.key())))
            return Future.failedFuture(new WorkflowDefinitionLifecycleException(
                    Reason.STALE_CLAIM, claim.key(), "publish token is obsolete"));
        tokens.remove(claim.key());
        rows.put(claim.key(), withStatus(row, DefinitionStatus.AVAILABLE));
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> release(ActivationClaim claim) {
        Objects.requireNonNull(claim, "claim");
        WorkflowDefinitionRecord row = rows.get(claim.key());
        if (row != null
                && row.status() == DefinitionStatus.PUBLISHING
                && claim.token().equals(tokens.get(claim.key()))) {
            tokens.remove(claim.key());
            rows.put(claim.key(), withStatus(row, DefinitionStatus.CANDIDATE));
        }
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> transition(DefinitionKey key, DefinitionStatus from, DefinitionStatus to) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        boolean supported = (from == DefinitionStatus.AVAILABLE && to == DefinitionStatus.DEPRECATED)
                || (to == DefinitionStatus.AVAILABLE
                        && (from == DefinitionStatus.DEPRECATED || from == DefinitionStatus.RETIRED));
        if (!supported)
            return Future.failedFuture(invalid(key, "transition " + from + " to " + to + " is not supported"));
        WorkflowDefinitionRecord row = rows.get(key);
        if (row == null) return Future.failedFuture(invalid(key, "definition not found"));
        if (row.status() != from)
            return Future.failedFuture(invalid(key, "expected status " + from + " but found " + row.status()));
        if (to == DefinitionStatus.DEPRECATED && namedByDefaultActivation(key))
            return Future.failedFuture(invalid(key, "the default scope's current version cannot be deprecated"));
        rows.put(key, withStatus(row, to));
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> retire(DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        WorkflowDefinitionRecord row = rows.get(key);
        if (row == null) return Future.failedFuture(invalid(key, "definition not found"));
        if (row.status() != DefinitionStatus.CANDIDATE && row.status() != DefinitionStatus.DEPRECATED)
            return Future.failedFuture(invalid(key, "cannot retire status " + row.status()));
        if (namedByDefaultActivation(key))
            return Future.failedFuture(invalid(key, "the default scope's current version cannot be retired"));
        rows.put(key, withStatus(row, DefinitionStatus.RETIRED));
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> resetStalePublishing(String definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> putActivation(DefinitionActivation activation) {
        Objects.requireNonNull(activation, "activation");
        DefinitionKey target =
                new DefinitionKey(activation.targetScopeKey(), activation.definitionId(), activation.version());
        WorkflowDefinitionRecord row = rows.get(target);
        if (row == null) return Future.failedFuture(invalid(target, "definition not found"));
        if (row.status() != DefinitionStatus.AVAILABLE)
            return Future.failedFuture(
                    invalid(target, "cannot activate status " + row.status() + ", only AVAILABLE can be activated"));
        WorkflowDefinitionContractMismatchException mismatch = scopeContractRefusal(activation, row);
        if (mismatch != null) return Future.failedFuture(mismatch);
        activations.put(new ActivationSlot(activation.scopeKey(), activation.definitionId()), activation);
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<Void> removeActivation(String scopeKey, String definitionId) {
        activations.remove(new ActivationSlot(scopeKey, definitionId));
        return Future.succeededFuture();
    }

    @Override
    public synchronized Future<List<DefinitionActivation>> listActivations(@Nullable String scopeKey) {
        List<DefinitionActivation> listed = activations.values().stream()
                .filter(activation -> scopeKey == null || scopeKey.equals(activation.scopeKey()))
                .sorted(Comparator.comparing(DefinitionActivation::definitionId)
                        .thenComparing(DefinitionActivation::scopeKey))
                .toList();
        return Future.succeededFuture(new ArrayList<>(listed));
    }

    @Override
    public synchronized Future<Void> recordFailure(DefinitionKey key, @Nullable DefinitionFailure failure) {
        Objects.requireNonNull(key, "key");
        WorkflowDefinitionRecord row = rows.get(key);
        if (row == null) return Future.failedFuture(invalid(key, "definition not found"));
        rows.put(
                key,
                new WorkflowDefinitionRecord(
                        row.key(),
                        row.kind(),
                        row.contractName(),
                        row.stateTypeName(),
                        row.planHash(),
                        row.status(),
                        row.provenance(),
                        row.documentFormat(),
                        row.createdAt(),
                        row.statusChangedAt(),
                        row.nameBinding(),
                        failure));
        return Future.succeededFuture();
    }

    /** The hash as a row stores it: a plan hash that is not 64 lowercase hexadecimal characters is digested first. */
    private static String storableHash(String planHash) {
        if (planHash.matches("[0-9a-f]{64}")) return planHash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(planHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The refusal for a candidate of another scope than the default one under another contract than the default's. */
    private @Nullable WorkflowDefinitionContractMismatchException defaultContractRefusal(
            WorkflowDefinitionRecord record) {
        String scope = record.key().scopeKey();
        if (WorkflowScopes.DEFAULT.equals(scope)) return null;
        String id = record.key().definitionId();
        if (rows.keySet().stream().anyMatch(k -> scope.equals(k.scopeKey()) && id.equals(k.definitionId())))
            return null;
        return rows.values().stream()
                .filter(row -> WorkflowScopes.DEFAULT.equals(row.key().scopeKey())
                        && id.equals(row.key().definitionId())
                        && !record.contractName().equals(row.contractName()))
                .findFirst()
                .map(defaultRow -> new WorkflowDefinitionContractMismatchException(
                        record.key(),
                        "Scope '" + scope + "' cannot load '" + id + "' under contract '"
                                + EchoedNames.bounded(record.contractName())
                                + "': the default scope holds it under contract '"
                                + EchoedNames.bounded(defaultRow.contractName()) + "'"))
                .orElse(null);
    }

    /** The refusal for pointing a scope at a default-scope version of another contract than the scope's own. */
    private @Nullable WorkflowDefinitionContractMismatchException scopeContractRefusal(
            DefinitionActivation activation, WorkflowDefinitionRecord target) {
        if (WorkflowScopes.DEFAULT.equals(activation.scopeKey())
                || !WorkflowScopes.DEFAULT.equals(activation.targetScopeKey())) return null;
        return rows.values().stream()
                .filter(row -> activation.scopeKey().equals(row.key().scopeKey())
                        && activation.definitionId().equals(row.key().definitionId())
                        && !target.contractName().equals(row.contractName()))
                .findFirst()
                .map(own -> new WorkflowDefinitionContractMismatchException(
                        target.key(),
                        "Scope '" + activation.scopeKey() + "' holds '" + activation.definitionId()
                                + "' under contract '" + EchoedNames.bounded(own.contractName())
                                + "', so it cannot follow the default scope's version, which implements contract '"
                                + EchoedNames.bounded(target.contractName()) + "'"))
                .orElse(null);
    }

    private boolean namedByDefaultActivation(DefinitionKey key) {
        if (!WorkflowScopes.DEFAULT.equals(key.scopeKey())) return false;
        DefinitionActivation current = activations.get(new ActivationSlot(WorkflowScopes.DEFAULT, key.definitionId()));
        return current != null && current.version() == key.definitionVersion();
    }

    private void activateFirstPublication(DefinitionKey key, boolean activateDefault, Instant at) {
        if (!activateDefault) return;
        ActivationSlot slot = new ActivationSlot(WorkflowScopes.DEFAULT, key.definitionId());
        DefinitionActivation current = activations.get(slot);
        if (current != null && current.version() >= key.definitionVersion()) return;
        activations.put(
                slot,
                new DefinitionActivation(
                        WorkflowScopes.DEFAULT,
                        key.definitionId(),
                        WorkflowScopes.DEFAULT,
                        key.definitionVersion(),
                        at));
    }

    private static WorkflowDefinitionRecord withStatus(WorkflowDefinitionRecord row, DefinitionStatus status) {
        return new WorkflowDefinitionRecord(
                row.key(),
                row.kind(),
                row.contractName(),
                row.stateTypeName(),
                row.planHash(),
                status,
                row.provenance(),
                row.documentFormat(),
                row.createdAt(),
                Instant.now(),
                row.nameBinding(),
                row.failure());
    }

    private static WorkflowDefinitionRecord withProvenance(
            WorkflowDefinitionRecord row, WorkflowDefinitionRecord source) {
        return new WorkflowDefinitionRecord(
                row.key(),
                row.kind(),
                row.contractName(),
                row.stateTypeName(),
                row.planHash(),
                row.status(),
                source.provenance(),
                row.documentFormat(),
                row.createdAt(),
                row.statusChangedAt(),
                row.nameBinding(),
                row.failure());
    }

    private static WorkflowDefinitionLifecycleException invalid(DefinitionKey key, String message) {
        return new WorkflowDefinitionLifecycleException(
                Reason.INVALID_TRANSITION, key, "Definition " + key + ": " + message);
    }
}
