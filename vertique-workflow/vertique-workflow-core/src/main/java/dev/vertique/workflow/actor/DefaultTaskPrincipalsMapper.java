// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.workflow.tasks.TaskPrincipals;
import java.util.Objects;
import java.util.Set;

/**
 * The default {@link TaskPrincipalsMapper}, used when the application binds none.
 *
 * <ul>
 *   <li><b>User id and type</b> are those of the effective principal: the delegated subject when the
 *       identity carries one, the actor otherwise. A caller acting on behalf of a user sees that user's
 *       tasks. This differs from {@link DefaultWorkflowActorMapper}, which records the actor as the one
 *       who acted.</li>
 *   <li><b>Roles</b> are the {@link AuthorityKind#ROLE} authority values.</li>
 *   <li><b>Queues</b> are the {@link AuthorityKind#GROUP} authority values.</li>
 * </ul>
 *
 * <p>Values are taken exactly as the claim mapper emitted them. No other authority kind and no
 * attribute contributes. A principal that is not a user keeps its type, so it matches no assignment.
 *
 * <p><b>Delegation.</b> The authority claims of a security context are those of the authenticated
 * principal, the actor. When the identity carries a subject other than the actor, the claims may not
 * be the subject's, so this mapper takes no roles and no queues: the subject matches only the tasks
 * assigned to its user id. An application whose delegated tokens carry the subject's own roles and
 * groups binds its own mapper.
 */
public final class DefaultTaskPrincipalsMapper implements TaskPrincipalsMapper {

    /** Creates the mapper. */
    public DefaultTaskPrincipalsMapper() {}

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    public TaskPrincipals toTaskPrincipals(SecurityContext context) {
        Objects.requireNonNull(context, "context");
        SecurityIdentity identity = context.identity();
        PrincipalRef actor = identity.actor();
        PrincipalRef principal = identity.subject().orElse(actor);
        if (!principal.id().equals(actor.id()) || principal.type() != actor.type()) {
            // A delegated subject: the claims belong to the actor, so none of them is the subject's.
            return new TaskPrincipals(principal.id(), principal.type(), Set.of(), Set.of());
        }
        return new TaskPrincipals(
                principal.id(),
                principal.type(),
                context.authorization().valuesOf(AuthorityKind.ROLE),
                context.authorization().valuesOf(AuthorityKind.GROUP));
    }
}
