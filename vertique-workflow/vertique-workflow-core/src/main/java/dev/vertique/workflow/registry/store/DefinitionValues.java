// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

/** Shared validation of the text and format values carried by definition keys, provenance and documents. */
final class DefinitionValues {
    private DefinitionValues() {}

    /**
     * Validates one text value. A value is storable when it holds no NUL character and no unpaired surrogate
     * (a proper surrogate pair, that is a supplementary character, is valid), and its length, counted in code
     * points, is within the maximum. A required value must also be nonblank. An absent optional value is accepted.
     *
     * @param value the value to check; may be {@code null} only when not required
     * @param name the field name used in the failure message
     * @param maximum the largest accepted length in code points
     * @param required whether the value must be present and nonblank
     * @throws IllegalArgumentException if the value is blank when required, too long, or not storable
     * @throws NullPointerException if the value is {@code null} and required
     */
    static void text(String value, String name, int maximum, boolean required) {
        if (value == null && !required) return;
        Objects.requireNonNull(value, name);
        if ((required && value.isBlank()) || value.codePointCount(0, value.length()) > maximum)
            throw new IllegalArgumentException(name + " must be nonblank and at most " + maximum + " code points");
        if (!storable(value))
            throw new IllegalArgumentException(name + " must not contain NUL or unpaired surrogate characters");
    }

    /**
     * Validates an identifying text value: everything {@link #text} requires, and additionally no ISO control
     * character (U+0000 to U+001F and U+007F to U+009F).
     *
     * @param value the value to check; must not be {@code null}
     * @param name the field name used in the failure message
     * @param maximum the largest accepted length in code points
     * @throws IllegalArgumentException if the value is blank, too long, not storable, or holds a control character
     * @throws NullPointerException if the value is {@code null}
     */
    static void identifier(String value, String name, int maximum) {
        text(value, name, maximum, true);
        if (value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " must not contain control characters");
    }

    /** Whether the value has no NUL and every surrogate is half of a high-then-low pair. */
    private static boolean storable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000') return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    static void format(String value) {
        if (!"YAML".equals(value) && !"JSON".equals(value))
            throw new IllegalArgumentException("documentFormat must be YAML or JSON");
    }
}
