// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides the {@code json-pointer}, {@code relative-json-pointer}, and {@code json-pointer-uri-fragment}
 * formats {@link PatternInputGuard} routes here: one left-to-right pass over the RFC 6901 grammar per
 * value, with no compiled pointer representation and no regular expression.
 */
final class PointerFormatChecks {

    private PointerFormatChecks() {}

    /**
     * Decides the {@code json-pointer} format in one left-to-right pass: {@code value} is empty or starts
     * with {@code /}, and every {@code ~} in it is immediately followed by {@code 0} or {@code 1}.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code json-pointer}
     */
    static boolean isPointer(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '~'
                    && (i + 1 >= value.length() || (value.charAt(i + 1) != '0' && value.charAt(i + 1) != '1'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides the {@code relative-json-pointer} format: a prefix of {@code 0} or a digit string with no
     * leading zero (one left-to-right pass over ASCII digits), followed by either {@code #} alone or
     * {@link #isPointer(String)} on the remainder.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code relative-json-pointer}
     */
    static boolean isRelativePointer(String value) {
        int i = 0;
        int length = value.length();
        if (i >= length || !isAsciiDigit(value.charAt(i))) {
            return false;
        }
        if (value.charAt(i) == '0') {
            i++;
        } else {
            while (i < length && isAsciiDigit(value.charAt(i))) {
                i++;
            }
        }
        String remainder = value.substring(i);
        return remainder.equals("#") || isPointer(remainder);
    }

    /**
     * Decides the {@code json-pointer-uri-fragment} format: a leading {@code #}, then the fragment
     * {@link URI} parses and percent-decodes, then {@link #isPointer(String)} on that fragment.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code json-pointer-uri-fragment}
     */
    static boolean isPointerFragment(String value) {
        if (value.isEmpty() || value.charAt(0) != '#') {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException notAFragment) {
            return false;
        }
        String fragment = uri.getFragment();
        return fragment != null && isPointer(fragment);
    }

    /**
     * Reports whether {@code c} is an ASCII digit, {@code 0} through {@code 9}.
     *
     * @param c the character
     * @return {@code true} when {@code c} is an ASCII digit
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
