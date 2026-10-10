// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.kafka.KafkaRecordHeaders;
import dev.vertique.kafka.serialization.KafkaSerializer;
import jakarta.annotation.Nullable;

/**
 * Default Jackson JSON serializer for Kafka producer messages.
 *
 * <p>The no-arg constructor does not capture a mapper: it resolves {@link VertiqueJson#mapper()} —
 * the process JSON codec's mapper — lazily, on every {@link #serialize} call. A no-arg serde may be
 * built before the process mapper is installed (e.g. at Dagger graph construction time), so
 * capturing it eagerly at construction would freeze in the pre-install raw mapper; resolving per
 * call always observes the currently-installed mapper. The two-argument constructor accepts a
 * caller-supplied {@code ObjectMapper} and stays eager — the caller controls its lifecycle.
 *
 * @param <V> the value type
 */
public class JacksonKafkaSerializer<V> implements KafkaSerializer<V> {

    @Nullable
    private final ObjectMapper mapper;

    /**
     * Creates a serializer that resolves the process JSON codec's mapper — {@link VertiqueJson#mapper()}
     * — lazily, on every {@link #serialize} call, rather than capturing it at construction time.
     */
    public JacksonKafkaSerializer() {
        this.mapper = null;
    }

    /**
     * Creates a serializer with a custom ObjectMapper, captured eagerly at construction.
     *
     * @param mapper the ObjectMapper to use
     */
    public JacksonKafkaSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Serializes the value as JSON. The topic and the headers are not used.
     *
     * @param value the value to serialize
     * @param topic the target Kafka topic; ignored
     * @param headers the application headers of the record being sent; ignored
     * @return the JSON bytes
     * @throws RuntimeException if the value cannot be serialized
     */
    @Override
    public byte[] serialize(V value, String topic, KafkaRecordHeaders headers) {
        ObjectMapper resolved = mapper != null ? mapper : VertiqueJson.mapper();
        try {
            return resolved.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize value: " + e.getMessage(), e);
        }
    }
}
