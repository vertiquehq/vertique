// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps {@link ParameterViolation} records to a {@link RestValidationException} with
 * HTTP-aware {@link ValidationErrorDetail} entries.
 *
 * <p>Each violation is enriched with an HTTP location ({@code "body"}, {@code "query"},
 * {@code "header"}, etc.) derived from the corresponding
 * {@link ResourceMethodMeta.ParamMeta#source() ParamSource}.
 *
 * <p>For {@link ResourceMethodMeta.ParamSource#BEAN_PARAM BEAN_PARAM} violations, the bean class
 * is reflected upon to find the JAX-RS annotation on the violated field or record component,
 * allowing the correct HTTP location and parameter name to be surfaced in the error response.
 */
final class ConstraintViolationMapper {

    /**
     * Cache of resolved field locations for {@code @BeanParam} types, keyed by bean class.
     * Each inner map is keyed by field name. Populated lazily on first violation for a given
     * bean type + field combination; eliminates per-request reflection overhead on error paths.
     */
    private static final Map<Class<?>, Map<String, FieldLocation>> BEAN_FIELD_CACHE = new ConcurrentHashMap<>();

    private ConstraintViolationMapper() {}

    /**
     * Converts a list of parameter violations to a {@link RestValidationException}
     * with per-field HTTP location context.
     *
     * @param meta       the resource method metadata providing parameter source info
     * @param violations the non-empty list of parameter violations
     * @return a REST validation exception ready for the error pipeline
     */
    static RestValidationException toRestValidationException(
            ResourceMethodMeta meta, List<ParameterViolation> violations) {
        List<ValidationErrorDetail> errors =
                violations.stream().map(pv -> toErrorDetail(meta, pv)).toList();
        return new RestValidationException("Validation failed", errors);
    }

    private static ValidationErrorDetail toErrorDetail(ResourceMethodMeta meta, ParameterViolation pv) {
        int idx = pv.parameterIndex();
        ViolationDetail detail = pv.detail();

        if (idx < 0 || idx >= meta.params().size()) {
            // Fallback: parameter index could not be determined
            return new ValidationErrorDetail(detail.path(), detail.message(), null, detail.type(), detail.args());
        }

        ResourceMethodMeta.ParamMeta pm = meta.params().get(idx);
        return switch (pm.source()) {
            case BODY ->
                new ValidationErrorDetail(detail.path(), detail.message(), "body", detail.type(), detail.args());
            case QUERY -> new ValidationErrorDetail(pm.name(), detail.message(), "query", detail.type(), detail.args());
            case PATH -> new ValidationErrorDetail(pm.name(), detail.message(), "path", detail.type(), detail.args());
            case HEADER ->
                new ValidationErrorDetail(pm.name(), detail.message(), "header", detail.type(), detail.args());
            case COOKIE ->
                new ValidationErrorDetail(pm.name(), detail.message(), "cookie", detail.type(), detail.args());
            case FORM -> new ValidationErrorDetail(pm.name(), detail.message(), "form", detail.type(), detail.args());
            case BEAN_PARAM -> resolveBeanParamDetail(pm, detail);
            default -> new ValidationErrorDetail(detail.path(), detail.message(), null, detail.type(), detail.args());
        };
    }

    /**
     * Resolves HTTP location for a {@code @BeanParam} violation by reflecting on the
     * bean class to find the JAX-RS annotation on the violated field or record component.
     *
     * @param pm     the parameter metadata for the bean param
     * @param detail the violation detail containing the violated field path
     * @return a {@link ValidationErrorDetail} with the inferred HTTP location, or {@code null} location
     *     if no JAX-RS annotation was found
     */
    private static ValidationErrorDetail resolveBeanParamDetail(
            ResourceMethodMeta.ParamMeta pm, ViolationDetail detail) {
        String fieldName = detail.path();
        // For nested paths like "address.city", use the first segment to find the field
        int dotIdx = fieldName.indexOf('.');
        String rootField = (dotIdx > 0) ? fieldName.substring(0, dotIdx) : fieldName;

        Class<?> beanType = pm.type();
        FieldLocation loc = findFieldLocation(beanType, rootField);
        if (loc != null) {
            // Preserve nested path suffix (e.g., "address.city" → loc.name() + ".city")
            String path = (dotIdx > 0) ? loc.name() + fieldName.substring(dotIdx) : loc.name();
            return new ValidationErrorDetail(path, detail.message(), loc.location(), detail.type(), detail.args());
        }
        // Fallback: no JAX-RS annotation found on the field
        return new ValidationErrorDetail(detail.path(), detail.message(), null, detail.type(), detail.args());
    }

    /** Pairs a parameter name (from the JAX-RS annotation value) with its HTTP location string. */
    private record FieldLocation(String name, String location) {}

    /** Sentinel cached when no JAX-RS annotation is found on a bean field. */
    private static final FieldLocation NO_LOCATION = new FieldLocation("", "");

    /**
     * Finds the JAX-RS annotation on a field or record component to determine HTTP location.
     * For records, components are checked first; for POJOs, the class hierarchy is walked.
     *
     * @param beanType  the bean class to inspect
     * @param fieldName the simple field name to look up
     * @return a {@link FieldLocation} if a recognized JAX-RS annotation is found, {@code null} otherwise
     */
    @Nullable
    private static FieldLocation findFieldLocation(Class<?> beanType, String fieldName) {
        Map<String, FieldLocation> forType = BEAN_FIELD_CACHE.computeIfAbsent(beanType, k -> new ConcurrentHashMap<>());
        FieldLocation loc = forType.computeIfAbsent(fieldName, fn -> {
            FieldLocation resolved = findFieldLocationUncached(beanType, fn);
            return resolved != null ? resolved : NO_LOCATION;
        });
        return loc == NO_LOCATION ? null : loc;
    }

    /**
     * Reflective lookup of a field's JAX-RS annotation. Called at most once per
     * (bean type, field name) pair; results are cached by {@link #findFieldLocation}.
     */
    @Nullable
    private static FieldLocation findFieldLocationUncached(Class<?> beanType, String fieldName) {
        if (beanType.isRecord()) {
            for (RecordComponent component : beanType.getRecordComponents()) {
                if (component.getName().equals(fieldName)) {
                    // Check component annotations first, then fall back to accessor (mirrors
                    // ResourceMethodInvoker.resolveComponentParam). JAX-RS annotations land on the
                    // accessor due to @Target not including RECORD_COMPONENT, but future annotations
                    // that target RECORD_COMPONENT should be found here.
                    FieldLocation loc = resolveAnnotationLocation(component.getAnnotations());
                    return loc != null
                            ? loc
                            : resolveAnnotationLocation(component.getAccessor().getAnnotations());
                }
            }
        }
        // POJO fields — walk the class hierarchy
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                return resolveAnnotationLocation(field.getAnnotations());
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Resolves the HTTP location and parameter name from a set of annotations by checking
     * for known JAX-RS parameter annotations.
     *
     * @param annotations the annotations to inspect (from a field or record component accessor)
     * @return a {@link FieldLocation} if a recognized JAX-RS annotation is present, {@code null} otherwise
     */
    @Nullable
    private static FieldLocation resolveAnnotationLocation(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            if (ann instanceof QueryParam qp) return new FieldLocation(qp.value(), "query");
            if (ann instanceof PathParam pp) return new FieldLocation(pp.value(), "path");
            if (ann instanceof HeaderParam hp) return new FieldLocation(hp.value(), "header");
            if (ann instanceof CookieParam cp) return new FieldLocation(cp.value(), "cookie");
            if (ann instanceof FormParam fp) return new FieldLocation(fp.value(), "form");
        }
        return null;
    }
}
