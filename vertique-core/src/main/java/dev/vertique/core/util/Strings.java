// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import jakarta.annotation.Nullable;

/**
 * Shared string-manipulation utilities used across multiple framework modules.
 *
 * <p>These are small, pure functions with no external dependencies. They live in
 * {@code vertique-core} so every consuming module (rest-jaxrs, kafka-core, etc.) can import a
 * single definition instead of maintaining private copies.
 *
 * <p>This class is a final utility class and cannot be instantiated.
 */
public final class Strings {

    private Strings() {}

    /**
     * Returns the first candidate that is non-{@code null} and non-blank, or {@code null} when all
     * candidates are {@code null} or blank.
     *
     * <p>Candidates are tested left-to-right; the first one that passes
     * {@code value != null && !value.isBlank()} is returned. This makes the varargs form a natural
     * n-ary extension of the pairwise {@code firstNonBlank(primary, fallback)} pattern:
     *
     * <pre>{@code
     * // before — nested pairs:
     * String a = firstNonBlank(firstNonBlank(method, producerConfig), annotation);
     *
     * // after — flat varargs:
     * String a = Strings.firstNonBlank(method, producerConfig, annotation);
     * }</pre>
     *
     * @param candidates zero or more candidate strings, tested in order; may individually be
     *     {@code null} or blank
     * @return the first non-{@code null}, non-blank candidate, or {@code null} if none is found
     */
    @Nullable
    public static String firstNonBlank(@Nullable String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
