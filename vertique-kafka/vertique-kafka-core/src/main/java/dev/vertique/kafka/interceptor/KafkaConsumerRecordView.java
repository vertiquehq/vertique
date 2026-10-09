// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.interceptor;

import dev.vertique.core.payload.PayloadSource;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Framework-owned view of one Kafka consumer record as the consumer received it, passed to
 * {@link KafkaConsumerInterceptor#onRecordCompleted}.
 *
 * <p>The consumer builds the view before the pre-deserialization filter, route resolution,
 * deserialization and any interceptor run, and holds it for the record's whole lifetime. No
 * interceptor can replace it, and {@link #identity()}, {@link #key()} and {@link #headers()} cannot
 * be changed by a filter, a deserializer or an interceptor.
 *
 * <p>{@link #value()} is different: it is <strong>not a snapshot</strong>. See its documentation
 * before retaining or recording it.
 *
 * <p>The framework provides the only implementation. Applications read this interface; they do not
 * implement it outside tests.
 */
public interface KafkaConsumerRecordView {

    /**
     * Returns the record's framework-owned identity.
     *
     * @return the identity; the same instance as {@link KafkaConsumerCompletedEvent#identity()};
     *     never {@code null}
     */
    KafkaConsumerRecordIdentity identity();

    /**
     * Returns the record key as the broker delivered it.
     *
     * @return the key, or {@code null} when the record has none
     */
    @Nullable
    String key();

    /**
     * Returns the record's headers as text, copied when the consumer received the record.
     *
     * <p>The map is an unmodifiable copy. The pre-deserialization filter and deserializers receive
     * a different, mutable map, so a change they make is not visible here.
     *
     * @return the headers; unmodifiable; never {@code null}
     */
    Map<String, String> headers();

    /**
     * Returns the record value: the array the broker delivered, <strong>uncopied</strong>.
     *
     * <p>This is not a snapshot. The deserializer, router property matching and, for a
     * {@code byte[]} consumer, the handler were given the same array. An in-place edit made by any
     * of them is visible through this source. Copy the bytes before retaining them beyond the
     * callback, and do not treat them as proof of the wire content when application code may have
     * edited the array.
     *
     * <p>The value is available for every record, including filtered, unroutable and
     * undeserializable ones.
     *
     * @return a buffered source over the delivered array, or
     *     {@link dev.vertique.core.payload.PayloadSources#absent()} for a tombstone (a record with
     *     a {@code null} value); never {@code null}
     */
    PayloadSource value();
}
