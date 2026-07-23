// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dev.vertique.core.validation.ViolationDetail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps {@link ConstraintViolation} instances to {@link ViolationDetail} records by
 * resolving violation types and extracting constraint arguments.
 *
 * <p>Type resolution chain:
 * <ol>
 *   <li>{@link ViolationTypeMapping} set (simple annotation to type lookups)</li>
 *   <li>{@link ViolationTypeMapper} set (programmatic mapping)</li>
 *   <li>Fallback: constraint annotation simple name</li>
 * </ol>
 *
 * <p>Args resolution:
 * <ol>
 *   <li>First {@link ViolationArgsInspector} whose {@link ViolationArgsInspector#supports}
 *       returns {@code true}</li>
 *   <li>Fallback: {@link BuiltInViolationArgsInspector}</li>
 * </ol>
 */
@Singleton
class ViolationDetailMapper {

    private final Map<Class<? extends Annotation>, String> typeMappings;
    private final List<ViolationTypeMapper> typeMappers;
    private final List<ViolationArgsInspector> argsInspectors;
    private final BuiltInViolationArgsInspector builtInArgsInspector;

    /**
     * Creates a new mapper with the contributed type mappings, mappers, and inspectors.
     *
     * @param typeMappings   simple annotation to type bindings
     * @param typeMappers    programmatic type mappers
     * @param argsInspectors custom args inspectors
     */
    @Inject
    ViolationDetailMapper(
            Set<ViolationTypeMapping> typeMappings,
            Set<ViolationTypeMapper> typeMappers,
            Set<ViolationArgsInspector> argsInspectors) {
        // Last-wins merge: user-contributed mappings override built-in defaults
        this.typeMappings = typeMappings.stream()
                .collect(Collectors.toMap(
                        ViolationTypeMapping::annotationType,
                        ViolationTypeMapping::type,
                        (existing, override) -> override));
        this.typeMappers = List.copyOf(typeMappers);
        this.argsInspectors = List.copyOf(argsInspectors);
        this.builtInArgsInspector = new BuiltInViolationArgsInspector();
    }

    /**
     * Converts a set of constraint violations to a list of {@link ViolationDetail} records.
     *
     * @param violations the constraint violations
     * @param <T>        the validated object type
     * @return an unmodifiable list of violation details
     */
    <T> List<ViolationDetail> toDetails(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(this::toDetail).toList();
    }

    /**
     * Converts a single constraint violation to a {@link ViolationDetail}.
     *
     * @param violation the constraint violation
     * @return the violation detail
     */
    ViolationDetail toDetail(ConstraintViolation<?> violation) {
        Class<? extends Annotation> annotationType =
                violation.getConstraintDescriptor().getAnnotation().annotationType();
        return new ViolationDetail(
                violation.getPropertyPath().toString(),
                violation.getMessage(),
                resolveType(annotationType),
                resolveArgs(annotationType, violation));
    }

    /**
     * Resolves the violation type for the given constraint annotation.
     *
     * @param annotationType the constraint annotation class
     * @return the resolved type string
     */
    private String resolveType(Class<? extends Annotation> annotationType) {
        // 1. Simple mappings
        String type = typeMappings.get(annotationType);
        if (type != null) {
            return type;
        }

        // 2. Programmatic mappers
        for (ViolationTypeMapper mapper : typeMappers) {
            type = mapper.typeFor(annotationType);
            if (type != null) {
                return type;
            }
        }

        // 3. Fallback: annotation simple name
        return annotationType.getSimpleName();
    }

    /**
     * Extracts args from the constraint violation using the first matching inspector,
     * falling back to the built-in inspector.
     *
     * @param annotationType the constraint annotation class (already extracted by caller)
     * @param violation      the constraint violation
     * @return the args map, or {@code null} if no args
     */
    private Map<String, Object> resolveArgs(
            Class<? extends Annotation> annotationType, ConstraintViolation<?> violation) {
        // Custom inspectors first
        for (ViolationArgsInspector inspector : argsInspectors) {
            if (inspector.supports(annotationType)) {
                return inspector.extract(violation);
            }
        }

        // Built-in fallback
        return builtInArgsInspector.extract(violation);
    }
}
