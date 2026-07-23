// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Utility for encoding arbitrary Jackson-serializable payloads to and from the JSONB storage
 * format used by the outbox table.
 *
 * <p>The PostgreSQL JSONB column requires a {@link JsonObject} (or {@link io.vertx.core.json.JsonArray})
 * as the bind parameter. This codec handles all payload types:
 * <ul>
 *   <li>POJOs and {@code Map<String, ?>} — serialized directly via {@link JsonObject#mapFrom(Object)}.
 *       The stored JSONB is the natural JSON representation of the object.</li>
 *   <li>Scalar values ({@code String}, {@code Number}, {@code Boolean}) and
 *       {@link io.vertx.core.json.JsonArray} — wrapped in a one-key envelope
 *       {@code {"_v": <value>}} so that the driver receives a {@link JsonObject}.
 *       The {@code _v} wrapper is transparent to handlers — {@link #decode(JsonObject)} and
 *       {@link #decode(JsonObject, Class)} unwrap it automatically.</li>
 *   <li>{@link JsonObject} values — stored as-is with no wrapping.</li>
 * </ul>
 *
 * <p>This class is not instantiable — use the static factory methods.
 */
public final class PayloadCodec {

    /** Marker key used to wrap non-object payloads in the JSONB envelope. */
    static final String SCALAR_KEY = "_v";

    /** Prevent instantiation. */
    private PayloadCodec() {}

    // --- Encode ---

    /**
     * Encodes a payload object to a {@link JsonObject} suitable for JSONB storage.
     *
     * <p>POJOs and maps are serialized via {@link JsonObject#mapFrom(Object)}. Values that cannot
     * be represented as a JSON object (scalars, arrays) are wrapped in
     * {@code {"_v": <value>}} so that the PostgreSQL driver receives a valid {@link JsonObject}.
     *
     * @param payload the value to encode; may be {@code null}
     * @return a {@link JsonObject} ready for use as a JSONB bind parameter, or {@code null} if
     *         {@code payload} is {@code null}
     */
    public static JsonObject encode(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof JsonObject jo) {
            return jo;
        }
        try {
            return JsonObject.mapFrom(payload);
        } catch (IllegalArgumentException e) {
            // Scalar or array — wrap in a single-key envelope
            return new JsonObject().put(SCALAR_KEY, payload);
        }
    }

    // --- Decode ---

    /**
     * Decodes a stored {@link JsonObject} back to the original payload value.
     *
     * <p>If the stored object matches the scalar envelope format (exactly one key {@code "_v"}),
     * the wrapped value is returned directly. Otherwise the {@link JsonObject} is returned as-is.
     *
     * @param stored the JSONB value read from the database; may be {@code null}
     * @return the unwrapped payload, or {@code null} if {@code stored} is {@code null}
     */
    public static Object decode(JsonObject stored) {
        if (stored == null) {
            return null;
        }
        if (isScalarEnvelope(stored)) {
            return stored.getValue(SCALAR_KEY);
        }
        return stored;
    }

    /**
     * Decodes a stored {@link JsonObject} and deserializes it to the specified target type.
     *
     * <p>If the stored object matches the scalar envelope format, the wrapped scalar is returned
     * — cast directly if it is already an instance of {@code targetType}, or converted via
     * {@link Json#decodeValue(String, Class)} otherwise. For non-scalar payloads
     * {@link JsonObject#mapTo(Class)} is used.
     *
     * @param <T>        the desired target type
     * @param stored     the JSONB value read from the database; may be {@code null}
     * @param targetType the class to deserialize into; must not be {@code null}
     * @return the deserialized value, or {@code null} if {@code stored} is {@code null}
     */
    public static <T> T decode(JsonObject stored, Class<T> targetType) {
        if (stored == null) {
            return null;
        }
        if (isScalarEnvelope(stored)) {
            Object scalar = stored.getValue(SCALAR_KEY);
            if (targetType.isInstance(scalar)) {
                return targetType.cast(scalar);
            }
            // Convert via JSON round-trip for type coercion (e.g. Number → Long, Object → UUID)
            return Json.decodeValue(Json.encode(scalar), targetType);
        }
        return stored.mapTo(targetType);
    }

    // --- Helpers ---

    /**
     * Returns {@code true} when {@code json} is a scalar envelope created by {@link #encode}.
     *
     * @param json the JSON object to inspect; must not be {@code null}
     * @return {@code true} if the object contains exactly the {@value #SCALAR_KEY} key
     */
    private static boolean isScalarEnvelope(JsonObject json) {
        return json.size() == 1 && json.containsKey(SCALAR_KEY);
    }
}
