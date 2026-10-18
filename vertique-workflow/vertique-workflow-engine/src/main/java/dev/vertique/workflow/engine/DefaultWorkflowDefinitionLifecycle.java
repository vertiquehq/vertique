// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
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
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Publishes durable document activations and compensates only the captured local publication. */
@Singleton
final class DefaultWorkflowDefinitionLifecycle implements WorkflowDefinitionLifecycle {
    private final Optional<WorkflowDefinitionRegistryStore> store;
    private final Provider<WorkflowRegistry> registry;
    private final Set<PersistedDefinitionLoader> loaders;
    private final DefinitionApplyQueue queue;

    @Inject
    DefaultWorkflowDefinitionLifecycle(
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
            return queue.submit(
                    key.definitionId(), () -> commitDeprecation(key, true).compose(ignored -> {
                        registry.get().markDeprecated(key.definitionId(), key.definitionVersion());
                        return Future.<Void>succeededFuture();
                    }));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
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
                    if (record.status() == DefinitionStatus.ACTIVE) return Future.succeededFuture();
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
                                local.register(attempt.definition);
                                attempt.publication = local.resolvePinned(key.definitionId(), key.definitionVersion());
                                return (Void) null;
                            }))
                            .compose(ignored -> complete(attempt))
                            .mapEmpty();
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
                // The commit outcome remains unknown: preserve the publication for reconciliation.
                return Future.failedFuture(original);
            }
            Optional<WorkflowDefinitionRecord> current = read.result();
            if (attempt.publication != null
                    && current.filter(r ->
                                    (r.status() == DefinitionStatus.ACTIVE || r.status() == DefinitionStatus.SUPERSEDED)
                                            && r.planHash().equals(attempt.record.planHash()))
                            .isPresent()) return Future.succeededFuture();
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
                    if (local.allRegistered().stream().anyMatch(runtime -> runtime == attempt.publication))
                        local.unregister(attempt.key.definitionId(), attempt.key.definitionVersion());
                } catch (RuntimeException compensation) {
                    if (compensation != original) original.addSuppressed(compensation);
                }
            }
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
        WorkflowDefinition<?, ?> definition;
        RuntimeWorkflow publication;
        Throwable definitiveCompletionFailure;

        Attempt(DefinitionKey key) {
            this.key = key;
        }
    }
}
