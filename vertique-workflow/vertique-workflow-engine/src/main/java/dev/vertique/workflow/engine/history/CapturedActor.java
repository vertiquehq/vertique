// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import dev.vertique.workflow.engine.spi.CommandActor;
import dev.vertique.workflow.history.HistoryAttribution;
import dev.vertique.workflow.state.HistoryActor;
import jakarta.annotation.Nullable;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: the actor of a workflow action in the form that history events and registry changes report it.
 *
 * @param actor       who acted
 * @param onBehalfOf  the subject the actor acted for, or {@code null}
 * @param attribution how far the actor can be relied on
 */
public record CapturedActor(HistoryActor actor, @Nullable String onBehalfOf, HistoryAttribution attribution) {

    private static final Logger log = LoggerFactory.getLogger(CapturedActor.class);

    /**
     * Returns the absence of an actor.
     *
     * @return no actor, with absent attribution
     */
    public static CapturedActor unattributed() {
        return new CapturedActor(new HistoryActor(HistoryActor.Kind.NONE, null, null), null, HistoryAttribution.ABSENT);
    }

    /**
     * Maps the actor of a command.
     *
     * @param actor the command actor
     * @return the same actor as reported
     */
    public static CapturedActor of(CommandActor actor) {
        return new CapturedActor(
                new HistoryActor(HistoryActor.Kind.valueOf(actor.kind().name()), actor.id(), actor.reason()),
                actor.onBehalfOf(),
                HistoryAttribution.valueOf(actor.attribution().name()));
    }

    /**
     * Captures the actor of the current caller. It must run on the calling thread, before any asynchronous hop; a
     * failed capture is logged by its exception class only and falls back to no actor.
     *
     * @param actors the capture of the actor of the current caller
     * @return the captured actor, never {@code null}
     */
    public static CapturedActor capture(Supplier<CommandActor> actors) {
        try {
            return of(actors.get());
        } catch (Exception | LinkageError | AssertionError | StackOverflowError failure) {
            // the exception message can carry identity text, so only its class is logged
            log.warn(
                    "The actor of a workflow action could not be captured ({}); it is reported as absent",
                    failure.getClass().getName());
            return unattributed();
        }
    }
}
