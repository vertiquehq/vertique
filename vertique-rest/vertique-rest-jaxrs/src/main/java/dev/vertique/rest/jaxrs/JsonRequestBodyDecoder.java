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
     * <p>Handles both JSON objects and JSON arrays. For {@code List<T>} target types,
     * the generic type parameter is used to determine the element class for mapping.
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

        // List<T>, Set<T>, or other Collection<T> with generic type info
        if (genericType instanceof ParameterizedType pt) {
            Type elementType = pt.getActualTypeArguments()[0];
            if (elementType instanceof Class<?> elementClass) {
                @SuppressWarnings("unchecked")
                Class<? extends Collection<?>> collType = (Class<? extends Collection<?>>) targetType;
                JavaType javaType = tf.constructCollectionType(collType, elementClass);
                return convertList(jsonArray.getList(), javaType, profileMapper);
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
