// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.util.Collections;

/**
 * Fails the request with 403 when the operation's effective policy requires roles the caller
 * {@link StubScheme} authenticated does not hold; otherwise continues. Stands in for the security
 * module's enforcement so the fixture needs no security runtime.
 */
final class RoleCheck implements OperationHandlerContributor {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void contribute(OperationRegistrationContext context) {
        SecurityPolicy policy = context.securityPolicy();
        context.route().addHandler(ctx -> {
            if (policy instanceof SecurityPolicy.Constrained constrained
                    && !constrained.requiredRoles().isEmpty()
                    && Collections.disjoint(constrained.requiredRoles(), StubScheme.roles(ctx))) {
                ctx.fail(403);
                return;
            }
            ctx.next();
        });
    }

    @Override
    public String orderKey() {
        return "roleCheck";
    }
}
