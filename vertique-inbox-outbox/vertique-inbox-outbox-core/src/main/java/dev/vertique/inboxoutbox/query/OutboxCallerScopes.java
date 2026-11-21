// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.security.PrincipalType;
import java.util.Objects;
import java.util.Set;

/**
 * The scopes a caller may see outbox entries of, with the type of the principal they were taken from.
 *
 * <p>An entry is visible to the caller when its scope key is one of {@link #scopeKeys()}, as
 * {@link #permits(String)} decides, and {@link OutboxQuery#visibleTo()} lists only such entries. Only a
 * {@link PrincipalType#USER} principal ever matches: a service, system or anonymous caller matches no entry,
 * whatever scope keys it carries.
 *
 * <p>Build the scopes from the authenticated security context with an {@link OutboxCallerScopesMapper}, never
 * from request input: whoever controls these values controls which entries the caller sees. A scope key is a
 * partition label, not a grant; this type is the grant.
 *
 * @param principalType the type of the effective principal; must not be null
 * @param scopeKeys the scope keys the caller may see, compared exactly with an entry's scope key; copied,
 *     immutable, possibly empty
 */
public record OutboxCallerScopes(PrincipalType principalType, Set<String> scopeKeys) {

    /**
     * Validates the components and copies the set.
     *
     * @throws NullPointerException if a component or a set element is null
     */
    public OutboxCallerScopes {
        Objects.requireNonNull(principalType, "principalType");
        scopeKeys = Set.copyOf(Objects.requireNonNull(scopeKeys, "scopeKeys"));
    }

    /**
     * Creates the scopes of a {@link PrincipalType#USER} caller.
     *
     * @param scopeKeys the scope keys the caller may see; must not be null
     * @return the scopes
     */
    public static OutboxCallerScopes ofUser(Set<String> scopeKeys) {
        return new OutboxCallerScopes(PrincipalType.USER, scopeKeys);
    }

    /**
     * Tells whether these scopes can match an entry at all, which only a {@link PrincipalType#USER} principal
     * with at least one scope key can.
     *
     * @return {@code true} when the principal is a user and holds a scope key
     */
    public boolean canMatchEntries() {
        return principalType == PrincipalType.USER && !scopeKeys.isEmpty();
    }

    /**
     * Tells whether an entry with the given scope key is visible to the caller.
     *
     * @param scopeKey the scope key of the entry; {@code null} is never permitted
     * @return {@code true} when the caller is a user and {@code scopeKey} is one of its scope keys
     */
    public boolean permits(String scopeKey) {
        return scopeKey != null && canMatchEntries() && scopeKeys.contains(scopeKey);
    }
}
