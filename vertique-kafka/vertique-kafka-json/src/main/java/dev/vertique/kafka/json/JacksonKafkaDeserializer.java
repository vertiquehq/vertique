// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Default Jackson JSON deserializer for Kafka record values.
 *
 * <p>The no-arg constructor does not capture a mapper: it resolves {@link VertiqueJson#mapper()} —
 * the process JSON codec's mapper — lazily, on every {@link #deserialize} call. A no-arg serde may
 * be built before the process mapper is installed (e.g. at Dagger graph construction time), so
 * capturing it eagerly at construction would freeze in the pre-install raw mapper; resolving per
 * call always observes the currently-installed mapper. The two-argument constructor accepts a
 * caller-supplied {@code ObjectMapper} and stays eager — the caller controls its lifecycle.
 *
 * @param <V> the value type
 */
public class JacksonKafkaDeserializer<V> implements KafkaDeserializer<V> {

    @Nullable
    private final ObjectMapper mapper;

    private final Class<V> type;

    /**
     * Creates a deserializer that resolves the process JSON codec's mapper —
     * {@link VertiqueJson#mapper()} — lazily, on every {@link #deserialize} call, rather than
     * capturing it at construction time.
     *
     * @param type the target type
     */
    public JacksonKafkaDeserializer(Class<V> type) {
        this.type = type;
        this.mapper = null;
    }

    /**
     * Creates a deserializer with a custom ObjectMapper, captured eagerly at construction.
     *
     * @param type the target type
     * @param mapper the ObjectMapper to use
     */
    public JacksonKafkaDeserializer(Class<V> type, ObjectMapper mapper) {
        this.type = type;
        this.mapper = mapper;
    }

    @Override
    public V deserialize(byte[] data, String topic, Map<String, String> headers) throws DeserializationException {
        ObjectMapper resolved = mapper != null ? mapper : VertiqueJson.mapper();
        try {
            return resolved.readValue(data, type);
        } catch (Exception e) {
            // Message is intentionally value-free: Jackson exception messages can embed offending
            // scalar values from the untrusted record, which must not leak into operator logs or
            // DLQ headers. The Jackson exception is preserved as the cause for stacktrace debugging.
            throw new DeserializationException(
                    "Failed to deserialize " + type.getSimpleName() + " from topic " + topic, e);
        }
    }
}
