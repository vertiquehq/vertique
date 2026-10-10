// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pre-deserialization filter for Kafka records. Evaluated before the value is deserialized,
 * so only the key and headers are available.
 *
 * <p>A filter is given the record's headers as they are on the wire: every header in order, with
 * repeated keys, {@code null} values and binary values. The collection is immutable and is the one
 * the deserializer, the interceptors and the handler receive for the same record, so a filter
 * cannot change what they read.
 *
 * <p>The static factories compare <em>text</em>. The value they read for a header name is the last
 * value of that key that is not {@code null}, decoded as UTF-8 with malformed input replaced by the
 * Unicode replacement character. A key that is absent, or that only has {@code null} values, has no
 * text value. Write a lambda over {@link KafkaRecordHeaders} when you need every value of a
 * repeated key, the raw bytes or a {@code null} value:
 *
 * <pre>{@code
 * KafkaRecordFilter signed = (key, headers) -> !headers.headers("signature").isEmpty();
 * }</pre>
 *
 * @see KafkaConsumerBinding.Builder#filter(KafkaRecordFilter)
 */
@FunctionalInterface
public interface KafkaRecordFilter {

    /**
     * Returns {@code true} if the record should be processed, {@code false} to skip it.
     *
     * @param key the record key, or {@code null}
     * @param headers the record headers as received, in wire order; immutable; never {@code null};
     *     {@link KafkaRecordHeaders#empty()} for a record without headers
     * @return {@code true} to accept, {@code false} to filter out
     */
    boolean accept(String key, KafkaRecordHeaders headers);

    /**
     * Creates a filter that accepts records where the text value of the header equals the expected
     * value. The text value is the last value of the key that is not {@code null}, decoded as
     * lenient UTF-8.
     *
     * @param name the header name
     * @param value the expected value
     * @return the filter; it rejects a record where the header is absent or only has {@code null}
     *     values
     */
    static KafkaRecordFilter headerEquals(String name, String value) {
        return (key, headers) -> value.equals(textValue(headers, name));
    }

    /**
     * Creates a filter that accepts records where the header exists with a value. A header with an
     * empty value exists; a header that is absent, or whose every occurrence has a {@code null}
     * value, does not.
     *
     * @param name the header name
     * @return the filter
     */
    static KafkaRecordFilter headerExists(String name) {
        return (key, headers) -> textValue(headers, name) != null;
    }

    /**
     * Creates a filter that accepts records where the text value of the header matches a regex
     * pattern in full. The text value is the last value of the key that is not {@code null}, decoded
     * as lenient UTF-8.
     *
     * @param name the header name
     * @param pattern the regex pattern
     * @return the filter; it rejects a record where the header is absent or only has {@code null}
     *     values
     */
    static KafkaRecordFilter headerMatches(String name, Pattern pattern) {
        return (key, headers) -> {
            String val = textValue(headers, name);
            return val != null && pattern.matcher(val).matches();
        };
    }

    /**
     * Creates a filter that accepts records where the text value of the header is one of the given
     * values. The text value is the last value of the key that is not {@code null}, decoded as
     * lenient UTF-8.
     *
     * @param name the header name
     * @param values the acceptable values
     * @return the filter; it throws {@link NullPointerException} for a record where the header is
     *     absent or only has {@code null} values, so combine it with {@link #headerExists(String)}
     *     when the header is optional
     */
    static KafkaRecordFilter headerIn(String name, String... values) {
        Set<String> valueSet = Set.copyOf(Arrays.asList(values));
        return (key, headers) -> valueSet.contains(textValue(headers, name));
    }

    /**
     * Creates a filter that accepts records only if all given filters accept. Every filter is given
     * the same headers.
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
     * Creates a filter that accepts records if any of the given filters accept. Every filter is
     * given the same headers.
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

    /**
     * Reads the text value the factories compare: the last value of the key that is not
     * {@code null}, decoded as UTF-8 with malformed input replaced.
     *
     * @param headers the record headers
     * @param name the header name
     * @return the text value, or {@code null} when the key is absent or only has {@code null} values
     */
    @Nullable
    private static String textValue(KafkaRecordHeaders headers, String name) {
        List<KafkaRecordHeader> entries = headers.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            KafkaRecordHeader entry = entries.get(i);
            if (entry.key().equals(name)) {
                String text = entry.valueAsLenientUtf8();
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }
}
