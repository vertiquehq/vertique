// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.VersionKey;
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
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.DocumentOrigin;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles the code and the packaged documents with the durable registry, and restores its live membership,
 * after migrations and before BOOTSTRAP verticle deployment.
 *
 * <p>The step runs these phases in order, each definition id under its slot of the shared id queue:
 *
 * <ol>
 *   <li>It attributes the registrations that already exist when it starts, the packaged static documents and
 *       the application-contributed definitions registered while the registry was constructed, to the global
 *       owner, and it snapshots the code definitions in the {@link WorkflowCodeDefinitionCatalog}.
 *   <li>It records every packaged document and every code definition in the table. Drift, a conflicting kind, a
 *       conflicting owner, or a stored code definition that the deployed code no longer provides fails startup.
 *   <li>It restores every live document of every owner, deprecated ones included, and attributes each to its
 *       owner.
 *   <li>It recomputes the active version of every owner and id.
 *   <li>It applies the deprecation marks, and removes the registrations of retired rows when they are still
 *       attributed to the row's own owner.
 *   <li>It resets activation attempts that went stale, for every definition id of the table.
 *   <li>It hands the committed metadata, the rows just reset included, to the loaders.
 * </ol>
 */
@Singleton
final class WorkflowDefinitionRegistryBootstrapStep implements ApplicationStartupStep {
    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionRegistryBootstrapStep.class);
    private static final Set<DefinitionStatus> LIVE =
            Set.of(DefinitionStatus.ACTIVE, DefinitionStatus.SUPERSEDED, DefinitionStatus.DEPRECATED);
    private static final Set<DefinitionStatus> HOLDING = Set.of(
            DefinitionStatus.ACTIVE,
            DefinitionStatus.SUPERSEDED,
            DefinitionStatus.DEPRECATED,
            DefinitionStatus.ACTIVATING);
    private static final Comparator<WorkflowDefinitionRecord> LISTING_ORDER = Comparator.comparing(
                    (WorkflowDefinitionRecord record) -> record.key().definitionId())
            .thenComparing(record -> record.key().tenantKey(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(record -> record.key().definitionVersion());

    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final ManagedRegistrations registrations;
    private final WorkflowCodeDefinitionCatalog catalog;

    @Inject
    WorkflowDefinitionRegistryBootstrapStep(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog) {
        this.store = store;
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
        this.registrations = registrations;
        this.catalog = catalog;
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
        return work(this::prepare).compose(prepared -> {
            WorkflowRegistry local = prepared.registry();
            return reconcileOrigins(prepared.origins())
                    .compose(ignored -> reconcileCode(prepared.code()))
                    .compose(ignored -> listAll())
                    .compose(reconciled -> requireCode(reconciled)
                            .compose(ignored -> restoreAll(local, reconciled))
                            .compose(verified -> recompute(reconciled)
                                    .compose(ignored -> listAll())
                                    .compose(committed ->
                                            applyMembership(local, committed).compose(ignored -> resetStale(committed)))
                                    .compose(ignored -> handOffMetadata(verified))));
        });
    }

    /**
     * Attributes what registry construction registered, snapshots the code definitions and collects the packaged
     * documents. It runs on a worker thread.
     */
    private Prepared prepare() {
        WorkflowRegistry local = registry.get();
        // Only what registry construction registered is attributed; later direct registration is not.
        registrations.attributeExistingToGlobal(local);
        List<DocumentOrigin> origins = loaders.stream()
                .flatMap(loader -> loader.documentOrigins().stream())
                .filter(origin -> origin.key().tenantKey() == null)
                .sorted(Comparator.comparing(
                                (DocumentOrigin origin) -> origin.key().definitionId())
                        .thenComparingLong(origin -> origin.key().definitionVersion()))
                .toList();
        Set<VersionKey> originKeys = origins.stream()
                .map(origin ->
                        new VersionKey(origin.key().definitionId(), origin.key().definitionVersion()))
                .collect(Collectors.toSet());
        return new Prepared(local, origins, catalog.capture(local, originKeys));
    }

    private Future<List<WorkflowDefinitionRecord>> listAll() {
        return store.orElseThrow()
                .list(new DefinitionFilter(DefinitionFilter.TenantScope.ALL, null, null))
                .map(records -> records.stream().sorted(LISTING_ORDER).toList());
    }

    /** Records every packaged document, so that a stored row of the same key is compared with it. */
    private Future<Void> reconcileOrigins(List<DocumentOrigin> origins) {
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
                        .compose(outcome -> reconcileResult(source, previous, outcome)));
            }));
        }
        return chain;
    }

    /** Records every code definition that the deployed code provides. */
    private Future<Void> reconcileCode(List<CodeDefinition> code) {
        Instant startedAt = Instant.now();
        Future<Void> chain = Future.succeededFuture();
        for (CodeDefinition definition : code) {
            chain = chain.compose(ignored -> queue.submit(definition.definitionId(), () -> {
                DefinitionKey key;
                try {
                    key = new DefinitionKey(null, definition.definitionId(), definition.version());
                } catch (IllegalArgumentException invalid) {
                    return Future.failedFuture(new WorkflowDefinitionException(
                            "Code definition " + printable(definition.definitionId()) + " version "
                                    + definition.version() + " cannot be recorded as a durable definition because it"
                                    + " violates the definition key rules: " + invalid.getMessage(),
                            invalid));
                }
                WorkflowDefinitionRecord source = new WorkflowDefinitionRecord(
                        key,
                        DefinitionKind.CODE,
                        definition.planHash(),
                        DefinitionStatus.SUPERSEDED,
                        new DefinitionProvenance(
                                "code",
                                definition.definition() == null
                                        ? null
                                        : definition.definition().getClass().getName(),
                                null,
                                null,
                                null,
                                startedAt),
                        null,
                        startedAt,
                        startedAt);
                return store.orElseThrow().find(key).compose(previous -> store.orElseThrow()
                        .upsertCode(source)
                        .compose(outcome -> reconcileResult(source, previous, outcome)));
            }));
        }
        return chain;
    }

    private Future<Void> reconcileResult(
            WorkflowDefinitionRecord source, Optional<WorkflowDefinitionRecord> previous, ReconcileOutcome outcome) {
        DefinitionKey key = source.key();
        boolean code = source.kind() == DefinitionKind.CODE;
        String subject = code ? "code" : "static DOCUMENT";
        switch (outcome) {
            case INSERTED, UNCHANGED -> {
                return Future.succeededFuture();
            }
            case REHASHED -> {
                log.warn(
                        "The plan hash of code definition {} changed from {} to {}; no instance is pinned to this"
                                + " exact version, so the stored hash was replaced",
                        key,
                        previous.map(WorkflowDefinitionRecord::planHash).orElse("(unknown)"),
                        source.planHash());
                return Future.succeededFuture();
            }
            case OWNER_CONFLICT -> {
                return ownerConflict(source);
            }
            case KIND_CONFLICT -> {
                return Future.failedFuture(failure(
                        Reason.INVALID_TRANSITION,
                        key,
                        subject + " reconciliation " + outcome + ": the stored row is "
                                + previous.map(row -> row.kind() + " from " + row.provenance())
                                        .orElse("of another kind")
                                + ", but this start provides " + source.kind() + " from " + source.provenance()
                                + "; a definition id and version belongs either to code or to a document"));
            }
            case DRIFT -> {
                if (code)
                    return Future.failedFuture(failure(
                            Reason.DRIFT,
                            key,
                            "code reconciliation " + outcome + ": stored hash "
                                    + previous.map(WorkflowDefinitionRecord::planHash)
                                            .orElse("(unknown)")
                                    + " differs from deployed code hash " + source.planHash()
                                    + ", and instances pinned to this exact version are still running, waiting or"
                                    + " compensating; stored "
                                    + previous.map(row -> row.provenance().toString())
                                            .orElse("source unavailable")
                                    + ", deployed " + source.provenance()
                                    + ". Keep the code of this version unchanged until those instances finish, or"
                                    + " bump the version."));
                if (previous.filter(row -> row.planHash().equals(source.planHash()))
                        .isPresent())
                    return Future.failedFuture(failure(
                            Reason.DRIFT,
                            key,
                            "static DOCUMENT reconciliation " + outcome + ": the text digest of the packaged document"
                                    + " differs from the stored text under an equal plan hash " + source.planHash()
                                    + "; stored from "
                                    + previous.orElseThrow().provenance() + ", packaged from " + source.provenance()
                                    + ". Bump the version of the changed document or retire the old row."));
                String stored = previous.map(row -> "stored hash " + row.planHash() + " from " + row.provenance())
                        .orElse("stored source unavailable");
                return Future.failedFuture(failure(
                        Reason.DRIFT,
                        key,
                        "static DOCUMENT reconciliation " + outcome + ": " + stored + "; static hash "
                                + source.planHash() + " from " + source.provenance()));
            }
        }
        throw new IllegalStateException("Unhandled reconcile outcome " + outcome);
    }

    /** Fails with the owners that hold the definition id, so that the operator can see who stands in the way. */
    private Future<Void> ownerConflict(WorkflowDefinitionRecord source) {
        DefinitionKey key = source.key();
        return listAll().compose(rows -> {
            String holders = rows.stream()
                    .filter(row -> row.key().definitionId().equals(key.definitionId()))
                    .filter(row -> !Objects.equals(row.key().tenantKey(), key.tenantKey()))
                    .filter(row -> HOLDING.contains(row.status()))
                    .map(row -> owner(row.key().tenantKey()) + " (version "
                            + row.key().definitionVersion() + " " + row.status() + ")")
                    .distinct()
                    .collect(Collectors.joining(", "));
            return Future.failedFuture(failure(
                    Reason.OWNER_CONFLICT,
                    key,
                    "reconciliation OWNER_CONFLICT: the " + owner(key.tenantKey()) + " owner cannot take this id"
                            + " because it is held by "
                            + (holders.isEmpty() ? "another owner" : holders) + "; this start provides "
                            + source.kind() + " from " + source.provenance()));
        });
    }

    private static String owner(@Nullable String tenantKey) {
        return tenantKey == null ? "global" : tenantKey;
    }

    /**
     * A stored code row that is still live must still be provided by the deployed code. Retired and
     * candidate code rows may outlive their code.
     */
    private Future<Void> requireCode(List<WorkflowDefinitionRecord> rows) {
        for (WorkflowDefinitionRecord row : rows) {
            if (row.kind() != DefinitionKind.CODE || !LIVE.contains(row.status())) continue;
            if (catalog.find(row.key().definitionId(), row.key().definitionVersion())
                    .isEmpty())
                return Future.failedFuture(failure(
                        Reason.CODE_UNAVAILABLE,
                        row.key(),
                        "the stored " + row.status() + " CODE definition (hash " + row.planHash() + ", "
                                + row.provenance() + ") is not provided by the deployed code any more;"
                                + " restore the code, or retire the definition before removing its code"));
        }
        return Future.succeededFuture();
    }

    /** Restores every live document of every owner that the registry does not hold yet. */
    private Future<List<VerifiedDocument>> restoreAll(WorkflowRegistry local, List<WorkflowDefinitionRecord> rows) {
        List<VerifiedDocument> verified = new ArrayList<>();
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord row : rows) {
            if (row.kind() != DefinitionKind.DOCUMENT || !LIVE.contains(row.status())) continue;
            chain = chain.compose(ignored -> queue.submit(row.key().definitionId(), () -> restore(local, row.key()))
                    .map(restored -> {
                        restored.ifPresent(verified::add);
                        return (Void) null;
                    }));
        }
        return chain.map(ignored -> List.copyOf(verified));
    }

    /**
     * Recomputes the active version of every owner and id that has a row in the active or superseded
     * status, code and documents alike.
     */
    private Future<Void> recompute(List<WorkflowDefinitionRecord> rows) {
        TreeSet<OwnerId> owners = new TreeSet<>(Comparator.comparing(OwnerId::definitionId)
                .thenComparing(OwnerId::tenantKey, Comparator.nullsFirst(Comparator.naturalOrder())));
        for (WorkflowDefinitionRecord row : rows) {
            if (row.status() == DefinitionStatus.ACTIVE || row.status() == DefinitionStatus.SUPERSEDED)
                owners.add(new OwnerId(row.key().tenantKey(), row.key().definitionId()));
        }
        Future<Void> chain = Future.succeededFuture();
        for (OwnerId owner : owners)
            chain = chain.compose(ignored -> queue.submit(owner.definitionId(), () -> store.orElseThrow()
                    .recomputeActive(owner.tenantKey(), owner.definitionId())));
        return chain;
    }

    /**
     * Marks deprecated rows and removes retired ones from the registry, each only while the registry
     * still holds the runtime that was published for the row's own owner.
     */
    private Future<Void> applyMembership(WorkflowRegistry local, List<WorkflowDefinitionRecord> committed) {
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord row : committed) {
            DefinitionKey key = row.key();
            if (row.status() == DefinitionStatus.DEPRECATED)
                chain = chain.compose(ignored -> queue.submit(key.definitionId(), () -> {
                    RuntimeWorkflow held = held(local, key);
                    if (standing(key, held) == ManagedRegistrations.Standing.OWN)
                        local.markDeprecated(key.definitionId(), key.definitionVersion());
                    return Future.<Void>succeededFuture();
                }));
            else if (row.status() == DefinitionStatus.RETIRED)
                chain = chain.compose(ignored -> queue.submit(key.definitionId(), () -> {
                    RuntimeWorkflow held = held(local, key);
                    if (standing(key, held) == ManagedRegistrations.Standing.OWN) {
                        local.unregister(key.definitionId(), key.definitionVersion());
                        registrations.clear(key.definitionId(), key.definitionVersion(), held);
                    }
                    return Future.<Void>succeededFuture();
                }));
        }
        return chain;
    }

    /**
     * Classifies the runtime the registry holds for the row. A runtime of another owner or none at all is left
     * alone; one that nobody published is preserved and fails the start, because it cannot be attributed.
     */
    private ManagedRegistrations.Standing standing(DefinitionKey key, @Nullable RuntimeWorkflow held) {
        ManagedRegistrations.Standing standing =
                registrations.classify(key.tenantKey(), key.definitionId(), key.definitionVersion(), held);
        if (standing == ManagedRegistrations.Standing.UNATTRIBUTED)
            throw failure(
                    Reason.INVALID_TRANSITION,
                    key,
                    "the registered runtime was not published by the durable lifecycle for this definition, so the"
                            + " committed state cannot be applied to it");
        return standing;
    }

    private static @Nullable RuntimeWorkflow held(WorkflowRegistry local, DefinitionKey key) {
        try {
            return local.resolvePinned(key.definitionId(), key.definitionVersion());
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absent) {
            return null;
        }
    }

    /**
     * Resets the activation attempts that went stale, under the id lock, for every definition id that
     * the table holds an activation attempt for, whether or not code or a loader knows the id.
     */
    private Future<Void> resetStale(List<WorkflowDefinitionRecord> committed) {
        TreeSet<String> ids = committed.stream()
                .filter(row -> row.status() == DefinitionStatus.ACTIVATING)
                .map(row -> row.key().definitionId())
                .collect(Collectors.toCollection(TreeSet::new));
        Future<Void> chain = Future.succeededFuture();
        for (String id : ids)
            chain = chain.compose(
                    ignored -> queue.submit(id, () -> store.orElseThrow().resetStaleActivating(id)));
        return chain;
    }

    /**
     * Gives the loaders the committed metadata. The restored documents are read again after the
     * recomputation, and candidate and retired documents, the rows just reset included, are read after the reset.
     */
    private Future<Void> handOffMetadata(List<VerifiedDocument> verified) {
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
        return chain.compose(ignored -> listAll()).map(rows -> {
            for (WorkflowDefinitionRecord row : rows) {
                if (row.kind() != DefinitionKind.DOCUMENT
                        || (row.status() != DefinitionStatus.CANDIDATE && row.status() != DefinitionStatus.RETIRED))
                    continue;
                for (PersistedDefinitionLoader loader : loaders)
                    if (loader.supports(row.documentFormat())) loader.onMetadata(row);
            }
            return (Void) null;
        });
    }

    private Future<Optional<VerifiedDocument>> restore(WorkflowRegistry local, DefinitionKey key) {
        return store.orElseThrow().find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "DOCUMENT row disappeared"));
            if (record.kind() != DefinitionKind.DOCUMENT)
                throw failure(Reason.INVALID_TRANSITION, key, "DOCUMENT kind changed before restoration");
            // A row that left the live statuses meanwhile is metadata only and is handed over with the others.
            if (!LIVE.contains(record.status())) return Future.succeededFuture(Optional.empty());
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
                        Optional<RuntimeWorkflow> existing = Optional.ofNullable(held(local, key));
                        if (existing.isPresent()) {
                            if (!record.planHash()
                                    .equals(existing.orElseThrow().plan().planHash()))
                                throw failure(
                                        Reason.DRIFT,
                                        key,
                                        "DOCUMENT stored hash " + record.planHash() + " differs from registered hash "
                                                + existing.orElseThrow().plan().planHash());
                        } else {
                            local.register(definition);
                            registrations.attribute(
                                    key.tenantKey(),
                                    key.definitionId(),
                                    key.definitionVersion(),
                                    local.resolvePinned(key.definitionId(), key.definitionVersion()));
                        }
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

    /** Renders text for a message with every non-printable character escaped, so that it cannot forge log lines. */
    private static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            boolean visible = type != Character.CONTROL
                    && type != Character.FORMAT
                    && type != Character.SURROGATE
                    && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR
                    && type != Character.PRIVATE_USE
                    && type != Character.UNASSIGNED;
            if (visible) {
                out.appendCodePoint(codePoint);
            } else {
                for (char unit : Character.toChars(codePoint)) out.append(String.format("\\u%04X", (int) unit));
            }
        });
        return out.toString();
    }

    private static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String detail) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + detail);
    }

    private record VerifiedDocument(
            PersistedDefinitionLoader loader, WorkflowDefinitionRecord record, WorkflowDefinition<?, ?> definition) {}

    private record Prepared(WorkflowRegistry registry, List<DocumentOrigin> origins, List<CodeDefinition> code) {}

    private record OwnerId(@Nullable String tenantKey, String definitionId) {}
}
