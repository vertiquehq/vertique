// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

/**
 * Describes a security policy inconsistency detected during startup validation.
 *
 * <p>Each violation includes the operationId, the type of inconsistency,
 * and a human-readable diagnostic message.
 *
 * @param operationId the OpenAPI operationId of the affected operation
 * @param type        the category of policy violation
 * @param message     human-readable description of the inconsistency
 */
public record SecurityPolicyViolation(String operationId, ViolationType type, String message) {

    /**
     * Categories of security policy violations.
     */
    public enum ViolationType {

        /**
         * Security annotations ({@code @RolesAllowed}, {@code @Authorized}, {@code @DenyAll})
         * are present on the operation but no OpenAPI security requirement is declared.
         */
        ANNOTATION_WITHOUT_OPENAPI_SECURITY,

        /**
         * OpenAPI security requirement is declared but no matching
         * {@link SecuritySchemeHandler} is configured at runtime.
         */
        OPENAPI_SECURITY_WITHOUT_HANDLER,

        /**
         * Conflicting security semantics detected, such as {@code @PermitAll}
         * on an operation that has an OpenAPI security requirement.
         */
        CONFLICTING_SEMANTICS,

        /**
         * Security annotations on the method are mutually exclusive (e.g. {@code @DenyAll} +
         * {@code @RolesAllowed}).
         */
        CONFLICTING_SECURITY_ANNOTATIONS,

        /**
         * {@code @RolesAllowed} is present with an empty role array ({@code {}}).
         * Use {@code @DenyAll} to deny all access, or specify at least one role.
         */
        EMPTY_ROLES_ALLOWED
    }
}
