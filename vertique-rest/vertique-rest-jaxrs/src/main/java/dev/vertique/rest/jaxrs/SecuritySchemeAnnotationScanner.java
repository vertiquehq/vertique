// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflective scanner that reads Swagger {@code @SecurityRequirement} annotations from a resource
 * operation's resolved method- and class-level annotation lists and maps them to the rest-core
 * neutral <strong>OR-of-AND</strong> model: a {@link List} of {@link SecurityRequirementSet}s.
 *
 * <p>This is the annotation-sourced replacement for reading security requirements from the generated
 * OpenAPI document. It reads the same {@code @SecurityRequirement}(s) the {@code swagger-maven-plugin}
 * reads for the spec, but at runtime via reflection. Resolution follows Swagger semantics:
 * <strong>operation-level (method) requirements override class-level requirements</strong> — if any
 * method-level requirement is present, only those apply; otherwise the class-level requirements apply.
 *
 * <p>Requirements are sourced from three equivalent annotation forms, all of which the
 * {@code swagger-maven-plugin} folds into the operation's spec security:
 * <ul>
 *   <li><strong>Standalone</strong> {@code @SecurityRequirement} / {@code @SecurityRequirements} on
 *       the method or class. Both forms of the {@code @Repeatable} annotation are handled: a single
 *       {@link io.swagger.v3.oas.annotations.security.SecurityRequirement} appears directly in the
 *       resolved list, while two or more collapse into the {@link SecurityRequirements} container.</li>
 *   <li><strong>Nested</strong> in the Swagger {@link Operation} annotation via
 *       {@link Operation#security()} — the common
 *       {@code @Operation(operationId = "...", security = { @SecurityRequirement(name = "bearerAuth") })}
 *       form. The standalone requirements and the {@code @Operation.security()} requirements at the
 *       same level are merged into the effective set for that level.</li>
 * </ul>
 *
 * <p><strong>Annotation → set mapping (mutually exclusive name/combine):</strong> each
 * {@code @SecurityRequirement} sets <em>exactly one</em> of {@code name()} (a single named scheme,
 * possibly with scopes) or {@code combine()} (an AND-group of {@link SecurityRequirementEntry}). A
 * named requirement maps to a <strong>single-scheme</strong> {@link SecurityRequirementSet}; a
 * {@code combine()} requirement maps to a <strong>multi-scheme AND</strong> set carrying one
 * {@link SecurityRequirement} per entry (entry order preserved). Multiple repeated
 * {@code @SecurityRequirement} annotations are <strong>OR</strong> alternatives, so each becomes a
 * separate set in declaration order.
 *
 * <p><strong>Fail-closed (C1):</strong> a {@code @SecurityRequirement} with <em>neither</em> a
 * {@code name()} nor a {@code combine()} — or with <em>both</em> — is malformed (the annotation
 * requires exactly one). The scanner throws {@link RestConfigurationException} naming the operationId
 * rather than silently dropping the requirement, which would let a secured operation mount public.
 *
 * <p><strong>Document level.</strong> An application's {@code @RestApplication} declaring interface
 * is its document: {@code @OpenAPIDefinition(security = ...)} on it applies to every operation the
 * application mounts that declares no requirement at the method or class level ({@link
 * #documentSecurity}). An {@code @OpenAPIDefinition} with a non-empty {@code security} reached through
 * a resource class's own hierarchy is refused, because the runtime would not honour it there.
 *
 * <p><strong>Absent vs empty {@code security = {}}:</strong> {@link Operation#security()} defaults to
 * an empty array, so reflection cannot distinguish a developer who wrote {@code security = {}} from
 * one who omitted {@code security} entirely — both surface as a zero-length array, and contribute no
 * requirement. The OpenAPI {@code security: []} "public" marker is instead the empty container
 * {@code @SecurityRequirements({})}: declared on the resource class or on the resource class's own
 * method, it exempts the operation from the document level (it never clears a class-level
 * requirement). Found only on a superclass, an interface or an inherited method, it is refused, so a
 * shared supertype cannot open every resource that inherits it.
 */
final class SecuritySchemeAnnotationScanner {

    private SecuritySchemeAnnotationScanner() {}

    /**
     * Returns the effective security requirement sets for an operation, resolved method-else-class and
     * modelled as <strong>OR-of-AND</strong>.
     *
     * <p>If any security requirement is declared at the method level — via a standalone
     * {@code @SecurityRequirement}(s) <em>or</em> via {@link Operation#security()} on a method-level
     * {@code @Operation} — only the method-level requirements are returned (operation-level overrides
     * class-level, matching Swagger semantics). Otherwise the class-level requirements are returned.
     *
     * <p>Each {@code @SecurityRequirement} becomes a {@link SecurityRequirementSet} (single-scheme for
     * a {@code name()} requirement, multi-scheme AND for a {@code combine()} requirement); the outer
     * list preserves declaration order as the OR alternatives.
     *
     * @param methodAnnotations the resolved method-level annotations (resource method plus overrides)
     * @param classAnnotations  the resolved class-level annotations (resource class plus hierarchy)
     * @return the immutable, possibly empty list of effective security requirement sets
     * @throws RestConfigurationException if any {@code @SecurityRequirement} is malformed (neither or
     *     both of {@code name()}/{@code combine()} set)
     */
    static List<SecurityRequirementSet> effectiveRequirements(
            List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        String operationId = resolveOperationId(methodAnnotations, classAnnotations);
        List<SecurityRequirementSet> methodLevel = collect(methodAnnotations, operationId);
        if (!methodLevel.isEmpty()) {
            return List.copyOf(methodLevel);
        }
        return List.copyOf(collect(classAnnotations, operationId));
    }

    /**
     * Returns the effective security requirement sets of a resource operation, applying, in order:
     * the method level, the class level, the public marker {@code @SecurityRequirements({})}, and
     * the document level of the application that mounts it.
     *
     * <ol>
     *   <li>Method-level requirements, when any: those (as {@link #effectiveRequirements(List, List)}).</li>
     *   <li>Otherwise class-level requirements, when any: those.</li>
     *   <li>Otherwise, when the empty container {@code @SecurityRequirements({})} is declared on the
     *       resource class or on the resource class's own method: no set (public with respect to the
     *       document level).</li>
     *   <li>Otherwise the document's sets ({@link DocumentSecurity#NONE} contributes none).</li>
     * </ol>
     *
     * @param meta the resource operation
     * @param document the document level of the mount that registers the operation
     * @return the immutable, possibly empty list of effective security requirement sets
     * @throws RestConfigurationException if a {@code @SecurityRequirement} is malformed, the resource's
     *     class hierarchy carries {@code @OpenAPIDefinition} with a non-empty {@code security}, or the
     *     public marker is only inherited
     */
    static List<SecurityRequirementSet> effectiveRequirements(ResourceMethodMeta meta, DocumentSecurity document) {
        List<Annotation> methodAnnotations = meta.methodAnnotations();
        List<Annotation> classAnnotations = meta.classAnnotations();
        Class<?> resourceClass = meta.resourceInstance().getClass();
        rejectResourceDocumentSecurity(classAnnotations, resourceClass);
        String operationId = resolveOperationId(methodAnnotations, classAnnotations);
        List<SecurityRequirementSet> methodLevel = collect(methodAnnotations, operationId);
        if (!methodLevel.isEmpty()) {
            return List.copyOf(methodLevel);
        }
        List<SecurityRequirementSet> classLevel = collect(classAnnotations, operationId);
        if (!classLevel.isEmpty()) {
            return List.copyOf(classLevel);
        }
        if (hasPublicMarker(methodAnnotations) || hasPublicMarker(classAnnotations)) {
            if (!declaresPublicMarker(resourceClass, meta.method())) {
                throw new RestConfigurationException(String.format(
                        "Operation '%s' inherits @SecurityRequirements({}) from a superclass, an interface or an "
                                + "inherited method of %s. The marker makes an operation public, so it is honoured "
                                + "only when declared on the resource class itself or on the resource class's own "
                                + "method; declare it there.",
                        operationId, resourceClass.getName()));
            }
            return List.of();
        }
        return document.sets();
    }

    /**
     * Reads the document-level security of an application from its declaring interface's {@code
     * @OpenAPIDefinition(security = ...)}, checking once for the shapes no operation could accept.
     *
     * @param declaringType the application's {@code @RestApplication} declaring interface
     * @param applicationName the application's name, for diagnostics
     * @return the document level; {@link DocumentSecurity#NONE} when the interface declares none
     * @throws RestConfigurationException if a requirement is malformed, is a multi-scheme (AND) group,
     *     or carries scopes while the document lists more than one alternative
     */
    static DocumentSecurity documentSecurity(Class<?> declaringType, String applicationName) {
        OpenAPIDefinition definition = declaringType.getAnnotation(OpenAPIDefinition.class);
        if (definition == null || definition.security().length == 0) {
            return DocumentSecurity.NONE;
        }
        String subject = String.format(
                "Application '%s' (%s) @OpenAPIDefinition(security)", applicationName, declaringType.getName());
        List<SecurityRequirementSet> sets = new ArrayList<>();
        for (io.swagger.v3.oas.annotations.security.SecurityRequirement req : definition.security()) {
            sets.add(toSet(req, subject));
        }
        if (sets.stream().anyMatch(set -> !set.isSingleScheme())) {
            throw new RestConfigurationException(subject
                    + " declares a multi-scheme requirement (combine() AND-group), which is not yet supported."
                    + " Declare a single scheme per requirement.");
        }
        if (sets.size() > 1 && sets.stream().anyMatch(SecurityRequirementSet::hasScopes)) {
            throw new RestConfigurationException(subject
                    + " declares scopes on one of several alternatives, which is not yet supported. Use a single"
                    + " scheme with scopes, or drop the scopes.");
        }
        return new DocumentSecurity(List.copyOf(sets));
    }

    /**
     * The document-level security requirement sets of an application: the OR alternatives its
     * declaring interface's {@code @OpenAPIDefinition(security = ...)} declares.
     *
     * @param sets the document's requirement sets, in declaration order; empty when it declares none
     */
    record DocumentSecurity(List<SecurityRequirementSet> sets) {

        /** No document level: a hand-built or legacy mount, or an application that declares none. */
        static final DocumentSecurity NONE = new DocumentSecurity(List.of());

        DocumentSecurity {
            sets = List.copyOf(sets);
        }
    }

    /**
     * Refuses an {@code @OpenAPIDefinition} with a non-empty {@code security} in a resource class's
     * resolved class annotations (the class, its superclasses and interfaces): the runtime reads
     * document-level security only from an application's declaring interface.
     */
    private static void rejectResourceDocumentSecurity(List<Annotation> classAnnotations, Class<?> resourceClass) {
        for (Annotation annotation : classAnnotations) {
            if (annotation instanceof OpenAPIDefinition definition && definition.security().length > 0) {
                throw new RestConfigurationException(String.format(
                        "Resource %s carries @OpenAPIDefinition(security) on itself or a supertype, which secures "
                                + "nothing at runtime. Declare it on the application's @RestApplication interface, or "
                                + "declare a class-level @SecurityRequirement on the resource.",
                        resourceClass.getName()));
            }
        }
    }

    /** Whether the list holds the public marker: an empty {@code @SecurityRequirements} container. */
    private static boolean hasPublicMarker(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (isPublicMarker(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublicMarker(@Nullable Annotation annotation) {
        return annotation instanceof SecurityRequirements container && container.value().length == 0;
    }

    /**
     * Whether the public marker is declared on the resource class itself or on the method the
     * resource class itself declares with the operation's signature.
     */
    private static boolean declaresPublicMarker(Class<?> resourceClass, Method method) {
        if (isPublicMarker(resourceClass.getDeclaredAnnotation(SecurityRequirements.class))) {
            return true;
        }
        try {
            Method own = resourceClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
            return isPublicMarker(own.getDeclaredAnnotation(SecurityRequirements.class));
        } catch (NoSuchMethodException inherited) {
            return false;
        }
    }

    /**
     * Collects the Swagger security requirements from a single annotation list into
     * {@link SecurityRequirementSet}s, expanding the {@link SecurityRequirements} container into its
     * members and the nested {@link Operation#security()} array into its entries.
     *
     * @param annotations the resolved annotation list to scan
     * @param operationId the operationId used in malformed-requirement diagnostics
     * @return the security requirement sets declared in the list, in declaration order (the OR
     *     alternatives)
     */
    private static List<SecurityRequirementSet> collect(List<Annotation> annotations, String operationId) {
        List<SecurityRequirementSet> result = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.security.SecurityRequirement req) {
                result.add(toSet(req, operationId));
            } else if (annotation instanceof SecurityRequirements container) {
                for (io.swagger.v3.oas.annotations.security.SecurityRequirement req : container.value()) {
                    result.add(toSet(req, operationId));
                }
            } else if (annotation instanceof Operation operation) {
                for (io.swagger.v3.oas.annotations.security.SecurityRequirement req : operation.security()) {
                    result.add(toSet(req, operationId));
                }
            }
        }
        return result;
    }

    /**
     * Maps a single Swagger {@code @SecurityRequirement} to a neutral {@link SecurityRequirementSet}.
     *
     * <p>Exactly one of {@code name()} / {@code combine()} must be set:
     * <ul>
     *   <li>{@code name()} non-blank, {@code combine()} empty → a single-scheme set.</li>
     *   <li>{@code name()} blank, {@code combine()} non-empty → a multi-scheme AND set, one
     *       {@link SecurityRequirement} per {@link SecurityRequirementEntry} (entry order preserved).</li>
     *   <li>neither set, or both set → malformed: {@link RestConfigurationException} is thrown.</li>
     * </ul>
     *
     * @param req         the Swagger requirement annotation to map
     * @param operationId the operationId named in the malformed-requirement diagnostic
     * @return the mapped {@link SecurityRequirementSet}
     * @throws RestConfigurationException if the requirement sets neither or both of name/combine
     */
    private static SecurityRequirementSet toSet(
            io.swagger.v3.oas.annotations.security.SecurityRequirement req, String operationId) {
        String name = req.name();
        boolean hasName = name != null && !name.isBlank();
        SecurityRequirementEntry[] combine = req.combine();
        boolean hasCombine = combine.length > 0;

        if (hasName && !hasCombine) {
            return new SecurityRequirementSet(List.of(new SecurityRequirement(name, List.of(req.scopes()))));
        }
        if (!hasName && hasCombine) {
            List<SecurityRequirement> schemes = new ArrayList<>(combine.length);
            for (SecurityRequirementEntry entry : combine) {
                schemes.add(new SecurityRequirement(entry.name(), List.of(entry.scopes())));
            }
            return new SecurityRequirementSet(schemes);
        }
        throw new RestConfigurationException(String.format(
                "Operation '%s' declares a malformed @SecurityRequirement: exactly one of name() or "
                        + "combine() must be set (found %s). Declare a named scheme (name = \"...\") or an "
                        + "AND-group (combine = { @SecurityRequirementEntry(...) }), not %s.",
                operationId, hasName ? "both" : "neither", hasName ? "both" : "neither"));
    }

    /**
     * Resolves the operationId for diagnostics from {@code @Operation(operationId)} (method-first then
     * class), falling back to a stable label when no non-blank operationId is declared.
     *
     * @param methodAnnotations the resolved method-level annotations
     * @param classAnnotations  the resolved class-level annotations
     * @return the resolved operationId, or {@code "<unknown>"} when none is declared
     */
    private static String resolveOperationId(List<Annotation> methodAnnotations, List<Annotation> classAnnotations) {
        String fromMethod = operationIdFrom(methodAnnotations);
        if (fromMethod != null) {
            return fromMethod;
        }
        String fromClass = operationIdFrom(classAnnotations);
        return fromClass != null ? fromClass : "<unknown>";
    }

    /**
     * Returns the non-blank {@code @Operation(operationId)} value from the given annotation list, or
     * {@code null} when no {@code @Operation} with a non-blank operationId is present.
     *
     * @param annotations the annotation list to scan
     * @return the non-blank operationId, or {@code null}
     */
    private static String operationIdFrom(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Operation operation
                    && !operation.operationId().isBlank()) {
                return operation.operationId();
            }
        }
        return null;
    }
}
