// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import dev.vertique.security.authz.ActionRef;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * The immutable authorization requirement resolved for one tool at compile time.
 *
 * <p>A tool resolves exactly one base policy and may additionally resolve one action. The three
 * legal shapes mirror the REST semantics:
 *
 * <ul>
 *   <li>{@link McpAccessMode#PERMIT_ALL} — public; no roles and no action.
 *   <li>{@link McpAccessMode#DENY_ALL} — closed; no roles and no action.
 *   <li>{@link McpAccessMode#RESTRICTED} — at least one role, an action, or both. Roles and an
 *       action compose with AND.
 * </ul>
 *
 * @param mode the resolved base policy
 * @param roles the required roles, defensively copied and empty unless {@code mode} is
 *     {@link McpAccessMode#RESTRICTED}
 * @param action the required action, or {@code null} when the tool declares none
 */
public record McpToolAccess(
        McpAccessMode mode, List<String> roles, @Nullable ActionRef action) {

    /**
     * Validates the resolved policy shape and defensively copies the role list.
     *
     * @throws NullPointerException if {@code mode} or {@code roles} is null, or if {@code roles}
     *     contains a null element ({@code List.copyOf} rejects null elements before the blank check
     *     runs)
     * @throws IllegalArgumentException if a role is blank, if a non-restricted mode carries roles or
     *     an action, or if a restricted mode carries neither a role nor an action
     */
    public McpToolAccess {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(roles, "roles");
        roles = List.copyOf(roles);
        if (roles.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("roles must not contain a blank role");
        }
        if (mode == McpAccessMode.RESTRICTED) {
            if (roles.isEmpty() && action == null) {
                throw new IllegalArgumentException("restricted tools require at least one role or an action");
            }
        } else if (!roles.isEmpty() || action != null) {
            throw new IllegalArgumentException(mode + " tools must not declare roles or an action");
        }
    }
}
