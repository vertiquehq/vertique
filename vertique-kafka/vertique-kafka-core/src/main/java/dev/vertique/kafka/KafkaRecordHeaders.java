// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The headers of one Kafka record exactly as they are on the wire: an ordered list that keeps
 * repeated keys, {@code null} values and binary values.
 *
 * <p>Kafka allows the same header key more than once and gives the order meaning. Nothing is
 * normalized here: keys are compared exactly, with no trimming and no case folding, and no entry is
 * dropped or merged.
 *
 * <p>The collection is an immutable snapshot. {@link #equals(Object)} and {@link #hashCode()} compare
 * the entries by content and in order.
 *
 * <p>Use {@link #asMap()} only where an API takes a {@code Map<String, String>}; it is lossy.
 *
 * @param entries the headers in wire order; never {@code null}; unmodifiable
 */
public record KafkaRecordHeaders(List<KafkaRecordHeader> entries) implements Iterable<KafkaRecordHeader> {

    private static final KafkaRecordHeaders EMPTY = new KafkaRecordHeaders(List.of());

    /**
     * Copies the list.
     *
     * @param entries the headers in wire order; must not be {@code null} and must not contain
     *                {@code null}
     * @throws NullPointerException if {@code entries} or one of its elements is {@code null}
     */
    public KafkaRecordHeaders {
        entries = List.copyOf(entries);
    }

    /**
     * Returns the header collection of a record that has no headers.
     *
     * @return the shared empty instance; never {@code null}
     */
    public static KafkaRecordHeaders empty() {
        return EMPTY;
    }

    /**
     * Returns every header with the given key, in wire order.
     *
     * @param key the exact key to look for; must not be {@code null}
     * @return the matching headers, including those with a {@code null} value; unmodifiable; empty
     *     when the key is absent; never {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public List<KafkaRecordHeader> headers(String key) {
        Objects.requireNonNull(key, "key");
        return entries.stream().filter(entry -> entry.key().equals(key)).toList();
    }

    /**
     * Returns the last header with the given key, which is the one Kafka's own "last header" lookup
     * returns.
     *
     * <p>The result is empty only when the key is absent. A header that is present with a
     * {@code null} value is returned, and its {@link KafkaRecordHeader#value()} is {@code null}.
     *
     * @param key the exact key to look for; must not be {@code null}
     * @return the last matching header, or empty when no header has that key
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Optional<KafkaRecordHeader> lastHeader(String key) {
        Objects.requireNonNull(key, "key");
        for (int i = entries.size() - 1; i >= 0; i--) {
            KafkaRecordHeader entry = entries.get(i);
            if (entry.key().equals(key)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the headers as a text map. This is a <strong>lossy text projection</strong>, kept for
     * APIs that take a {@code Map<String, String>}. It has the content of the map the consumer gives
     * to the pre-deserialization filter, deserializers and handlers; they are given their own
     * mutable copy, so an edit a filter or deserializer makes to that copy is not reflected here.
     *
     * <p>What is lost:
     *
     * <ul>
     *   <li><strong>Duplicates collapse.</strong> A repeated key keeps one value: the last one that
     *       is not {@code null}.
     *   <li><strong>Null values vanish.</strong> A header with a {@code null} value is skipped. It
     *       does not appear, and it does not replace an earlier value of the same key.
     *   <li><strong>Binary values are decoded as text.</strong> Every value is decoded as UTF-8, and
     *       malformed input is replaced with the Unicode replacement character rather than
     *       rejected, so the original bytes cannot be recovered.
     *   <li><strong>Order is not kept.</strong>
     * </ul>
     *
     * <p>Read {@link #entries()}, {@link #headers(String)} or {@link #lastHeader(String)} when any
     * of that matters.
     *
     * @return an unmodifiable map; never {@code null}
     */
    public Map<String, String> asMap() {
        if (entries.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(toMutableTextMap());
    }

    /**
     * Builds the text projection described on {@link #asMap()} as a new mutable map. The consumer
     * hands this map to filters and deserializers, which have always been given a mutable one.
     *
     * @return a new {@link HashMap} that the caller owns; never {@code null}
     */
    Map<String, String> toMutableTextMap() {
        Map<String, String> map = new HashMap<>();
        for (KafkaRecordHeader entry : entries) {
            String text = entry.valueAsLenientUtf8();
            if (text != null) {
                map.put(entry.key(), text);
            }
        }
        return map;
    }

    /**
     * Iterates the headers in wire order.
     *
     * @return an iterator that does not support removal
     */
    @Override
    public Iterator<KafkaRecordHeader> iterator() {
        return entries.iterator();
    }
}
