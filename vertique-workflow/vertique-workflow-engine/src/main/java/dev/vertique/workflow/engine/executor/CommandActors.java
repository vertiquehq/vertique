// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActorCapture;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * INTERNAL: takes the actor of a command from the security identity of the caller that records it.
 *
 * <p>The identity is read from the current {@link dev.vertique.security.SecurityContext} through the {@link ContextHolder}, never from a
 * payload, a header or a carrier, and nothing is ever bound: the executor that runs a command later reads the actor
 * from the command's row and never creates or rebuilds a security context.
 *
 */
@Singleton
public final class CommandActors {

    private final ContextHolder holder;
    private final WorkflowActorMapper mapper;
    private final ExecutorEvents events;

    /**
     * Creates the capture for a Dagger graph: the application's mapper when it binds one, the default otherwise.
     *
     * @param holder    the holder the current security context is read from
     * @param bound     the application's mapper binding, if any
     * @param observers the executor observers that receive an actor-absent event for every recording with no actor
     */
    @Inject
    CommandActors(ContextHolder holder, Optional<WorkflowActorMapper> bound, Set<WorkflowExecutorObserver> observers) {
        this(holder, WorkflowActorMapper.orDefault(bound), observers);
    }

    /**
     * Creates the capture with the observers that are told about a recording with no actor.
     *
     * @param holder    the holder the current security context is read from
     * @param mapper    the mapper from a security identity to a workflow actor
     * @param observers the executor observers that receive an actor-absent event for every recording with no actor
     */
    public CommandActors(ContextHolder holder, WorkflowActorMapper mapper, Set<WorkflowExecutorObserver> observers) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.events = ExecutorEvents.to(Objects.requireNonNull(observers, "observers"));
    }

    /**
     * Creates the capture with no observers.
     *
     * @param holder the holder the current security context is read from
     * @param mapper the mapper from a security identity to a workflow actor
     */
    public CommandActors(ContextHolder holder, WorkflowActorMapper mapper) {
        this(holder, mapper, Set.of());
    }

    /**
     * Creates the capture over the framework's default holder and mapper, with no observers.
     *
     * @return the capture
     */
    public static CommandActors ambient() {
        return new CommandActors(new DefaultContextHolder(), new DefaultWorkflowActorMapper());
    }

    /**
     * Takes the actor from the current security identity.
     *
     * @return the actor; {@link WorkflowAttribution.Status#ABSENT} when there is no identity or it is anonymous
     * @throws RuntimeException whatever the actor mapper throws for the identity, unchanged
     */
    public WorkflowAttribution capture() {
        return WorkflowActorCapture.capture(holder, mapper);
    }

    /**
     * Takes the actor from the current security identity as a future, so that a failure of the actor mapper refuses the
     * recording the way any other failed step does.
     *
     * @return the actor, or the mapper's failure
     */
    public Future<WorkflowAttribution> captured() {
        try {
            return Future.succeededFuture(capture());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Captures the actor and runs the write that records a command with it. The write is not run when the capture
     * fails, so a mapper failure refuses the recording with the mapper's own exception. When the write succeeds and
     * the actor is {@link WorkflowAttribution.Status#ABSENT}, the observers are told of an {@code ActorAbsent} event for the
     * command kind.
     *
     * @param commandKind the kind of the command being recorded, for the event
     * @param write       the write that records the command with the actor
     * @param <T>         the type the write yields
     * @return the future of the write, or the failure of the capture
     */
    public <T> Future<T> recording(String commandKind, Function<WorkflowAttribution, Future<T>> write) {
        return captured().compose(actor -> write.apply(actor).onSuccess(ignored -> reportAbsence(actor, commandKind)));
    }

    /**
     * Tells the observers that a command was recorded with no actor, when that is so.
     *
     * @param actor       the actor the command was recorded with
     * @param commandKind the kind of the command
     */
    public void reportAbsence(WorkflowAttribution actor, String commandKind) {
        if (actor.status() == WorkflowAttribution.Status.ABSENT && events.any()) {
            events.emit(new WorkflowExecutorEvent.ActorAbsent(commandKind));
        }
    }
}
