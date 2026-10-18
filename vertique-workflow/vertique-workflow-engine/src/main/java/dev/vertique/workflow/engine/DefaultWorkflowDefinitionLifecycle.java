// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.ActivationOutcome;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Publishes durable document activations and compensates only the captured local publication.
 *
 * <p>When the outcome of a completion stays unknown, the runtime this lifecycle published is remembered per
 * key. A later activation of the same key adopts exactly that runtime, and only while the registry still holds
 * that same instance; a registration the lifecycle did not publish is never adopted or removed.
 *
 * <p>Every publication this lifecycle makes is attributed to its owner in {@link ManagedRegistrations}. Ordinary
 * deprecation and retirement act only on a registration that is attributed and still held as the same instance,
 * and refuse, before any durable change, one that is not.
 */
@Singleton
final class DefaultWorkflowDefinitionLifecycle implements WorkflowDefinitionLifecycle {
    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final ManagedRegistrations registrations;
    /** Runtimes this lifecycle published whose completion outcome is still unknown, by key. */
    private final ConcurrentMap<DefinitionKey, RuntimeWorkflow> uncertain = new ConcurrentHashMap<>();

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

    @Inject
    DefaultWorkflowDefinitionLifecycle(
            Optional<WorkflowDefinitionRegistryStore> store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            ManagedRegistrations registrations) {
        this.store = store;
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
        this.registrations = registrations;
    }

    @Override
    public Future<Void> activate(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            if (store.isEmpty())
                return Future.failedFuture(failure(Reason.INVALID_TRANSITION, key, "durable registry is unavailable"));
            return queue.submit(key.definitionId(), () -> activateQueued(key));
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
                ManagedRegistrations.Standing standing = preflight(key);
                return commitDeprecation(key, true).compose(ignored -> {
                    // A registration published for another owner is not this definition's to mark.
                    if (standing != ManagedRegistrations.Standing.OTHER_OWNER)
                        registry.get().markDeprecated(key.definitionId(), key.definitionVersion());
                    return Future.<Void>succeededFuture();
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

    /**
     * Refuses an unattributed or replaced registration, commits the retirement, and only then removes the local
     * registration, when it is the very instance this lifecycle published for the same owner.
     */
    private Future<Void> retireQueued(DefinitionKey key) {
        preflight(key);
        return store.orElseThrow().retire(key).compose(ignored -> {
            WorkflowRegistry local = registry.get();
            String id = key.definitionId();
            long version = key.definitionVersion();
            RuntimeWorkflow held = held(local, id, version);
            if (registrations.classify(key.tenantKey(), id, version, held) == ManagedRegistrations.Standing.OWN) {
                local.unregister(id, version);
                registrations.clear(id, version, held);
                uncertain.remove(key, held);
            }
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Classifies the local registration of the key's id and version before any durable change. A registration
     * the lifecycle did not publish, or one that a direct registry call replaced after publication, fails as an
     * invalid transition and stays registered; one published for another owner is only reported, never touched.
     */
    private ManagedRegistrations.Standing preflight(DefinitionKey key) {
        ManagedRegistrations.Standing standing = registrations.classify(
                key.tenantKey(),
                key.definitionId(),
                key.definitionVersion(),
                held(registry.get(), key.definitionId(), key.definitionVersion()));
        if (standing == ManagedRegistrations.Standing.UNATTRIBUTED)
            throw failure(
                    Reason.INVALID_TRANSITION,
                    key,
                    "the registered runtime was not published by the durable lifecycle for this definition");
        return standing;
    }

    /** The runtime the registry holds for the exact id and version, or {@code null} when it holds none. */
    private static @Nullable RuntimeWorkflow held(WorkflowRegistry local, String definitionId, long version) {
        try {
            return local.resolvePinned(definitionId, version);
        } catch (WorkflowVersionPinUnavailableException | WorkflowDefinitionMissingException absent) {
            return null;
        }
    }

    private void attribute(Attempt attempt) {
        if (attempt.publication != null)
            registrations.attribute(
                    attempt.key.tenantKey(),
                    attempt.key.definitionId(),
                    attempt.key.definitionVersion(),
                    attempt.publication);
    }

    /** Reads the current status, then moves an active or superseded row to deprecated; already deprecated is accepted. */
    private Future<Void> commitDeprecation(DefinitionKey key, boolean retryOnConflict) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        return durable.find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
            DefinitionStatus status = record.status();
            if (status == DefinitionStatus.DEPRECATED) return Future.<Void>succeededFuture();
            if (status != DefinitionStatus.ACTIVE && status != DefinitionStatus.SUPERSEDED)
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
                            && (now == DefinitionStatus.ACTIVE || now == DefinitionStatus.SUPERSEDED);
                    return retryOnConflict && moved
                            ? commitDeprecation(key, false)
                            : Future.<Void>failedFuture(original);
                });
            });
        });
    }

    private Future<Void> activateQueued(DefinitionKey key) {
        WorkflowDefinitionRegistryStore durable = store.orElseThrow();
        Attempt attempt = new Attempt(key);
        Future<Void> publication = durable.find(key)
                .<Void>compose(found -> {
                    WorkflowDefinitionRecord record =
                            found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
                    if (record.status() == DefinitionStatus.ACTIVE) {
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
                                attempt.definition = attempt.loader.load(attempt.record, source);
                                String hash = attempt.loader.planHash(attempt.definition);
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
                                local.register(attempt.definition);
                                attempt.publication = local.resolvePinned(key.definitionId(), key.definitionVersion());
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
        // Hook failures fail the operation, but cannot undo a committed activation.
        return publication.compose(ignored -> {
            if (attempt.definition == null) return Future.succeededFuture();
            return durable.find(key).map(found -> {
                WorkflowDefinitionRecord current =
                        found.orElseThrow(() -> failure(Reason.STALE_CLAIM, key, "completed row disappeared"));
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
                    && current.filter(r ->
                                    (r.status() == DefinitionStatus.ACTIVE || r.status() == DefinitionStatus.SUPERSEDED)
                                            && r.planHash().equals(attempt.record.planHash()))
                            .isPresent()) {
                attribute(attempt);
                uncertain.remove(attempt.key);
                return Future.succeededFuture();
            }
            return compensate(attempt, original);
        });
    }

    private Future<ActivationOutcome> complete(Attempt attempt) {
        Future<ActivationOutcome> completion;
        try {
            completion = store.orElseThrow().complete(attempt.claim);
        } catch (RuntimeException failure) {
            completion = Future.failedFuture(failure);
        }
        return completion.recover(failure -> {
            // Only a refusal produced by this complete call can establish this attempt's failure.
            if (failure instanceof WorkflowDefinitionLifecycleException refusal
                    && refusal.key().equals(attempt.claim.key())
                    && (refusal.reason() == Reason.STALE_CLAIM || refusal.reason() == Reason.OWNER_CONFLICT))
                attempt.definitiveCompletionFailure = failure;
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
                    if (isRegistered(local, attempt.publication))
                        local.unregister(attempt.key.definitionId(), attempt.key.definitionVersion());
                    registrations.clear(
                            attempt.key.definitionId(), attempt.key.definitionVersion(), attempt.publication);
                    // Forget the runtime only once it is gone; a failed removal leaves it to be adopted by a retry.
                    uncertain.remove(attempt.key, attempt.publication);
                } catch (RuntimeException compensation) {
                    if (compensation != original) original.addSuppressed(compensation);
                }
            }
            return Future.failedFuture(original);
        });
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
}
