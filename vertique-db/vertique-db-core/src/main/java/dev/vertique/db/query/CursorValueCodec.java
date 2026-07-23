// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import java.util.Objects;
import java.util.function.Function;

/**
 * Codec for serializing and deserializing a single keyset value type in {@link PageCursor} tokens.
 *
 * <p>Each codec is identified by a unique type prefix (e.g., {@code "uuid"}, {@code "bigdec"})
 * that is stored in the cursor token alongside the serialized value. Built-in codecs are
 * registered in {@link CursorCodecs#defaults()}. Custom codecs can be added via
 * {@link CursorCodecs#with(CursorValueCodec)} or registered globally via
 * {@link PageCursor#registerCodec(CursorValueCodec)}.
 *
 * <p>Example of creating a custom codec:
 * <pre>{@code
 * CursorValueCodec<MyId> codec = CursorValueCodec.of(
 *     "myid", MyId.class,
 *     v -> String.valueOf(v.value()),
 *     s -> new MyId(Long.parseLong(s))
 * );
 * PageCursor.registerCodec(codec);
 * }</pre>
 *
 * @param <T> the Java type this codec handles
 * @see CursorCodecs
 * @see PageCursor#registerCodec(CursorValueCodec)
 */
public interface CursorValueCodec<T> {

    /**
     * Returns the type prefix stored in cursor tokens (e.g., {@code "uuid"}).
     * Must not contain {@code ':'} as that character is used as the separator between
     * the prefix and the serialized value.
     *
     * @return the type prefix string
     */
    String typePrefix();

    /**
     * Returns the Java class this codec handles.
     *
     * @return the handled Java class
     */
    Class<T> type();

    /**
     * Serializes a non-null value to its string representation. The returned string must not
     * be null and will be stored after the type prefix in the cursor token.
     *
     * @param value the value to serialize (never null)
     * @return the serialized string representation
     */
    String serialize(T value);

    /**
     * Deserializes a string back to the Java type. The input is the raw value portion of the
     * cursor token (after removing the type prefix and colon separator).
     *
     * @param raw the raw serialized string
     * @return the deserialized value
     */
    T deserialize(String raw);

    /**
     * Creates a codec from a type prefix, class, and serializer/deserializer functions.
     *
     * @param <T>          the Java type the codec handles
     * @param prefix       the type prefix stored in cursor tokens; must not contain {@code ':'}
     * @param type         the Java class this codec handles
     * @param serializer   function that converts a value to its string representation
     * @param deserializer function that converts a string back to the value type
     * @return a new {@code CursorValueCodec} backed by the provided functions
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code prefix} contains {@code ':'}
     */
    static <T> CursorValueCodec<T> of(
            String prefix, Class<T> type, Function<T, String> serializer, Function<String, T> deserializer) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(serializer, "serializer");
        Objects.requireNonNull(deserializer, "deserializer");
        if (prefix.contains(":")) {
            throw new IllegalArgumentException("Type prefix must not contain ':'");
        }
        return new CursorValueCodec<>() {
            @Override
            public String typePrefix() {
                return prefix;
            }

            @Override
            public Class<T> type() {
                return type;
            }

            @Override
            public String serialize(T value) {
                return serializer.apply(value);
            }

            @Override
            public T deserialize(String raw) {
                return deserializer.apply(raw);
            }
        };
    }
}
