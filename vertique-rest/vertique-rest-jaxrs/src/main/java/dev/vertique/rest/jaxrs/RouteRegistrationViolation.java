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
        UNRESOLVABLE_PARAM_CONVERTER,

        /**
         * A {@code @FormParam} whose element type is a native multipart target
         * ({@code FileUpload}/{@code EntityPart}) is declared in a collection shape other than
         * {@code List}. Only a scalar target and {@code List<T>} are materialized natively;
         * {@code Set}, {@code SortedSet}, {@code NavigableSet}, and {@code Collection} have no
         * native materialization and would otherwise fall through to string conversion and fail
         * per-request.
         *
         * <p>Array shapes of a native target (e.g. {@code FileUpload[]}) are <em>not</em> reported
         * here, because {@code ResourceScanner.resolveComponentType} only resolves an element type
         * for arrays whose component passes its scalar-element policy — which excludes
         * {@code FileUpload}/{@code EntityPart}. Such a parameter therefore carries no component
         * type, is invisible to this check, and fails startup as
         * {@link #UNRESOLVABLE_PARAM_CONVERTER} instead. Both outcomes fail fast; only the
         * violation type differs.
         *
         * <p>Scoped to {@code FORM}. A native element type on another source (e.g.
         * {@code @QueryParam List<FileUpload>}) keeps the more accurate
         * {@link #UNRESOLVABLE_PARAM_CONVERTER} diagnostic rather than being mislabelled a
         * multipart-shape problem. Bean-param fields never reach this check either, since they
         * carry no component type at all.
         */
        UNSUPPORTED_MULTIPART_COLLECTION_SHAPE,

        /**
         * A parameter declared as {@code SortedSet<T>} or {@code NavigableSet<T>} has an element type
         * that is not comparable to <em>itself</em> — either it does not implement {@link Comparable} at
         * all, or it implements {@code Comparable<X>} for a type {@code X} that is not {@code T} or a
         * supertype of it. Both shapes are materialized as a {@link java.util.TreeSet}, which orders
         * elements by their natural ordering, so the first request supplying a value would throw
         * {@code ClassCastException} — from the comparison itself when the type is not
         * {@link Comparable}, or from the compiler-synthesized {@code compareTo(Object)} bridge's cast
         * when it compares against an unrelated type. A JAX-RS parameter declaration cannot supply a
         * {@link java.util.Comparator}, so the shape has no valid materialization at all and is rejected
         * at registration instead of failing per-request.
         *
         * <p>Declare the parameter as {@code Set<T>}, {@code List<T>}, or {@code Collection<T>} — none
         * of which imposes an ordering — or make the element type implement {@code Comparable<T>}.
         *
         * <p>A {@code Comparable} declaration whose type argument cannot be resolved to a concrete class
         * is <em>accepted</em>: that covers a raw {@code implements Comparable} (which compares against
         * {@link Object}) and every self-referential generic declaration, notably an {@code enum}
         * ({@code Enum<E extends Enum<E>> implements Comparable<E>}).
         *
         * <p>Only collection-shaped parameters are inspected (those for which
         * {@code ResourceScanner.resolveComponentType} resolved an element type), so array shapes are
         * unaffected: an array is never a {@code SortedSet}, and its component type is restricted to
         * {@code Comparable} scalars anyway. Bean-param fields carry no component type and are
         * likewise invisible here. A native multipart element type
         * ({@code FileUpload}/{@code EntityPart}) is also excluded so it keeps its more accurate
         * diagnostic — {@link #UNSUPPORTED_MULTIPART_COLLECTION_SHAPE} on {@code FORM},
         * {@link #UNRESOLVABLE_PARAM_CONVERTER} on any other source.
         */
        NON_COMPARABLE_SORTED_SET_ELEMENT
    }
}
