// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

/**
 * The identity on whose behalf an administrative command runs.
 *
 * <p>The facade never resolves an identity itself: the caller, typically an administration adapter,
 * projects its authenticated principal into this value. The facade records it and does not authorize it.
 *
 * @param type kind of actor (for example {@code "user"} or {@code "service"}); 1 to 64 characters, not
 *             blank
 * @param id   identifier of the actor within its type; 1 to 255 characters, not blank; neither part may contain a
 *             control character or an unpaired surrogate
 */
public record OutboxAdminActor(String type, String id) {

    /** Maximum length of {@link #type()} in characters. */
    public static final int MAX_TYPE_LENGTH = 64;

    /** Maximum length of {@link #id()} in characters. */
    public static final int MAX_ID_LENGTH = 255;

    /**
     * Creates an actor.
     *
     * @param type kind of actor; 1 to 64 characters, not blank
     * @param id   identifier of the actor within its type; 1 to 255 characters, not blank
     * @throws dev.vertique.core.exception.ValidationException if a component is absent, blank, too long, or holds a
     *         control character or an unpaired surrogate
     */
    public OutboxAdminActor {
        AdminChecks.text(type, "actor type", MAX_TYPE_LENGTH);
        AdminChecks.text(id, "actor id", MAX_ID_LENGTH);
    }

    /** Returns a fixed string that carries no actor data, so the actor cannot leak through logging. */
    @Override
    public String toString() {
        return "OutboxAdminActor[redacted]";
    }
}
