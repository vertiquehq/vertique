// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import io.vertx.ext.web.RoutingContext;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code bearerAuth} stub scheme's request-context contract: the {@code X-Test-User} and
 * {@code X-Test-Roles} headers it authenticates from, and the {@link RoutingContext#data()} key it
 * stashes the caller's roles under for {@code authz100} to read.
 */
final class TestAuthentication {

    /** Header naming the authenticated user; its absence fails authentication with 401. */
    static final String USER_HEADER = "X-Test-User";

    /** Header carrying the authenticated user's comma-separated roles, if any. */
    static final String ROLES_HEADER = "X-Test-Roles";

    private static final String ROLES_KEY = "syntheticOperationIT.roles";

    private TestAuthentication() {}

    /**
     * Stashes the authenticated caller's roles on the routing context.
     *
     * @param ctx   the routing context
     * @param roles the authenticated caller's roles
     */
    static void putRoles(RoutingContext ctx, List<String> roles) {
        ctx.put(ROLES_KEY, roles);
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

    /**
     * Parses the {@value #ROLES_HEADER} header value into a role list.
     *
     * @param rolesHeader the raw header value, possibly {@code null} or blank
     * @return the parsed, comma-separated roles, or an empty list when the header is absent or blank
     */
    static List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        return Arrays.asList(rolesHeader.split(","));
    }
}
