// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Typed Kafka message-key hash configuration read from {@code kafka.messageKeyHash}.
 *
 * <p>Carries the optional HMAC secret used to hash Kafka message keys for safe downstream capture
 * (external path {@code kafka.messageKeyHash.hmacSecret}). The secret is read <em>from</em> config,
 * so its component must stay constructor-bound — it is <strong>not</strong> {@code @JsonIgnore} (which
 * would block deserialization and make the secret unreadable). Instead it is annotated {@link
 * JsonProperty.Access#WRITE_ONLY} (deserialized in, never serialized out) and {@link #toString()} is
 * overridden to redact it. Thus the secret can be read by the runtime but never leaks through
 * Jackson re-serialization, a log line, or an exception message.
 *
 * @param hmacSecret the HMAC secret used to hash Kafka message keys, or {@code null} when not
 *     configured; never serialized out and redacted in {@link #toString()}
 */
public record KafkaMessageKeyHashConfig(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String hmacSecret) {
    /**
     * Redacted rendering: the HMAC secret is never included, so a log line or exception message can
     * never reveal it.
     *
     * @return a redacted string rendering of this config
     */
    @Override
    public String toString() {
        return "KafkaMessageKeyHashConfig[hmacSecret=" + (hmacSecret != null ? "<redacted>" : "null") + "]";
    }
}
