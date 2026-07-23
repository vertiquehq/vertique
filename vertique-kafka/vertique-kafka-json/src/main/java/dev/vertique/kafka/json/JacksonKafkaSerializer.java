// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.kafka.serialization.KafkaSerializer;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.Map;

/**
 * Default Jackson JSON serializer for Kafka producer messages.
 *
 * <p>Uses the framework's shared {@link ObjectMapper} from {@link DatabindCodec} by default.
 *
 * @param <V> the value type
 */
public class JacksonKafkaSerializer<V> implements KafkaSerializer<V> {

    private final ObjectMapper mapper;

    /**
     * Creates a serializer using the framework's shared ObjectMapper.
     */
    public JacksonKafkaSerializer() {
        this(DatabindCodec.mapper());
    }

    /**
     * Creates a serializer with a custom ObjectMapper.
     *
     * @param mapper the ObjectMapper to use
     */
    public JacksonKafkaSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] serialize(V value, String topic, Map<String, String> headers) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize value: " + e.getMessage(), e);
        }
    }
}
