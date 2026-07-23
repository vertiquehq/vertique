// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.List;
import java.util.Optional;

/**
 * The result of a context decode operation, carrying either a successfully decoded value or a list
 * of warnings explaining why decoding failed or was incomplete.
 *
 * <p>Decoders MUST return a non-null {@code ContextDecodeResult}. A null return from a decoder is
 * treated as a decode failure.
 *
 * <p>Use the static factory methods to construct results:
 * <ul>
 *   <li>{@link #of(Object)} — successful decode with a value.
 *   <li>{@link #empty()} — no value, no warnings (e.g., metadata keys absent).
 *   <li>{@link #failure(List)} — decode failed; includes diagnostic warnings.
 * </ul>
 *
 * @param <T>      the decoded value type
 * @param value    the decoded value, or empty if decoding produced no result
 * @param warnings warnings accumulated during decoding; never {@code null}
 */
public record ContextDecodeResult<T>(Optional<T> value, List<ContextDecodeWarning> warnings) {

    /**
     * Compact constructor that defensively copies the warnings list.
     *
     * @param value    the decoded value
     * @param warnings the warnings; copied to an immutable list
     */
    public ContextDecodeResult {
        warnings = List.copyOf(warnings);
    }

    // --- Static Factories ---

    /**
     * Returns an empty result with no value and no warnings. Suitable when the relevant metadata
     * keys are absent.
     *
     * @param <T> the decoded value type
     * @return an empty result
     */
    public static <T> ContextDecodeResult<T> empty() {
        return new ContextDecodeResult<>(Optional.empty(), List.of());
    }

    /**
     * Returns a successful result carrying the decoded value.
     *
     * @param value the decoded value; must not be {@code null}
     * @param <T>   the decoded value type
     * @return a successful result
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static <T> ContextDecodeResult<T> of(T value) {
        return new ContextDecodeResult<>(Optional.of(value), List.of());
    }

    /**
     * Returns a failure result with no value and one or more diagnostic warnings.
     *
     * @param warnings the warnings explaining why decoding failed; must not be {@code null}
     * @param <T>      the decoded value type
     * @return a failure result
     */
    public static <T> ContextDecodeResult<T> failure(List<ContextDecodeWarning> warnings) {
        return new ContextDecodeResult<>(Optional.empty(), warnings);
    }
}
