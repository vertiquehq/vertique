// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * INTERNAL: describes one framework-owned synthetic operation for {@link SyntheticOperations}.
 *
 * <p>An operation is guarded by one security scheme and is either authenticated-only ({@link
 * #authenticated}) or restricted to a non-empty list of roles ({@link #withRoles}). {@link
 * SyntheticOperations#install} gives it the effective security policy of a resource method annotated
 * with {@code @SecurityRequirement(name = schemeName)} plus, respectively, {@code @Authorized} or
 * {@code @RolesAllowed(rolesAllowed)}.
 *
 * <p>Instances are immutable and built only through the two factories, which reject a {@code null}
 * argument with a {@link NullPointerException} naming it, and a role list that is empty or holds a
 * blank role with an {@link IllegalArgumentException} naming {@code rolesAllowed}, so no operation,
 * and therefore no route, can exist with such a list.
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

    private SyntheticOperation(
            String origin,
            String operationId,
            String schemeName,
            String applicationName,
            @Nullable List<String> rolesAllowed) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.schemeName = Objects.requireNonNull(schemeName, "schemeName");
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.rolesAllowed = rolesAllowed;
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
        return new SyntheticOperation(origin, operationId, schemeName, applicationName, null);
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
        return new SyntheticOperation(origin, operationId, schemeName, applicationName, List.copyOf(rolesAllowed));
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
     * Returns the security scheme name this operation is guarded by.
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
     * authenticated-only operation.
     *
     * @return the non-empty, unmodifiable list of allowed roles, or empty for authenticated-only
     */
    public Optional<List<String>> rolesAllowed() {
        return Optional.ofNullable(rolesAllowed);
    }
}
