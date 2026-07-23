// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable aggregate of identity information for a single request or operation.
 *
 * <p>A {@code SecurityIdentity} always carries a mandatory {@link #actor()} — the principal that
 * is directly authenticated. The remaining fields are optional and express more complex delegation
 * patterns:
 * <ul>
 *   <li>{@link #subject()} — the end-user on whose behalf the actor is operating (PSD2/PIS,
 *       token exchange)</li>
 *   <li>{@link #delegation()} — the authority grant that permits the actor-to-subject delegation
 *       </li>
 *   <li>{@link #client()} — the OAuth 2.0 application through which the request arrived</li>
 * </ul>
 *
 * <p>Use the typed factory methods for common shapes:
 * <ul>
 *   <li>{@link #user(PrincipalRef)} — actor must be {@link PrincipalType#USER}</li>
 *   <li>{@link #service(PrincipalRef)} — actor must be {@link PrincipalType#SERVICE}</li>
 *   <li>{@link #anonymous()} — unauthenticated caller</li>
 * </ul>
 *
 * <p>{@link PrincipalType#SYSTEM} identities must be created via {@link SystemIdentities}; there
 * is intentionally no {@code system(...)} factory on this class.
 *
 * @param actor      the directly authenticated principal; never null
 * @param subject    the end-user on whose behalf the actor is operating; {@link Optional#empty()}
 *                   when not applicable
 * @param delegation the authority grant permitting actor-to-subject delegation;
 *                   {@link Optional#empty()} when not applicable
 * @param client     the OAuth 2.0 client through which the request arrived;
 *                   {@link Optional#empty()} when not applicable
 */
public record SecurityIdentity(
        PrincipalRef actor,
        Optional<PrincipalRef> subject,
        Optional<DelegationContext> delegation,
        Optional<ClientRef> client) {

    /**
     * Compact constructor — validates that actor and all Optional fields are non-null.
     */
    public SecurityIdentity {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(client, "client");
    }

    // --- factory methods ---

    /**
     * Creates a {@code SecurityIdentity} for an authenticated human end-user.
     *
     * @param actor the user principal; must have type {@link PrincipalType#USER}
     * @return a new identity with all optional fields empty
     * @throws IllegalArgumentException if {@code actor.type()} is not {@link PrincipalType#USER}
     */
    public static SecurityIdentity user(PrincipalRef actor) {
        if (actor.type() != PrincipalType.USER) {
            throw new IllegalArgumentException("user() factory requires PrincipalType.USER, got: " + actor.type());
        }
        return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Creates a {@code SecurityIdentity} for an authenticated service or application.
     *
     * @param actor the service principal; must have type {@link PrincipalType#SERVICE}
     * @return a new identity with all optional fields empty
     * @throws IllegalArgumentException if {@code actor.type()} is not
     *         {@link PrincipalType#SERVICE}
     */
    public static SecurityIdentity service(PrincipalRef actor) {
        if (actor.type() != PrincipalType.SERVICE) {
            throw new IllegalArgumentException(
                    "service() factory requires PrincipalType.SERVICE, got: " + actor.type());
        }
        return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Creates a {@code SecurityIdentity} for an unauthenticated caller.
     *
     * <p>The actor is a fixed {@link PrincipalType#ANONYMOUS} principal with id {@code "anonymous"}
     * and no attributes.
     *
     * @return a new anonymous identity with all optional fields empty
     */
    public static SecurityIdentity anonymous() {
        PrincipalRef anon = new PrincipalRef(PrincipalType.ANONYMOUS, "anonymous", Map.of());
        return new SecurityIdentity(anon, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
