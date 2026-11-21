// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.workflow.actor.ActorText;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.state.HistoryActor;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Who made the last change to a stored definition or activation, in the shape the instance history uses: the actor, the
 * party it acted for and how far the actor can be relied on.
 *
 * <p>The registry keeps only the last change of a row. A full record of every change is the job of a
 * {@link WorkflowDefinitionLifecycleListener}.
 *
 * @param actor       who made the change; {@link HistoryActor.Kind#NONE} when no identity was present
 * @param onBehalfOf  the subject the actor acted for, or {@code null}
 * @param attribution how far the actor can be relied on
 */
public record DefinitionChangeActor(
        HistoryActor actor, @Nullable String onBehalfOf, HistoryAttribution attribution) {

    /**
     * Validates the required components and cleans the actor's id and reason and the party it acted for the way a
     * command's actor is cleaned: control characters, line and paragraph separators and format characters are removed
     * and the text is cut at 255 code points, so a value always fits its column.
     */
    public DefinitionChangeActor {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(attribution, "attribution");
        actor = new HistoryActor(actor.kind(), ActorText.clean(actor.id()), ActorText.clean(actor.reason()));
        onBehalfOf = ActorText.clean(onBehalfOf);
    }

    /**
     * Returns the absence of an actor.
     *
     * @return no actor, with absent attribution
     */
    public static DefinitionChangeActor unattributed() {
        return new DefinitionChangeActor(
                new HistoryActor(HistoryActor.Kind.NONE, null, null), null, HistoryAttribution.ABSENT);
    }

    /**
     * Returns the system as the actor, for a change the framework makes by itself.
     *
     * @param reason why the system acted, for example {@code bootstrap}
     * @return the system actor with verified attribution
     */
    public static DefinitionChangeActor system(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new DefinitionChangeActor(
                new HistoryActor(HistoryActor.Kind.SYSTEM, null, reason), null, HistoryAttribution.VERIFIED);
    }

    /**
     * Takes the actor from the security identity in the current context, the way the engine takes the actor of a
     * command: never from a payload, with the {@link WorkflowActorMapper} deciding what kind of actor an identity is.
     * An anonymous identity, or no identity, is {@link #unattributed()}. It must run on the calling thread, before any
     * asynchronous hop.
     *
     * @param holder the holder the current security context is read from
     * @param mapper the mapper from a security identity to a workflow actor
     * @return the actor; never {@code null}
     * @throws RuntimeException whatever the mapper throws for the identity, unchanged
     */
    public static DefinitionChangeActor capture(ContextHolder holder, WorkflowActorMapper mapper) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(mapper, "mapper");
        SecurityIdentity identity = holder.current(SecurityContext.class)
                .map(SecurityContext::identity)
                .orElse(null);
        if (identity == null || identity.actor().type() == PrincipalType.ANONYMOUS) {
            return unattributed();
        }
        WorkflowActor mapped = mapper.toWorkflowActor(identity);
        String actorId = identity.actor().id();
        String onBehalfOf = identity.subject()
                .map(subject -> subject.id())
                .filter(subjectId -> !subjectId.equals(actorId))
                .orElse(null);
        HistoryAttribution attribution =
                holder.current(SnapshotDegradationMarker.class).isPresent()
                        ? HistoryAttribution.DEGRADED
                        : HistoryAttribution.VERIFIED;
        HistoryActor logged =
                switch (mapped) {
                    case WorkflowActor.User user -> new HistoryActor(HistoryActor.Kind.USER, user.userId(), null);
                    case WorkflowActor.Service service ->
                        new HistoryActor(HistoryActor.Kind.SERVICE, service.serviceId(), null);
                    case WorkflowActor.System system ->
                        new HistoryActor(HistoryActor.Kind.SYSTEM, null, system.reason());
                };
        return new DefinitionChangeActor(logged, onBehalfOf, attribution);
    }
}
