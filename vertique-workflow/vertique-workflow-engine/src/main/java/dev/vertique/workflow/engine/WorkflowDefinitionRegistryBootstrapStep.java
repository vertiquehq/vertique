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
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
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
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import dev.vertique.workflow.types.WorkflowTypeNames;
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
 *       the application-contributed definitions registered while the registry was constructed, to the default
 *       scope, and it snapshots the code definitions in the {@link WorkflowCodeDefinitionCatalog}.
 *   <li>It records every packaged document and every code definition in the table as available. Drift, a
 *       conflicting kind, or a stored code definition that the deployed code no longer provides fails startup.
 *       A version that is inserted here for the first time and does not opt out of activation on deploy also
 *       becomes the default scope's current version, in the same transaction as its insert, when it is higher than
 *       the current one. A version that is already recorded is never activated by a restart.
 *       A recorded version keeps the type names its plan hash was computed with: the registered plan is rebuilt
 *       under the stored name binding when that gives the stored hash, so a class that gained a logical name since
 *       does not change the hash. A recorded name that no longer resolves fails startup, except for a retired
 *       version.
 *   <li>It restores every durable activation record into the node's view, including the records just written and
 *       those that name a version this node has no code for.
 *   <li>It restores every live document of every scope, deprecated ones included, and attributes each to its
 *       key.
 *   <li>It applies the deprecation marks, and removes the registrations of retired rows when they are still
 *       attributed to the row's own key.
 *   <li>It records in the node's view every live row whose runtime this node holds for the row's own key.
 *   <li>It resets publication attempts that went stale, for every definition id of the table.
 *   <li>It hands the committed metadata, the rows just reset included, to the loaders.
 * </ol>
 */
@Singleton
final class WorkflowDefinitionRegistryBootstrapStep implements ApplicationStartupStep {
    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionRegistryBootstrapStep.class);
    private static final Set<DefinitionStatus> LIVE = Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED);
    private static final Comparator<WorkflowDefinitionRecord> LISTING_ORDER = Comparator.comparing(
                    (WorkflowDefinitionRecord record) -> record.key().definitionId())
            .thenComparing(record -> record.key().scopeKey())
            .thenComparingLong(record -> record.key().definitionVersion());

    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final ManagedRegistrations registrations;
    private final WorkflowCodeDefinitionCatalog catalog;
    private final DefaultWorkflowDefinitionRegistryView view;
    private final DefinitionActivations activations;
    private final WorkflowTypeNames typeNames;

    @Inject
    WorkflowDefinitionRegistryBootstrapStep(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog,
            DefaultWorkflowDefinitionRegistryView view,
            DefinitionActivations activations,
            WorkflowTypeNames typeNames) {
        this.typeNames = typeNames;
        this.activations = activations;
        this.view = view;
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
            return reconcileOrigins(prepared.origins(), prepared.optedOut())
                    .compose(ignored -> reconcileCode(prepared.code()))
                    .compose(ignored -> restoreActivations())
                    .compose(ignored -> listAll())
                    .compose(reconciled -> requireCode(reconciled)
                            .compose(ignored -> restoreAll(local, reconciled))
                            .compose(verified -> listAll()
                                    .compose(committed -> applyMembership(local, committed)
                                            .compose(ignored -> recordView(local, committed))
                                            .compose(ignored -> resetStale(committed)))
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
        registrations.attributeExistingToDefaultScope(local);
        List<DocumentOrigin> origins = loaders.stream()
                .flatMap(loader -> loader.documentOrigins().stream())
                .filter(origin -> WorkflowScopes.DEFAULT.equals(origin.key().scopeKey()))
                .sorted(Comparator.comparing(
                                (DocumentOrigin origin) -> origin.key().definitionId())
                        .thenComparingLong(origin -> origin.key().definitionVersion()))
                .toList();
        Set<VersionKey> originKeys = origins.stream()
                .map(origin ->
                        new VersionKey(origin.key().definitionId(), origin.key().definitionVersion()))
                .collect(Collectors.toSet());
        Set<DefinitionKey> optedOut = origins.stream()
                .map(DocumentOrigin::key)
                .filter(key -> local.registeredDefinition(key)
                        .map(definition -> !definition.activateOnDeploy())
                        .orElse(false))
                .collect(Collectors.toSet());
        return new Prepared(local, origins, catalog.capture(local, originKeys), optedOut);
    }

    private Future<List<WorkflowDefinitionRecord>> listAll() {
        return store.orElseThrow()
                .list(new DefinitionFilter(null, null, null))
                .map(records -> records.stream().sorted(LISTING_ORDER).toList());
    }

    /**
     * Records every packaged document, so that a stored row of the same key is compared with it. A document that
     * does not opt out is activated in the default scope when this records it for the first time.
     */
    private Future<Void> reconcileOrigins(List<DocumentOrigin> origins, Set<DefinitionKey> optedOut) {
        Future<Void> chain = Future.succeededFuture();
        for (DocumentOrigin origin : origins) {
            chain = chain.compose(ignored -> queue.submit(origin.key().definitionId(), () -> {
                WorkflowDefinitionRecord source = new WorkflowDefinitionRecord(
                        origin.key(),
                        DefinitionKind.DOCUMENT,
                        origin.contractName(),
                        origin.stateTypeName(),
                        origin.planHash(),
                        DefinitionStatus.AVAILABLE,
                        origin.provenance(),
                        origin.document().documentFormat(),
                        origin.provenance().loadedAt(),
                        origin.provenance().loadedAt(),
                        registry.get().nameBinding(origin.key()));
                return store.orElseThrow().find(origin.key()).compose(previous -> {
                    WorkflowDefinitionRecord bound = applyStoredBinding(source, previous);
                    return store.orElseThrow()
                            .upsertDocument(bound, origin.document(), !optedOut.contains(origin.key()))
                            .compose(outcome -> reconcileResult(bound, previous, outcome));
                });
            }));
        }
        return chain;
    }

    /**
     * Records every code definition that the deployed code provides. A definition that does not opt out is activated
     * in the default scope when this records it for the first time.
     */
    private Future<Void> reconcileCode(List<CodeDefinition> code) {
        Instant startedAt = Instant.now();
        Future<Void> chain = Future.succeededFuture();
        for (CodeDefinition definition : code) {
            chain = chain.compose(ignored -> queue.submit(definition.definitionId(), () -> {
                DefinitionKey key;
                try {
                    key = new DefinitionKey(WorkflowScopes.DEFAULT, definition.definitionId(), definition.version());
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
                        definition.contractName(),
                        definition.stateTypeName(),
                        definition.planHash(),
                        DefinitionStatus.AVAILABLE,
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
                        startedAt,
                        definition.nameBinding());
                return store.orElseThrow().find(key).compose(previous -> {
                    WorkflowDefinitionRecord bound = applyStoredBinding(source, previous);
                    return store.orElseThrow()
                            .upsertCode(
                                    bound,
                                    definition.definition() == null
                                            || definition.definition().activateOnDeploy())
                            .compose(outcome -> reconcileResult(bound, previous, outcome));
                });
            }));
        }
        return chain;
    }

    /**
     * Applies the name binding stored on the version's row to the plan this start registered. A version
     * recorded with a binding keeps the plan hash it was recorded with when a class has gained a logical name
     * since: the registered plan is rebuilt under the bound names, and the record to reconcile carries the stored
     * hash and binding, so an unchanged step graph reconciles as unchanged. A changed step graph does not rebuild
     * to the stored hash, keeps the plan registered under current names, and reconciles as a changed hash. A
     * retired row is not registered, so its binding is neither resolved nor applied.
     *
     * @return the record to reconcile; {@code source} itself when no stored binding applies
     * @throws WorkflowUnknownTypeNameException when the binding names a class that no longer resolves
     */
    private WorkflowDefinitionRecord applyStoredBinding(
            WorkflowDefinitionRecord source, Optional<WorkflowDefinitionRecord> previous) {
        if (previous.isEmpty()) return source;
        WorkflowDefinitionRecord stored = previous.orElseThrow();
        if (stored.kind() != source.kind()
                || stored.status() == DefinitionStatus.RETIRED
                || stored.nameBinding().isEmpty()) return source;
        requireBoundNamesResolve(stored);
        if (stored.planHash().equals(source.planHash())) return source;
        DefinitionKey key = source.key();
        Optional<RuntimeWorkflow> rebound = registry.get().rebind(key, stored.nameBinding(), stored.planHash());
        if (rebound.isEmpty()) return source;
        registrations.attribute(key.scopeKey(), key.definitionId(), key.definitionVersion(), rebound.get());
        if (source.kind() == DefinitionKind.CODE)
            catalog.rebound(key.definitionId(), key.definitionVersion(), stored.planHash());
        return new WorkflowDefinitionRecord(
                source.key(),
                source.kind(),
                source.contractName(),
                source.stateTypeName(),
                stored.planHash(),
                source.status(),
                source.provenance(),
                source.documentFormat(),
                source.createdAt(),
                source.statusChangedAt(),
                stored.nameBinding());
    }

    /**
     * Fails when a name in the row's binding no longer resolves to a class, so a version bound to the binary name
     * of a class that moved stops startup instead of failing its instances later. Names resolve only through the
     * type-name index; no class is loaded by a stored name.
     */
    private void requireBoundNamesResolve(WorkflowDefinitionRecord row) {
        List<String> unresolved = row.nameBinding().values().stream()
                .distinct()
                .filter(name -> typeNames.find(name).isEmpty())
                .sorted()
                .toList();
        if (unresolved.isEmpty()) return;
        DefinitionKey key = row.key();
        throw new WorkflowUnknownTypeNameException("definition '" + printable(key.definitionId()) + "' version "
                + key.definitionVersion() + " in scope '" + printable(key.scopeKey())
                + "' was recorded with type names that no class resolves any more: "
                + unresolved.stream().map(EchoedNames::bounded).collect(Collectors.joining(", "))
                + "; keep the class under that name, or retire the version before moving the class");
    }

    /** Records every durable activation record in the node's view, whether or not this node has the code it names. */
    private Future<Void> restoreActivations() {
        return store.orElseThrow().listActivations(null).map(records -> {
            records.forEach(activations::put);
            return (Void) null;
        });
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
            case KIND_CONFLICT -> {
                return Future.failedFuture(failure(
                        Reason.INVALID_TRANSITION,
                        key,
                        subject + " reconciliation " + outcome + ": the stored row is "
                                + previous.map(row -> row.kind() + " from " + described(row.provenance()))
                                        .orElse("of another kind")
                                + ", but this start provides " + source.kind() + " from "
                                + described(source.provenance())
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
                                    + previous.map(row -> described(row.provenance()))
                                            .orElse("source unavailable")
                                    + ", deployed " + described(source.provenance())
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
                                    + described(previous.orElseThrow().provenance()) + ", packaged from "
                                    + described(source.provenance())
                                    + ". Bump the version of the changed document; it is not enough to retire the old row, because a stored row of"
                                    + " the same id and version blocks startup in every status."));
                String stored = previous.map(
                                row -> "stored hash " + row.planHash() + " from " + described(row.provenance()))
                        .orElse("stored source unavailable");
                return Future.failedFuture(failure(
                        Reason.DRIFT,
                        key,
                        "static DOCUMENT reconciliation " + outcome + ": " + stored + "; static hash "
                                + source.planHash() + " from " + described(source.provenance())));
            }
        }
        throw new IllegalStateException("Unhandled reconcile outcome " + outcome);
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
                                + described(row.provenance()) + ") is not provided by the deployed code any more;"
                                + " restore the code, or retire the definition before removing its code"));
        }
        return Future.succeededFuture();
    }

    /** Restores every live document of every scope that the registry does not hold yet. */
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
     * Marks deprecated rows and removes retired ones from the registry, each only while the registry
     * still holds the runtime that was published for the row's own key.
     */
    private Future<Void> applyMembership(WorkflowRegistry local, List<WorkflowDefinitionRecord> committed) {
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord row : committed) {
            DefinitionKey key = row.key();
            if (row.status() == DefinitionStatus.DEPRECATED)
                chain = chain.compose(ignored -> queue.submit(key.definitionId(), () -> {
                    RuntimeWorkflow held = held(local, key);
                    if (standing(key, held) == ManagedRegistrations.Standing.OWN) local.markDeprecated(key);
                    return Future.<Void>succeededFuture();
                }));
            else if (row.status() == DefinitionStatus.RETIRED)
                chain = chain.compose(ignored -> queue.submit(key.definitionId(), () -> {
                    RuntimeWorkflow held = held(local, key);
                    if (standing(key, held) == ManagedRegistrations.Standing.OWN) {
                        local.unregister(key);
                        registrations.clear(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
                    }
                    return Future.<Void>succeededFuture();
                }));
        }
        return chain;
    }

    /** Records in the node view each live row of the committed listing whose runtime is held for the row's key. */
    private Future<Void> recordView(WorkflowRegistry local, List<WorkflowDefinitionRecord> committed) {
        Future<Void> chain = Future.succeededFuture();
        for (WorkflowDefinitionRecord row : committed) {
            if (!LIVE.contains(row.status())) continue;
            DefinitionKey key = row.key();
            chain = chain.compose(ignored -> queue.submit(key.definitionId(), () -> {
                RuntimeWorkflow held = held(local, key);
                if (registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held)
                        == ManagedRegistrations.Standing.OWN) view.applied(row);
                return Future.<Void>succeededFuture();
            }));
        }
        return chain;
    }

    /**
     * Classifies the runtime the registry holds for the row. No runtime at all is left alone; one that nobody
     * published is preserved and fails the start, because it cannot be attributed.
     */
    private ManagedRegistrations.Standing standing(DefinitionKey key, @Nullable RuntimeWorkflow held) {
        ManagedRegistrations.Standing standing =
                registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
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
            return local.resolve(key);
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absent) {
            return null;
        }
    }

    /**
     * Resets the publication attempts that went stale, under the id lock, for every definition id that
     * the table holds a publication attempt for, whether or not code or a loader knows the id.
     */
    private Future<Void> resetStale(List<WorkflowDefinitionRecord> committed) {
        TreeSet<String> ids = committed.stream()
                .filter(row -> row.status() == DefinitionStatus.PUBLISHING)
                .map(row -> row.key().definitionId())
                .collect(Collectors.toCollection(TreeSet::new));
        Future<Void> chain = Future.succeededFuture();
        for (String id : ids)
            chain = chain.compose(
                    ignored -> queue.submit(id, () -> store.orElseThrow().resetStalePublishing(id)));
        return chain;
    }

    /**
     * Gives the loaders the committed metadata. The restored documents are read again, and candidate and retired
     * documents, the rows just reset included, are read after the reset.
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
                        requireBoundNamesResolve(record);
                        WorkflowDefinition<?, ?> definition;
                        String hash;
                        try {
                            definition = loader.load(record, source);
                            hash = DefinitionPublication.planHash(loader, definition, record);
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
                            DefinitionPublication.publish(local, registrations, key, definition, record.nameBinding());
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

    /** Renders a source record for a message, with every non-printable character of its labels escaped. */
    private static String described(Object provenance) {
        return printable(String.valueOf(provenance));
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

    private record Prepared(
            WorkflowRegistry registry,
            List<DocumentOrigin> origins,
            List<CodeDefinition> code,
            Set<DefinitionKey> optedOut) {}
}
