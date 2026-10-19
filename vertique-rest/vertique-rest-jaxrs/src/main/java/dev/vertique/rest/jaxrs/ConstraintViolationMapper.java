// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps {@link ParameterViolation} records to a {@link RestValidationException} with
 * HTTP-aware {@link ValidationErrorDetail} entries.
 *
 * <p>Each violation is enriched with an HTTP location ({@code "body"}, {@code "query"},
 * {@code "header"}, etc.) derived from the corresponding
 * {@link ResourceMethodMeta.ParamMeta#source() ParamSource}.
 *
 * <p>A violation on the request body is reported in {@code pointer} when a framework binder
 * materialized a JSON body with the mapper that also decides the route's wire names: an RFC 6901
 * JSON Pointer over those names, verified against the bound document, so it is shorter than the
 * violation's path where the document or the mapper cannot confirm a segment, never different. The
 * binders record what they did in a {@link BodyBindingMark}. The first 100 body violations in sorted
 * order are resolved; later ones, a body no framework binder materialized (an application decoder), a
 * body bound with another mapper, a body with no readable document and a violation a validator
 * reports without a structured path carry neither member and keep {@code location: "body"}. A
 * form-urlencoded body is a set of request parameters: its violations are reported in
 * {@code parameter} with {@code location: "form"}, under the field name of the mapper that bound the
 * body, and are not capped. The validator's path text is never echoed.
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
@Slf4j
final class ConstraintViolationMapper {

    /** The most body violations of one call that are resolved to a pointer, and logged. */
    private static final int MAX_BODY_POINTERS = 100;

    /** Marks the pointer of a logged event that stops before the end of the violation's path. */
    private static final String TRUNCATED = "(truncated)";

    /**
     * Logged in place of a pointer when no framework binder materialized the body, or no readable
     * document of it is available.
     */
    private static final String OMITTED_UNBOUND = "<omitted:unbound>";

    /** Logged in place of a pointer when the body was bound with another mapper than the route's. */
    private static final String OMITTED_MISMATCH = "<omitted:mismatch>";

    /** Logged in place of a pointer when the validator reported only the path text, no structured path. */
    private static final String OMITTED_UNSTRUCTURED = "<omitted:unstructured>";

    /**
     * One violation with the keys it is ordered by, each computed once, so the comparator only reads
     * fields: the parameter index, the neutralized Java path, the constraint type, the message and the
     * position the violation arrived in.
     */
    private record Ranked(
            int parameterIndex,
            String path,
            @Nullable String type,
            @Nullable String message,
            int position,
            ParameterViolation violation) {}

    private static final Comparator<Ranked> ORDER = Comparator.comparingInt(Ranked::parameterIndex)
            .thenComparing(Ranked::path)
            .thenComparing(Ranked::type, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Ranked::message, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(Ranked::position);

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
     * <p>The violations are first sorted by parameter index, the neutralized Java path, constraint
     * type, message and arrival position, so the response and the choice of which body violations are
     * resolved do not depend on the validator's iteration order.
     *
     * @param meta             the resource method metadata providing parameter source info
     * @param violations       the non-empty list of parameter violations
     * @param ctx              the routing context of the request, carrying how the body was bound
     * @param bodyNames        the route's wire &rarr; Java name projection for its bodies
     * @param projectionMapper the mapper {@code bodyNames} was built from, captured when the route was
     *                         registered
     * @return a REST validation exception ready for the error pipeline
     */
    static RestValidationException toRestValidationException(
            ResourceMethodMeta meta,
            List<ParameterViolation> violations,
            RoutingContext ctx,
            InputFieldNameResolver bodyNames,
            ObjectMapper projectionMapper) {
        List<Ranked> ranked = new ArrayList<>(violations.size());
        for (ParameterViolation violation : violations) {
            ranked.add(rank(ranked.size(), violation));
        }
        ranked.sort(ORDER);
        BodyReport body = new BodyReport(ctx, bodyNames, projectionMapper);
        List<ValidationErrorDetail> errors = new ArrayList<>(ranked.size());
        for (Ranked entry : ranked) {
            errors.add(toErrorDetail(meta, entry, body));
        }
        return new RestValidationException("Validation failed", errors);
    }

    private static Ranked rank(int position, ParameterViolation pv) {
        ViolationDetail detail = pv.detail();
        PropertyPath path = pv.propertyPath();
        String javaPath =
                path != null ? JakartaPathPointers.loggablePath(path) : JakartaPathPointers.loggablePath(detail.path());
        return new Ranked(pv.parameterIndex(), javaPath, detail.type(), detail.message(), position, pv);
    }

    private static ValidationErrorDetail toErrorDetail(ResourceMethodMeta meta, Ranked ranked, BodyReport body) {
        ParameterViolation pv = ranked.violation();
        int idx = pv.parameterIndex();
        ViolationDetail detail = pv.detail();

        if (idx < 0 || idx >= meta.params().size()) {
            // The parameter could not be determined: report the failure without a location
            return unlocated(detail);
        }

        ResourceMethodMeta.ParamMeta pm = meta.params().get(idx);
        return switch (pm.source()) {
            case BODY -> body.entry(pm, ranked);
            case QUERY -> parameterEntry(pm.name(), "query", pv);
            case PATH -> parameterEntry(pm.name(), "path", pv);
            case HEADER -> parameterEntry(pm.name(), "header", pv);
            case COOKIE -> parameterEntry(pm.name(), "cookie", pv);
            case FORM -> parameterEntry(pm.name(), "form", pv);
            case BEAN_PARAM -> resolveBeanParamDetail(pm, pv);
            default -> unlocated(detail);
        };
    }

    /**
     * The body-violation state of one call: how the body was bound, the mapper in force for this
     * request, the document the body was bound from and the resolution sessions, each read or opened
     * on first use and dropped with the call. Nothing here outlives the request.
     */
    private static final class BodyReport {

        private final RoutingContext ctx;
        private final InputFieldNameResolver names;
        private final ObjectMapper projectionMapper;
        private final @Nullable BodyBindingMark.Binding binding;
        private @Nullable ObjectMapper effectiveMapper;
        private boolean documentRead;
        private @Nullable Object document;
        private @Nullable JakartaPathPointers.Resolver jsonSession;
        private @Nullable JakartaPathPointers.Resolver formSession;

        /** The JSON body violations seen so far, which the cap counts. */
        private int jsonViolations;

        BodyReport(RoutingContext ctx, InputFieldNameResolver names, ObjectMapper projectionMapper) {
            this.ctx = ctx;
            this.names = names;
            this.projectionMapper = projectionMapper;
            this.binding = BodyBindingMark.of(ctx);
        }

        /** The entry for one violation on the body parameter {@code pm}. */
        ValidationErrorDetail entry(ResourceMethodMeta.ParamMeta pm, Ranked ranked) {
            ParameterViolation pv = ranked.violation();
            ViolationDetail detail = pv.detail();
            Type rootType = pm.genericType() != null ? pm.genericType() : pm.type();
            if (binding != null && binding.kind() == BodyBindingMark.Kind.FORM) {
                return formEntry(rootType, pv);
            }
            if (jsonViolations++ >= MAX_BODY_POINTERS) {
                return bodyEntry(null, detail);
            }
            String omitted = null;
            Located located = null;
            if (binding == null) {
                omitted = OMITTED_UNBOUND;
            } else if (binding.mapper() != effectiveMapper() || binding.mapper() != projectionMapper) {
                // Compared by identity: a mapper with the same configuration is another instance, and
                // the projection is only valid for the mapper it was built from.
                omitted = OMITTED_MISMATCH;
            } else if (document() == null) {
                omitted = OMITTED_UNBOUND;
            } else if (pv.propertyPath() == null) {
                // A validator without structured paths: the path text is never parsed, so the entry
                // carries neither member.
                omitted = OMITTED_UNSTRUCTURED;
            } else {
                located = locate(pv.propertyPath(), rootType);
            }
            if (log.isDebugEnabled()) {
                String pointer = located != null
                        ? loggable(located.pointer()) + (located.truncated() ? TRUNCATED : "")
                        : omitted;
                log.debug(
                        "body violation path={} pointer={} rootType={}",
                        ranked.path(),
                        pointer,
                        rootType.getTypeName());
            }
            return bodyEntry(located != null ? located.pointer() : null, detail);
        }

        private static ValidationErrorDetail bodyEntry(@Nullable String pointer, ViolationDetail detail) {
            return new ValidationErrorDetail(pointer, null, detail.message(), "body", detail.type(), detail.args());
        }

        /** The pointer of the violation and whether it stops before the end of the violation's path. */
        private Located locate(PropertyPath path, Type rootType) {
            if (jsonSession == null) {
                jsonSession = JakartaPathPointers.resolver(binding.mapper(), names);
            }
            String pointer = jsonSession.toPointer(path, rootType, document);
            return new Located(pointer, segments(pointer) < path.segments().size());
        }

        /**
         * The entry for a violation on a form-urlencoded body bound to a POJO: the field's name in the
         * mapper that bound the body, followed by the position run after it. The name is taken from
         * the first property of the structured path, and an unbound property or a path without a
         * structured form carries neither member.
         */
        private ValidationErrorDetail formEntry(Type rootType, ParameterViolation pv) {
            ViolationDetail detail = pv.detail();
            String parameter = null;
            PropertyPath path = pv.propertyPath();
            if (path != null && !path.segments().isEmpty() && path.segments().get(0) instanceof PropertyPath.Property) {
                String name = formFieldName(path.segments().get(0), rootType);
                if (name != null) {
                    parameter = name + leadingIndexes(path, 1);
                }
            }
            return new ValidationErrorDetail(null, parameter, detail.message(), "form", detail.type(), detail.args());
        }

        /**
         * The primary wire name of {@code property} in the mapper that bound the form body, or
         * {@code null} when that mapper does not bind it or the name is empty.
         */
        @Nullable
        private String formFieldName(PropertyPath.Segment property, Type rootType) {
            if (formSession == null) {
                formSession = JakartaPathPointers.resolver(binding.mapper(), null);
            }
            String pointer = formSession.toPointer(new PropertyPath(List.of(property)), rootType, null);
            if (pointer.length() < 2 || pointer.charAt(0) != '/') {
                return null;
            }
            // One emitted segment holds no raw slash: it is the property's name, RFC 6901 escaped.
            return pointer.substring(1).replace("~1", "/").replace("~0", "~");
        }

        /** The mapper in force for this request: the route's resolved one, else the live process mapper. */
        private ObjectMapper effectiveMapper() {
            if (effectiveMapper == null) {
                effectiveMapper = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER) instanceof ObjectMapper stashed
                        ? stashed
                        : VertiqueJson.mapper();
            }
            return effectiveMapper;
        }

        /** The JSON document the body was bound from, or {@code null} when the request holds none. */
        @Nullable
        private Object document() {
            if (!documentRead) {
                documentRead = true;
                try {
                    if (ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST) instanceof BoundRequest bound) {
                        Object raw = bound.body().get();
                        if (raw instanceof JsonObject
                                || raw instanceof JsonArray
                                || raw instanceof Map<?, ?>
                                || raw instanceof List<?>) {
                            document = raw;
                        }
                    }
                } catch (RuntimeException e) {
                    document = null;
                }
            }
            return document;
        }
    }

    /** A resolved pointer and whether the resolver stopped before the end of the violation's path. */
    private record Located(String pointer, boolean truncated) {}

    /** The segments of an emitted pointer: each one adds a slash, and a name holds none unescaped. */
    private static int segments(String pointer) {
        int count = 0;
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes every character that could forge or hide a log line: ISO control characters, Unicode
     * format characters and the line and paragraph separators.
     */
    private static String loggable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT
                    || codePoint == 0x2028
                    || codePoint == 0x2029) {
                continue;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    /** An entry with neither {@code pointer} nor {@code parameter} and no location. */
    private static ValidationErrorDetail unlocated(ViolationDetail detail) {
        return new ValidationErrorDetail(null, null, detail.message(), null, detail.type(), detail.args());
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
        return new ValidationErrorDetail(null, parameter, detail.message(), location, detail.type(), detail.args());
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
