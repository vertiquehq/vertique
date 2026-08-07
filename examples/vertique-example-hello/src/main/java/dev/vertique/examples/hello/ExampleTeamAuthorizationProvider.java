// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import io.vertx.core.Future;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import java.util.Set;

/**
 * The framework's worked example of contributing a Vert.x {@link AuthorizationProvider}.
 *
 * <p>Grants the {@code team-lead} role to the subject {@code team-alice} — a stand-in for an
 * external membership lookup (LDAP group, database table, entitlement service) that augments the
 * roles carried in the JWT itself. Because the grant comes from this provider rather than a token
 * claim, it demonstrates that provider-granted roles satisfy {@code @RolesAllowed} exactly like
 * claim-derived ones.
 *
 * <p><strong>Requires {@link dev.vertique.rest.security.VertxAuthorizationImportModule}.</strong>
 * Contributing this provider via {@code @Provides @IntoSet} (see {@link AppModule}) is inert on its
 * own; the import module must also be included in the application component (see
 * {@code AppComponent}) so the provider chain runs during identity resolution and its grants merge
 * into the framework's authorization claims.
 */
public final class ExampleTeamAuthorizationProvider implements AuthorizationProvider {

    /** The subject this example provider recognizes as a team lead. */
    private static final String TEAM_LEAD_SUBJECT = "team-alice";

    @Override
    public String getId() {
        return "teams";
    }

    @Override
    public Future<Void> getAuthorizations(User user) {
        if (TEAM_LEAD_SUBJECT.equals(user.principal().getString("sub"))) {
            user.authorizations().put(getId(), Set.of(RoleBasedAuthorization.create("team-lead")));
        }
        return Future.succeededFuture();
    }
}
