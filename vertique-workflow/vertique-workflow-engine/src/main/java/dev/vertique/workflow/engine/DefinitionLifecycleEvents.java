// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.executor.CommandActors;
import dev.vertique.workflow.engine.history.CapturedActor;
import dev.vertique.workflow.engine.history.ListenerDispatch;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.ActivationChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.ActivationOperation;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionChange;
import dev.vertique.workflow.registry.store.DefinitionLifecycleChange.VersionOperation;
import dev.vertique.workflow.registry.store.DefinitionLifecycleFailure;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycleListener;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: tells the {@link WorkflowDefinitionLifecycleListener}s what the definition registry changed, after the
 * change is durable, and which change it refused or failed to commit.
 *
 * <p>{@link #around} wraps a public lifecycle operation: it captures the actor on the calling thread, mints the id
 * of the change, and reports the outcome of the future the operation returns. {@link #bootstrapped} reports the
 * versions that startup recorded, which bypass the lifecycle. A listener is called on the thread that completes the
 * operation; an {@link Exception} it throws is logged and never reaches the operation, whose result is always returned
 * unchanged, while an {@link Error} is not caught. The actor and {@code occurredAt} are taken when the operation is
 * called, not when it completes. For an activation, {@code targetScopeKey} is the scope that owns the version for
 * {@code ACTIVATE}, the default scope for {@code FOLLOW} and {@code null} for {@code DEACTIVATE_DEFAULT}.
 *
 * <p>With no listener bound the wrappers only call the operation: no actor is captured and nothing is built.
 */
@Singleton
final class DefinitionLifecycleEvents {

    private static final Logger log = LoggerFactory.getLogger(DefinitionLifecycleEvents.class);
    private static final String BOOTSTRAP_REASON = "bootstrap";

    /** Builds the change of one call, from the values that must be taken before the call is queued. */
    @FunctionalInterface
    interface ChangeFactory {
        DefinitionLifecycleChange build(UUID changeId, CapturedActor actor, Instant occurredAt);
    }

    private final List<WorkflowDefinitionLifecycleListener> listeners;
    private final Supplier<CommandActor> actors;
    private final Clock clock;

    @Inject
    DefinitionLifecycleEvents(Set<WorkflowDefinitionLifecycleListener> listeners, CommandActors actors) {
        this(listeners, actors::capture, Clock.systemUTC());
    }

    DefinitionLifecycleEvents(
            Set<WorkflowDefinitionLifecycleListener> listeners, Supplier<CommandActor> actors, Clock clock) {
        this.listeners = List.copyOf(listeners);
        this.actors = actors;
        this.clock = clock;
    }

    /** Returns events with no listener, which only call the operations they are given. */
    static DefinitionLifecycleEvents disabled() {
        return new DefinitionLifecycleEvents(
                Set.of(),
                () -> {
                    throw new IllegalStateException("no listener is bound");
                },
                Clock.systemUTC());
    }

    boolean enabled() {
        return !listeners.isEmpty();
    }

    /**
     * Runs a lifecycle operation and reports its outcome.
     *
     * @param factory builds the change from the actor captured here, or {@code null} when the arguments do not
     *                allow a change to be built, so only the operation runs
     * @param start   starts the operation, on the calling thread; an exception it throws becomes a failed future
     * @return the future of the operation, unchanged
     */
    Future<Void> around(@Nullable ChangeFactory factory, Supplier<Future<Void>> start) {
        DefinitionLifecycleChange change = enabled() && factory != null ? build(factory) : null;
        Future<Void> result;
        try {
            result = start.get();
        } catch (RuntimeException failure) {
            result = Future.failedFuture(failure);
        }
        if (change != null) {
            result.onComplete(done -> {
                if (done.succeeded()) {
                    changed(change);
                } else {
                    failed(change, done.cause());
                }
            });
        }
        return result;
    }

    /** Builds the change on the calling thread; arguments that cannot form a change are the operation's to refuse. */
    private @Nullable DefinitionLifecycleChange build(ChangeFactory factory) {
        try {
            return factory.build(UUID.randomUUID(), CapturedActor.capture(actors), clock.instant());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Nullable
    ChangeFactory version(VersionOperation operation, @Nullable DefinitionKey key) {
        return key == null
                ? null
                : (id, actor, at) -> new VersionChange(
                        id, operation, key, actor.actor(), actor.onBehalfOf(), actor.attribution(), at);
    }

    ChangeFactory activation(
            ActivationOperation operation,
            String scopeKey,
            String definitionId,
            @Nullable String targetScopeKey,
            @Nullable Long version) {
        return (id, actor, at) -> new ActivationChange(
                id,
                operation,
                scopeKey,
                definitionId,
                targetScopeKey,
                version,
                actor.actor(),
                actor.onBehalfOf(),
                actor.attribution(),
                at);
    }

    /**
     * Reports the versions that startup recorded for the first time, after the transaction that recorded them
     * committed: a publication, and an activation of the default when the version also became the default. Any other
     * outcome changed nothing that a listener hears of.
     *
     * @param key     the version startup reconciled
     * @param outcome what the reconciliation did
     */
    void bootstrapped(DefinitionKey key, ReconcileOutcome outcome) {
        if (!enabled()
                || (outcome != ReconcileOutcome.INSERTED && outcome != ReconcileOutcome.INSERTED_AND_ACTIVATED)) {
            return;
        }
        CapturedActor actor = CapturedActor.of(CommandActor.system(BOOTSTRAP_REASON));
        changed(new VersionChange(
                UUID.randomUUID(),
                VersionOperation.PUBLISH,
                key,
                actor.actor(),
                actor.onBehalfOf(),
                actor.attribution(),
                clock.instant()));
        if (outcome == ReconcileOutcome.INSERTED_AND_ACTIVATED) {
            changed(new ActivationChange(
                    UUID.randomUUID(),
                    ActivationOperation.ACTIVATE,
                    WorkflowScopes.DEFAULT,
                    key.definitionId(),
                    WorkflowScopes.DEFAULT,
                    key.definitionVersion(),
                    actor.actor(),
                    actor.onBehalfOf(),
                    actor.attribution(),
                    clock.instant()));
        }
    }

    private void changed(DefinitionLifecycleChange change) {
        ListenerDispatch.forEach(
                listeners,
                listener -> listener.onDefinitionLifecycleChange(change),
                (listener, thrown) -> log.warn(
                        "Definition lifecycle listener {} failed for change {}",
                        listener.getClass().getName(),
                        change.changeId(),
                        thrown));
    }

    private void failed(DefinitionLifecycleChange change, Throwable cause) {
        DefinitionLifecycleFailure failure = new DefinitionLifecycleFailure(
                change,
                cause instanceof WorkflowDefinitionLifecycleException refusal ? refusal.reason() : null,
                cause.getClass().getSimpleName());
        ListenerDispatch.forEach(
                listeners,
                listener -> listener.onDefinitionLifecycleFailed(failure),
                (listener, thrown) -> log.warn(
                        "Definition lifecycle listener {} failed for the failure of change {}",
                        listener.getClass().getName(),
                        change.changeId(),
                        thrown));
    }
}
