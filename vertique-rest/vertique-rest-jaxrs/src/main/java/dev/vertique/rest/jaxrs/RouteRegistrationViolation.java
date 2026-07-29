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
         * that is not comparable to <em>itself</em> — it does not implement {@link Comparable} at all, or
         * its effective {@code compareTo} takes a type the element type is not assignable to. Both
         * shapes are materialized as a {@link java.util.TreeSet}, which orders elements by their natural
         * ordering, so the first request supplying a value would throw {@code ClassCastException} — from
         * the comparison itself when the type is not {@link Comparable}, or from the
         * compiler-synthesized {@code compareTo(Object)} bridge's cast when it compares against an
         * unrelated type. A JAX-RS parameter declaration cannot supply a {@link java.util.Comparator},
         * so the shape has no valid materialization at all and is rejected at registration instead of
         * failing per-request.
         *
         * <p>Declare the parameter as {@code Set<T>}, {@code List<T>}, or {@code Collection<T>} — none
         * of which imposes an ordering — or make the element type implement {@code Comparable<T>}.
         *
         * <p>What decides is the parameter type of the element type's <em>effective non-bridge</em>
         * {@code compareTo} method, because that is precisely what the {@code compareTo(Object)} bridge
         * casts to before delegating. Reading it off {@link Class#getMethods()} needs no special cases:
         * a raw {@code implements Comparable} yields {@code compareTo(Object)}; an {@code enum} yields
         * {@link Enum}'s {@code compareTo(Enum)}; a type variable forwarded through an interface or
         * superclass yields its <em>leftmost bound</em>, exactly as the compiler erased it. So
         * {@code class X implements Ord<X>} (over {@code interface Ord<T> extends Comparable<T>}),
         * {@code class Node<T extends Node<T>> implements Comparable<T>}, and a subclass of an
         * <em>unbounded</em> {@code Base<T> implements Comparable<T>} are all accepted — a real
         * {@code TreeSet} orders them — while {@code class Bad implements Ord<String>} and a subclass of
         * a <em>bounded</em> {@code Base<T extends CharSequence> implements Comparable<T>} are rejected,
         * because their effective {@code compareTo} takes {@code String}/{@code CharSequence}.
         *
         * <p>Scoped to the sources that can carry a component type and are materialized element-wise —
         * {@code QUERY}, {@code HEADER}, {@code COOKIE}, and {@code FORM}. A {@code BODY} parameter is
         * excluded because a body's validity belongs to the selected {@code RequestBodyDecoder}, not to
         * route validation: the decoder <em>deserializes</em> the body instead of materializing it
         * element-wise. That is not a claim that a {@code SortedSet<T>} body is universally safe — under
         * the built-in JSON decoder Jackson's concrete type for {@code SortedSet}/{@code NavigableSet} is
         * {@code TreeSet}, so a non-self-comparable element fails that decoder per request — but a custom
         * decoder may return a comparator-backed set, so the choice is the decoder's. The scoping also
         * preserves runtime/codegen parity: the generated dispatch path resolves a {@code componentType}
         * for BODY while the reflective scanner does not. {@code FILE_UPLOADS}/{@code ENTITY_PARTS} are
         * excluded because they are always {@code List<T>} and are materialized natively.
         *
         * <p>Only collection-shaped parameters are inspected (those for which a component type was
         * resolved), so array shapes are unaffected: an array is never a {@code SortedSet}, and its
         * component type is restricted to {@code Comparable} scalars anyway. Bean-param fields carry no
         * component type and are likewise invisible here. A native multipart element type
         * ({@code FileUpload}/{@code EntityPart}) is also excluded so it keeps its more accurate
         * diagnostic — {@link #UNSUPPORTED_MULTIPART_COLLECTION_SHAPE} on {@code FORM},
         * {@link #UNRESOLVABLE_PARAM_CONVERTER} on any other source.
         */
        NON_COMPARABLE_SORTED_SET_ELEMENT
    }
}
