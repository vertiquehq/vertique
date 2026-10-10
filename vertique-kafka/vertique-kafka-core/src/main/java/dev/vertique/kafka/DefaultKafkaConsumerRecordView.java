// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordIdentity;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * The consumer's {@link KafkaConsumerRecordView}: built once per record before the filter, route
 * resolution, deserialization and the interceptor chain, and held for the record's lifetime.
 *
 * <p>The headers are the immutable collection the consumer extracted from the record; the filter and
 * deserializers receive a separate mutable text map derived from it, so the collection is held as
 * is. The value is not copied: the source aliases the array the broker delivered.
 */
final class DefaultKafkaConsumerRecordView implements KafkaConsumerRecordView {

    private final KafkaConsumerRecordIdentity identity;

    @Nullable
    private final String key;

    private final KafkaRecordHeaders headers;
    private final PayloadSource value;

    /**
     * Creates the view of one received record.
     *
     * @param identity the record's framework-owned identity
     * @param key      the record key, or {@code null}
     * @param headers  the headers extracted from the record; immutable, so not copied
     * @param rawBytes the delivered value array, or {@code null} for a tombstone; not copied
     */
    DefaultKafkaConsumerRecordView(
            KafkaConsumerRecordIdentity identity,
            @Nullable String key,
            KafkaRecordHeaders headers,
            @Nullable byte[] rawBytes) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.key = key;
        this.headers = Objects.requireNonNull(headers, "headers");
        this.value = rawBytes == null ? PayloadSources.absent() : PayloadSources.buffered(rawBytes, null);
    }

    @Override
    public KafkaConsumerRecordIdentity identity() {
        return identity;
    }

    @Override
    @Nullable
    public String key() {
        return key;
    }

    @Override
    public KafkaRecordHeaders headers() {
        return headers;
    }

    @Override
    public PayloadSource value() {
        return value;
    }
}
