// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Classifies the kind of principal represented by a {@link PrincipalRef}.
 *
 * <p>Each constant describes the authentication and authorization semantics of the principal:
 * <ul>
 *   <li>{@link #USER} — a human end-user authenticated via an identity provider</li>
 *   <li>{@link #SERVICE} — a machine/application principal authenticated via client credentials or
 *       mTLS</li>
 *   <li>{@link #SYSTEM} — a framework-internal actor (workflow engine, scheduler, etc.); created
 *       exclusively via {@link SystemIdentities}</li>
 *   <li>{@link #ANONYMOUS} — an unauthenticated caller; created via
 *       {@link SecurityIdentity#anonymous()}</li>
 * </ul>
 */
public enum PrincipalType {

    /** Human end-user authenticated via an identity provider. */
    USER,

    /** Machine or application principal authenticated via client credentials or mTLS. */
    SERVICE,

    /**
     * Framework-internal actor (workflow engine, scheduled job, etc.).
     * Must be created exclusively via {@link SystemIdentities}.
     */
    SYSTEM,

    /** Unauthenticated caller. Created via {@link SecurityIdentity#anonymous()}. */
    ANONYMOUS
}
