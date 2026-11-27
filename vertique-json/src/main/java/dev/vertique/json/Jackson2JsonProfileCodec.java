// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.vertique.core.json.JsonCodecException;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileConfigurationException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link JsonProfileCodec} over one Jackson 2 {@link ObjectMapper}.
 *
 * <p>Subclasses supply the mapper through {@code mapper()}. The base calls it <em>once</em>, on first
 * use, and runs every operation and {@link #unwrap(Class)} on that one instance, so a subclass whose
 * {@code mapper()} returns a new instance per call still behaves as one profile. The mapper is never
 * reconfigured: decoding adds Jackson's trailing-token check through a reader per call, not by changing
 * the shared mapper.
 *
 * <p>Failures become {@link JsonCodecException} with a fixed, value-free message — {@code "JSON encoding
 * failed"}, {@code "JSON decoding failed"} or {@code "JSON conversion failed"} — and the Jackson exception
 * as the cause. The cause may quote the input; never return it to a client.
 */
public abstract class Jackson2JsonProfileCodec implements JsonProfileCodec {

    private static final String ENCODING_FAILED = "JSON encoding failed";
    private static final String DECODING_FAILED = "JSON decoding failed";
    private static final String CONVERSION_FAILED = "JSON conversion failed";

    private final Object captureLock = new Object();

    /** The mapper captured on first use; written once under {@link #captureLock}. */
    private volatile ObjectMapper captured;

    /** Creates the profile; the mapper is resolved on first use, not here. */
    protected Jackson2JsonProfileCodec() {}

    /**
     * Returns the mapper this profile runs on. The base calls it once, on first use.
     *
     * @return the non-null mapper
     */
    protected abstract ObjectMapper mapper();

    @Override
    public final Buffer encode(@Nullable Object value) {
        try {
            return Buffer.buffer(captured().writeValueAsBytes(value));
        } catch (IOException | IllegalArgumentException e) {
            throw new JsonCodecException(ENCODING_FAILED, e);
        }
    }

    @Override
    public final String encodeToString(@Nullable Object value) {
        try {
            return captured().writeValueAsString(value);
        } catch (IOException | IllegalArgumentException e) {
            throw new JsonCodecException(ENCODING_FAILED, e);
        }
    }

    @Override
    public final void encodeTo(@Nullable Object value, OutputStream out) {
        Objects.requireNonNull(out, "out must not be null");
        try {
            captured()
                    .writer()
                    .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .without(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM)
                    .writeValue(out, value);
        } catch (IOException | IllegalArgumentException e) {
            throw new JsonCodecException(ENCODING_FAILED, e);
        }
    }

    @Override
    public final <T> T decode(Buffer json, Type type) {
        Objects.requireNonNull(json, "json must not be null");
        ObjectMapper mapper = captured();
        try {
            return adaptTopLevel(reader(mapper, type).readValue(json.getBytes()), type);
        } catch (IOException | IllegalArgumentException e) {
            throw new JsonCodecException(DECODING_FAILED, e);
        }
    }

    @Override
    public final <T> T decode(String json, Type type) {
        Objects.requireNonNull(json, "json must not be null");
        ObjectMapper mapper = captured();
        try {
            return adaptTopLevel(reader(mapper, type).readValue(json), type);
        } catch (IOException | IllegalArgumentException e) {
            throw new JsonCodecException(DECODING_FAILED, e);
        }
    }

    @Override
    public final <T> T convert(@Nullable Object value, Type type) {
        Objects.requireNonNull(type, "type must not be null");
        ObjectMapper mapper = captured();
        try {
            return adaptTopLevel(mapper.convertValue(value, mapper.constructType(type)), type);
        } catch (IllegalArgumentException e) {
            throw new JsonCodecException(CONVERSION_FAILED, e);
        }
    }

    @Override
    public final <T> Optional<T> unwrap(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        ObjectMapper mapper = captured();
        return type.isInstance(mapper) ? Optional.of(type.cast(mapper)) : Optional.empty();
    }

    /**
     * Returns the Jackson 2 {@link ObjectMapper} behind {@code profile}.
     *
     * @param profile the profile; must not be {@code null}
     * @return the profile's mapper, the same instance on every call
     * @throws JsonProfileConfigurationException naming the profile id when it has no Jackson 2 mapper
     */
    public static ObjectMapper objectMapper(JsonProfileCodec profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        return profile.unwrap(ObjectMapper.class)
                .orElseThrow(() -> new JsonProfileConfigurationException(
                        "JSON profile '" + profile.id().value() + "' has no Jackson 2 ObjectMapper"));
    }

    /** Returns the mapper captured on first use, capturing it if needed. */
    private ObjectMapper captured() {
        ObjectMapper mapper = captured;
        if (mapper == null) {
            synchronized (captureLock) {
                mapper = captured;
                if (mapper == null) {
                    mapper = Objects.requireNonNull(mapper(), "mapper() must not return null");
                    captured = mapper;
                }
            }
        }
        return mapper;
    }

    private static ObjectReader reader(ObjectMapper mapper, Type type) {
        Objects.requireNonNull(type, "type must not be null");
        return mapper.readerFor(mapper.constructType(type)).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * For an {@code Object} target, wraps a top-level map as {@link JsonObject} and a top-level list or
     * array as {@link JsonArray}; any other result is returned unchanged.
     */
    @SuppressWarnings("unchecked")
    private static <T> T adaptTopLevel(@Nullable Object value, Type type) {
        if (type != Object.class) {
            return (T) value;
        }
        if (value instanceof Map<?, ?> map) {
            return (T) new JsonObject((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return (T) new JsonArray(list);
        }
        if (value instanceof Object[] array) {
            return (T) new JsonArray(Arrays.asList(array));
        }
        return (T) value;
    }
}
