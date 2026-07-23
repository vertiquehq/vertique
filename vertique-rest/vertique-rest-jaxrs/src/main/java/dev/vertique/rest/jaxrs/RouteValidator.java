// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates route registration constraints at startup.
 *
 * <p>Checks include: body parameter count, form/body conflicts, duplicate operationIds,
 * unmatched operationIds, and security annotations present without an auth module installed.
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
        return violations;
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
