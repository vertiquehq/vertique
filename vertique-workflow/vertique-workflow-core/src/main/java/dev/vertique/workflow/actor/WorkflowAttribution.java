// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.PrincipalRef;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Who a workflow action is recorded as done by: the actor, and the party it acted for. Attribution is recorded
 * evidence and does not confer authority.
 *
 * <p>An action with no attributable caller has no actor. The subject holds only its durable type and identifier.
 * The text is kept as given: nothing is stripped or cut, and whoever prints it formats it for where it goes. A value
 * the store cannot hold is refused instead: an actor id, a system reason or a subject id of more than
 * {@value #MAX_CODE_POINTS} code points, or one that holds a NUL character or an unpaired surrogate.
 *
 * @param actor the actor, or {@code null} when the action had no attributable caller
 * @param onBehalfOf the party the actor acted for, or {@code null}; requires an actor
 */
public record WorkflowAttribution(
        @Nullable WorkflowActor actor, @Nullable PrincipalRef onBehalfOf) {

    /** The most code points of an actor id, a system reason or a subject id. */
    public static final int MAX_CODE_POINTS = 255;

    private static final WorkflowAttribution ABSENT = new WorkflowAttribution(null, null);

    /**
     * Validates the attribution and drops request-scoped attributes from the subject.
     *
     * @throws IllegalArgumentException if a subject is given without an actor, or a text value cannot be stored
     */
    public WorkflowAttribution {
        if (actor == null && onBehalfOf != null) {
            throw new IllegalArgumentException("A subject requires an actor");
        }
        switch (actor) {
            case WorkflowActor.User user -> requireStorable(user.userId(), "actor id");
            case WorkflowActor.Service service -> requireStorable(service.serviceId(), "actor id");
            case WorkflowActor.System system -> requireStorable(system.reason(), "actor reason");
            case null -> {
                // no actor, nothing to check
            }
        }
        if (onBehalfOf != null) {
            requireStorable(onBehalfOf.id(), "subject id");
            if (!onBehalfOf.attributes().isEmpty()) {
                onBehalfOf = new PrincipalRef(onBehalfOf.type(), onBehalfOf.id(), Map.of());
            }
        }
    }

    /**
     * Returns the attribution of an action with no attributable caller.
     *
     * @return no actor and no subject
     */
    public static WorkflowAttribution absent() {
        return ABSENT;
    }

    /**
     * Returns the system as the actor, for an action the framework takes by itself.
     *
     * @param reason why the system acted, for example {@code bootstrap}
     * @return the system actor, acting for no one
     */
    public static WorkflowAttribution system(String reason) {
        return new WorkflowAttribution(new WorkflowActor.System(reason), null);
    }

    // The message names the field only: the value is identity text and may be hostile.
    private static void requireStorable(String text, String field) {
        if (text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("The " + field + " is longer than " + MAX_CODE_POINTS + " code points");
        }
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            boolean paired = Character.isHighSurrogate(unit)
                    && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1));
            if (unit == 0 || (Character.isSurrogate(unit) && !paired)) {
                throw new IllegalArgumentException("The " + field + " holds a character that cannot be stored");
            }
            if (paired) {
                index++;
            }
        }
    }
}
