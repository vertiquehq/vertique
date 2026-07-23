// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Stable vocabulary of machine-readable reason codes carried on a {@link DelegationGrantDecision}.
 *
 * <p>Mirrors the {@code AuthzReasonCodes} convention used for {@code AuthorizationDecision}: each
 * constant is a frozen wire value emitted on delegation-grant evaluation and safe for audit output.
 * Exactly one reason code is reported per decision.
 *
 * <p>This class is not instantiable.
 */
public final class DelegationReasonCodes {

    private DelegationReasonCodes() {
        throw new AssertionError("DelegationReasonCodes is not instantiable");
    }

    // --- permit ---

    /** The grant currently authorizes the evaluated (actor, subject, scope) triple. */
    public static final String GRANT_VALID = "GRANT_VALID";

    // --- deny ---

    /** No grant with the requested id exists. */
    public static final String GRANT_NOT_FOUND = "GRANT_NOT_FOUND";

    /** A grant was found but its {@code expiresAt} has already passed. */
    public static final String GRANT_EXPIRED = "GRANT_EXPIRED";

    /**
     * A grant was found but does not match the requested actor/subject direction, or the requested
     * {@code scopeKind}/{@code scopeRef}.
     */
    public static final String GRANT_OUT_OF_SCOPE = "GRANT_OUT_OF_SCOPE";

    /**
     * The grant lookup itself failed or timed out (e.g. a durable store outage). Fail-closed: never
     * surfaced as a failed {@link io.vertx.core.Future} — always mapped to this deny reason instead.
     */
    public static final String GRANT_LOOKUP_FAILED = "GRANT_LOOKUP_FAILED";
}
