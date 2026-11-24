// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.workflow.actor.CurrentWorkflowActor;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.history.ListenerDispatch;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the actor of a command from the caller that records it, and tells the executor observers about a command that
 * was recorded with no actor.
 *
 * <p>The actor is read by {@link CurrentWorkflowActor} on the calling thread, never from a payload, a header or a
 * carrier, and nothing is ever bound: the executor that runs a command later reads the actor from the command's row
 * and never creates or rebuilds a security context.
 */
@Singleton
final class RecordedActors {

    private static final Logger log = LoggerFactory.getLogger(RecordedActors.class);

    private final CurrentWorkflowActor current;
    private final List<WorkflowExecutorObserver> observers;

    @Inject
    RecordedActors(CurrentWorkflowActor current, Set<WorkflowExecutorObserver> observers) {
        this.current = Objects.requireNonNull(current, "current");
        this.observers = List.copyOf(observers);
    }

    /** Actors over the framework's default holder and mapper, with no observers. */
    static RecordedActors ambient() {
        return new RecordedActors(
                new CurrentWorkflowActor(new DefaultContextHolder(), new DefaultWorkflowActorMapper()), Set.of());
    }

    /**
     * Takes the actor of the current caller as a future, so that a failure of the actor mapper refuses the recording
     * the way any other failed step does.
     *
     * @return the actor, {@code null} when the context holds no security context, or the mapper's failure
     */
    Future<WorkflowActor> captured() {
        try {
            return Future.succeededFuture(current.get().orElse(null));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Captures the actor and runs the write that records a command with it. The write is not run when the capture
     * fails, so a mapper failure refuses the recording with the mapper's own exception. When the write succeeds and
     * there is no actor, the observers are told of an {@code ActorAbsent} event for the command kind.
     *
     * @param commandKind the kind of the command being recorded, for the event
     * @param write       the write that records the command with the actor
     * @param <T>         the type the write yields
     * @return the future of the write, or the failure of the capture
     */
    <T> Future<T> recording(String commandKind, Function<WorkflowActor, Future<T>> write) {
        return captured().compose(actor -> write.apply(actor).onSuccess(ignored -> reportAbsence(actor, commandKind)));
    }

    /**
     * Tells the observers that a command was recorded with no actor, when that is so.
     *
     * @param actor       the actor the command was recorded with
     * @param commandKind the kind of the command
     */
    void reportAbsence(@Nullable WorkflowActor actor, String commandKind) {
        if (actor != null || observers.isEmpty()) {
            return;
        }
        WorkflowExecutorEvent event = new WorkflowExecutorEvent.ActorAbsent(commandKind);
        ListenerDispatch.forEachIsolatingErrors(
                observers,
                observer -> observer.onEvent(event),
                (observer, thrown) -> log.warn(
                        "Executor observer {} threw while handling a {} event: {}",
                        observer.getClass().getName(),
                        event.getClass().getSimpleName(),
                        thrown.getClass().getName()));
    }
}
