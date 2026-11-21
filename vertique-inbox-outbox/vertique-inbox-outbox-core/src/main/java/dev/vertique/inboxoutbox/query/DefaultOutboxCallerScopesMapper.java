// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityKind;
import java.util.Objects;
import java.util.Set;

/**
 * The default {@link OutboxCallerScopesMapper}, used when the application binds none.
 *
 * <ul>
 *   <li><b>Type</b> is that of the effective principal: the delegated subject when the identity carries one, the
 *       actor otherwise. Only a user can see entries.</li>
 *   <li><b>Scope keys</b> are the {@link AuthorityKind#ENTITLEMENT} authority values, taken exactly as the claim
 *       mapper emitted them: an entitlement whose value is a scope key grants that scope.</li>
 * </ul>
 *
 * <p>No other authority kind and no attribute contributes, so a caller with no entitlement sees no entry. A
 * principal that is not a user keeps its type, so it matches no entry.
 *
 * <p><b>Delegation.</b> The authority claims of a security context are those of the authenticated principal, the
 * actor. When the identity carries a subject other than the actor, the claims may not be the subject's, so this
 * mapper takes no scope keys: the subject sees nothing. An application whose delegated tokens carry the subject's
 * own entitlements binds its own mapper.
 */
public final class DefaultOutboxCallerScopesMapper implements OutboxCallerScopesMapper {

    /** Creates the mapper. */
    public DefaultOutboxCallerScopesMapper() {}

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    public OutboxCallerScopes toCallerScopes(SecurityContext context) {
        Objects.requireNonNull(context, "context");
        SecurityIdentity identity = context.identity();
        PrincipalRef actor = identity.actor();
        PrincipalRef principal = identity.subject().orElse(actor);
        if (!principal.id().equals(actor.id()) || principal.type() != actor.type()) {
            // A delegated subject: the claims belong to the actor, so none of them is the subject's.
            return new OutboxCallerScopes(principal.type(), Set.of());
        }
        return new OutboxCallerScopes(principal.type(), context.authorization().valuesOf(AuthorityKind.ENTITLEMENT));
    }
}
