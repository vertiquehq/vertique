// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * SPI for a code-owned, named JSON profile: one JSON configuration seen through operations that name
 * no JSON library type.
 *
 * <p>A profile pairs a stable {@link JsonProfileId} with encode, decode and convert operations.
 * Applications contribute profiles to bind framework JSON boundaries (request body parsing, REST
 * client serialization, Kafka serde) to a specific configuration. The reserved
 * {@link JsonProfileId#SYSTEM} profile is the framework-supplied baseline; applications must not
 * contribute a profile with that id, with either of the other reserved ids ({@code vertique},
 * {@code vertique-strict}), or with the retired {@code vertx} id.
 *
 * <p>The Jackson 2 implementation is {@code dev.vertique.json.Jackson2JsonProfileCodec} in
 * {@code vertique-json}; the framework registry and process codec accept only that implementation.
 * Code that needs the underlying mapper reaches it through {@link #unwrap(Class)}.
 */
public interface JsonProfileCodec {

    /**
     * Returns the stable identifier under which this profile is registered and selected.
     *
     * @return the non-null profile id
     */
    JsonProfileId id();

    /**
     * Encodes {@code value} as UTF-8 JSON.
     *
     * @param value the value to encode; may be {@code null}
     * @return the encoded bytes
     * @throws JsonCodecException if encoding fails
     */
    Buffer encode(@Nullable Object value);

    /**
     * Encodes {@code value} as JSON text.
     *
     * @param value the value to encode; may be {@code null}
     * @return the JSON text
     * @throws JsonCodecException if encoding fails
     */
    String encodeToString(@Nullable Object value);

    /**
     * Writes {@code value} as UTF-8 JSON to {@code out}. Never closes {@code out} and never flushes it
     * through to an underlying stream.
     *
     * @param value the value to encode; may be {@code null}
     * @param out the target stream; left open
     * @throws JsonCodecException if encoding fails
     */
    void encodeTo(@Nullable Object value, OutputStream out);

    /**
     * Decodes exactly one complete JSON value from {@code json} into {@code type}, a {@code Class} or a
     * parameterized type. Content after the value is always rejected, whatever the underlying mapper's
     * own trailing-token setting. For an {@code Object} target a JSON object yields a
     * {@code JsonObject} and an array a {@code JsonArray}.
     *
     * @param <T> the target type
     * @param json the UTF-8 JSON input
     * @param type the target type
     * @return the decoded value
     * @throws JsonCodecException on malformed input, trailing content or a binding failure
     */
    <T> T decode(Buffer json, Type type);

    /**
     * Decodes exactly one complete JSON value from {@code json}, as {@link #decode(Buffer, Type)}.
     *
     * @param <T> the target type
     * @param json the JSON text
     * @param type the target type
     * @return the decoded value
     * @throws JsonCodecException on malformed input, trailing content or a binding failure
     */
    <T> T decode(String json, Type type);

    /**
     * Converts {@code value} into {@code type} through this profile's binding rules. For an
     * {@code Object} target a map yields a {@code JsonObject} and a list a {@code JsonArray}.
     *
     * @param <T> the target type
     * @param value the source value; may be {@code null}
     * @param type the target type
     * @return the converted value
     * @throws JsonCodecException if the value cannot be converted
     */
    <T> T convert(@Nullable Object value, Type type);

    /**
     * Returns the underlying mapper when it is an instance of {@code type}, otherwise empty.
     *
     * <p>Implementations return <strong>one stable instance per profile</strong>: every call returns the
     * same mapper, so callers may compare mappers by identity to decide whether two boundaries share a
     * configuration. Callers must not mutate the returned mapper; the registry validates a profile once,
     * at construction, and cannot prevent later reconfiguration, which this contract forbids.
     *
     * @param <T> the requested mapper type
     * @param type the requested mapper type, for example {@code com.fasterxml.jackson.databind.ObjectMapper}
     * @return the mapper, or empty when it is not an instance of {@code type}
     */
    <T> Optional<T> unwrap(Class<T> type);

    /**
     * Returns the JSON Schema overrides this profile declares for the schema generator — the wire
     * contract the profile actually produces or accepts for specific Java classes.
     *
     * <p>Implementations return the same stable, non-null, unmodifiable list on each call and must
     * defensively copy any caller-supplied collection. The empty default preserves every profile
     * implementation written before overrides existed.
     *
     * @return the non-null, unmodifiable list of declared overrides; empty by default
     */
    default List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
        return List.of();
    }
}
