// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.util.TypeResolver;
import dev.vertique.rest.core.context.RestContextMessages;
import dev.vertique.rest.core.context.RestContextTypes;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import io.vertx.ext.web.FileUpload;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

/**
 * Validates route registration constraints at startup.
 *
 * <p>Checks include: body parameter count, form/body conflicts, unsupported native multipart
 * collection shapes, non-{@link Comparable} elements in a {@code SortedSet}/{@code NavigableSet}
 * shape, duplicate operationIds, unmatched operationIds, and security annotations present without an
 * auth module installed.
 * All methods are static; this class is not intended to be instantiated.
 */
class RouteValidator {

    private RouteValidator() {}

    /**
     * Validates method params: at most one body param, form and body params are mutually exclusive.
     *
     * @param meta the resource method metadata to validate
     * @return list of violations found; empty if the method params are valid
     */
    static List<RouteRegistrationViolation> validateMethodParams(ResourceMethodMeta meta) {
        List<RouteRegistrationViolation> violations = new ArrayList<>();
        long bodyCount = meta.params().stream()
                .filter(p -> p.source() == ResourceMethodMeta.ParamSource.BODY)
                .count();
        if (bodyCount > 1) {
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.MULTIPLE_BODY_PARAMS,
                    String.format(
                            "Method %s.%s() has %d body parameters; at most one is allowed",
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            bodyCount)));
        }
        boolean hasFormParams = meta.params().stream()
                .anyMatch(p -> p.source() == ResourceMethodMeta.ParamSource.FORM
                        || p.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS
                        || p.source() == ResourceMethodMeta.ParamSource.ENTITY_PARTS);
        if (hasFormParams && bodyCount > 0) {
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.FORM_AND_BODY_CONFLICT,
                    String.format(
                            "Method %s.%s() mixes @FormParam/file upload parameters with a body parameter; use one or the other",
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName())));
        }
        addContextParamViolations(meta, violations);
        addFilePartViolations(meta, violations);
        addMultipartCollectionShapeViolations(meta, violations);
        addSortedSetElementViolations(meta, violations);
        return violations;
    }

    /**
     * Rejects a parameter declared as {@code SortedSet<T>} / {@code NavigableSet<T>} whose element type
     * is not comparable to itself (see {@link #isSelfComparable} — which covers an element type that
     * does not implement {@link Comparable} at all, one that implements it against an unrelated type,
     * and one whose {@code Comparable} argument cannot be resolved to a concrete cast target).
     * {@code ParameterExtractor.materializeCollection} builds both shapes with
     * {@code new TreeSet<>(elements)}, which orders elements by their natural ordering, so such a
     * parameter has no valid materialization: every request supplying a value would throw
     * {@code ClassCastException} (a 500), and a JAX-RS declaration cannot supply a
     * {@link java.util.Comparator}. Startup therefore fails fast, exactly as it does for an
     * unsupported native multipart shape (see {@link #addMultipartCollectionShapeViolations}).
     *
     * <p><b>Scoped to the sources that are materialized element-wise</b> — {@code QUERY},
     * {@code HEADER}, {@code COOKIE}, and {@code FORM} (see {@link #isElementWiseMaterializedSource}).
     * Those are exactly the sources whose values reach
     * {@code ParameterExtractor.materializeCollection}. A {@code BODY} parameter is
     * <em>deserialized</em> by the body decoders ({@code extractParamValue} dispatches BODY to
     * {@code deserializeBody}), so no {@code TreeSet} is ever built from its elements and a
     * {@code SortedSet<T>} body is a perfectly legal declaration — Jackson populates it through the
     * declared type. The gate is load-bearing rather than cosmetic: the generated dispatch path
     * <em>does</em> resolve a {@code componentType} for BODY
     * ({@code EffectiveJaxRsContractResolver.resolvesComponentType}, a deliberately deferred
     * divergence), so without the source scoping a codegen'd resource with a
     * {@code SortedSet<Pojo>} body would fail startup while its reflective twin — whose scanner
     * hard-codes {@code componentType = null} for BODY — mounts fine. {@code FILE_UPLOADS} /
     * {@code ENTITY_PARTS} are likewise excluded: both are always declared {@code List<T>} and are
     * materialized natively, never through a {@code TreeSet}.
     *
     * <p>Running from {@link #validateMethodParams} places this check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest
     * of the operation as soon as method-param validation reports anything), so a non-self-comparable
     * element type that also lacks a converter is reported once, with the shape-specific diagnostic.
     *
     * <p>Native multipart element types ({@link FileUpload} / {@link EntityPart}) are skipped: they are
     * not {@link Comparable} either, but their accurate diagnostic is
     * {@code UNSUPPORTED_MULTIPART_COLLECTION_SHAPE} on {@code FORM} (reported by
     * {@link #addMultipartCollectionShapeViolations}) and {@code UNRESOLVABLE_PARAM_CONVERTER} on any
     * other source. Only collection-shaped
     * parameters are inspected, so array shapes and bean-param fields — neither of which carries a
     * component type here — never reach the check.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any non-self-comparable sorted-element violations are
     *                   appended
     */
    private static void addSortedSetElementViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (!isElementWiseMaterializedSource(pm.source())) {
                continue;
            }
            if (pm.componentType() == null || !SortedSet.class.isAssignableFrom(pm.type())) {
                continue;
            }
            if (isSelfComparable(pm.componentType()) || isNativeMultipartCollection(pm)) {
                continue;
            }
            String shape = pm.type().getSimpleName();
            String element = pm.componentType().getSimpleName();
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.NON_COMPARABLE_SORTED_SET_ELEMENT,
                    String.format(
                            "Parameter '%s' of %s.%s() declares %s<%s>, but %s is not comparable to itself; "
                                    + "a %s is materialized as a TreeSet, so every request carrying a value would "
                                    + "fail — declare it as Set<%s>, List<%s>, or Collection<%s>, or make %s "
                                    + "implement Comparable<%s>.",
                            pm.name(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            shape,
                            element,
                            element,
                            shape,
                            element,
                            element,
                            element,
                            element,
                            element)));
        }
    }

    /**
     * Returns whether a parameter source's values are materialized <em>element-wise</em> into the
     * declared collection type by {@code ParameterExtractor.materializeCollection} — the only path that
     * builds a {@code TreeSet} and therefore the only one the sorted-shape guard applies to.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code QUERY}, {@code HEADER}, {@code COOKIE}, and {@code FORM}
     */
    private static boolean isElementWiseMaterializedSource(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case QUERY, HEADER, COOKIE, FORM -> true;
            case PATH, BODY, CONTEXT, PRECONDITIONS, FILE_UPLOADS, ENTITY_PARTS, BEAN_PARAM -> false;
        };
    }

    /**
     * Returns whether {@code elementType} is comparable to <em>itself</em>, i.e. whether
     * {@code new TreeSet<>(elements)} can order instances of it without a
     * {@link ClassCastException}.
     *
     * <p>Raw assignability to {@link Comparable} is <em>not</em> sufficient: a
     * {@code class Money implements Comparable<BigDecimal>} is assignable to {@link Comparable}, but
     * {@code TreeSet} invokes the compiler-synthesized {@code compareTo(Object)} bridge, which casts its
     * argument to {@code BigDecimal} and throws. What decides is therefore the <em>erasure of the type
     * argument at the {@code Comparable} declaration site</em> — precisely the type that bridge casts to
     * (see {@link #comparableCastTarget}).
     *
     * <p>The verdict:
     *
     * <ul>
     *   <li>not {@link Comparable} at all &rarr; <b>rejected</b>;
     *   <li>an {@code enum} &rarr; <b>accepted</b>. {@code Enum<E extends Enum<E>> implements
     *       Comparable<E>} declares {@code compareTo(E)} in {@link Enum} itself, so the bridge casts to
     *       {@link Enum} and every enum constant passes it. This is a known-safe carve-out, not a
     *       fail-open: the declaration's type argument is a type variable that carries no concrete
     *       target on its own;
     *   <li>a <em>raw</em> {@code implements Comparable} &rarr; <b>accepted</b>: {@code compareTo(Object)}
     *       is implemented directly, no bridge cast exists, and every argument passes;
     *   <li>a resolved cast target &rarr; accepted only when it is assignable <em>from</em>
     *       {@code elementType} (so {@code Comparable<Self>}, {@code Comparable<Supertype>}, and
     *       {@code Comparable<ParameterizedSupertype<?>>} — e.g. {@code LocalDateTime}'s
     *       {@code Comparable<ChronoLocalDateTime<?>>} — pass, while {@code Comparable<Unrelated>} does
     *       not);
     *   <li>a {@code Comparable} declaration whose argument resolves to <em>no</em> concrete cast target
     *       &rarr; <b>rejected</b>. That is the type-variable-forwarding case: with
     *       {@code interface Fwd<T> extends Comparable<T>} and {@code class Bad implements Fwd<String>},
     *       the concrete argument is bound one level below the declaration, so the bridge generated in
     *       {@code Bad} casts to {@code String} and a {@code TreeSet} of {@code Bad} throws. Resolving
     *       such a declaration would require substituting type arguments through the hierarchy, which
     *       the shared {@link TypeResolver} deliberately does not do; rejecting is the fail-<em>closed</em>
     *       answer, so a shape whose safety cannot be proven fails at startup instead of per request.
     * </ul>
     *
     * @param elementType the declared element type of a {@code SortedSet}/{@code NavigableSet} shape
     * @return {@code true} when a {@code TreeSet} of {@code elementType} can order its own elements
     */
    private static boolean isSelfComparable(Class<?> elementType) {
        if (!Comparable.class.isAssignableFrom(elementType)) {
            return false;
        }
        if (elementType.isEnum()) {
            return true;
        }
        Class<?> castTarget = comparableCastTarget(elementType);
        return castTarget != null && castTarget.isAssignableFrom(elementType);
    }

    /**
     * Resolves the type the {@code compareTo(Object)} bridge of {@code elementType}'s
     * {@link Comparable} implementation casts its argument to — the erasure of the type argument at the
     * declaration site.
     *
     * <p>The declaration is looked up over the element type's own class chain (most-derived first, so
     * the closest declaration wins) and then over every transitively implemented interface
     * ({@link TypeResolver#getAllInterfaces(Class)}). A compiling type can instantiate
     * {@link Comparable} at most once, so the first declaration found is the only one.
     *
     * @param elementType the element type, already known to be assignable to {@link Comparable}
     * @return {@link Object} for a raw {@code implements Comparable} (no cast is generated), the
     *     erasure of the declared type argument, or {@code null} when the argument is a type variable
     *     (or another non-erasable shape) whose concrete binding lives elsewhere in the hierarchy
     */
    private static Class<?> comparableCastTarget(Class<?> elementType) {
        List<Class<?>> declarationSites = new ArrayList<>();
        for (Class<?> current = elementType; current != null && current != Object.class; ) {
            declarationSites.add(current);
            current = current.getSuperclass();
        }
        declarationSites.addAll(TypeResolver.getAllInterfaces(elementType));
        for (Class<?> site : declarationSites) {
            for (Type declared : site.getGenericInterfaces()) {
                if (declared == Comparable.class) {
                    return Object.class;
                }
                if (declared instanceof ParameterizedType parameterized
                        && parameterized.getRawType() == Comparable.class) {
                    return erasure(parameterized.getActualTypeArguments()[0]);
                }
            }
        }
        return null;
    }

    /**
     * Returns the erasure of a declared type argument, i.e. the class the compiler casts to when it
     * synthesizes a bridge for it.
     *
     * @param type the declared type argument
     * @return the argument's own class, the raw type of a parameterized argument, or {@code null} for a
     *     type variable, wildcard, or generic array — none of which names a concrete cast target
     *     without substituting the surrounding declaration
     */
    private static Class<?> erasure(Type type) {
        if (type instanceof Class<?> concrete) {
            return concrete;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    /**
     * Rejects a {@code @FormParam} whose element type is a native multipart target
     * ({@link FileUpload} / {@link EntityPart}) but whose declared type is a collection shape other
     * than {@link List} — {@code Set}, {@code SortedSet}, {@code NavigableSet}, {@code Collection}.
     * {@code ParameterExtractor.extractFormParam} materializes only a scalar target and
     * {@code List<T>} natively (ADR-0191 decision 6); any other shape would fall through to string
     * conversion and fail per-request, so startup fails fast instead.
     *
     * <p>Running from {@link #validateMethodParams} places this check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest
     * of the operation as soon as method-param validation reports anything), so the offending
     * parameter is reported once, with the shape-specific diagnostic rather than a misleading
     * "no ParamConverter for FileUpload" one.
     *
     * <p>Only FORM-sourced parameters are inspected. The {@code FILE_UPLOADS}/{@code ENTITY_PARTS}
     * aggregates are always declared {@code List<T>} by the scanner, and a non-FORM parameter
     * carrying a native element type (e.g. {@code @QueryParam List<FileUpload>}) is not a multipart
     * declaration at all — it is correctly reported as an unresolvable converter.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any unsupported-shape violations are appended
     */
    private static void addMultipartCollectionShapeViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (pm.source() != ResourceMethodMeta.ParamSource.FORM || pm.componentType() == null) {
                continue;
            }
            if (!isNativeMultipartCollection(pm) || isNativelyMaterializedFormCollection(pm)) {
                continue;
            }
            violations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.UNSUPPORTED_MULTIPART_COLLECTION_SHAPE,
                    String.format(
                            "@FormParam '%s' of %s.%s() declares %s<%s>; only a scalar %s and "
                                    + "List<%s> are materialized natively — declare it as List<%s>.",
                            pm.name(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName(),
                            pm.type().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName(),
                            pm.componentType().getSimpleName())));
        }
    }

    /**
     * Returns whether a collection-shaped parameter's element type is a native multipart target.
     * Reuses {@link #isEntityPartTarget} for the {@link EntityPart} half so the two checks share one
     * notion of "native {@code EntityPart} target".
     *
     * @param pm the parameter metadata; its {@code componentType()} is non-{@code null}
     * @return {@code true} when the element type is {@link FileUpload} or {@link EntityPart}
     */
    private static boolean isNativeMultipartCollection(ResourceMethodMeta.ParamMeta pm) {
        return pm.componentType() == FileUpload.class || isEntityPartTarget(pm);
    }

    /**
     * Returns whether a FORM parameter carrying a native multipart element type is one of the shapes
     * {@code ParameterExtractor.extractFormParam} materializes natively.
     *
     * <p>For {@link FileUpload} targets this delegates to {@link #isSupportedFileUploadTarget} — the
     * same predicate {@code @FilePart} validation uses — so the supported-shape policy lives in one
     * place. {@link EntityPart} has no {@code @FilePart}-facing counterpart ({@code @FilePart} is
     * invalid on an {@code EntityPart} parameter), so the identical {@code List}-only rule is applied
     * directly.
     *
     * @param pm the FORM parameter metadata; its {@code componentType()} is a native multipart type
     * @return {@code true} when the declared shape has a native materialization
     */
    private static boolean isNativelyMaterializedFormCollection(ResourceMethodMeta.ParamMeta pm) {
        if (pm.componentType() == FileUpload.class) {
            return isSupportedFileUploadTarget(pm);
        }
        return pm.type() == List.class;
    }

    /**
     * Validates every {@link FilePart} declaration before operation-descriptor construction. The
     * public {@link FilePartDescriptor} constructor remains the single authority for size and media
     * type grammar; constructor failures are translated into typed startup diagnostics rather than
     * leaking raw {@link IllegalArgumentException}s.
     *
     * <p>Only constrained descriptors participate in overlap checks. For each later declaration,
     * at most one violation is emitted when it overlaps a prior constrained declaration: equal
     * named parts overlap, and an aggregate overlaps every named or aggregate declaration.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which file-part declaration violations are appended
     */
    private static void addFilePartViolations(ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        List<FilePartDescriptor> constrainedDescriptors = new ArrayList<>();

        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            Optional<FilePart> declaration = pm.findAnnotation(FilePart.class);
            if (declaration.isEmpty()) {
                continue;
            }

            String parameterName = filePartParameterName(pm);
            if (isEntityPartTarget(pm)) {
                addFilePartViolation(
                        meta, parameterName, "EntityPart parameters cannot declare file-part constraints", violations);
                continue;
            }
            if (!isSupportedFileUploadTarget(pm)) {
                addFilePartViolation(
                        meta,
                        parameterName,
                        "only @FormParam FileUpload/List<FileUpload> and aggregate "
                                + "List<FileUpload> parameters are supported",
                        violations);
                continue;
            }

            FilePart filePart = declaration.get();
            FilePartDescriptor descriptor;
            try {
                descriptor = new FilePartDescriptor(
                        pm.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS ? null : pm.name(),
                        List.of(filePart.allowedTypes()),
                        filePart.maxSizeBytes());
            } catch (IllegalArgumentException exception) {
                addFilePartViolation(meta, parameterName, exception.getMessage(), violations);
                continue;
            }

            if (!descriptor.constrained()) {
                continue;
            }
            if (constrainedDescriptors.stream()
                    .anyMatch(previous -> filePartDescriptorsOverlap(previous, descriptor))) {
                addFilePartViolation(
                        meta,
                        parameterName,
                        "constraints overlap another constrained file-part declaration",
                        violations);
                continue;
            }
            constrainedDescriptors.add(descriptor);
        }
    }

    private static boolean isEntityPartTarget(ResourceMethodMeta.ParamMeta pm) {
        return pm.type() == EntityPart.class || pm.componentType() == EntityPart.class;
    }

    private static boolean isSupportedFileUploadTarget(ResourceMethodMeta.ParamMeta pm) {
        boolean namedScalar = pm.source() == ResourceMethodMeta.ParamSource.FORM
                && pm.type() == FileUpload.class
                && pm.componentType() == null;
        boolean fileUploadList = pm.type() == List.class && pm.componentType() == FileUpload.class;
        boolean namedList = pm.source() == ResourceMethodMeta.ParamSource.FORM && fileUploadList;
        boolean aggregateList = pm.source() == ResourceMethodMeta.ParamSource.FILE_UPLOADS && fileUploadList;
        return namedScalar || namedList || aggregateList;
    }

    private static boolean filePartDescriptorsOverlap(FilePartDescriptor first, FilePartDescriptor second) {
        return first.partName() == null
                || second.partName() == null
                || first.partName().equals(second.partName());
    }

    private static String filePartParameterName(ResourceMethodMeta.ParamMeta pm) {
        return pm.name() != null ? pm.name() : "<aggregate>";
    }

    private static void addFilePartViolation(
            ResourceMethodMeta meta, String parameterName, String reason, List<RouteRegistrationViolation> violations) {
        violations.add(new RouteRegistrationViolation(
                meta.operationId(),
                RouteRegistrationViolation.ViolationType.INVALID_FILE_PART_DECLARATION,
                "@FilePart on parameter '" + parameterName + "' of " + meta.operationId() + ": " + reason));
    }

    /**
     * Validates all {@code CONTEXT}-sourced parameters in the given method metadata and appends any
     * violations found to the supplied list. For each context parameter, at most one violation is
     * emitted, in the following priority order:
     *
     * <ol>
     *   <li>{@link RouteRegistrationViolation.ViolationType#CONTEXT_PARAM_CONFLICT} — the parameter
     *       carries both {@code @Context} and a JAX-RS value-binding annotation
     *       ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam}, {@code @CookieParam},
     *       {@code @FormParam}, {@code @BeanParam}).</li>
     *   <li>{@link RouteRegistrationViolation.ViolationType#UNSUPPORTED_JAXRS_CONTEXT_TYPE} — the
     *       parameter's type is in
     *       {@link RestContextTypes#RESERVED_UNSUPPORTED_JAXRS_FQNS}.</li>
     *   <li>{@link RouteRegistrationViolation.ViolationType#NON_INJECTABLE_CONTEXT_TYPE} — the
     *       parameter's type is not injectable per
     *       {@link RestContextTypes#isInjectable(Class)}.</li>
     * </ol>
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any context-param violations are appended
     */
    private static void addContextParamViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        String className = meta.method().getDeclaringClass().getSimpleName();
        String methodName = meta.method().getName();

        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (pm.source() != ResourceMethodMeta.ParamSource.CONTEXT) {
                continue;
            }

            // 1. @Context + value-binding annotation conflict
            if (hasBindingAnnotation(pm.annotationsLazy().get())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.CONTEXT_PARAM_CONFLICT,
                        RestContextMessages.contextParamConflict(
                                className, methodName, pm.type().getSimpleName())));
                continue;
            }

            // 2. Reserved JAX-RS type that is unsupported in this version
            if (RestContextTypes.RESERVED_UNSUPPORTED_JAXRS_FQNS.contains(
                    pm.type().getName())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.UNSUPPORTED_JAXRS_CONTEXT_TYPE,
                        RestContextMessages.unsupportedJaxRsContextType(
                                className, methodName, pm.type().getSimpleName())));
                continue;
            }

            // 3. Not an injectable type at all
            if (!RestContextTypes.isInjectable(pm.type())) {
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.NON_INJECTABLE_CONTEXT_TYPE,
                        RestContextMessages.nonInjectableContextType(
                                className, methodName, pm.type().getSimpleName())));
            }
        }
    }

    /**
     * Returns {@code true} if the given annotation array contains any JAX-RS value-binding
     * annotation ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     * {@code @CookieParam}, {@code @FormParam}, {@code @BeanParam}).
     *
     * @param annotations the parameter annotation array; may be {@code null}
     * @return {@code true} if a binding annotation is present
     */
    private static boolean hasBindingAnnotation(Annotation[] annotations) {
        if (annotations == null) {
            return false;
        }
        for (Annotation ann : annotations) {
            Class<? extends Annotation> type = ann.annotationType();
            if (type == PathParam.class
                    || type == QueryParam.class
                    || type == HeaderParam.class
                    || type == CookieParam.class
                    || type == FormParam.class
                    || type == BeanParam.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks for a duplicate operationId against already-registered methods.
     *
     * @param meta       the resource method metadata to check
     * @param registered map of already-registered operationIds to their method metadata
     * @return a violation if {@code meta.operationId()} is already registered; empty otherwise
     */
    static Optional<RouteRegistrationViolation> checkDuplicateOperationId(
            ResourceMethodMeta meta, Map<String, ResourceMethodMeta> registered) {
        ResourceMethodMeta existing = registered.get(meta.operationId());
        if (existing != null) {
            return Optional.of(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.DUPLICATE_OPERATION_ID,
                    String.format(
                            "Duplicate operationId '%s': %s.%s() and %s.%s()",
                            meta.operationId(),
                            existing.method().getDeclaringClass().getSimpleName(),
                            existing.method().getName(),
                            meta.method().getDeclaringClass().getSimpleName(),
                            meta.method().getName())));
        }
        return Optional.empty();
    }

    /**
     * Checks for restrictive security policies declared when the auth module is not installed.
     *
     * <p>The check evaluates each operation's <strong>effective</strong> security policy
     * ({@code descriptor.effectiveSecurityPolicy()}), not the raw {@code meta.securityPolicy()}. This
     * is essential because a scoped {@code @SecurityRequirement(name=…, scopes={…})} with no
     * {@code @Authorized}/{@code @RolesAllowed} leaves the <em>raw</em> policy non-restrictive (e.g.
     * {@link SecurityPolicy.None}) while folding its scopes into a restrictive
     * {@link SecurityPolicy.Constrained} <em>effective</em> policy (finding C2 / SH-4). The folded
     * scopes are enforced only by the authorization contributor, which is wired solely when the
     * auth-enforcement capability is present; with auth absent, evaluating the raw policy would let
     * such a route mount with its scopes silently unenforced (an authorization fail-open). Evaluating
     * the effective policy closes that gap — mirroring the action-only handling in
     * {@link JaxRsRouteRegistrar#resolveRequiredAction}, where {@link SecurityPolicy.None#isRestrictive()}
     * being {@code false} hides {@code @RequiresAction}-only routes from this check.
     *
     * <p>Action-only routes are <em>not</em> double-reported here: their effective policy stays
     * {@link SecurityPolicy.None} (no scopes fold), so they remain invisible to this check and are
     * caught solely by {@code resolveRequiredAction}. Routes already restrictive in the raw policy
     * ({@code @Authorized}/{@code @RolesAllowed}) are reported exactly once.
     *
     * @param effectivePolicies map of all registered operationIds to their effective security policy
     *                          (raw policy with any single-scheme {@code @SecurityRequirement} scopes
     *                          folded in)
     * @param authEnabled       whether the auth module is installed
     * @return list of violations found; empty if auth is enabled or no restrictive policies exist
     */
    static List<RouteRegistrationViolation> checkSecurityWithoutAuth(
            Map<String, SecurityPolicy> effectivePolicies, boolean authEnabled) {
        List<RouteRegistrationViolation> violations = new ArrayList<>();
        if (!authEnabled) {
            for (Map.Entry<String, SecurityPolicy> entry : effectivePolicies.entrySet()) {
                if (entry.getValue().isRestrictive()) {
                    String operationId = entry.getKey();
                    violations.add(new RouteRegistrationViolation(
                            operationId,
                            RouteRegistrationViolation.ViolationType.SECURITY_ANNOTATIONS_WITHOUT_AUTH_MODULE,
                            String.format(
                                    "Operation '%s' has restrictive security annotations (or a scoped "
                                            + "@SecurityRequirement) but AuthModule is not installed. "
                                            + "Include AuthModule in your Dagger component to enable security features.",
                                    operationId)));
                }
            }
        }
        return violations;
    }
}
