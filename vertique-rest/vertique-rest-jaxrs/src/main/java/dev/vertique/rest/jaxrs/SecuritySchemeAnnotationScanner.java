// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirementEntry;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.lang.annotation.Annotation;
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
 * <p><strong>Absent vs empty {@code security = {}}:</strong> {@link Operation#security()} defaults to
 * an empty array, so reflection cannot distinguish a developer who wrote {@code security = {}} from
 * one who omitted {@code security} entirely — both surface as a zero-length array. This scanner
 * therefore treats an {@code @Operation} that contributes no named requirement at the method level as
 * "no method-level requirement from {@code @Operation}" and continues to consider standalone
 * method-level {@code @SecurityRequirement}s before falling back to the class level. In practice this
 * matches the spec the build-time plugin generates: the OpenAPI {@code security: []} "no auth" marker
 * is not expressible through this annotation-only path, and an operation that means "secured" carries
 * a named scheme via one of the forms above.
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
