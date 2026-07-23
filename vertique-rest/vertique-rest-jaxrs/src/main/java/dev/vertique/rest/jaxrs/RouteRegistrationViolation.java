// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

/**
 * Describes a route registration inconsistency detected during startup.
 *
 * <p>Each violation includes the operationId, the type of inconsistency,
 * and a human-readable diagnostic message.
 *
 * @param operationId the operationId of the affected operation
 * @param type        the category of registration violation
 * @param message     human-readable description of the inconsistency
 */
public record RouteRegistrationViolation(String operationId, ViolationType type, String message) {

    /**
     * Categories of route registration violations detected at startup.
     */
    public enum ViolationType {

        /**
         * Two or more JAX-RS methods declare the same operationId.
         */
        DUPLICATE_OPERATION_ID,

        /**
         * A JAX-RS method has more than one unannotated (body) parameter.
         */
        MULTIPLE_BODY_PARAMS,

        /**
         * Restrictive security annotations are present on a method but the
         * auth module is not installed.
         */
        SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,

        /**
         * A method mixes @FormParam / file upload parameters with a JSON body parameter.
         */
        FORM_AND_BODY_CONFLICT,

        /**
         * A @FilePart declaration is invalid: placed on a non-file or EntityPart parameter,
         * carries maxSizeBytes == 0 or < -1, an allowedTypes entry outside the frozen grammar,
         * or two constrained declarations cover the same part name.
         */
        INVALID_FILE_PART_DECLARATION,

        /**
         * A {@code @Context}-annotated parameter also carries a JAX-RS value-binding annotation
         * ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, {@code @CookieParam},
         * {@code @FormParam}, or {@code @BeanParam}). The two annotations are mutually exclusive:
         * context parameters are injected by the framework, not bound from the request.
         */
        CONTEXT_PARAM_CONFLICT,

        /**
         * A {@code @Context}-annotated parameter's declared type is a reserved JAX-RS context type
         * (e.g. {@code jakarta.ws.rs.core.UriInfo}, {@code jakarta.ws.rs.core.HttpHeaders}) that is
         * syntactically valid JAX-RS but is not supported in this version of the Vertique framework
         * (FR-REST-188).
         */
        UNSUPPORTED_JAXRS_CONTEXT_TYPE,

        /**
         * A {@code @Context}-annotated parameter's declared type is not an injectable context type.
         * Injectable types are {@link io.vertx.ext.web.RoutingContext},
         * {@link jakarta.ws.rs.core.SecurityContext},
         * {@link dev.vertique.security.SecurityContext}, and any type that implements
         * {@link dev.vertique.core.context.ContextValue}. All other types are rejected at startup
         * to prevent silent {@code null} injection or {@code NullPointerException} at request time.
         */
        NON_INJECTABLE_CONTEXT_TYPE,

        /**
         * A method or class declares {@code @RequiresAction} whose canonical value is either
         * unparseable or not present in the {@code ActionRegistry}, or {@code @RequiresAction} is
         * present but the authz engine (and thus the registry) is not installed so the action gate
         * cannot be enforced. The action cannot be authorized, so startup fails (fail-closed) rather
         * than deferring to first request.
         */
        REQUIRES_ACTION_INVALID,

        /**
         * A method or class combines {@code @RequiresAction} with {@code @PermitAll} or
         * {@code @DenyAll}. {@code @RequiresAction} AND-composes only with {@code @RolesAllowed} /
         * {@code @Authorized}; pairing it with a blanket allow/deny is a contradiction rejected at
         * startup (and at compile time by the code-generation annotation processor).
         */
        REQUIRES_ACTION_POLICY_CONFLICT,

        /**
         * A declared conversion-applicable parameter (path/query/header/cookie/form) has a type that
         * no built-in converter, application {@code ParamConverterBinding}, or JAX-RS
         * {@code ParamConverterProvider} can satisfy. Such a parameter could only ever fail (opaquely)
         * at request time, so startup fails fast (PRD-REST-018).
         */
        UNRESOLVABLE_PARAM_CONVERTER
    }
}
