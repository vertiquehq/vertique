// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.PersistedDefinitionLoader;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Moves durable definition rows through their lifecycle: publishes document candidates, deprecates, retires and
 * reinstates versions, and keeps the activation records.
 *
 * <p>Every change is a change of the row in the definition store; the node keeps no copy of it. A node that
 * runs a version reads the row, and builds the plan of a document from it, when the version is first used.
 *
 * <p>Publishing a candidate claims the row, compiles the stored text, checks the rebuilt plan hash against the row's,
 * builds the plan so that every plan rule and validator refuses an invalid document before it becomes available, and
 * only then completes the claim. A failure before the completion releases the claim; a completion whose outcome is
 * unknown is decided by reading the row again.
 *
 * <p>Reinstating a retired version first restores its executable source: the deployed code for a code row, or the
 * stored document text for a document row, each checked against the hash of the row. Only then is the durable change
 * committed.
 */
@Singleton
final class DefaultWorkflowDefinitionLifecycle implements WorkflowDefinitionLifecycle {

    private final WorkflowDefinitionRegistryStore store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;
    private final WorkflowCodeDefinitionCatalog catalog;

    /** Constructs a lifecycle over an empty code catalog, for unit tests only. */
    DefaultWorkflowDefinitionLifecycle(
            WorkflowDefinitionRegistryStore store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue) {
        this(store, registry, loaders, queue, new WorkflowCodeDefinitionCatalog());
    }

    @Inject
    DefaultWorkflowDefinitionLifecycle(
            WorkflowDefinitionRegistryStore store,
            Provider<WorkflowRegistry> registry,
            Set<PersistedDefinitionLoader> loaders,
            DefinitionApplyQueue queue,
            WorkflowCodeDefinitionCatalog catalog) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = registry;
        this.loaders = loaders;
        this.queue = queue;
        this.catalog = catalog;
    }

    @Override
    public Future<Void> publish(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            return queue.submit(key.definitionId(), () -> publishQueued(key));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> deprecate(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            return queue.submit(key.definitionId(), () -> commitDeprecation(key, true));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> retire(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
            return queue.submit(key.definitionId(), () -> retireQueued(key));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    @Override
    public Future<Void> reinstate(DefinitionKey key) {
        try {
            Objects.requireNonNull(key, "key");
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
            return queue.submit(definitionId, () -> store.putActivation(activation));
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
            return store.listActivations(scopeKey);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    private Future<Void> removeRecord(String scopeKey, String definitionId) {
        return queue.submit(definitionId, () -> store.removeActivation(scopeKey, definitionId));
    }

    /**
     * Reads the row and reinstates it. A retired row has its executable source restored and checked, and then the
     * durable change is committed. A deprecated row needs no source check: its change is committed at once.
     */
    private Future<Void> reinstateQueued(DefinitionKey key) {
        return store.find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
            DefinitionStatus status = record.status();
            if (status == DefinitionStatus.DEPRECATED) {
                return commitReinstatement(record);
            }
            if (status != DefinitionStatus.RETIRED)
                throw failure(Reason.INVALID_TRANSITION, key, "cannot reinstate status " + status);
            return restoreSource(record).compose(ignored -> commitReinstatement(record));
        });
    }

    /**
     * Checks that the executable source of the retired row is available and has the row's hash: the deployed code
     * for a code row, and for a document row the stored text, which must compile and build under the names the row
     * was recorded with.
     */
    private Future<Void> restoreSource(WorkflowDefinitionRecord record) {
        DefinitionKey key = record.key();
        if (record.kind() != DefinitionKind.DOCUMENT) {
            DefinitionPublication.verifiedCode(catalog, record);
            return Future.succeededFuture();
        }
        PersistedDefinitionLoader loader = DefinitionPublication.loaderFor(loaders, record);
        return store.findDocument(key)
                .compose(source -> work(() -> {
                    var text =
                            source.orElseThrow(() -> failure(Reason.LOADER_MISSING, key, "stored source is missing"));
                    buildStored(loader, record, text);
                    return (Void) null;
                }));
    }

    /**
     * Compiles the stored text of a row, checks the rebuilt plan hash against the row's and builds the plan under the
     * names the row was recorded with, which applies every plan rule and validator.
     */
    private WorkflowDefinition<?, ?> buildStored(
            PersistedDefinitionLoader loader, WorkflowDefinitionRecord record, WorkflowDefinitionDocument text) {
        DefinitionKey key = record.key();
        requireBoundNamesResolve(record);
        WorkflowDefinition<?, ?> definition = loader.load(record, text);
        String hash = DefinitionPublication.planHash(loader, definition, record);
        if (!definition.definitionId().equals(key.definitionId())
                || definition.definitionVersion() != key.definitionVersion()
                || !record.planHash().equals(hash))
            throw failure(
                    Reason.DRIFT, key, "stored hash " + record.planHash() + " differs from compiled hash " + hash);
        DefinitionPublication.build(registry.get(), key, definition, record.nameBinding());
        return definition;
    }

    /** Refuses a row whose bound type names no class resolves any more, before anything is loaded or built. */
    private void requireBoundNamesResolve(WorkflowDefinitionRecord record) {
        if (record.nameBinding().isEmpty()) return;
        DefinitionPublication.requireBoundNamesResolve(WorkflowEngineFactory.registryTypeNames(registry.get()), record);
    }

    /**
     * Commits the durable reinstatement. A failure whose outcome is unknown is decided by reading the row again: an
     * available row with the plan hash of the row that was read was committed, and any other row was not.
     */
    private Future<Void> commitReinstatement(WorkflowDefinitionRecord record) {
        Future<Void> transition;
        try {
            transition = store.transition(record.key(), record.status(), DefinitionStatus.AVAILABLE);
        } catch (RuntimeException failure) {
            transition = Future.failedFuture(failure);
        }
        return transition.recover(original -> {
            if (original instanceof Error) return Future.failedFuture(original);
            return store.find(record.key()).transform(read -> {
                if (read.failed()) {
                    if (read.cause() != original) original.addSuppressed(read.cause());
                    return Future.<Void>failedFuture(original);
                }
                boolean committed = read.result()
                        .filter(row -> row.status() == DefinitionStatus.AVAILABLE
                                && row.planHash().equals(record.planHash()))
                        .isPresent();
                return committed ? Future.<Void>succeededFuture() : Future.<Void>failedFuture(original);
            });
        });
    }

    /**
     * Commits the retirement. A retirement that fails is read back: a row that is retired means the commit won.
     */
    private Future<Void> retireQueued(DefinitionKey key) {
        return store.retire(key).recover(original -> {
            if (original instanceof Error) return Future.failedFuture(original);
            return store.find(key).transform(read -> {
                boolean retired = read.succeeded()
                        && read.result()
                                .map(WorkflowDefinitionRecord::status)
                                .filter(DefinitionStatus.RETIRED::equals)
                                .isPresent();
                if (retired) return Future.<Void>succeededFuture();
                if (read.failed() && read.cause() != original) original.addSuppressed(read.cause());
                return Future.<Void>failedFuture(original);
            });
        });
    }

    /** Reads the current status, then moves an available row to deprecated; already deprecated is accepted. */
    private Future<Void> commitDeprecation(DefinitionKey key, boolean retryOnConflict) {
        return store.find(key).compose(found -> {
            WorkflowDefinitionRecord record =
                    found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
            DefinitionStatus status = record.status();
            if (status == DefinitionStatus.DEPRECATED) return Future.<Void>succeededFuture();
            if (status != DefinitionStatus.AVAILABLE)
                throw failure(Reason.INVALID_TRANSITION, key, "cannot deprecate status " + status);
            return store.transition(key, status, DefinitionStatus.DEPRECATED).recover(original -> {
                if (original instanceof Error) return Future.failedFuture(original);
                // A failed transition may still have committed, so re-read before deciding.
                return store.find(key).transform(read -> {
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
        Attempt attempt = new Attempt(key);
        return store.find(key)
                .<Void>compose(found -> {
                    WorkflowDefinitionRecord record =
                            found.orElseThrow(() -> failure(Reason.INVALID_TRANSITION, key, "definition not found"));
                    if (record.status() == DefinitionStatus.AVAILABLE) return Future.succeededFuture();
                    if (record.kind() != DefinitionKind.DOCUMENT)
                        throw failure(
                                Reason.INVALID_TRANSITION, key, "CODE definitions register from deployed sources");
                    return store.claim(key)
                            .compose(claim -> {
                                attempt.claim = claim;
                                return store.find(key);
                            })
                            .compose(current -> {
                                attempt.record = current.orElseThrow(
                                        () -> failure(Reason.STALE_CLAIM, key, "claimed row disappeared"));
                                attempt.loader = DefinitionPublication.loaderFor(loaders, attempt.record);
                                return store.findDocument(key);
                            })
                            .compose(document -> work(() -> {
                                var source = document.orElseThrow(
                                        () -> failure(Reason.LOADER_MISSING, key, "stored source is missing"));
                                buildStored(attempt.loader, attempt.record, source);
                                return (Void) null;
                            }))
                            .compose(ignored -> complete(attempt));
                })
                .recover(original -> recover(attempt, original));
    }

    /**
     * Decides a publication that failed. A failure before the completion releases the claim. A completion that
     * failed with a definitive refusal releases it too; one whose outcome is unknown is decided by reading the row
     * again, and when that read fails too the claim stays for the stale-claim reset.
     */
    private Future<Void> recover(Attempt attempt, Throwable original) {
        if (original instanceof Error || attempt.claim == null) return Future.failedFuture(original);
        if (original == attempt.definitiveCompletionFailure || !attempt.completing)
            return compensate(attempt, original);
        return store.find(attempt.key).transform(read -> {
            if (read.failed()) {
                if (read.cause() != original) original.addSuppressed(read.cause());
                return Future.failedFuture(original);
            }
            boolean committed = read.result()
                    .filter(row -> row.status() == DefinitionStatus.AVAILABLE
                            && row.planHash().equals(attempt.record.planHash()))
                    .isPresent();
            return committed ? Future.<Void>succeededFuture() : compensate(attempt, original);
        });
    }

    private Future<Void> complete(Attempt attempt) {
        Future<Void> completion;
        attempt.completing = true;
        try {
            completion = store.complete(attempt.claim).mapEmpty();
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
            release = store.release(attempt.claim);
        } catch (RuntimeException failure) {
            release = Future.failedFuture(failure);
        }
        return release.transform(released -> {
            if (released.failed() && released.cause() != original) original.addSuppressed(released.cause());
            return Future.failedFuture(original);
        });
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
        /** Whether the completion was started, so that a failure may have committed. */
        boolean completing;

        Throwable definitiveCompletionFailure;

        Attempt(DefinitionKey key) {
            this.key = key;
        }
    }
}
