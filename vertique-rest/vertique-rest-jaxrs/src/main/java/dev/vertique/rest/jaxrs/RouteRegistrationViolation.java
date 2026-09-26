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
         * Two parameters of the same method bind the <em>same name</em> from the same request source but
         * cannot <em>share one declared parameter</em>. It is legal Java that compiles, e.g.
         * {@code get(@QueryParam("id") String a, @QueryParam("id") List<String> b)}.
         *
         * <p><b>Why there is no correct binding.</b> Request binding resolves a name to a
         * <em>single</em> declared parameter: {@code DefaultBoundRequest.findDescriptor} returns the
         * <em>first</em> descriptor matching a location and name, and that one declaration then decides —
         * for <em>every</em> parameter reading that name — the multiplicity of the bound value, the scalar
         * conversion applied to it, and the single parameter schema derived for the name. Two declarations
         * that disagree about any of those cannot both be honored, so the declaration is rejected at
         * registration rather than mounted and mis-bound per request. The reported disagreements:
         *
         * <ul>
         *   <li><b>Incompatible multiplicities</b> — one is collection-shaped (it carries a component
         *       type — {@code List<T>}, {@code Set<T>}, {@code SortedSet<T>}, {@code NavigableSet<T>},
         *       {@code Collection<T>}, or {@code T[]}) and the other is scalar. Exactly one is then always
         *       mis-bound, whichever descriptor wins: the <b>scalar</b> one makes the collection-shaped
         *       parameter degrade to a one-element collection (with a warning), silently dropping every
         *       repeated value; the <b>collection</b> one hands the scalar parameter a {@code JsonArray}
         *       its declared type has no converter for.</li>
         *   <li><b>Different declared types on a scalar pair</b> — e.g. {@code @QueryParam("id") Integer}
         *       plus {@code @QueryParam("id") UUID}. {@code DefaultBoundRequest.wrapScalar} converts the
         *       raw value <em>once</em>, with the first declaration's type, and extraction passes an
         *       already-converted value through unchanged, so the other parameter receives the wrong type
         *       and every request carrying the name fails opaquely in {@code Method.invoke}.</li>
         *   <li><b>Different element types on a collection pair</b> — e.g. {@code List<String>} plus
         *       {@code List<UUID>}. One request name cannot mean two element types: the single parameter
         *       schema derived for the name describes only one of them, and each parameter's element
         *       conversion is fail-closed, so a value valid for one declaration fails the other.</li>
         *   <li><b>Different binding-affecting annotations</b> on an otherwise identical shape — including
         *       a different {@code @DefaultValue}, and any constraint or converter-selecting annotation.
         *       The one descriptor carries one annotation array: it is what the JAX-RS
         *       {@code ParamConverterProvider} chain is offered when choosing a converter, and what the
         *       per-name parameter schema is built from, so one declaration's semantics silently govern
         *       both.</li>
         * </ul>
         *
         * <p><b>What to do instead:</b> give the two parameters distinct names. A collection-shaped
         * declaration on its own already receives every submitted value, so a scalar declaration of the
         * same name is rarely what was wanted.
         *
         * <p><b>A pair that agrees on all of the above is accepted</b>, because sharing one descriptor
         * then costs nothing: two identical declarations, and two collection shapes over one element type
         * ({@code List<String>} plus {@code Set<String>}), are redundant but correct. For a collection the
         * descriptor decides multiplicity only — the values are bound unconverted and each parameter
         * converts its elements and materializes its own declared collection type.
         *
         * <p><b>Scoping.</b> Reported for the sources whose binding {@code findDescriptor} decides:
         * {@code PATH}, {@code QUERY}, {@code HEADER}, and {@code COOKIE}. Names are compared exactly as
         * {@code findDescriptor} matches them — <em>case-insensitively</em> for {@code HEADER} and
         * {@code COOKIE} (so {@code @HeaderParam("X-Id")} and {@code @HeaderParam("x-id")} do collide),
         * <em>case-sensitively</em> for {@code PATH} and {@code QUERY} (so {@code @QueryParam("id")} and
         * {@code @QueryParam("Id")} do not). Comparing them any other way would be a defect in either
         * direction: too loose rejects a legal declaration, too strict lets the mis-binding through.
         * Sources are never compared across each other — a {@code @HeaderParam("token")} and a
         * {@code @QueryParam("token")} read different maps and cannot conflict.
         *
         * <p>{@code FORM} is deliberately <em>excluded</em>: {@code ParameterExtractor.extractFormParam}
         * reads {@code formAttributes()} directly per parameter and never consults
         * {@code findDescriptor}, so every {@code @FormParam} of a repeated name converts, defaults, and
         * materializes independently — a scalar one takes the first submitted value while a
         * collection-shaped one takes all of them, both well-defined. {@code BODY}, {@code CONTEXT},
         * {@code PRECONDITIONS}, {@code BEAN_PARAM}, and the {@code FILE_UPLOADS}/{@code ENTITY_PARTS}
         * aggregates are not name-matched request parameters at all.
         *
         * <p>The check is independent of the other shape guards: a conflicting pair whose collection half
         * is <em>also</em> an unsupported shape reports both violations, because both are real and each
         * has its own fix.
         *
         * <p><b>Name note.</b> The constant is named for the multiplicity conflict it originally covered;
         * it now reports every reason two same-name declarations cannot share one descriptor. It is a
         * public enum constant, so it is kept as-is rather than renamed.
         */
        DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT,

        /**
         * An operation has no explicit security policy: it neither restricts callers ({@code
         * DenyAll}/{@code AuthenticatedOnly}/{@code Constrained}, a non-empty, non-anonymous
         * {@code securityRequirementSets()}, or a resolved required action) nor is declared public
         * with {@code @PermitAll}. Reported only when {@code jaxrs.security.requireExplicitPolicy}
         * is {@code true}; without the opt-in, such an operation only produces a warning on the
         * owning application mount, and startup succeeds.
         */
        NO_EXPLICIT_SECURITY_POLICY,

        /**
         * A request-evidence capturer rejected the route when it validated it at router build — for
         * example because the evidence-capture policy the route selects does not exist. Such a
         * route could only ever fail its capture (silently) on every request, so startup fails fast.
         */
        EVIDENCE_CAPTURE_REJECTED
    }
}
