// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.context.RestContextMessages;
import dev.vertique.rest.core.context.RestContextTypes;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import io.vertx.ext.web.FileUpload;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates route registration constraints at startup.
 *
 * <p>Checks include: body parameter count, form/body conflicts, unsupported native multipart
 * collection shapes, two same-name parameters at one location that cannot share one declared
 * parameter, duplicate operationIds, unmatched operationIds, and security annotations present without
 * an auth module installed. All methods are static; this class is not intended to be instantiated.
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
        addDuplicateParamNameViolations(meta, violations);
        return violations;
    }

    /**
     * Rejects two parameters that bind the <em>same name</em> from the same request source but cannot
     * <em>share one declared parameter</em>. {@code DefaultBoundRequest.findDescriptor} resolves a request
     * name to the <em>first</em> matching descriptor, and that single descriptor is the only declaration
     * consulted for everything binding decides per name, so a pair that disagrees about any of those
     * decisions has no correct binding and startup fails fast — exactly as it does for the other
     * unbindable shapes (see {@link #addMultipartCollectionShapeViolations}). The disagreements, all
     * verified against the runtime paths named below, are enumerated in
     * {@link #sharedDescriptorDisagreement}.
     *
     * <p><b>Scoped to the sources {@code findDescriptor} is consulted for</b> — see
     * {@link #isDescriptorMatchedSource}, and {@link #bindsSameName} for the per-location name-matching
     * rule it mirrors. {@code FORM} is excluded because
     * {@code ParameterExtractor.extractFormParam} reads {@code formAttributes()} per parameter instead of
     * going through {@code findDescriptor}: a scalar {@code @FormParam} takes the first submitted value
     * while a collection-shaped one of the same name takes all of them, so both bind correctly.
     *
     * <p>A pair that agrees on <em>every</em> shared decision is <b>accepted</b>: two identical
     * declarations, or two collection shapes over one element type ({@code List<String>} plus
     * {@code Set<String>}), are redundant but correct, so rejecting them would break declarations that
     * bind correctly today.
     *
     * <p>At most <em>one</em> violation is emitted per colliding name, against its first declaration and
     * the first disagreeing partner: a name declared three times is one defect with one fix, not two.
     * Running from {@link #validateMethodParams} places the check <em>before</em> the
     * {@code UNRESOLVABLE_PARAM_CONVERTER} probe in {@code JaxRsRouteRegistrar} (which skips the rest of
     * the operation as soon as method-param validation reports anything), so the shape-specific
     * diagnostic wins over a converter one. The check is independent of the other shape guards, so a
     * conflicting pair that is <em>also</em> an unsupported shape reports both violations — both are real
     * and each has its own fix.
     *
     * @param meta       the resource method metadata to inspect
     * @param violations mutable list to which any duplicate-name violations are appended
     */
    private static void addDuplicateParamNameViolations(
            ResourceMethodMeta meta, List<RouteRegistrationViolation> violations) {
        List<ResourceMethodMeta.ParamMeta> params = meta.params();
        List<ResourceMethodMeta.ParamMeta> reported = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            ResourceMethodMeta.ParamMeta first = params.get(i);
            if (!isDescriptorMatchedSource(first.source()) || first.name() == null) {
                continue;
            }
            if (reported.stream().anyMatch(already -> bindsSameName(already, first))) {
                continue;
            }
            for (int j = i + 1; j < params.size(); j++) {
                ResourceMethodMeta.ParamMeta second = params.get(j);
                if (!bindsSameName(first, second)) {
                    continue;
                }
                String disagreement = sharedDescriptorDisagreement(first, second);
                if (disagreement == null) {
                    continue;
                }
                reported.add(first);
                violations.add(new RouteRegistrationViolation(
                        meta.operationId(),
                        RouteRegistrationViolation.ViolationType.DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT,
                        String.format(
                                "%s parameters '%s' (%s) and '%s' (%s) of %s.%s() bind the same name%s but cannot "
                                        + "share one declared parameter: they %s. Binding resolves a request name to "
                                        + "a single declared parameter (first match wins), and that one declaration "
                                        + "decides what both parameters receive, so at least one of the two can never "
                                        + "be honored: give them distinct names.",
                                first.source(),
                                first.name(),
                                describeShape(first),
                                second.name(),
                                describeShape(second),
                                meta.method().getDeclaringClass().getSimpleName(),
                                meta.method().getName(),
                                matchesNameCaseInsensitively(first.source()) ? " (matched case-insensitively)" : "",
                                disagreement)));
                break;
            }
        }
    }

    /**
     * Returns whether a parameter source's binding is decided by
     * {@code DefaultBoundRequest.findDescriptor}, i.e. whether two same-name declarations of that source
     * necessarily share one descriptor.
     *
     * <p>{@code FORM} is absent because {@code ParameterExtractor.extractFormParam} reads
     * {@code formAttributes()} per parameter and never consults {@code findDescriptor}, so every
     * {@code @FormParam} of a repeated name converts, defaults, and materializes independently.
     * {@code PATH} is present because {@code findDescriptor} <em>is</em> consulted for it
     * ({@code DefaultBoundRequest.bindPath}): two {@code @PathParam}s of one name share the descriptor
     * that converts the value, so a type or annotation disagreement there is as unbindable as on
     * {@code QUERY}. No {@code @PathParam} carries a component type today, so only the multiplicity half
     * of the check is unreachable for it; keeping the source in scope means adding {@code @PathParam}
     * collection support cannot silently escape that half either.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code PATH}, {@code QUERY}, {@code HEADER}, and {@code COOKIE}
     */
    private static boolean isDescriptorMatchedSource(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE -> true;
            case FORM, BODY, CONTEXT, PRECONDITIONS, FILE_UPLOADS, ENTITY_PARTS, BEAN_PARAM -> false;
        };
    }

    /**
     * Returns whether two parameters resolve to the <em>same</em> declared descriptor at bind time, i.e.
     * whether they share a source and a name under that source's matching rule.
     *
     * <p>The name comparison mirrors {@code DefaultBoundRequest.findDescriptor} exactly, including its use
     * of {@link String#equalsIgnoreCase(String)} rather than a lower-cased key: {@code HEADER} and
     * {@code COOKIE} match case-insensitively (both bound maps are keyed by lower-cased name, and HTTP/2
     * transmits header names in lower case per RFC 9113 §8.2.1), while {@code PATH} and {@code QUERY}
     * match verbatim. Diverging from that rule would misjudge which declarations actually collide.
     *
     * @param first  the earlier declaration; its source is descriptor-matched and its name non-{@code null}
     * @param second the later declaration
     * @return {@code true} when both bind the same request name from the same source
     */
    private static boolean bindsSameName(ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        if (first.source() != second.source() || second.name() == null) {
            return false;
        }
        return matchesNameCaseInsensitively(first.source())
                ? first.name().equalsIgnoreCase(second.name())
                : first.name().equals(second.name());
    }

    /**
     * Returns whether the given source's declared parameter names are matched case-insensitively by
     * {@code DefaultBoundRequest.findDescriptor} and {@code ParameterExtractor.lookup}.
     *
     * @param source the parameter's source
     * @return {@code true} for {@code HEADER} and {@code COOKIE}
     */
    private static boolean matchesNameCaseInsensitively(ResourceMethodMeta.ParamSource source) {
        return source == ResourceMethodMeta.ParamSource.HEADER || source == ResourceMethodMeta.ParamSource.COOKIE;
    }

    /**
     * Returns the way two declarations of one name disagree about something their <em>single shared
     * descriptor</em> decides, as a clause for the diagnostic — or {@code null} when they agree on all of
     * them and can therefore share one descriptor harmlessly.
     *
     * <p>The disagreements, in the order they are reported:
     *
     * <ol>
     *   <li><b>Multiplicity</b> ({@link #multiplicityConflicts}) — exactly one of the two is
     *       collection-shaped. The single descriptor decides the multiplicity of the bound value for both
     *       parameters, so exactly one is always mis-bound: the collection parameter degrades to a
     *       one-element collection (dropping every repeated value), or the scalar parameter receives a
     *       {@code JsonArray} its declared type has no converter for.</li>
     *   <li><b>The conversion target type.</b> Which type that is depends on the shape, because the
     *       descriptor's role differs:
     *       <ul>
     *         <li><em>Scalar pair</em> — the descriptor performs the conversion:
     *             {@code DefaultBoundRequest.wrapScalar} converts the raw value once through
     *             {@code ConversionContexts.forDescriptor}, whose {@code rawType}/{@code genericType} come
     *             from the first declaration, and {@code ParameterExtractor.coerce} passes an
     *             already-converted value through unchanged. So {@code @QueryParam("id") Integer} plus
     *             {@code @QueryParam("id") UUID} mounts and then fails in {@code Method.invoke} on every
     *             request carrying {@code id}. Both {@code type()} and {@code genericType()} are compared,
     *             since both are handed to a {@code ParamConverterProvider}.</li>
     *         <li><em>Collection pair</em> — the descriptor decides multiplicity <em>only</em>:
     *             {@code wrapValues} wraps the raw values into one {@code JsonArray} without converting,
     *             and {@code ParameterExtractor.coerceCollection} converts each element and materializes
     *             the collection from the <em>parameter's own</em> {@code componentType()}/{@code type()}.
     *             The concrete collection type is therefore <em>not</em> compared: {@code List<String>}
     *             plus {@code Set<String>} each materialize their own declared shape correctly. The
     *             <em>element</em> type is compared, because one request name cannot mean two element
     *             types at once — the single per-name schema derived from the descriptor
     *             ({@code AnnotationSchemaSource.synthesizeParam}) can describe only one of them, and each
     *             parameter's element conversion is fail-closed, so a value valid for one declaration
     *             fails the other.</li>
     *       </ul></li>
     *   <li><b>Binding-affecting annotations</b> ({@link #bindingAnnotationsAgree}) — the descriptor
     *       carries one annotation array for the name.</li>
     * </ol>
     *
     * @param first  the earlier declaration
     * @param second the later declaration; binds the same name from the same source
     * @return the disagreement clause for the diagnostic, or {@code null} when the two can share one
     *     descriptor
     */
    @Nullable
    private static String sharedDescriptorDisagreement(
            ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        if (multiplicityConflicts(first, second)) {
            return "declare incompatible multiplicities — one collection-shaped, one scalar";
        }
        if (first.componentType() != null) {
            if (first.componentType() != second.componentType()) {
                return "declare different element types";
            }
        } else if (first.type() != second.type() || !Objects.equals(first.genericType(), second.genericType())) {
            return "declare different types";
        }
        if (!bindingAnnotationsAgree(first, second)) {
            return "declare one shape with different binding-affecting annotations";
        }
        return null;
    }

    /**
     * Returns whether two declarations of one name carry the same binding-affecting annotations, compared
     * as an unordered set (declaration order on a parameter is irrelevant to every consumer).
     *
     * <p>The compared set is <em>every</em> parameter annotation except the JAX-RS source annotation
     * itself ({@link #isBindingAnnotation}). Comparing the rest wholesale is deliberate and fails
     * <em>closed</em>: the descriptor's annotation array is handed over whole to
     * {@code ParamConverterProvider.getConverter(rawType, genericType, annotations)} — an application SPI
     * that may key on any annotation — and whole to
     * {@code AnnotationSchemaSource.applyConstraints}, which maps a growing set ({@code @Size},
     * {@code @Pattern}, {@code @Min}, {@code @Max}, {@code @DecimalMin}, {@code @DecimalMax}, Swagger's
     * {@code @Schema}, …) into the single per-name schema, so the second declaration's constraints
     * silently replace the first's. An allow-list of "conversion-affecting" annotations would have to
     * track both an open SPI and a mapper in another module, and would fail open — silently — whenever
     * either grows. {@code @DefaultValue} is compared for the same reason, and because two different
     * defaults are two absent-value contracts for one request name.
     *
     * <p>The one exclusion is the source annotation, whose {@code value()} is the parameter name that
     * {@link #bindsSameName} already compared under {@code findDescriptor}'s own rule. Comparing it again
     * would reject a case-differing {@code @HeaderParam("X-Id")}/{@code @HeaderParam("x-id")} pair whose
     * declarations bind identically.
     *
     * @param first  the earlier declaration
     * @param second the later declaration
     * @return {@code true} when both declare the same binding-affecting annotations
     */
    private static boolean bindingAnnotationsAgree(
            ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        List<Annotation> firstAnnotations = comparedAnnotations(first);
        List<Annotation> secondAnnotations = comparedAnnotations(second);
        return firstAnnotations.size() == secondAnnotations.size()
                && firstAnnotations.containsAll(secondAnnotations)
                && secondAnnotations.containsAll(firstAnnotations);
    }

    /**
     * Returns the parameter's annotations minus the JAX-RS source annotation, i.e. the set
     * {@link #bindingAnnotationsAgree} compares.
     *
     * @param pm the parameter metadata
     * @return the compared annotations; empty when the parameter captured none
     */
    private static List<Annotation> comparedAnnotations(ResourceMethodMeta.ParamMeta pm) {
        Annotation[] declared = pm.annotationsLazy().get();
        if (declared == null) {
            return List.of();
        }
        return Arrays.stream(declared)
                .filter(annotation -> !isBindingAnnotation(annotation.annotationType()))
                .toList();
    }

    /**
     * Returns whether two declarations of one name disagree about <em>multiplicity</em> — exactly one of
     * them carries a component type. Two collection shapes agree on multiplicity (both bind all values),
     * as do two scalars.
     *
     * @param first  the earlier declaration
     * @param second the later declaration
     * @return {@code true} when one is collection-shaped and the other is scalar
     */
    private static boolean multiplicityConflicts(
            ResourceMethodMeta.ParamMeta first, ResourceMethodMeta.ParamMeta second) {
        return (first.componentType() == null) != (second.componentType() == null);
    }

    /**
     * Renders a parameter's declared shape for a diagnostic: {@code String}, {@code List<String>}, or
     * {@code String[]}.
     *
     * @param pm the parameter metadata
     * @return the declared type's simple name, parameterized with the element type for a collection shape
     */
    private static String describeShape(ResourceMethodMeta.ParamMeta pm) {
        if (pm.componentType() == null || pm.type().isArray()) {
            return pm.type().getSimpleName();
        }
        return pm.type().getSimpleName() + "<" + pm.componentType().getSimpleName() + ">";
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
            if (isBindingAnnotation(ann.annotationType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the given annotation type is a JAX-RS value-binding (source) annotation. Shared by
     * {@link #hasBindingAnnotation} and {@link #comparedAnnotations}, which excludes exactly this set from
     * its comparison, so the two never disagree about what a source annotation is.
     *
     * @param type the annotation type to test
     * @return {@code true} for {@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     *     {@code @CookieParam}, {@code @FormParam}, and {@code @BeanParam}
     */
    private static boolean isBindingAnnotation(Class<? extends Annotation> type) {
        return type == PathParam.class
                || type == QueryParam.class
                || type == HeaderParam.class
                || type == CookieParam.class
                || type == FormParam.class
                || type == BeanParam.class;
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
