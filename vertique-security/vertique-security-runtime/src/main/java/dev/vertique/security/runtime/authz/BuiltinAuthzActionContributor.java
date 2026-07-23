// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;

/**
 * Built-in {@link ActionContributor} that reserves the framework's own authorization actions.
 *
 * <p>Contributes two stable actions into the {@link dev.vertique.security.authz.ActionRegistry}:
 * <ul>
 *   <li>{@code authz.action.list} — authorizes access to the action-registry introspection endpoint
 *       (Phase 5: lists all registered actions)</li>
 *   <li>{@code authz.action.introspect} — authorizes access to the per-subject introspection
 *       endpoint (Phase 5: returns the set of actions allowed for the calling subject)</li>
 * </ul>
 *
 * <p>Both actions are in the {@code authz} subsystem, reserving that segment for the framework.
 * Application subsystems must use a different leading segment.
 *
 * <p>This contributor is bound {@code @IntoSet} by {@link dev.vertique.security.runtime.authz.SecurityAuthzModule}
 * and is present in every application that includes that module.
 */
@Singleton
public final class BuiltinAuthzActionContributor implements ActionContributor {

    /** The canonical action for listing all registered actions (registry endpoint). */
    public static final ActionRef AUTHZ_ACTION_LIST = ActionRef.of("authz", "action", "list");

    /** The canonical action for per-subject allowed-action introspection (introspection endpoint). */
    public static final ActionRef AUTHZ_ACTION_INTROSPECT = ActionRef.of("authz", "action", "introspect");

    private static final List<ActionDefinition> BUILTIN_ACTIONS =
            List.of(new ActionDefinition(AUTHZ_ACTION_LIST), new ActionDefinition(AUTHZ_ACTION_INTROSPECT));

    /**
     * Creates the built-in contributor. Injected by Dagger as a {@link Singleton}.
     */
    @Inject
    public BuiltinAuthzActionContributor() {}

    /**
     * Returns the two built-in framework authorization actions.
     *
     * @return an immutable collection containing {@code authz.action.list} and
     *     {@code authz.action.introspect}; never {@code null}
     */
    @Override
    public Collection<ActionDefinition> actions() {
        return BUILTIN_ACTIONS;
    }
}
