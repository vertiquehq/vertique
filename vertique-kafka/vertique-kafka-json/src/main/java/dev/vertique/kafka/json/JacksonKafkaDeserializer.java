// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.Map;

/**
 * Default Jackson JSON deserializer for Kafka record values.
 *
 * <p>Uses the framework's shared {@link ObjectMapper} from {@link DatabindCodec} by default.
 *
 * @param <V> the value type
 */
public class JacksonKafkaDeserializer<V> implements KafkaDeserializer<V> {

    private final ObjectMapper mapper;
    private final Class<V> type;

    /**
     * Creates a deserializer using the framework's shared ObjectMapper.
     *
     * @param type the target type
     */
    public JacksonKafkaDeserializer(Class<V> type) {
        this(type, DatabindCodec.mapper());
    }

    /**
     * Creates a deserializer with a custom ObjectMapper.
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
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            // Message is intentionally value-free: Jackson exception messages can embed offending
            // scalar values from the untrusted record, which must not leak into operator logs or
            // DLQ headers. The Jackson exception is preserved as the cause for stacktrace debugging.
            throw new DeserializationException(
                    "Failed to deserialize " + type.getSimpleName() + " from topic " + topic, e);
        }
    }
}
