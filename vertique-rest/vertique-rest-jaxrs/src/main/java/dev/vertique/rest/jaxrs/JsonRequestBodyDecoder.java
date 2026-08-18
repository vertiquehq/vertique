// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Fallback {@link RequestBodyDecoder} that decodes JSON request bodies.
 *
 * <p>Accepts requests when the {@code Content-Type} is either unset ({@code null}) or
 * JSON-compatible (contains {@code "json"}, e.g. {@code application/json},
 * {@code application/problem+json}).
 *
 * <p>Dispatches decoding by target type:
 * <ul>
 *   <li>{@link JsonObject} — returns the raw JSON object from the body value</li>
 *   <li>{@link JsonArray} — returns the raw JSON array from the body value</li>
 *   <li>{@link String} — returns the raw JSON string representation</li>
 *   <li>{@code List<T>} — maps each JSON array element to {@code T} via the resolved mapper</li>
 *   <li>Any other type — maps the JSON object to the target type via the resolved mapper</li>
 * </ul>
 *
 * <p><b>Profile-aware materialization (FR-JSON-024B/022/023):</b> POJO and collection binding use the
 * non-{@code vertx} JSON profile mapper resolved for the method (stashed on the {@link RoutingContext}
 * under {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER}) when present, applying the profile's strict
 * materialization features; when absent (the {@code vertx} default) binding is byte-for-byte identical
 * to today ({@code JsonObject.mapTo()} / {@code DatabindCodec.mapper().convertValue()}). A profiled
 * materialization rejection surfaces as an HTTP {@code 400} via a {@code ValidationException}.
 *
 * <p>Priority: {@code 1100} (fallback, runs last among framework defaults).
 */
class JsonRequestBodyDecoder implements RequestBodyDecoder {

    /**
     * Resolved {@link JavaType}s for declared parameterized collection body types, keyed by the
     * declared {@link Type} itself.
     *
     * <p>{@link TypeFactory#constructType(Type)} walks the declared type's generic hierarchy to bind
     * {@code Collection<E>}, and Jackson's own type cache does not amortize a
     * {@link ParameterizedType} input, so the walk would otherwise run per request. The
     * {@link TypeFactory} it resolves against is always {@code DatabindCodec.mapper()}'s — a
     * {@link JavaType} is mapper-independent, so the cached value is valid whatever profile mapper
     * binds the elements.
     *
     * <p>Retention is bounded by the set of distinct declared body parameter types, which is fixed
     * at route registration; the reflective {@link ParameterizedType} implementations define
     * {@code equals}/{@code hashCode}, so routes declaring the same type share one entry. This
     * decoder is a singleton, so the map dies with its Dagger component.
     */
    private final ConcurrentMap<Type, JavaType> declaredCollectionTypes = new ConcurrentHashMap<>();

    @Override
    public int priority() {
        return 1100;
    }

    /**
     * Returns {@code true} when the content type is unset or JSON-compatible.
     *
     * @param targetType  the desired Java type (ignored)
     * @param contentType the {@code Content-Type} header value, or {@code null} if absent
     * @return {@code true} if {@code contentType} is {@code null} or contains {@code "json"}
     */
    @Override
    public boolean canDecode(Class<?> targetType, String contentType) {
        return contentType == null || contentType.toLowerCase().contains("json");
    }

    /**
     * Decodes the request body to the target type using JSON deserialization.
     *
     * <p>Handles both JSON objects and JSON arrays. For collection target types the full declared
     * generic type drives element binding (see {@link #decodeArray}).
     *
     * @param ctx         the current routing context (unused by this decoder)
     * @param body        the request body value
     * @param targetType  the raw Java class for the result
     * @param genericType the full generic type (e.g. {@code List<MyPojo>}), or {@code null}
     * @return the decoded body value, or {@code null} if the body is absent
     */
    @Override
    public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType, Type genericType) {
        if (targetType == JsonObject.class) {
            return body.getJsonObject();
        }
        if (targetType == JsonArray.class) {
            return body.getJsonArray();
        }
        if (targetType == String.class) {
            return body.getString();
        }

        // FR-JSON-024B/022/023: a non-vertx JSON profile resolved for this method (slice 2.1) is
        // stashed on the routing context under KEY_RESOLVED_BODY_MAPPER. When present, that profile
        // mapper owns MATERIALIZATION (POJO + collection binding); when absent (the vertx default),
        // the calls below are byte-for-byte identical to today (JsonObject.mapTo / DatabindCodec).
        ObjectMapper profileMapper = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER);

        // JSON array → List<T>, Set<T>, or T[]
        if (Collection.class.isAssignableFrom(targetType) || targetType.isArray()) {
            JsonArray jsonArray = body.getJsonArray();
            if (jsonArray != null) {
                return decodeArray(jsonArray, targetType, genericType, profileMapper);
            }
        }

        // JSON object → POJO
        JsonObject jsonBody = body.getJsonObject();
        if (jsonBody == null) {
            return null;
        }
        if (profileMapper != null) {
            // Profile path: bind via the resolved profile mapper so its strict materialization features
            // apply; a rejection becomes a 400 via the standard error pipeline (ValidationException).
            return ProfileBodyMaterialization.convertValue(profileMapper, jsonBody.getMap(), targetType);
        }
        return jsonBody.mapTo(targetType);
    }

    /**
     * Decodes a JSON array to a typed collection or array using Jackson's
     * {@link ObjectMapper#convertValue} for proper type coercion.
     *
     * <p>Handles {@code List<T>}, {@code Set<T>}, and {@code T[]} targets.
     * Uses {@link TypeFactory} to construct the correct {@link JavaType} so that
     * scalar elements (e.g. Integer → Long) are coerced to the declared element type.
     *
     * <p>For a collection target carrying declared type info the whole declared type is handed to
     * {@link TypeFactory#constructType(Type)}, so the element comes from the type's
     * {@code Collection<E>} supertype binding rather than from a type-argument position: a declared
     * argument is not the element type ({@code class Weird<A, B> extends ArrayList<B>} declared
     * {@code Weird<Other, Dto>} binds {@code Dto}). That resolution is memoized in
     * {@link #declaredCollectionTypes}, so the generic-hierarchy walk runs once per declared type
     * rather than once per request.
     *
     * <p>The gate for entering that resolution is {@code genericType != null} — <em>not</em> whether
     * {@code genericType} is itself a {@link ParameterizedType}. A concrete, non-generic collection
     * subtype ({@code final class Dtos extends ArrayList<Dto> {}} used as the plain field/parameter
     * type {@code Dtos}) reflects as a plain {@link Class}, not a {@code ParameterizedType} — yet
     * {@link TypeFactory#constructType(Type)} still walks its generic superclass chain and resolves
     * {@code Dto} correctly, exactly as it does for a directly-parameterized declaration. Gating on
     * {@code instanceof ParameterizedType} would reject that use site's own type before ever asking
     * Jackson, the same mistake the reflective {@code TypeClassifier} and the APT
     * {@code AnnotationCollector} both had to correct — a use site with no local type argument can
     * still have its element fixed by its own declaration.
     *
     * <p>A resolvable element still requires the {@link JavaType}'s content type to be something other
     * than plain {@code java.lang.Object}: that is Jackson's own signal for "no binding to report,"
     * covering both a genuinely raw target ({@code List} with no generic signature at all, whose
     * content type resolves to {@code Object} the same way a raw {@code Collection} does) and an
     * unresolvable owner-bound binding. Either falls through to the untyped branch below and is
     * returned unconverted, exactly as before.
     *
     * <p>The {@link JavaType} is built the same way regardless of profile; the element binding then
     * routes through {@code profileMapper} when a non-{@code vertx} profile applies (FR-JSON-022/023),
     * or {@code DatabindCodec.mapper()} when {@code profileMapper} is {@code null} (the unchanged vertx
     * path). A profile-mapper rejection is translated to a
     * {@link dev.vertique.core.exception.ValidationException} (HTTP 400).
     *
     * @param jsonArray     the JSON array to decode
     * @param targetType    the raw target class (e.g. {@code List.class}, {@code Set.class}, or array)
     * @param genericType   the full generic type for element type resolution
     * @param profileMapper the resolved non-{@code vertx} profile mapper for element binding, or
     *     {@code null} to use today's {@code DatabindCodec.mapper()} vertx path
     * @return the decoded collection or array
     */
    private Object decodeArray(JsonArray jsonArray, Class<?> targetType, Type genericType, ObjectMapper profileMapper) {
        // The JavaType is constructed from the vertx mapper's TypeFactory in both branches (a JavaType
        // is mapper-independent); only the binding call differs (profile vs vertx).
        TypeFactory tf = DatabindCodec.mapper().getTypeFactory();

        // T[] — array target
        if (targetType.isArray()) {
            Class<?> componentType = targetType.getComponentType();
            JavaType arrayType = tf.constructArrayType(componentType);
            return convertList(jsonArray.getList(), arrayType, profileMapper);
        }

        // List<T>, Set<T>, or other Collection<T> with declared type info. The full declared type
        // goes to Jackson, which binds the element from the Collection<E> supertype binding — a
        // declared type argument is not the element type (class Weird<A, B> extends ArrayList<B>
        // declared Weird<Other, Dto> has element Dto, and no argument position is reliably the
        // element). The gate is genericType != null, not "is a ParameterizedType": a non-generic
        // fixed subtype (Dtos extends ArrayList<Dto>, used as the plain type Dtos) reflects as a
        // Class, not a ParameterizedType, yet Jackson still resolves its element from the class's
        // own generic superclass. A content type of plain Object means Jackson found no binding to
        // report — a genuinely raw target or an unresolvable owner-bound generic — and both fall
        // through to the untyped branch below unconverted, exactly as before.
        if (genericType != null) {
            JavaType declaredType = declaredCollectionTypes.computeIfAbsent(genericType, tf::constructType);
            if (declaredType.isCollectionLikeType()
                    && declaredType.getContentType() != null
                    && declaredType.getContentType().getRawClass() != Object.class) {
                return convertList(jsonArray.getList(), declaredType, profileMapper);
            }
        }

        // Raw collection or unknown generic — return appropriate collection type
        @SuppressWarnings("unchecked")
        java.util.List<Object> rawList = jsonArray.getList();
        if (java.util.Set.class.isAssignableFrom(targetType)) {
            return new java.util.LinkedHashSet<>(rawList);
        }
        return rawList;
    }

    /**
     * Binds a JSON list to the resolved {@link JavaType} via the resolved mapper: the profile mapper
     * when non-{@code null} (translating a rejection to a 400
     * {@link dev.vertique.core.exception.ValidationException}), else the byte-for-byte-unchanged
     * {@code DatabindCodec.mapper()} vertx path.
     *
     * @param list          the raw element list from the JSON array
     * @param javaType      the target collection/array {@link JavaType}
     * @param profileMapper the resolved non-{@code vertx} profile mapper, or {@code null} for vertx
     * @return the materialized collection or array
     * @throws ValidationException when {@code profileMapper} rejects the body (HTTP 400)
     */
    private static Object convertList(java.util.List<?> list, JavaType javaType, ObjectMapper profileMapper) {
        if (profileMapper != null) {
            return ProfileBodyMaterialization.convertValue(profileMapper, list, javaType);
        }
        return DatabindCodec.mapper().convertValue(list, javaType);
    }
}
