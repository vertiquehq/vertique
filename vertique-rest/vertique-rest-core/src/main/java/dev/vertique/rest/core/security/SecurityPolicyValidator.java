// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import java.util.List;

/**
 * Validates security policy consistency between Java annotations and the operation's declared
 * security requirements for a single operation.
 *
 * <p>Called during route registration for each discovered operation. Implementations check for
 * drift between annotation-declared security intent (the {@link SecurityPolicy} resolved from
 * {@code @RolesAllowed}/{@code @PermitAll}/{@code @DenyAll}/{@code @Authorized}) and the operation's
 * declared security requirement sets ({@link RestOperationDescriptor#securityRequirementSets()},
 * sourced from Swagger {@code @SecurityRequirement} annotations at scan time) together with the
 * configured runtime scheme handlers.
 *
 * <p>Contributed via Dagger binding. Violations cause {@link SecurityPolicyViolationException} to
 * be thrown at startup.
 *
 * @see SecurityPolicyViolation
 * @see SecurityPolicyViolationException
 */
public interface SecurityPolicyValidator {

    /**
     * Validates security policy consistency for a single operation.
     *
     * <p>The operation identifier is read from {@link RestOperationDescriptor#operationId()} and the
     * declared security requirement sets from {@link RestOperationDescriptor#securityRequirementSets()}
     * (annotation-sourced, not read from the generated OpenAPI document).
     *
     * @param op     the neutral descriptor for the operation being validated
     * @param policy the resolved security policy derived from annotations on the resource method
     * @return list of violations found (empty if consistent)
     */
    List<SecurityPolicyViolation> validate(RestOperationDescriptor op, SecurityPolicy policy);
}
