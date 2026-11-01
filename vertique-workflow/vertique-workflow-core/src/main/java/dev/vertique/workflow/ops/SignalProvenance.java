// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import java.util.Map;
import java.util.Objects;

/**
 * Where a correlated signal came from: the transport that carried it, the named source that received it, and the
 * transport's own coordinates of the message.
 *
 * <p>The record names a message and never holds its key, headers or payload.
 *
 * @param transport the transport that carried the message, such as {@code kafka} or {@code camel}
 * @param source the name of the source that received the message
 * @param attributes the coordinates of the message on the transport, by name; copied into an unmodifiable map
 */
public record SignalProvenance(String transport, String source, Map<String, String> attributes) {

    /**
     * Validates the components and copies the attributes.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public SignalProvenance {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(source, "source");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Describes a message received from a Kafka topic.
     *
     * @param source the name of the source that received the message
     * @param topic the topic the record was read from
     * @param partition the partition the record was read from
     * @param offset the offset of the record in its partition
     * @return the provenance, with transport {@code kafka} and the topic, partition and offset as attributes
     */
    public static SignalProvenance kafka(String source, String topic, int partition, long offset) {
        return new SignalProvenance(
                "kafka",
                source,
                Map.of(
                        "topic", Objects.requireNonNull(topic, "topic"),
                        "partition", Integer.toString(partition),
                        "offset", Long.toString(offset)));
    }

    /**
     * Describes a message received by a Camel trigger.
     *
     * @param source the name of the source that received the message
     * @param triggerId the id of the trigger that received the message
     * @param messageHash a hash that identifies the message
     * @return the provenance, with transport {@code camel} and the trigger id and message hash as attributes
     */
    public static SignalProvenance camel(String source, String triggerId, String messageHash) {
        return new SignalProvenance(
                "camel",
                source,
                Map.of(
                        "triggerId", Objects.requireNonNull(triggerId, "triggerId"),
                        "messageHash", Objects.requireNonNull(messageHash, "messageHash")));
    }
}
