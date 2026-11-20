// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.ext.web.RoutingContext;
import java.util.Arrays;
import java.util.List;

/**
 * The {@value #NAME} security scheme of the fixture: authenticates from the {@value #USER_HEADER}
 * header, failing with 401 when it is absent, and stashes the comma-separated roles of
 * {@value #ROLES_HEADER} on the routing context for {@link RoleCheck} to read.
 */
final class StubScheme implements SecuritySchemeHandler {

    /** The security scheme name {@link ManagementResource} declares. */
    static final String NAME = "stub";

    /** Header naming the authenticated user; its absence fails authentication with 401. */
    static final String USER_HEADER = "X-Test-User";

    /** Header carrying the authenticated user's comma-separated roles, if any. */
    static final String ROLES_HEADER = "X-Test-Roles";

    private static final String ROLES_KEY = "applicationLabelIT.roles";

    @Override
    public String schemeName() {
        return NAME;
    }

    @Override
    public void configure(SecuritySchemeRegistry registry) {
        registry.authenticationHandler(ctx -> {
            String user = ctx.request().getHeader(USER_HEADER);
            if (user == null) {
                ctx.fail(401);
                return;
            }
            ctx.put(ROLES_KEY, parseRoles(ctx.request().getHeader(ROLES_HEADER)));
            ctx.next();
        });
    }

    /**
     * Returns the authenticated caller's roles, or an empty list when none were stashed.
     *
     * @param ctx the routing context
     * @return the authenticated caller's roles
     */
    static List<String> roles(RoutingContext ctx) {
        List<String> roles = ctx.get(ROLES_KEY);
        return roles != null ? roles : List.of();
    }

    private static List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        return Arrays.asList(rolesHeader.split(","));
    }
}
