// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.spi;

import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Who asked for a command, as the command log and the history keep it.
 *
 * <p>INTERNAL: framework wiring between the engine and the repositories. The values are taken from the security
 * identity of the caller that recorded the command, never from a payload, and are already stripped of control
 * characters and bounded by the engine; a repository stores them as they are.
 *
 * @param kind        the kind of actor; {@link Kind#NONE} when the caller had no identity or an anonymous one
 * @param id          the user or service id; {@code null} for a system actor and for {@link Kind#NONE}
 * @param reason      the reason a system actor names; {@code null} for the other kinds
 * @param onBehalfOf  the subject the actor acted for when it differs from the actor, or {@code null}
 * @param attribution how far the actor can be relied on
 */
public record CommandActor(
        Kind kind,
        @Nullable String id,
        @Nullable String reason,
        @Nullable String onBehalfOf,
        Attribution attribution) {

    /** The kinds of actor that are logged. {@code NONE} exists only on the logged actor. */
    public enum Kind {
        /** A person. */
        USER,
        /** A service principal. */
        SERVICE,
        /** The system, with a reason. */
        SYSTEM,
        /** No identity, or an anonymous one. */
        NONE
    }

    /** How far a logged actor can be relied on. */
    public enum Attribution {
        /** The actor is the verified identity of the caller. */
        VERIFIED,
        /** There was no identity to attribute to. */
        ABSENT,
        /** The identity was rebuilt from a snapshot that could not be verified, and the delivery continued. */
        DEGRADED
    }

    /**
     * Creates an actor.
     *
     * @param kind        the kind of actor
     * @param id          the user or service id, or {@code null}
     * @param reason      the reason of a system actor, or {@code null}
     * @param onBehalfOf  the subject acted for, or {@code null}
     * @param attribution how far the actor can be relied on
     */
    public CommandActor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(attribution, "attribution");
    }

    /**
     * The actor of a command recorded with no identity.
     *
     * @return {@link Kind#NONE}, {@link Attribution#ABSENT}
     */
    public static CommandActor none() {
        return new CommandActor(Kind.NONE, null, null, null, Attribution.ABSENT);
    }

    /**
     * The actor of a command the system records itself, such as a timer.
     *
     * @param reason the reason the system names
     * @return a verified system actor
     */
    public static CommandActor system(String reason) {
        return new CommandActor(Kind.SYSTEM, null, reason, null, Attribution.VERIFIED);
    }
}
