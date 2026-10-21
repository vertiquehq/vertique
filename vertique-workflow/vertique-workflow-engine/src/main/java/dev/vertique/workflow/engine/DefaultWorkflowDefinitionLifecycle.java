// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowCodeDefinitionCatalog.CodeDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.EchoedNames;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Publishes durable document candidates and compensates only the captured local publication.
 *
 * <p>When the outcome of a completion stays unknown, the runtime this lifecycle published is remembered per
 * key. A later publication of the same key adopts exactly that runtime, and only while the registry still holds
 * that same instance; a registration the lifecycle did not publish is never adopted or removed.
 *
 * <p>Every publication this lifecycle makes is attributed to its key in {@link ManagedRegistrations}. Ordinary
 * deprecation and retirement act only on a registration that is attributed and still held as the same instance,
 * and refuse, before any durable change, one that is not.
 *
 * <p>Reinstatement of a retired version restores the executable source first, from the deployed code catalog or
 * the stored document text, publishes it under the key's attribution, and only then commits the durable change. A
 * failed commit removes just the publication that this call made, or that an earlier call of this lifecycle left
 * unresolved. A deprecated version is still registered, so its reinstatement commits the durable change and then
 * clears the deprecation mark without publishing the source again.
 */
@Singleton
final class DefaultWorkflowDefinitionLifecycle implements WorkflowDefinitionLifecycle {
    /** The statuses in which a row's version is registered on every node. */
    private static final Set<DefinitionStatus> REGISTERED =
            Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED);

    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final ManagedRegistrations registrations;
    private final WorkflowCodeDefinitionCatalog catalog;
    /** The node's view of the applied records, or {@code null} when the lifecycle was built without one. */
    private final @Nullable DefaultWorkflowDefinitionRegistryView view;
    /** The node's view of the activation records, which a start without a version reads. */
    private final DefinitionActivations activations;
    /** Runtimes this lifecycle published whose completion outcome is still unknown, by key. */
    private final ConcurrentMap<DefinitionKey, RuntimeWorkflow> uncertain = new ConcurrentHashMap<>();
    /** Runtimes a reinstatement published whose commit outcome could not be read back, by key. */
    private final ConcurrentMap<DefinitionKey, RuntimeWorkflow> unresolvedReinstatements = new ConcurrentHashMap<>();

    /**
     * Constructs a lifecycle with its own private attributions, for unit tests only. Production graphs inject
     * the attributions shared with the startup step.
     */
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue) {
        this(store, registry, loaders, queue, new ManagedRegistrations());
    }

    /** Constructs a lifecycle over the given attributions and an empty code catalog, for unit tests only. */
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations) {
        this(store, registry, loaders, queue, registrations, new WorkflowCodeDefinitionCatalog());
    }

    /** Constructs a lifecycle that does not feed a node view, for unit tests only. */
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog) {
        this(store, registry, loaders, queue, registrations, catalog, null);
    }

    /** Constructs a lifecycle with its own private activation view, for unit tests only. */
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog,
            @Nullable DefaultWorkflowDefinitionRegistryView view) {
        this(store, registry, loaders, queue, registrations, catalog, view, new DefinitionActivations());
    }

    @Inject
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations,
            WorkflowCodeDefinitionCatalog catalog,
            @Nullable DefaultWorkflowDefinitionRegistryView view,
            DefinitionActivations activations) {
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
    public Future<Void> publish(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            if (store.isEmpty())
                return Future.failedFuture(failure(Reason.INVALID_TRANSITION, key, "durable registry is unavailable"));
            return queue.submit(key.definitionId(), () -> publishQueued(key));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> deprecate(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            if (store.isEmpty())
                return Future.failedFuture(failure(Reason.INVALID_TRANSITION, key, "durable registry is unavailable"));
            // Commit the durable status first; only a committed status is projected onto the local registry.
            return queue.submit(key.definitionId(), () -> {
                // A registration this lifecycle did not publish is refused before the durable status changes.
                preflight(key);
                return commitDeprecation(key, true).compose(ignored -> {
                    registry.get().markDeprecated(key);
                    return refreshView(key);
                });
            });
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> retire(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            if (store.isEmpty())
                return Future.failedFuture(failure(Reason.INVALID_TRANSITION, key, "durable registry is unavailable"));
            return queue.submit(key.definitionId(), () -> retireQueued(key));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> reinstate(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            if (store.isEmpty())
                return Future.failedFuture(failure(Reason.INVALID_TRANSITION, key, "durable registry is unavailable"));
            return queue.submit(key.definitionId(), () -> reinstateQueued(key));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> activate(String scopeKey, String definitionId, String targetScopeKey, long version) {
        try {
            DefinitionActivation activation =
                    new DefinitionActivation(scopeKey, definitionId, targetScopeKey, version, Instant.now());
            if (store.isEmpty()) return activateInView(activation);
            WorkflowDefinitionRegistryStore durable = store.orElseThrow();
            // The view follows the commit, so a refused or failed write leaves it as it was.
            return queue.submit(definitionId, () -> durable.putActivation(activation)
                    .onSuccess(committed -> activations.put(activation)));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> deactivateDefault(String definitionId) {
        try {
            new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
            return removeRecord(WorkflowScopes.DEFAULT, definitionId);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> follow(String scopeKey, String definitionId) {
        try {
            new DefinitionKey(scopeKey, definitionId, 1);
            if (WorkflowScopes.DEFAULT.equals(scopeKey))
                throw new IllegalArgumentException(
                        "the default scope cannot follow itself; clear its record with deactivateDefault");
            return removeRecord(scopeKey, definitionId);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<List<DefinitionActivation>> activations(@Nullable String scopeKey) {
        try {
            if (scopeKey != null) new DefinitionKey(scopeKey, WorkflowScopes.DEFAULT, 1);
            if (store.isEmpty()) return Future.succeededFuture(activations.list(registry.get(), scopeKey));
            return store.orElseThrow().listActivations(scopeKey);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Writes a record into the node view alone, as without a durable registry. The target must be registered here
     * and not deprecated, which is the most this node can check.
     */
    private Future<Void> activateInView(DefinitionActivation activation) {
        DefinitionKey target =
                new DefinitionKey(activation.targetScopeKey(), activation.definitionId(), activation.version());
        WorkflowRegistry local = registry.get();
        if (local.find(target).isEmpty() || local.isDeprecated(target))
            return Future.failedFuture(failure(
                    Reason.INVALID_TRANSITION,
                    target,
                    "cannot activate a version that is not registered and available"));
        WorkflowDefinitionContractMismatchException refusal = defaultContractRefusal(local, activation, target);
        if (refusal != null) return Future.failedFuture(refusal);
        activations.put(activation);
        return Future.succeededFuture();
    }

    /**
     * The refusal for pointing a scope at a default-scope version of another contract than the scope's own
     * versions of the id, judged from the registrations of this node.
     */
    private static @Nullable WorkflowDefinitionContractMismatchException defaultContractRefusal(
            WorkflowRegistry local, DefinitionActivation activation, DefinitionKey target) {
        if (WorkflowScopes.DEFAULT.equals(activation.scopeKey())
                || !WorkflowScopes.DEFAULT.equals(activation.targetScopeKey())) return null;
        String own = local.contractOf(activation.scopeKey(), activation.definitionId())
                .orElse(null);
        String targetContract = local.contractOf(WorkflowScopes.DEFAULT, activation.definitionId())
                .orElse(null);
        if (own == null || own.equals(targetContract)) return null;
        return new WorkflowDefinitionContractMismatchException(
                target,
                "Scope '" + activation.scopeKey() + "' holds '" + activation.definitionId() + "' under contract '"
                        + EchoedNames.bounded(own)
                        + "', so it cannot follow the default scope's version, which implements contract '"
                        + EchoedNames.bounded(String.valueOf(targetContract)) + "'");
    }

    /** Removes a record from the store and then from the node view, or from the view alone without a store. */
    private Future<Void> removeRecord(String scopeKey, String definitionId) {
        if (store.isEmpty()) {
            activations.remove(scopeKey, definitionId);
            return Future.succeededFuture();
        }
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        return queue.submit(definitionId, () -> durable.removeActivation(scopeKey, definitionId)
                .onSuccess(committed -> activations.remove(scopeKey, definitionId)));
    }

    /**
     * Reads the row and reinstates it. A retired row has its executable source restored and published, and then
     * the durable change is committed; the commit is authoritative and a failure before it removes what this call
     * published. A deprecated row is still registered, so the durable change is committed first and the
     * deprecation mark is cleared afterwards, without publishing the source again. The committed row is handed
     * to the document loader.
     *
     * <p>A row that is already available is accepted only as the result of an earlier attempt of this lifecycle
     * whose commit outcome was unknown: the runtime that attempt left registered must still stand, be attributed
     * to the key and carry the row's plan hash. The same hand-off then runs as after a first success.
     */
    private Future<Void> reinstateQueued(DefinitionKey key) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        Reinstatement attempt = new Reinstatement(key);
        return durable.find(key)
                .compose(found -> {
                    attempt.record =
                            found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
                    DefinitionStatus status = attempt.record.status();
                    if (status == DefinitionStatus.AVAILABLE && adoptSettled(attempt)) {
                        attempt.settled = true;
                        return Future.<Void>succeededFuture();
                    }
                    if (status == DefinitionStatus.DEPRECATED) {
                        // The version is registered and marked; only the durable status and the mark change.
                        preflight(key);
                        attempt.deprecated = true;
                        return Future.<Void>succeededFuture();
                    }
                    if (status != DefinitionStatus.RETIRED)
                        throw failure(Reason.INVALID_TRANSITION, key, "cannot reinstate status " + status);
                    attempt.retired = true;
                    return publishSource(attempt);
                })
                // The row was read as retired, so a failure before the commit is attempted is a definitive
                // refusal: a publication that an earlier unresolved attempt left behind cannot be a commit.
                .recover(original -> {
                    if (attempt.retired && !(original instanceof Error)) dropUnresolved(attempt, original);
                    return Future.<Void>failedFuture(original);
                })
                .compose(ignored -> attempt.settled ? Future.<Void>succeededFuture() : commitReinstatement(attempt))
                .compose(ignored -> handOffReinstated(attempt));
    }

    /**
     * Whether a row that is already available is the committed result of an earlier reinstatement of this
     * lifecycle: the runtime it left unresolved must still be the registered one, attributed to the key, with the
     * row's plan hash. Prepares the hand-off of the row when it is.
     */
    private boolean adoptSettled(Reinstatement attempt) {
        DefinitionKey key = attempt.key;
        RuntimeWorkflow remembered = unresolvedReinstatements.get(key);
        if (remembered == null) return false;
        WorkflowRegistry local = registry.get();
        RuntimeWorkflow held = held(local, key);
        if (held != remembered
                || registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held)
                        != ManagedRegistrations.Standing.OWN
                || !attempt.record.planHash().equals(held.plan().planHash())) return false;
        if (attempt.record.kind() == DefinitionKind.DOCUMENT) attempt.loader = loaderFor(attempt.record);
        attempt.definition = local.registeredDefinition(key).orElse(null);
        return true;
    }

    /** Removes the runtime an earlier unresolved reinstatement left registered, if it is still the held instance. */
    private void dropUnresolved(Reinstatement attempt, Throwable original) {
        RuntimeWorkflow remembered = unresolvedReinstatements.remove(attempt.key);
        if (remembered == null) return;
        try {
            WorkflowRegistry local = registry.get();
            if (isRegistered(local, remembered)) local.unregister(attempt.key);
            registrations.clear(
                    attempt.key.scopeKey(), attempt.key.definitionId(), attempt.key.definitionVersion(), remembered);
        } catch (RuntimeException compensation) {
            if (compensation != original) original.addSuppressed(compensation);
        }
    }

    /**
     * Makes the executable source of the retired row available in the registry, attributed to its key. A
     * registration this lifecycle published for the key is kept as it is, unless it is the one an earlier
     * reinstatement left unresolved, which this call then owns and removes if it fails; one this lifecycle did
     * not publish fails the call before anything is registered.
     */
    private Future<Void> publishSource(Reinstatement attempt) {
        DefinitionKey key = attempt.key;
        WorkflowRegistry local = registry.get();
        RuntimeWorkflow held = held(local, key);
        ManagedRegistrations.Standing standing =
                registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held);
        if (standing == ManagedRegistrations.Standing.UNATTRIBUTED)
            throw failure(
                    Reason.INVALID_TRANSITION,
                    key,
                    "the registered runtime was not published by the durable lifecycle for this definition");
        // A remembered publication that is no longer the held instance is stale and is forgotten.
        RuntimeWorkflow remembered = unresolvedReinstatements.get(key);
        if (remembered != null && remembered != held) {
            unresolvedReinstatements.remove(key, remembered);
            registrations.clear(key.scopeKey(), key.definitionId(), key.definitionVersion(), remembered);
        }
        boolean document = attempt.record.kind() == DefinitionKind.DOCUMENT;
        if (document) attempt.loader = loaderFor(attempt.record);
        if (standing == ManagedRegistrations.Standing.OWN) {
            // The registration that stands is valid for this key: keep it, and remove it on failure only when
            // it is the one an earlier unresolved reinstatement of this lifecycle left behind.
            String registered = held.plan().planHash();
            if (!attempt.record.planHash().equals(registered))
                throw failure(
                        Reason.DRIFT,
                        key,
                        "stored hash " + attempt.record.planHash() + " differs from registered hash " + registered);
            attempt.definition = local.registeredDefinition(key).orElse(null);
            // A registration that an earlier unresolved attempt of this lifecycle published is this attempt's to
            // remove.
            if (remembered == held) attempt.publication = held;
            return Future.succeededFuture();
        }
        if (!document)
            return work(() -> {
                publishCode(attempt, local);
                return (Void) null;
            });
        return store.orElseThrow()
                .findDocument(key)
                .compose(source -> work(() -> {
                    var text =
                            source.orElseThrow(() -> failure(Reason.LOADER_MISSING, key, "stored source is missing"));
                    requireBoundNamesResolve(attempt.record);
                    WorkflowDefinition<?, ?> definition = attempt.loader.load(attempt.record, text);
                    String hash = DefinitionPublication.planHash(attempt.loader, definition, attempt.record);
                    if (!definition.definitionId().equals(key.definitionId())
                            || definition.definitionVersion() != key.definitionVersion()
                            || !attempt.record.planHash().equals(hash))
                        throw failure(
                                Reason.DRIFT,
                                key,
                                "stored hash " + attempt.record.planHash() + " differs from compiled hash " + hash);
                    publish(attempt, local, definition);
                    attempt.definition = definition;
                    return (Void) null;
                }));
    }

    /** Refuses a row whose bound type names no class resolves any more, before anything is loaded or registered. */
    private void requireBoundNamesResolve(WorkflowDefinitionRecord record) {
        if (record.nameBinding().isEmpty()) return;
        DefinitionPublication.requireBoundNamesResolve(WorkflowEngineFactory.registryTypeNames(registry.get()), record);
    }

    /** Registers the code definition the deployed code provides, after its hash was checked against the row. */
    private void publishCode(Reinstatement attempt, WorkflowRegistry local) {
        CodeDefinition code = DefinitionPublication.verifiedCode(catalog, attempt.record);
        publish(attempt, local, code.definition());
    }

    /** Registers the definition and records the exact runtime as this attempt's own, attributed publication. */
    private void publish(Reinstatement attempt, WorkflowRegistry local, WorkflowDefinition<?, ?> definition) {
        attempt.publication = DefinitionPublication.publish(
                local, registrations, attempt.key, definition, attempt.record.nameBinding());
    }

    private PersistedDefinitionLoader loaderFor(WorkflowDefinitionRecord record) {
        return DefinitionPublication.loaderFor(loaders, record);
    }

    /**
     * Commits the durable reinstatement. A failure whose outcome is unknown is decided by reading the row
     * again: an available row with the plan hash of the row that was read was committed, and any other
     * row was not, which removes this attempt's publication, if it made one. When the row cannot be read the publication stays,
     * remembered, for a retry to adopt or to remove.
     */
    private Future<Void> commitReinstatement(Reinstatement attempt) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        Future<Void> transition;
        try {
            transition = durable.transition(attempt.key, attempt.record.status(), DefinitionStatus.AVAILABLE);
        } catch (RuntimeException failure) {
            transition = Future.failedFuture(failure);
        }
        return transition.recover(original -> {
            if (original instanceof Error) return Future.failedFuture(original);
            return durable.find(attempt.key).transform(read -> {
                if (read.failed()) {
                    if (read.cause() != original) original.addSuppressed(read.cause());
                    if (attempt.publication != null) unresolvedReinstatements.put(attempt.key, attempt.publication);
                    return Future.<Void>failedFuture(original);
                }
                boolean committed = read.result()
                        .filter(row -> row.status() == DefinitionStatus.AVAILABLE
                                && row.planHash().equals(attempt.record.planHash()))
                        .isPresent();
                unresolvedReinstatements.remove(attempt.key);
                if (committed) return Future.<Void>succeededFuture();
                removePublication(attempt, original);
                return Future.<Void>failedFuture(original);
            });
        });
    }

    /** Removes only the runtime this attempt published, and only while the registry still holds that instance. */
    private void removePublication(Reinstatement attempt, Throwable original) {
        if (attempt.publication == null) return;
        try {
            WorkflowRegistry local = registry.get();
            if (isRegistered(local, attempt.publication)) local.unregister(attempt.key);
            registrations.clear(
                    attempt.key.scopeKey(),
                    attempt.key.definitionId(),
                    attempt.key.definitionVersion(),
                    attempt.publication);
        } catch (RuntimeException compensation) {
            if (compensation != original) original.addSuppressed(compensation);
        }
    }

    /** Hands the committed document row to its loader, so that the loader's cache agrees with the table. */
    private Future<Void> handOffReinstated(Reinstatement attempt) {
        if (attempt.deprecated) prepareUndeprecated(attempt);
        if (attempt.loader == null) {
            unresolvedReinstatements.remove(attempt.key);
            return refreshView(attempt.key);
        }
        return store.orElseThrow().find(attempt.key).map(found -> {
            WorkflowDefinitionRecord current =
                    found.orElseThrow(() -> failure(Reason.STALE_CLAIM, attempt.key, "reinstated row disappeared"));
            if (view != null) view.applied(current);
            if (attempt.definition != null) attempt.loader.onRestored(current, attempt.definition);
            else attempt.loader.onMetadata(current);
            // The adopted publication is settled only once the committed row was handed over.
            unresolvedReinstatements.remove(attempt.key);
            return (Void) null;
        });
    }

    /**
     * Clears the deprecation mark of the registration this lifecycle published for the key, now that the row is
     * available, and prepares the hand-off of the committed row to the loader of a document.
     */
    private void prepareUndeprecated(Reinstatement attempt) {
        DefinitionKey key = attempt.key;
        WorkflowRegistry local = registry.get();
        RuntimeWorkflow held = held(local, key);
        if (registrations.classify(key.scopeKey(), key.definitionId(), key.definitionVersion(), held)
                != ManagedRegistrations.Standing.OWN) return;
        local.unmarkDeprecated(key);
        // The change is committed, so a missing loader must not fail the call; the node view still refreshes.
        if (attempt.record.kind() == DefinitionKind.DOCUMENT) {
            List<PersistedDefinitionLoader> matches = loaders.stream()
                    .filter(loader -> loader.supports(attempt.record.documentFormat()))
                    .toList();
            if (matches.size() == 1) attempt.loader = matches.getFirst();
        }
        attempt.definition = local.registeredDefinition(key).orElse(null);
    }

    /**
     * Refuses an unattributed or replaced registration, commits the retirement, and only then removes the local
     * registration, when it is the very instance this lifecycle published for the same key. A retirement that
     * fails is read back: a row that is retired means the commit won, so the local removal still runs.
     */
    private Future<Void> retireQueued(DefinitionKey key) {
        preflight(key);
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        return durable.retire(key)
                .recover(original -> {
                    if (original instanceof Error) return Future.failedFuture(original);
                    return durable.find(key).transform(read -> {
                        boolean retired = read.succeeded()
                                && read.result()
                                        .map(WorkflowDefinitionRecord::status)
                                        .filter(DefinitionStatus.RETIRED::equals)
                                        .isPresent();
                        if (retired) return Future.<Void>succeededFuture();
                        if (read.failed() && read.cause() != original) original.addSuppressed(read.cause());
                        return Future.<Void>failedFuture(original);
                    });
                })
                .compose(ignored -> removeRetired(key));
    }

    /** Removes the runtime this lifecycle published for the retired key; any other registration stays. */
    private Future<Void> removeRetired(DefinitionKey key) {
        if (view != null) view.removed(key);
        WorkflowRegistry local = registry.get();
        String id = key.definitionId();
        long version = key.definitionVersion();
        RuntimeWorkflow held = held(local, key);
        if (registrations.classify(key.scopeKey(), id, version, held) == ManagedRegistrations.Standing.OWN) {
            local.unregister(key);
            registrations.clear(key.scopeKey(), id, version, held);
            uncertain.remove(key, held);
            unresolvedReinstatements.remove(key, held);
        }
        return Future.succeededFuture();
    }

    /**
     * Classifies the local registration of the key's id and version before any durable change. A registration
     * the lifecycle did not publish, or one that a direct registry call replaced after publication, fails as an
     * invalid transition and stays registered.
     */
    private ManagedRegistrations.Standing preflight(DefinitionKey key) {
        ManagedRegistrations.Standing standing = registrations.classify(
                key.scopeKey(), key.definitionId(), key.definitionVersion(), held(registry.get(), key));
        if (standing == ManagedRegistrations.Standing.UNATTRIBUTED)
            throw failure(
                    Reason.INVALID_TRANSITION,
                    key,
                    "the registered runtime was not published by the durable lifecycle for this definition");
        return standing;
    }

    /** The runtime the registry holds for the exact id and version, or {@code null} when it holds none. */
    private static @Nullable RuntimeWorkflow held(WorkflowRegistry local, DefinitionKey key) {
        return DefinitionPublication.held(local, key);
    }

    private void attribute(Attempt attempt) {
        if (attempt.publication != null)
            registrations.attribute(
                    attempt.key.scopeKey(),
                    attempt.key.definitionId(),
                    attempt.key.definitionVersion(),
                    attempt.publication);
    }

    /** Reads the current status, then moves an available row to deprecated; already deprecated is accepted. */
    private Future<Void> commitDeprecation(DefinitionKey key, boolean retryOnConflict) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        return durable.find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
            DefinitionStatus status = record.status();
            if (status == DefinitionStatus.DEPRECATED) return Future.<Void>succeededFuture();
            if (status != DefinitionStatus.AVAILABLE)
                throw failure(Reason.INVALID_TRANSITION, key, "cannot deprecate status " + status);
            return durable.transition(key, status, DefinitionStatus.DEPRECATED).recover(original -> {
                if (original instanceof Error) return Future.failedFuture(original);
                // A failed transition may still have committed, so re-read before deciding.
                return durable.find(key).transform(read -> {
                    if (read.failed()) {
                        if (read.cause() != original) original.addSuppressed(read.cause());
                        return Future.<Void>failedFuture(original);
                    }
                    DefinitionStatus now =
                            read.result().map(WorkflowDefinitionRecord::status).orElse(null);
                    if (now == DefinitionStatus.DEPRECATED) return Future.<Void>succeededFuture();
                    // The status moved between the read and the transition: decide again once.
                    boolean moved = original instanceof WorkflowDefinitionLifecycleException refusal
                            && refusal.reason() == Reason.INVALID_TRANSITION
                            && now == DefinitionStatus.AVAILABLE;
                    return retryOnConflict && moved
                            ? commitDeprecation(key, false)
                            : Future.<Void>failedFuture(original);
                });
            });
        });
    }

    private Future<Void> publishQueued(DefinitionKey key) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        Attempt attempt = new Attempt(key);
        Future<Void> publication = durable.find(key)
                .<Void>compose(found -> {
                    WorkflowDefinitionRecord record =
                            found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
                    if (record.status() == DefinitionStatus.AVAILABLE) {
                        uncertain.remove(key);
                        return Future.succeededFuture();
                    }
                    if (record.kind() != DefinitionKind.DOCUMENT)
                        throw failure(
                                Reason.INVALID_TRANSITION, key, "CODE definitions register from deployed sources");
                    return durable.claim(key)
                            .compose(claim -> {
                                attempt.claim = claim;
                                return durable.find(key);
                            })
                            .compose(current -> {
                                attempt.record = current.orElseThrow(
                                        () -> failure(Reason.STALE_CLAIM, key, "claimed row disappeared"));
                                List<PersistedDefinitionLoader> matches = loaders.stream()
                                        .filter(l -> l.supports(attempt.record.documentFormat()))
                                        .toList();
                                if (matches.size() != 1)
                                    throw failure(
                                            Reason.LOADER_MISSING,
                                            key,
                                            "expected one loader for " + attempt.record.documentFormat() + ", found "
                                                    + matches.size());
                                attempt.loader = matches.getFirst();
                                return durable.findDocument(key);
                            })
                            .compose(document -> work(() -> {
                                var source = document.orElseThrow(
                                        () -> failure(Reason.LOADER_MISSING, key, "stored source is missing"));
                                requireBoundNamesResolve(attempt.record);
                                attempt.definition = attempt.loader.load(attempt.record, source);
                                String hash = DefinitionPublication.planHash(
                                        attempt.loader, attempt.definition, attempt.record);
                                if (!attempt.definition.definitionId().equals(key.definitionId())
                                        || attempt.definition.definitionVersion() != key.definitionVersion()
                                        || !attempt.record.planHash().equals(hash))
                                    throw failure(
                                            Reason.DRIFT,
                                            key,
                                            "stored hash " + attempt.record.planHash() + " differs from compiled hash "
                                                    + hash);
                                WorkflowRegistry local = registry.get();
                                RuntimeWorkflow remembered = uncertain.get(key);
                                if (remembered != null && isRegistered(local, remembered)) {
                                    // The registry still holds the runtime this lifecycle published earlier.
                                    attempt.publication = remembered;
                                    return (Void) null;
                                }
                                // Stale: whatever the registry holds now is not the remembered runtime.
                                if (remembered != null) uncertain.remove(key, remembered);
                                if (attempt.record.nameBinding().isEmpty())
                                    local.register(attempt.key.scopeKey(), attempt.definition);
                                else
                                    local.register(
                                            attempt.key.scopeKey(), attempt.definition, attempt.record.nameBinding());
                                attempt.publication = local.resolve(key);
                                return (Void) null;
                            }))
                            .compose(ignored -> complete(attempt))
                            .map(ignored -> {
                                attribute(attempt);
                                uncertain.remove(key);
                                return (Void) null;
                            });
                })
                .recover(original -> recover(attempt, original));
        // Hook failures fail the operation, but cannot undo a committed publication.
        return publication.compose(ignored -> {
            if (attempt.definition == null) return Future.succeededFuture();
            return durable.find(key).map(found -> {
                WorkflowDefinitionRecord current =
                        found.orElseThrow(() -> failure(Reason.STALE_CLAIM, key, "completed row disappeared"));
                if (view != null) view.applied(current);
                attempt.loader.onRestored(current, attempt.definition);
                return (Void) null;
            });
        });
    }

    private Future<Void> recover(Attempt attempt, Throwable original) {
        if (original instanceof Error || attempt.claim == null) return Future.failedFuture(original);
        if (original == attempt.definitiveCompletionFailure) return compensate(attempt, original);
        // Without a publication nothing can have been committed, so no reread is needed to decide.
        if (attempt.publication == null) return compensate(attempt, original);
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        return durable.find(attempt.key).transform(read -> {
            if (read.failed()) {
                if (read.cause() != original) original.addSuppressed(read.cause());
                // The commit outcome remains unknown: preserve the publication for reconciliation, and
                // remember it so a retry adopts it instead of registering the same version again.
                uncertain.put(attempt.key, attempt.publication);
                attribute(attempt);
                return Future.failedFuture(original);
            }
            Optional<WorkflowDefinitionRecord> current = read.result();
            if (attempt.publication != null
                    && current.filter(r -> r.status() == DefinitionStatus.AVAILABLE
                                    && r.planHash().equals(attempt.record.planHash()))
                            .isPresent()) {
                attribute(attempt);
                uncertain.remove(attempt.key);
                return Future.succeededFuture();
            }
            return compensate(attempt, original);
        });
    }

    private Future<Void> complete(Attempt attempt) {
        Future<Void> completion;
        try {
            completion = store.orElseThrow().complete(attempt.claim).mapEmpty();
        } catch (RuntimeException failure) {
            completion = Future.failedFuture(failure);
        }
        return completion.recover(failure -> {
            // Only a refusal produced by this complete call can establish this attempt's failure.
            if (failure instanceof WorkflowDefinitionLifecycleException refusal
                    && refusal.key().equals(attempt.claim.key())
                    && refusal.reason() == Reason.STALE_CLAIM) attempt.definitiveCompletionFailure = failure;
            return Future.failedFuture(failure);
        });
    }

    private Future<Void> compensate(Attempt attempt, Throwable original) {
        Future<Void> release;
        try {
            release = store.orElseThrow().release(attempt.claim);
        } catch (RuntimeException failure) {
            release = Future.failedFuture(failure);
        }
        return release.transform(released -> {
            if (released.failed() && released.cause() != original) original.addSuppressed(released.cause());
            if (attempt.publication != null) {
                try {
                    WorkflowRegistry local = registry.get();
                    if (isRegistered(local, attempt.publication)) local.unregister(attempt.key);
                    registrations.clear(
                            attempt.key.scopeKey(),
                            attempt.key.definitionId(),
                            attempt.key.definitionVersion(),
                            attempt.publication);
                    // Forget the runtime only once it is gone; a failed removal leaves it to be adopted by a retry.
                    uncertain.remove(attempt.key, attempt.publication);
                } catch (RuntimeException compensation) {
                    if (compensation != original) original.addSuppressed(compensation);
                }
            }
            return Future.failedFuture(original);
        });
    }

    /**
     * Brings the node view up to date with the committed row after an operation that left no row in hand. The
     * refresh is best effort: it never fails the operation, and it is skipped when no view was injected, so that
     * a lifecycle built without one makes no additional store call.
     */
    private Future<Void> refreshView(DefinitionKey key) {
        if (view == null) return Future.succeededFuture();
        try {
            return store.orElseThrow()
                    .find(key)
                    .<Void>map(found -> {
                        WorkflowRegistry local = registry.get();
                        boolean own = registrations.classify(
                                        key.scopeKey(), key.definitionId(), key.definitionVersion(), held(local, key))
                                == ManagedRegistrations.Standing.OWN;
                        found.filter(row -> REGISTERED.contains(row.status()))
                                .filter(row -> own)
                                .ifPresentOrElse(view::applied, () -> view.removed(key));
                        return null;
                    })
                    .recover(ignored -> Future.succeededFuture());
        } catch (RuntimeException ignored) {
            return Future.succeededFuture();
        }
    }

    /** Whether the registry holds exactly this runtime instance. */
    private static boolean isRegistered(WorkflowRegistry local, RuntimeWorkflow runtime) {
        return local.allRegistered().stream().anyMatch(registered -> registered == runtime);
    }

    private static <T> Future<T> work(Supplier<T> action) {
        var context = Vertx.currentContext();
        if (context != null) return context.executeBlocking(action::get, false);
        try {
            return Future.succeededFuture(action.get());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    private static WorkflowDefinitionLifecycleException failure(Reason reason, DefinitionKey key, String message) {
        return new WorkflowDefinitionLifecycleException(reason, key, "Definition " + key + ": " + message);
    }

    private static final class Attempt {
        final DefinitionKey key;
        ActivationClaim claim;
        WorkflowDefinitionRecord record;
        PersistedDefinitionLoader loader;
        WorkflowDefinition<?, ?> definition;
        RuntimeWorkflow publication;
        Throwable definitiveCompletionFailure;

        Attempt(DefinitionKey key) {
            this.key = key;
        }
    }

    private static final class Reinstatement {
        final DefinitionKey key;
        WorkflowDefinitionRecord record;
        PersistedDefinitionLoader loader;
        WorkflowDefinition<?, ?> definition;
        /** The runtime this attempt published, or {@code null} when it kept a registration that already stood. */
        RuntimeWorkflow publication;
        /** Whether the row was read as retired, so that nothing of this reinstatement can be committed yet. */
        boolean retired;
        /** Whether the row was read as deprecated: it is still registered and only its status and mark change. */
        boolean deprecated;
        /** Whether the row was found already committed by an earlier unresolved attempt of this lifecycle. */
        boolean settled;

        Reinstatement(DefinitionKey key) {
            this.key = key;
        }
    }
}
