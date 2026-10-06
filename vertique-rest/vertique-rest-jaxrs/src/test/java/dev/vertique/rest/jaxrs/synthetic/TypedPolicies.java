// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.RequiresAction;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

/**
 * The access policies the typed synthetic operation proofs install: one per supported requirement
 * shape. Every policy is a public, directly declared {@link AccessPolicy} so the resolver accepts
 * it; the {@link #ACTION} every action-bearing policy requires is the one the fixture's action
 * registry knows.
 */
public final class TypedPolicies {

    /** The action every action-bearing fixture policy requires, registered by the fixture. */
    public static final String ACTION = "typed.doc.read";

    /** An action no fixture registry registers. */
    public static final String UNREGISTERED_ACTION = "typed.doc.unregistered";

    /** The role every role-bearing fixture policy requires. */
    public static final String ROLE = "admin";

    /** The scope every scope-bearing fixture policy requires. */
    public static final String SCOPE = "docs.read";

    private TypedPolicies() {}

    /** Anyone may call. */
    @PermitAll
    public interface Public extends AccessPolicy {}

    /** Nobody may call. */
    @DenyAll
    public interface Deny extends AccessPolicy {}

    /** Any authenticated caller may call. */
    @Authorized
    public interface Authenticated extends AccessPolicy {}

    /** Callers holding the {@link #ROLE} role may call. */
    @RolesAllowed(ROLE)
    public interface Roles extends AccessPolicy {}

    /** Callers holding the {@link #SCOPE} scope may call. */
    @Authorized(scopes = SCOPE)
    public interface Scopes extends AccessPolicy {}

    /** Callers the {@link #ACTION} action gate permits may call. */
    @RequiresAction(ACTION)
    public interface Action extends AccessPolicy {}

    /** Callers holding the {@link #ROLE} role and permitted the {@link #ACTION} action may call. */
    @RolesAllowed(ROLE)
    @RequiresAction(ACTION)
    public interface RolesAndAction extends AccessPolicy {}

    /** Callers holding the role and the scope and permitted the action may call. */
    @RolesAllowed(ROLE)
    @Authorized(scopes = SCOPE)
    @RequiresAction(ACTION)
    public interface Combined extends AccessPolicy {}

    /** Requires an action that no fixture registry registers. */
    @RequiresAction(UNREGISTERED_ACTION)
    public interface UnregisteredAction extends AccessPolicy {}
}
