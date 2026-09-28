// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

/**
 * Decides the {@code uri-template} format {@link PatternInputGuard} routes here: the framework's own
 * single-forward-pass RFC 6570 §2 template-syntax scanner, an exception among these format checks to
 * using no new parser. It is inspired by, not copied from, {@code handy-uri-templates}; no code was
 * taken from that or any other library.
 *
 * <p>The scanner reads RFC 6570 §2 on Unicode code points, combining a surrogate pair and rejecting a
 * lone surrogate, in one left-to-right pass that holds only a constant amount of state — the current
 * index — with no recursion and no quantified group:
 *
 * <ul>
 *   <li>A literal character (§2.1) is a {@code pct-encoded} triplet checked in place, a combined
 *       supplementary code point in the {@code ucschar} or {@code iprivate} ranges, or any other code
 *       point outside the control characters, the space, DEL, and {@code " % < > \ ^ ` { | }}.
 *   <li>An expression (§2.2–§2.4) is {@code {}, an optional operator drawn from op-level2 ({@code + #}),
 *       op-level3 ({@code . / ; ? &}), or op-reserve ({@code = , ! @ |}), a comma-separated list of one
 *       or more varspecs, then {@code }}.
 *   <li>A varspec is a varname, {@code varchar *(["."] varchar)} with varchar {@code ALPHA / DIGIT / "_"
 *       / pct-encoded}, followed by at most one modifier: a {@code :} prefix length of 1 to 9999 (its
 *       first digit 1 to 9, at most four digits) or an explode {@code *}.
 * </ul>
 *
 * <p>An empty expression ({@code {}}), a nested {@code {}, and a stray {@code }} are rejected.
 */
final class UriTemplateSyntax {

    /**
     * The literal characters RFC 6570 §2.1 disallows outside {@code pct-encoded} and the ucschar
     * ranges. The grammar also excludes {@code '} ({@code %x27}); this scanner deliberately accepts
     * it as a literal character.
     */
    private static final String DISALLOWED_LITERAL_CHARS = "\"%<>\\^`{|}";

    /** The operator characters: op-level2, op-level3, and op-reserve (RFC 6570 §2.2). */
    private static final String OPERATOR_CHARS = "+#./;?&=,!@|";

    /** The highest number of digits a prefix modifier's length may carry (1 to 9999). */
    private static final int MAX_PREFIX_DIGITS = 4;

    private UriTemplateSyntax() {}

    /**
     * Decides the {@code uri-template} format: {@code value} is a sequence of literal characters and
     * expressions per RFC 6570 §2, scanned left to right with no recursion.
     *
     * @param value the instance
     * @return {@code true} when {@code value} is a valid {@code uri-template}
     */
    static boolean isValid(String value) {
        int index = 0;
        int length = value.length();
        while (index < length) {
            char c = value.charAt(index);
            if (c == '{') {
                int afterExpression = scanExpression(value, index);
                if (afterExpression < 0) {
                    return false;
                }
                index = afterExpression;
            } else if (c == '}') {
                return false;
            } else {
                int afterLiteral = scanLiteralChar(value, index);
                if (afterLiteral < 0) {
                    return false;
                }
                index = afterLiteral;
            }
        }
        return true;
    }

    /**
     * Scans one literal character (§2.1) at {@code index}: a {@code pct-encoded} triplet, a combined
     * surrogate pair in the ucschar or iprivate ranges, or any other allowed code point.
     *
     * @param value the instance
     * @param index the index of the character to scan
     * @return the index immediately after the literal character, or {@code -1} when it is not one
     */
    private static int scanLiteralChar(String value, int index) {
        char c = value.charAt(index);
        if (c == '%') {
            return isPctEncodedAt(value, index) ? index + 3 : -1;
        }
        if (Character.isHighSurrogate(c)) {
            if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                int codePoint = Character.toCodePoint(c, value.charAt(index + 1));
                return isUcscharOrIprivate(codePoint) ? index + 2 : -1;
            }
            return -1;
        }
        if (Character.isLowSurrogate(c)) {
            return -1;
        }
        return isDisallowedLiteralChar(c) ? -1 : index + 1;
    }

    /**
     * Reports whether {@code value} carries a {@code pct-encoded} triplet, {@code %} followed by two hex
     * digits, starting at {@code index}.
     *
     * @param value the instance
     * @param index the index of the {@code %}
     * @return {@code true} when a complete triplet starts at {@code index}
     */
    private static boolean isPctEncodedAt(String value, int index) {
        return index + 2 < value.length() && isHexDigit(value.charAt(index + 1)) && isHexDigit(value.charAt(index + 2));
    }

    /**
     * Reports whether {@code c} is one of the literal characters RFC 6570 §2.1 disallows outside a
     * {@code pct-encoded} triplet: a control character, the space, DEL, or one of
     * {@value #DISALLOWED_LITERAL_CHARS}; a code point above ASCII is disallowed unless it is a
     * (single-unit) ucschar or iprivate code point.
     *
     * @param c the character
     * @return {@code true} when {@code c} may not appear as a literal character
     */
    private static boolean isDisallowedLiteralChar(char c) {
        if (c <= 0x20 || c == 0x7f) {
            return true;
        }
        if (c > 0x7e) {
            return !isUcscharOrIprivate(c);
        }
        return DISALLOWED_LITERAL_CHARS.indexOf(c) >= 0;
    }

    /**
     * Reports whether {@code codePoint} is in RFC 3987's {@code ucschar} ranges or RFC 6570's
     * {@code iprivate} ranges, the non-ASCII code points a literal character may hold.
     *
     * @param codePoint the code point, a single UTF-16 unit or a combined surrogate pair
     * @return {@code true} when {@code codePoint} is a ucschar or an iprivate code point
     */
    private static boolean isUcscharOrIprivate(int codePoint) {
        return (codePoint >= 0xA0 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF900 && codePoint <= 0xFDCF)
                || (codePoint >= 0xFDF0 && codePoint <= 0xFFEF)
                || (codePoint >= 0x10000 && codePoint <= 0x1FFFD)
                || (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
                || (codePoint >= 0x30000 && codePoint <= 0x3FFFD)
                || (codePoint >= 0x40000 && codePoint <= 0x4FFFD)
                || (codePoint >= 0x50000 && codePoint <= 0x5FFFD)
                || (codePoint >= 0x60000 && codePoint <= 0x6FFFD)
                || (codePoint >= 0x70000 && codePoint <= 0x7FFFD)
                || (codePoint >= 0x80000 && codePoint <= 0x8FFFD)
                || (codePoint >= 0x90000 && codePoint <= 0x9FFFD)
                || (codePoint >= 0xA0000 && codePoint <= 0xAFFFD)
                || (codePoint >= 0xB0000 && codePoint <= 0xBFFFD)
                || (codePoint >= 0xC0000 && codePoint <= 0xCFFFD)
                || (codePoint >= 0xD0000 && codePoint <= 0xDFFFD)
                || (codePoint >= 0xE1000 && codePoint <= 0xEFFFD)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    /**
     * Scans one expression (§2.2–§2.4) starting at {@code index}: {@code {}, an optional operator, a
     * comma-separated variable list of one or more varspecs, and {@code }}.
     *
     * @param value the instance
     * @param index the index of the {@code {}
     * @return the index immediately after the matching {@code }}, or {@code -1} when {@code value} does
     *     not hold a well-formed expression starting at {@code index}
     */
    private static int scanExpression(String value, int index) {
        int i = index + 1;
        int length = value.length();
        if (i < length && isOperator(value.charAt(i))) {
            i++;
        }
        boolean sawVarspec = false;
        while (true) {
            int afterVarspec = scanVarspec(value, i);
            if (afterVarspec < 0) {
                return -1;
            }
            sawVarspec = true;
            i = afterVarspec;
            if (i < length && value.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
        return sawVarspec && i < length && value.charAt(i) == '}' ? i + 1 : -1;
    }

    /**
     * Reports whether {@code c} is an operator character: op-level2 ({@code + #}), op-level3
     * ({@code . / ; ? &}), or op-reserve ({@code = , ! @ |}).
     *
     * @param c the character
     * @return {@code true} when {@code c} is an operator character
     */
    private static boolean isOperator(char c) {
        return OPERATOR_CHARS.indexOf(c) >= 0;
    }

    /**
     * Scans one varspec starting at {@code index}: a varname, {@code varchar *(["."] varchar)} with
     * varchar {@code ALPHA / DIGIT / "_" / pct-encoded}, followed by at most one of a {@code :} prefix
     * length of 1 to 9999 or an explode {@code *}.
     *
     * @param value the instance
     * @param index the varspec's starting index
     * @return the index immediately after the varspec, or {@code -1} when {@code value} does not hold a
     *     well-formed varspec starting at {@code index}
     */
    private static int scanVarspec(String value, int index) {
        int i = index;
        int length = value.length();
        int varcharCount = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '%') {
                if (!isPctEncodedAt(value, i)) {
                    return -1;
                }
                i += 3;
                varcharCount++;
                continue;
            }
            if (isVarchar(c)) {
                i++;
                varcharCount++;
                continue;
            }
            if (c == '.'
                    && varcharCount > 0
                    && i + 1 < length
                    && (isVarchar(value.charAt(i + 1)) || value.charAt(i + 1) == '%')) {
                i++;
                continue;
            }
            break;
        }
        if (varcharCount == 0) {
            return -1;
        }
        if (i < length && value.charAt(i) == ':') {
            return scanPrefixLength(value, i + 1);
        }
        if (i < length && value.charAt(i) == '*') {
            return i + 1;
        }
        return i;
    }

    /**
     * Scans a prefix modifier's length digits starting at {@code index}, right after its {@code :}: 1 to
     * 4 ASCII digits whose first digit is not {@code 0}.
     *
     * @param value the instance
     * @param index the index right after the {@code :}
     * @return the index immediately after the digits, or {@code -1} when none, too many, or leading-zero
     */
    private static int scanPrefixLength(String value, int index) {
        int i = index;
        int length = value.length();
        int digits = 0;
        while (i < length && isAsciiDigit(value.charAt(i)) && digits < MAX_PREFIX_DIGITS) {
            i++;
            digits++;
        }
        return digits > 0 && value.charAt(index) != '0' ? i : -1;
    }

    /**
     * Reports whether {@code c} is a varchar: an ASCII letter, an ASCII digit, or {@code _}.
     *
     * @param c the character
     * @return {@code true} when {@code c} is a varchar
     */
    private static boolean isVarchar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
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

    /**
     * Reports whether {@code c} is an ASCII hex digit, {@code 0}-{@code 9}, {@code a}-{@code f}, or
     * {@code A}-{@code F}.
     *
     * @param c the character
     * @return {@code true} when {@code c} is an ASCII hex digit
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
