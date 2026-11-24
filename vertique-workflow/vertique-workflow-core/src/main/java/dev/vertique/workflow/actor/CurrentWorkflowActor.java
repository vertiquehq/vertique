// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * The caller of the current request as a {@link WorkflowActor}.
 *
 * <p>The identity is read from the {@link SecurityContext} that the {@link ContextHolder} holds for the calling
 * thread and is mapped by the {@link WorkflowActorMapper}: the application's binding when it has one, the
 * {@link DefaultWorkflowActorMapper} otherwise. The mapper decides who a caller is recorded as, so a service that acts
 * for a user can be recorded as the user, as the service, or as anything else the mapper chooses.
 *
 * <p>Use it to take the actor of a task command from the authenticated caller, never from a request body:
 *
 * <pre>{@code
 * WorkflowActor actor = currentActor.get().orElseThrow();
 * TaskCompletionCommand command = new TaskCompletionCommand(taskId, "approve", payload, key, actor, null);
 * }</pre>
 *
 * <p>Call {@link #get()} on the thread that handles the request, before any asynchronous hop: a thread that the
 * request moves to later may hold a different context or none.
 *
 * <p>The engine takes the actor of every other workflow action, such as a start, a signal or a cancel, from the same
 * identity in the same way.
 */
@Singleton
public final class CurrentWorkflowActor {

    private final ContextHolder holder;
    private final WorkflowActorMapper mapper;

    /**
     * Creates the reader for a Dagger graph: the application's mapper when it binds one, the default otherwise.
     *
     * @param holder the holder the current security context is read from
     * @param mapper the application's mapper binding, if any
     */
    @Inject
    public CurrentWorkflowActor(ContextHolder holder, Optional<WorkflowActorMapper> mapper) {
        this(holder, WorkflowActorMapper.orDefault(mapper));
    }

    /**
     * Creates the reader with the mapper to use.
     *
     * @param holder the holder the current security context is read from
     * @param mapper the mapper from a security identity to a workflow actor
     */
    public CurrentWorkflowActor(ContextHolder holder, WorkflowActorMapper mapper) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Returns the caller of the current context as a workflow actor.
     *
     * <p>An unauthenticated caller is mapped like any other: the default mapper gives
     * {@link WorkflowActor.Anonymous}.
     *
     * @return the actor the mapper gives for the caller's identity; empty when the current context holds no security
     *     context
     * @throws NullPointerException if the mapper returns {@code null}
     * @throws RuntimeException whatever the mapper throws for the identity, unchanged
     */
    public Optional<WorkflowActor> get() {
        return holder.current(SecurityContext.class)
                .map(SecurityContext::identity)
                .map(this::map);
    }

    private WorkflowActor map(SecurityIdentity identity) {
        return Objects.requireNonNull(mapper.toWorkflowActor(identity), "mapped actor");
    }
}
