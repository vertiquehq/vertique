// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;

/**
 * The result of a {@link CharacterPolicy#validate} invocation.
 *
 * <p>A valid result has {@link #valid()} set to {@code true} and all other fields are {@code null}.
 * An invalid result has {@link #valid()} set to {@code false} and carries the position and code
 * point of the first offending character along with a human-readable reason.
 *
 * <p>Use the static factory methods rather than the canonical constructor:
 * <ul>
 *   <li>{@link #passed()} — for passing validation</li>
 *   <li>{@link #failed(int, int, String)} — for a detected violation</li>
 * </ul>
 *
 * @param valid            {@code true} if the value passed character policy validation
 * @param invalidIndex     zero-based index of the first offending character; {@code null} when valid
 * @param invalidCodePoint Unicode code point of the offending character; {@code null} when valid
 * @param reason           human-readable description of why the character is disallowed; {@code null} when valid
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterPolicyResult(
        boolean valid,
        @Nullable Integer invalidIndex,
        @Nullable Integer invalidCodePoint,
        @Nullable String reason) {

    // --- Factory Methods ---

    /**
     * Returns a result indicating that the value passed character policy validation.
     *
     * @return a {@code CharacterPolicyResult} with {@code valid=true} and all detail fields {@code null}
     */
    public static CharacterPolicyResult passed() {
        return new CharacterPolicyResult(true, null, null, null);
    }

    /**
     * Returns a result indicating that the value failed character policy validation.
     *
     * @param index      the zero-based index of the first offending character in the string
     * @param codePoint  the Unicode code point of the offending character
     * @param reason     a human-readable description of why the character is disallowed
     * @return a {@code CharacterPolicyResult} with {@code valid=false} carrying the violation details
     */
    public static CharacterPolicyResult failed(int index, int codePoint, String reason) {
        return new CharacterPolicyResult(false, index, codePoint, reason);
    }
}
