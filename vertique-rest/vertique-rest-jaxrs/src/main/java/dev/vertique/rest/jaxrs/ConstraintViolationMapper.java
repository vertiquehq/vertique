// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
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
import java.util.HashMap;
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
 * <p>A violation on a {@code QUERY}, {@code PATH}, {@code HEADER}, {@code COOKIE} or {@code FORM}
 * parameter is reported in {@code parameter}, never in {@code pointer}: the declared parameter name
 * followed by the leading {@code [n]} run of consecutive list or array positions at the start of the
 * violation's {@link PropertyPath} (so {@code @QueryParam("ids") List<@Min(1) Integer>} reports
 * {@code ids[1]}). Nothing after the first segment that is not a position is rendered, and a violation
 * without a structured path renders the name alone: the validator's path string is never echoed.
 *
 * <p>For {@link ResourceMethodMeta.ParamSource#BEAN_PARAM BEAN_PARAM} violations, the bean class is
 * reflected upon to find the JAX-RS annotation on the violated member (the first property of the
 * structured path, or, without one, the violation's path cut at its first {@code .} or {@code [}). The
 * entry carries that annotation's name and location, followed by the position run after the member.
 *
 * <p>A violation whose parameter cannot be determined (an unknown parameter index, as for a
 * cross-parameter or return-value constraint), a {@code @BeanParam} member without a JAX-RS annotation
 * or with an empty name, an empty declared parameter name, and any other parameter source carry
 * neither {@code pointer} nor {@code parameter} and no location, keeping the message and type.
 */
final class ConstraintViolationMapper {

    /**
     * Cache of resolved field locations for {@code @BeanParam} types, keyed by bean class. Each inner
     * map is keyed by the declared field (or record component) names of that class and is built in one
     * pass on the first violation for the bean type, so a name taken from a request never becomes a
     * key and error paths do not reflect per request.
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
            // The parameter could not be determined: report the failure without a location
            return unlocated(detail);
        }

        ResourceMethodMeta.ParamMeta pm = meta.params().get(idx);
        return switch (pm.source()) {
            case BODY ->
                new ValidationErrorDetail(detail.path(), detail.message(), "body", detail.type(), detail.args());
            case QUERY -> parameterEntry(pm.name(), "query", pv);
            case PATH -> parameterEntry(pm.name(), "path", pv);
            case HEADER -> parameterEntry(pm.name(), "header", pv);
            case COOKIE -> parameterEntry(pm.name(), "cookie", pv);
            case FORM -> parameterEntry(pm.name(), "form", pv);
            case BEAN_PARAM -> resolveBeanParamDetail(pm, pv);
            default -> unlocated(detail);
        };
    }

    /** An entry with neither {@code pointer} nor {@code parameter} and no location. */
    private static ValidationErrorDetail unlocated(ViolationDetail detail) {
        return new ValidationErrorDetail(null, null, null, detail.message(), null, detail.type(), detail.args());
    }

    /**
     * An entry naming a non-body request location: the declared name followed by the leading position
     * run of the violation's structured path, starting at segment {@code firstIndexSegment}. An absent
     * or empty name leaves the entry unlocated, so {@code parameter} is never empty.
     */
    private static ValidationErrorDetail parameterEntry(
            @Nullable String name, String location, ParameterViolation pv, int firstIndexSegment) {
        ViolationDetail detail = pv.detail();
        if (name == null || name.isEmpty()) {
            return unlocated(detail);
        }
        String parameter = name + leadingIndexes(pv.propertyPath(), firstIndexSegment);
        return new ValidationErrorDetail(
                null, parameter, null, detail.message(), location, detail.type(), detail.args());
    }

    private static ValidationErrorDetail parameterEntry(@Nullable String name, String location, ParameterViolation pv) {
        return parameterEntry(name, location, pv, 0);
    }

    /**
     * Renders the run of consecutive {@link PropertyPath.Index} segments that starts at segment
     * {@code from}, as {@code [n]} each, and stops at the first segment of any other kind. A
     * {@code null} path renders nothing.
     */
    private static String leadingIndexes(@Nullable PropertyPath path, int from) {
        if (path == null) {
            return "";
        }
        List<PropertyPath.Segment> segments = path.segments();
        StringBuilder out = new StringBuilder();
        for (int i = from; i < segments.size() && segments.get(i) instanceof PropertyPath.Index index; i++) {
            out.append('[').append(index.index()).append(']');
        }
        return out.toString();
    }

    /**
     * Resolves name and location for a {@code @BeanParam} violation by reflecting on the bean class
     * to find the JAX-RS annotation on the violated field or record component. The violated member is
     * the first property of the structured path; without a structured path it is the violation's path
     * cut at its first {@code .} or {@code [}, which counts only when it is a declared member.
     *
     * @param pm the parameter metadata for the bean param
     * @param pv the violation
     * @return an entry with the annotation's name, the position run after the member and the
     *     annotation's location, or an entry with neither member and no location if the member is not
     *     a declared field, carries no JAX-RS annotation, or has an empty annotation name
     */
    private static ValidationErrorDetail resolveBeanParamDetail(
            ResourceMethodMeta.ParamMeta pm, ParameterViolation pv) {
        PropertyPath path = pv.propertyPath();
        String rootField;
        if (path == null) {
            rootField = rootOf(pv.detail().path());
        } else if (!path.segments().isEmpty() && path.segments().get(0) instanceof PropertyPath.Property root) {
            rootField = root.name();
        } else {
            return unlocated(pv.detail());
        }

        FieldLocation loc = findFieldLocation(pm.type(), rootField);
        if (loc == null) {
            // Not a declared member, or no JAX-RS annotation found on it
            return unlocated(pv.detail());
        }
        return parameterEntry(loc.name(), loc.location(), pv, 1);
    }

    /** The leading name of a Java path: the text before its first {@code .} or {@code [}. */
    private static String rootOf(String path) {
        int end = path.length();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                end = i;
                break;
            }
        }
        return path.substring(0, end);
    }

    /** Pairs a parameter name (from the JAX-RS annotation value) with its HTTP location string. */
    private record FieldLocation(String name, String location) {}

    /** Sentinel cached when no JAX-RS annotation is found on a bean field. */
    private static final FieldLocation NO_LOCATION = new FieldLocation("", "");

    /**
     * Finds the JAX-RS annotation on a declared field or record component to determine name and HTTP
     * location.
     *
     * @param beanType  the bean class to inspect
     * @param fieldName the simple field name to look up; any text is accepted, only a declared name
     *                  can match
     * @return a {@link FieldLocation} if {@code fieldName} is declared and carries a recognized JAX-RS
     *     annotation, {@code null} otherwise
     */
    @Nullable
    private static FieldLocation findFieldLocation(Class<?> beanType, String fieldName) {
        Map<String, FieldLocation> forType =
                BEAN_FIELD_CACHE.computeIfAbsent(beanType, ConstraintViolationMapper::scanFieldLocations);
        FieldLocation loc = forType.get(fieldName);
        return loc == NO_LOCATION ? null : loc;
    }

    /**
     * Reflective scan of every declared member's JAX-RS annotation. Called at most once per bean type;
     * the result is cached by {@link #findFieldLocation}. Members without a recognized annotation map
     * to {@link #NO_LOCATION}.
     */
    private static Map<String, FieldLocation> scanFieldLocations(Class<?> beanType) {
        Map<String, FieldLocation> byName = new HashMap<>();
        if (beanType.isRecord()) {
            for (RecordComponent component : beanType.getRecordComponents()) {
                // Check component annotations first, then fall back to accessor (mirrors
                // ResourceMethodInvoker.resolveComponentParam). JAX-RS annotations land on the
                // accessor due to @Target not including RECORD_COMPONENT, but future annotations
                // that target RECORD_COMPONENT should be found here.
                FieldLocation loc = resolveAnnotationLocation(component.getAnnotations());
                if (loc == null) {
                    loc = resolveAnnotationLocation(component.getAccessor().getAnnotations());
                }
                byName.put(component.getName(), loc != null ? loc : NO_LOCATION);
            }
            return Map.copyOf(byName);
        }
        // POJO fields: walk the class hierarchy, a subclass field hides a superclass field of the same name
        Class<?> current = beanType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                FieldLocation loc = resolveAnnotationLocation(field.getAnnotations());
                byName.putIfAbsent(field.getName(), loc != null ? loc : NO_LOCATION);
            }
            current = current.getSuperclass();
        }
        return Map.copyOf(byName);
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
