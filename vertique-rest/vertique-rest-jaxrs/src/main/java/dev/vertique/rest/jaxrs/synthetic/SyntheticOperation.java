// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * INTERNAL: describes one framework-owned synthetic operation for {@link SyntheticOperationInstaller}.
 *
 * <p>An operation is guarded by one security scheme, or by none for a public typed operation, and is
 * authenticated-only ({@link #authenticated}), restricted to a non-empty list of roles ({@link
 * #withRoles}), or governed by a typed {@link AccessPolicy} ({@link #withPolicy}). {@link
 * SyntheticOperationInstaller#install} gives it the effective security policy of a resource method
 * annotated with {@code @SecurityRequirement(name = schemeName)} plus, respectively,
 * {@code @Authorized}, {@code @RolesAllowed(rolesAllowed)}, or the requirements the policy declares.
 *
 * <p>Instances are immutable. The factories reject a {@code null} argument with a {@link
 * NullPointerException} naming it, and {@code withRoles} rejects a role list that is empty or holds a
 * blank role with an {@link IllegalArgumentException} naming {@code rolesAllowed}, so no operation,
 * and therefore no route, can exist with such a list. {@code withPolicy} carries its policy as given
 * and does not judge it; the installer resolves it and refuses an invalid policy before it registers
 * any route.
 *
 * <p>Public only for cross-module use by sibling framework modules; outside the maturity promise
 * and not an application contract.
 */
public final class SyntheticOperation {

    private final String origin;
    private final String operationId;
    private final String schemeName;
    private final String applicationName;
    private final @Nullable List<String> rolesAllowed;
    private final @Nullable Class<? extends AccessPolicy> accessPolicy;

    private SyntheticOperation(
            String origin,
            String operationId,
            String schemeName,
            String applicationName,
            @Nullable List<String> rolesAllowed,
            @Nullable Class<? extends AccessPolicy> accessPolicy) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.schemeName = Objects.requireNonNull(schemeName, "schemeName");
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.rolesAllowed = rolesAllowed;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Builds an authenticated-only synthetic operation: any caller the scheme authenticates may
     * invoke it, as on a resource method annotated {@code @Authorized}.
     *
     * @param origin          an opaque message prefix identifying the declaration that produced
     *                        this operation; every installation failure message starts with it
     * @param operationId     the synthetic operation id
     * @param schemeName      the security scheme name the operation is guarded by
     * @param applicationName the name of the application whose document this operation serves,
     *                        stored unchanged
     * @return the built operation
     * @throws NullPointerException if any argument is {@code null}; the message names it
     */
    public static SyntheticOperation authenticated(
            String origin, String operationId, String schemeName, String applicationName) {
        return new SyntheticOperation(origin, operationId, schemeName, applicationName, null, null);
    }

    /**
     * Builds a role-restricted synthetic operation: only a caller the scheme authenticates and who
     * holds one of {@code rolesAllowed} may invoke it, as on a resource method annotated
     * {@code @RolesAllowed(rolesAllowed)}.
     *
     * @param origin          an opaque message prefix identifying the declaration that produced
     *                        this operation; every installation failure message starts with it
     * @param operationId     the synthetic operation id
     * @param schemeName      the security scheme name the operation is guarded by
     * @param applicationName the name of the application whose document this operation serves,
     *                        stored unchanged
     * @param rolesAllowed    the roles allowed to invoke the operation; copied, never empty, and
     *                        holding no {@code null} or blank role
     * @return the built operation
     * @throws NullPointerException     if any argument, or any element of {@code rolesAllowed}, is
     *     {@code null}; the message names the argument
     * @throws IllegalArgumentException if {@code rolesAllowed} is empty or holds a blank role; the
     *     message names {@code rolesAllowed}
     */
    public static SyntheticOperation withRoles(
            String origin, String operationId, String schemeName, String applicationName, List<String> rolesAllowed) {
        Objects.requireNonNull(rolesAllowed, "rolesAllowed");
        if (rolesAllowed.isEmpty()) {
            throw new IllegalArgumentException("rolesAllowed must name at least one role");
        }
        for (String role : rolesAllowed) {
            Objects.requireNonNull(role, "rolesAllowed must not contain a null role");
            if (role.isBlank()) {
                throw new IllegalArgumentException("rolesAllowed must not contain a blank role");
            }
        }
        return new SyntheticOperation(
                origin, operationId, schemeName, applicationName, List.copyOf(rolesAllowed), null);
    }

    /**
     * Builds a synthetic operation governed by a typed access policy: it is installed with the
     * requirements the policy declares (public, deny, roles, scopes, a required action, or a
     * combination), enforced by the same contributor chain an equally annotated resource method gets.
     *
     * <p>The policy is carried as given and is not validated here; the installer resolves it and
     * refuses an invalid one, with this operation's {@code origin}, before it registers any route.
     *
     * <p>An empty {@code schemeName} means the operation names no security scheme and is legal only
     * for a policy that resolves to public access; the installer then adds no authentication handler.
     * Any other value, including a blank one, is a supplied scheme: it must name a registered security
     * scheme whose handler provides authentication, and combining it with a public policy is rejected
     * by the security policy validator. A deny policy needs a supplied scheme, authenticates the
     * caller by it, then denies. Every other restrictive policy needs a supplied scheme too.
     *
     * <p>Enforcement is supported in a composition that includes {@code AuthModule} and
     * {@code SecurityModule}, or a module that includes them; a policy needing authentication, role,
     * scope or action enforcement is refused at installation, before any route exists, when that
     * composition is missing. This method does not check the composition of an arbitrary graph beyond
     * those installation checks.
     *
     * @param origin          an opaque message prefix identifying the declaration that produced
     *                        this operation; every installation failure message starts with it
     * @param operationId     the synthetic operation id
     * @param schemeName      the security scheme name the operation is guarded by, or empty for none
     * @param applicationName the name of the application whose document this operation serves,
     *                        stored unchanged
     * @param policy          the typed access policy the operation is governed by
     * @return the built operation
     * @throws NullPointerException if any argument is {@code null}; the message names it
     */
    public static SyntheticOperation withPolicy(
            String origin,
            String operationId,
            String schemeName,
            String applicationName,
            Class<? extends AccessPolicy> policy) {
        Objects.requireNonNull(policy, "policy");
        return new SyntheticOperation(origin, operationId, schemeName, applicationName, null, policy);
    }

    /**
     * Returns the opaque message prefix identifying the declaration that produced this operation.
     *
     * @return the origin
     */
    public String origin() {
        return origin;
    }

    /**
     * Returns the synthetic operation id.
     *
     * @return the operation id
     */
    public String operationId() {
        return operationId;
    }

    /**
     * Returns the security scheme name this operation is guarded by, exactly as given to the factory.
     * Empty means no scheme; only a public {@link #withPolicy} operation installs with it.
     *
     * @return the scheme name
     */
    public String schemeName() {
        return schemeName;
    }

    /**
     * Returns the name of the application whose document this operation serves, exactly as given to
     * the factory.
     *
     * @return the documented application's name
     */
    public String applicationName() {
        return applicationName;
    }

    /**
     * Returns the roles allowed to invoke the operation, or {@link Optional#empty()} for an
     * authenticated-only operation or one created through {@link #withPolicy}.
     *
     * @return the non-empty, unmodifiable list of allowed roles, or empty for authenticated-only or typed
     */
    public Optional<List<String>> rolesAllowed() {
        return Optional.ofNullable(rolesAllowed);
    }

    /**
     * Returns the typed access policy this operation is governed by, or {@link Optional#empty()} for
     * an operation created through {@link #authenticated} or {@link #withRoles}.
     *
     * @return the policy exactly as given to {@link #withPolicy}, or empty for a legacy operation
     */
    public Optional<Class<? extends AccessPolicy>> accessPolicy() {
        return Optional.ofNullable(accessPolicy);
    }
}
