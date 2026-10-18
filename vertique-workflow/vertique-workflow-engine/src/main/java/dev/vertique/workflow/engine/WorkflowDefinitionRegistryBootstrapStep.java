// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Restores global document membership after migrations and before BOOTSTRAP verticle deployment. */
@Singleton
final class WorkflowDefinitionRegistryBootstrapStep implements ApplicationStartupStep {
    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;

    @Inject
    WorkflowDefinitionRegistryBootstrapStep(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue) {
        this.store = store;
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
    }

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.BOOTSTRAP;
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE / 2;
    }

    @Override
    public Future<Void> start() {
        if (store.isEmpty()) return Future.succeededFuture();
        // Registry construction invokes static contributors; keep their parsing off the event loop.
        return work(registry::get).compose(local -> reconcileOrigins()
                .compose(ignored ->
                        store.orElseThrow().list(new DefinitionFilter(DefinitionFilter.TenantScope.GLOBAL, null, null)))
                .compose(records -> restoreAll(local, records).compose(verified -> recomputeGlobal(records)
                        .compose(ignored -> refreshCommittedMetadata(verified)))));
    }

    private Future<List<VerifiedDocument>> restoreAll(WorkflowRegistry local, List<WorkflowDefinitionRecord> records) {
        List<VerifiedDocument> verified = new ArrayList<>();
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord record : records) {
            if (record.kind() == DefinitionKind.DOCUMENT)
                chain = chain.compose(
                        ignored -> queue.submit(record.key().definitionId(), () -> restore(local, record.key()))
                                .map(restored -> {
                                    restored.ifPresent(verified::add);
                                    return (Void) null;
                                }));
        }
        return chain.map(ignored -> List.copyOf(verified));
    }

    private Future<Void> recomputeGlobal(List<WorkflowDefinitionRecord> records) {
        List<String> definitionIds = records.stream()
                .filter(record -> record.kind() == DefinitionKind.DOCUMENT)
                .map(record -> record.key().definitionId())
                .distinct()
                .sorted()
                .toList();
        Future<Void> chain = Future.succeededFuture();
        for (String definitionId : definitionIds)
            chain = chain.compose(ignored ->
                    queue.submit(definitionId, () -> store.orElseThrow().recomputeActive(null, definitionId)));
        return chain;
    }

    private Future<Void> refreshCommittedMetadata(List<VerifiedDocument> verified) {
        Future<Void> chain = Future.succeededFuture();
        for (VerifiedDocument restored : verified) {
            DefinitionKey key = restored.record().key();
            chain = chain.compose(ignored -> queue.submit(
                    key.definitionId(), () -> store.orElseThrow().find(key).compose(found -> {
                        WorkflowDefinitionRecord record = found.orElseThrow(
                                () -> failure(Reason.INVALID_TRANSITION, key, "DOCUMENT row disappeared"));
                        if (record.kind() != DefinitionKind.DOCUMENT
                                || !record.planHash().equals(restored.record().planHash()))
                            throw failure(Reason.DRIFT, key, "DOCUMENT changed after startup verification");
                        return work(() -> {
                            restored.loader().onRestored(record, restored.definition());
                            return (Void) null;
                        });
                    })));
        }
        return chain;
    }

    private Future<Void> reconcileOrigins() {
        List<DocumentOrigin> origins = loaders.stream()
                .flatMap(loader -> loader.documentOrigins().stream())
                .filter(origin -> origin.key().tenantKey() == null)
                .sorted(Comparator.comparing(
                                (DocumentOrigin origin) -> origin.key().definitionId())
                        .thenComparingLong(origin -> origin.key().definitionVersion()))
                .toList();
        Future<Void> chain = Future.succeededFuture();
        for (DocumentOrigin origin : origins) {
            chain = chain.compose(ignored -> queue.submit(origin.key().definitionId(), () -> {
                WorkflowDefinitionRecord source = new WorkflowDefinitionRecord(
                        origin.key(),
                        DefinitionKind.DOCUMENT,
                        origin.planHash(),
                        DefinitionStatus.SUPERSEDED,
                        origin.provenance(),
                        origin.document().documentFormat(),
                        origin.provenance().loadedAt(),
                        origin.provenance().loadedAt());
                return store.orElseThrow().find(origin.key()).compose(previous -> store.orElseThrow()
                        .upsertDocument(source, origin.document())
                        .compose(outcome -> reconcileResult(origin, previous, outcome)));
            }));
        }
        return chain;
    }

    private static Future<Void> reconcileResult(
            DocumentOrigin origin, Optional<WorkflowDefinitionRecord> previous, ReconcileOutcome outcome) {
        if (outcome == ReconcileOutcome.INSERTED || outcome == ReconcileOutcome.UNCHANGED)
            return Future.succeededFuture();
        Reason reason = outcome == ReconcileOutcome.DRIFT
                ? Reason.DRIFT
                : outcome == ReconcileOutcome.OWNER_CONFLICT ? Reason.OWNER_CONFLICT : Reason.INVALID_TRANSITION;
        String stored = previous.map(row -> "stored hash " + row.planHash() + " from " + row.provenance())
                .orElse("stored source unavailable");
        return Future.failedFuture(failure(
                reason,
                origin.key(),
                "static DOCUMENT reconciliation " + outcome + ": " + stored + "; static hash " + origin.planHash()
                        + " from " + origin.provenance()));
    }

    private Future<Optional<VerifiedDocument>> restore(WorkflowRegistry local, DefinitionKey key) {
        return store.orElseThrow().find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "DOCUMENT row disappeared"));
            if (record.kind() != DefinitionKind.DOCUMENT)
                throw failure(Reason.INVALID_TRANSITION, key, "DOCUMENT kind changed before restoration");
            if (record.status() == DefinitionStatus.CANDIDATE || record.status() == DefinitionStatus.RETIRED) {
                for (PersistedDefinitionLoader loader : loaders)
                    if (loader.supports(record.documentFormat())) loader.onMetadata(record);
                return Future.succeededFuture(Optional.empty());
            }
            if (record.status() != DefinitionStatus.ACTIVE && record.status() != DefinitionStatus.SUPERSEDED)
                return Future.succeededFuture(Optional.empty());
            List<PersistedDefinitionLoader> matches = loaders.stream()
                    .filter(loader -> loader.supports(record.documentFormat()))
                    .toList();
            if (matches.size() != 1)
                throw failure(
                        Reason.LOADER_MISSING,
                        key,
                        "DOCUMENT format " + record.documentFormat() + " requires exactly one loader; found "
                                + matches.size());
            PersistedDefinitionLoader loader = matches.getFirst();
            return store.orElseThrow()
                    .findDocument(key)
                    .compose(document -> work(() -> {
                        WorkflowDefinitionDocument source = document.orElseThrow(
                                () -> failure(Reason.LOADER_MISSING, key, "DOCUMENT source is missing"));
                        WorkflowDefinition<?, ?> definition;
                        String hash;
                        try {
                            definition = loader.load(record, source);
                            hash = loader.planHash(definition);
                        } catch (RuntimeException failure) {
                            throw new WorkflowDefinitionException(
                                    "Cannot restore DOCUMENT " + key + " from stored source", failure);
                        }
                        if (!key.definitionId().equals(definition.definitionId())
                                || key.definitionVersion() != definition.definitionVersion()
                                || !record.planHash().equals(hash))
                            throw failure(
                                    Reason.DRIFT,
                                    key,
                                    "DOCUMENT stored hash " + record.planHash() + " differs from compiled hash "
                                            + hash);
                        Optional<RuntimeWorkflow> existing;
                        try {
                            existing = Optional.of(local.resolvePinned(key.definitionId(), key.definitionVersion()));
                        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absence) {
                            existing = Optional.empty();
                        }
                        if (existing.isPresent()) {
                            if (!record.planHash()
                                    .equals(existing.orElseThrow().plan().planHash()))
                                throw failure(
                                        Reason.DRIFT,
                                        key,
                                        "DOCUMENT stored hash " + record.planHash() + " differs from registered hash "
                                                + existing.orElseThrow().plan().planHash());
                        } else local.register(definition);
                        loader.onRestored(record, definition);
                        return Optional.of(new VerifiedDocument(loader, record, definition));
                    }));
        });
    }

    private static <T> Future<T> work(Supplier<T> action) {
        var context = Vertx.currentContext();
        if (context != null) return context.executeBlocking(action::get, false);
        try {
            return Future.succeededFuture(action.get());
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String detail) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + detail);
    }

    private record VerifiedDocument(
            PersistedDefinitionLoader loader, WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {}
}
