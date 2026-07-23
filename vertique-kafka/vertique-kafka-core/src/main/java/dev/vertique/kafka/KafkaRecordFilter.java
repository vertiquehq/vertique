// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pre-deserialization filter for Kafka records. Evaluated before the value is deserialized,
 * so only the key and headers are available.
 *
 * @see KafkaConsumerBinding.Builder#filter(KafkaRecordFilter)
 */
@FunctionalInterface
public interface KafkaRecordFilter {

    /**
     * Returns {@code true} if the record should be processed, {@code false} to skip it.
     *
     * @param key the record key, or {@code null}
     * @param headers the record headers
     * @return {@code true} to accept, {@code false} to filter out
     */
    boolean accept(String key, Map<String, String> headers);

    /**
     * Creates a filter that accepts records where the header value equals the expected value.
     *
     * @param name the header name
     * @param value the expected value
     * @return the filter
     */
    static KafkaRecordFilter headerEquals(String name, String value) {
        return (key, headers) -> value.equals(headers.get(name));
    }

    /**
     * Creates a filter that accepts records where the header exists.
     *
     * @param name the header name
     * @return the filter
     */
    static KafkaRecordFilter headerExists(String name) {
        return (key, headers) -> headers.containsKey(name);
    }

    /**
     * Creates a filter that accepts records where the header value matches a regex pattern.
     *
     * @param name the header name
     * @param pattern the regex pattern
     * @return the filter
     */
    static KafkaRecordFilter headerMatches(String name, Pattern pattern) {
        return (key, headers) -> {
            String val = headers.get(name);
            return val != null && pattern.matcher(val).matches();
        };
    }

    /**
     * Creates a filter that accepts records where the header value is one of the given values.
     *
     * @param name the header name
     * @param values the acceptable values
     * @return the filter
     */
    static KafkaRecordFilter headerIn(String name, String... values) {
        Set<String> valueSet = Set.copyOf(Arrays.asList(values));
        return (key, headers) -> valueSet.contains(headers.get(name));
    }

    /**
     * Creates a filter that accepts records only if all given filters accept.
     *
     * @param filters the filters to combine
     * @return the combined filter
     */
    static KafkaRecordFilter allOf(KafkaRecordFilter... filters) {
        return (key, headers) -> {
            for (KafkaRecordFilter f : filters) {
                if (!f.accept(key, headers)) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Creates a filter that accepts records if any of the given filters accept.
     *
     * @param filters the filters to combine
     * @return the combined filter
     */
    static KafkaRecordFilter anyOf(KafkaRecordFilter... filters) {
        return (key, headers) -> {
            for (KafkaRecordFilter f : filters) {
                if (f.accept(key, headers)) {
                    return true;
                }
            }
            return false;
        };
    }
}
