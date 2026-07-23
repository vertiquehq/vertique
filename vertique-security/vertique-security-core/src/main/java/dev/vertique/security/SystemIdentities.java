// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Optional;

/**
 * Factory class for {@link PrincipalType#SYSTEM} identities.
 *
 * <p>This is the only sanctioned path to creating a {@link SecurityIdentity} with a
 * {@link PrincipalType#SYSTEM} actor. Each factory method requires a non-blank reason string that
 * downstream audit events and logs propagate, ensuring every system action is traceable.
 *
 * <p>{@link SecurityIdentity} deliberately exposes no {@code system(...)} factory method; callers
 * must use this class to make the provenance explicit.
 */
public final class SystemIdentities {

    private SystemIdentities() {
        /* utility class — no instances */
    }

    // --- factories ---

    /**
     * Creates a {@code SecurityIdentity} for the workflow engine.
     *
     * @param reason non-null, non-blank description of why the workflow is acting
     * @return a new SYSTEM identity with actor id {@code "system:workflow"} and
     *         {@code system.reason} attribute set to {@code reason}
     * @throws IllegalArgumentException if {@code reason} is null, empty, or blank
     */
    public static SecurityIdentity workflow(String reason) {
        return systemIdentity("workflow", reason);
    }

    /**
     * Creates a {@code SecurityIdentity} for a scheduled job.
     *
     * @param reason non-null, non-blank description of why the job is acting
     * @return a new SYSTEM identity with actor id {@code "system:scheduledJob"} and
     *         {@code system.reason} attribute set to {@code reason}
     * @throws IllegalArgumentException if {@code reason} is null, empty, or blank
     */
    public static SecurityIdentity scheduledJob(String reason) {
        return systemIdentity("scheduledJob", reason);
    }

    /**
     * Creates a {@code SecurityIdentity} for internal framework operations (e.g. outbox relay,
     * delayed-job retry).
     *
     * @param reason non-null, non-blank description of why the internal operation is acting
     * @return a new SYSTEM identity with actor id {@code "system:internal"} and
     *         {@code system.reason} attribute set to {@code reason}
     * @throws IllegalArgumentException if {@code reason} is null, empty, or blank
     */
    public static SecurityIdentity internal(String reason) {
        return systemIdentity("internal", reason);
    }

    // --- private helpers ---

    /**
     * Validates reason and constructs a SYSTEM {@link SecurityIdentity} for the given name.
     *
     * @param name   short name identifying the system actor (e.g. {@code "workflow"})
     * @param reason the caller-supplied provenance reason
     * @return the constructed identity
     */
    private static SecurityIdentity systemIdentity(String name, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be null or blank");
        }
        PrincipalRef actor = new PrincipalRef(PrincipalType.SYSTEM, "system:" + name, Map.of("system.reason", reason));
        return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
