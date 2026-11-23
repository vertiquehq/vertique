// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.PrincipalRef;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * The actor and provenance recorded for a workflow action. The subject contains only its durable
 * type and identifier. Attribution is recorded evidence and does not confer authority.
 *
 * @param actor the actor, or {@code null} when absent or unavailable
 * @param onBehalfOf the delegated subject, or {@code null}
 * @param status how the attribution was established
 */
public record WorkflowAttribution(
        @Nullable WorkflowActor actor, @Nullable PrincipalRef onBehalfOf, Status status) {

    /** How the workflow obtained the recorded attribution. */
    public enum Status {
        /** A verified caller or a framework-generated system action. */
        VERIFIED,
        /** The caller identity was reconstructed from an unverified snapshot. */
        DEGRADED,
        /** The action had no attributable caller. */
        ABSENT,
        /** Capture failed or the stored attribution could not be interpreted. */
        UNAVAILABLE
    }

    /** Validates the attribution and drops request-scoped attributes from the delegated subject. */
    public WorkflowAttribution {
        Objects.requireNonNull(status, "status");
        if ((status == Status.VERIFIED || status == Status.DEGRADED) != (actor != null)
                || (actor == null && onBehalfOf != null)) {
            throw new IllegalArgumentException("Actor and subject must agree with attribution status");
        }
        if (onBehalfOf != null && !onBehalfOf.attributes().isEmpty()) {
            onBehalfOf = new PrincipalRef(onBehalfOf.type(), onBehalfOf.id(), Map.of());
        }
    }

    public static WorkflowAttribution absent() {
        return new WorkflowAttribution(null, null, Status.ABSENT);
    }

    public static WorkflowAttribution unavailable() {
        return new WorkflowAttribution(null, null, Status.UNAVAILABLE);
    }

    public static WorkflowAttribution system(String reason) {
        return new WorkflowAttribution(new WorkflowActor.System(reason), null, Status.VERIFIED);
    }
}
