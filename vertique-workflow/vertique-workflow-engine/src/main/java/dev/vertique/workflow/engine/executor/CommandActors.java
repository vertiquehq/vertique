// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.workflow.actor.ActorText;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.executor.WorkflowExecutorEvent;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * INTERNAL: takes the actor of a command from the security identity of the caller that records it.
 *
 * <p>The identity is read from the current {@link SecurityContext} through the {@link ContextHolder}, never from a
 * payload, a header or a carrier, and nothing is ever bound: the executor that runs a command later reads the actor
 * from the command's row and never creates or rebuilds a security context.
 *
 * <ul>
 *   <li>No security context, or an anonymous identity, is {@link CommandActor.Kind#NONE}. This is decided before the
 *       {@link WorkflowActorMapper} runs, so the mapper never sees an anonymous identity.</li>
 *   <li>Any other identity is mapped by the mapper; an exception the mapper throws propagates unchanged and refuses
 *       the recording.</li>
 *   <li>The party the actor acted for ({@code onBehalfOf}) is the identity's subject when its id differs from the
 *       actor's.</li>
 *   <li>The attribution is {@code ABSENT} with no identity, {@code DEGRADED} when the context carries the security
 *       module's snapshot-degradation marker (a relayed identity that could not be verified and was let through), and
 *       {@code VERIFIED} otherwise.</li>
 *   <li>Ids and reasons are logged without control characters, without the line and paragraph separators U+2028 and
 *       U+2029 and without Unicode format characters (bidirectional overrides, zero-width characters), then cut at
 *       {@value #MAX_LENGTH} characters. An id left empty by that is logged as {@value #UNPRINTABLE}.</li>
 * </ul>
 */
@Singleton
public final class CommandActors {

    /** The most characters of an actor id, reason or party acted for that are logged. */
    static final int MAX_LENGTH = ActorText.MAX_LENGTH;

    /** What an id, reason or party acted for is logged as when nothing of it is printable. */
    static final String UNPRINTABLE = ActorText.UNPRINTABLE;

    private final ContextHolder holder;
    private final WorkflowActorMapper mapper;
    private final ExecutorEvents events;

    /**
     * Creates the capture with the observers that are told about a recording with no actor.
     *
     * @param holder    the holder the current security context is read from
     * @param mapper    the mapper from a security identity to a workflow actor
     * @param observers the executor observers that receive an actor-absent event for every recording with no actor
     */
    @Inject
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
     * @return the actor; {@link CommandActor.Kind#NONE} when there is no identity or it is anonymous
     * @throws RuntimeException whatever the actor mapper throws for the identity, unchanged
     */
    public CommandActor capture() {
        SecurityIdentity identity = holder.current(SecurityContext.class)
                .map(SecurityContext::identity)
                .orElse(null);
        if (identity == null || identity.actor().type() == PrincipalType.ANONYMOUS) {
            return CommandActor.none();
        }
        WorkflowActor mapped = mapper.toWorkflowActor(identity);
        String actorId = identity.actor().id();
        String onBehalfOf = identity.subject()
                .map(subject -> subject.id())
                .filter(subjectId -> !subjectId.equals(actorId))
                .map(CommandActors::clean)
                .orElse(null);
        CommandActor.Attribution attribution =
                holder.current(SnapshotDegradationMarker.class).isPresent()
                        ? CommandActor.Attribution.DEGRADED
                        : CommandActor.Attribution.VERIFIED;
        return switch (mapped) {
            case WorkflowActor.User user ->
                new CommandActor(CommandActor.Kind.USER, clean(user.userId()), null, onBehalfOf, attribution);
            case WorkflowActor.Service service ->
                new CommandActor(CommandActor.Kind.SERVICE, clean(service.serviceId()), null, onBehalfOf, attribution);
            case WorkflowActor.System system ->
                new CommandActor(CommandActor.Kind.SYSTEM, null, clean(system.reason()), onBehalfOf, attribution);
        };
    }

    /**
     * Takes the actor from the current security identity as a future, so that a failure of the actor mapper refuses the
     * recording the way any other failed step does.
     *
     * @return the actor, or the mapper's failure
     */
    public Future<CommandActor> captured() {
        try {
            return Future.succeededFuture(capture());
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Captures the actor and runs the write that records a command with it. The write is not run when the capture
     * fails, so a mapper failure refuses the recording with the mapper's own exception. When the write succeeds and
     * the actor is {@link CommandActor.Kind#NONE}, the observers are told of an {@code ActorAbsent} event for the
     * command kind.
     *
     * @param commandKind the kind of the command being recorded, for the event
     * @param write       the write that records the command with the actor
     * @param <T>         the type the write yields
     * @return the future of the write, or the failure of the capture
     */
    public <T> Future<T> recording(String commandKind, Function<CommandActor, Future<T>> write) {
        return captured().compose(actor -> write.apply(actor).onSuccess(ignored -> reportAbsence(actor, commandKind)));
    }

    /**
     * Tells the observers that a command was recorded with no actor, when that is so.
     *
     * @param actor       the actor the command was recorded with
     * @param commandKind the kind of the command
     */
    public void reportAbsence(CommandActor actor, String commandKind) {
        if (actor.kind() == CommandActor.Kind.NONE && events.any()) {
            events.emit(new WorkflowExecutorEvent.ActorAbsent(commandKind));
        }
    }

    /**
     * Removes control characters, the line and paragraph separators U+2028 and U+2029, and Unicode format characters
     * (bidirectional overrides and isolates, zero-width characters), then cuts the text at the bound. Text that is
     * left empty by the removal, such as an id made only of such characters, becomes {@value #UNPRINTABLE}, so that an
     * identity is never recorded as a verified actor without an id.
     */
    @Nullable
    static String clean(@Nullable String text) {
        return ActorText.clean(text);
    }
}
