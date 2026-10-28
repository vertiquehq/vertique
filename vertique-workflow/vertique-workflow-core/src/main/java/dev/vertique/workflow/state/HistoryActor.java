// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.state;

import jakarta.annotation.Nullable;

/**
 * The actor of a history entry as it was logged.
 *
 * <p>{@link Kind#NONE} records that work ran with no identity at all; it exists only here, never as a caller identity.
 *
 * @param kind   the kind of actor
 * @param id     the actor's identifier, or {@code null} when the kind has none
 * @param reason the reason the actor is of this kind, for example why work ran as the system, or {@code null}
 */
public record HistoryActor(
        Kind kind, @Nullable String id, @Nullable String reason) {

    /** The kinds of actor a history entry records. */
    public enum Kind {
        /** A person. */
        USER,
        /** A service identity. */
        SERVICE,
        /** The system itself, for example a timer or a recovery. */
        SYSTEM,
        /** No identity was present. */
        NONE
    }
}
