// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

/**
 * The single compile-time authority for the application path grammar: the normalization of a
 * {@code @RestApplication.path} value as written and the rule that rejects a malformed result.
 *
 * <ol>
 *   <li><strong>Step 1 — normalize the start.</strong> An empty value becomes {@code "/"}; a
 *       value without a leading {@code /} gets one.</li>
 *   <li><strong>Step 2 — normalize the end.</strong> One terminal {@code /*} is removed, then
 *       every trailing {@code /}; an empty result becomes {@code "/"}.</li>
 *   <li><strong>Step 3 — reject a malformed result.</strong> {@link #violatedRule} checks the
 *       normalized path against a fixed, ordered list of rules and returns the first one it
 *       violates: {@code wildcard}, {@code router pattern}, {@code query}, {@code fragment},
 *       {@code repeated separator}, {@code dot segment}, {@code encoded separator}, and
 *       {@code unsupported character}. Returns {@code null} when the normalized path violates
 *       none of them, meaning it is made only of {@code /} and the RFC 3986 unreserved characters
 *       ({@code A-Z a-z 0-9 . _ ~ -}).</li>
 *   <li><strong>Step 4 — report the rejection.</strong> The caller
 *       ({@link RestApplicationScanner}) reports one compile error naming the declaration, the
 *       value as written, and the violated rule.</li>
 * </ol>
 *
 * <p>{@link #normalize(String)} applies steps 1 and 2, and {@link #violatedRule(String)} is step 3.
 * Step 5 (mount-path derivation) belongs to the runtime composer, not the processor.
 */
public final class ApplicationPathGrammar {

    private ApplicationPathGrammar() {}

    /**
     * Normalizes an application path value as written (steps 1 and 2), such as
     * {@code @RestApplication.path}: an empty value becomes {@code "/"}, a missing leading {@code /}
     * is added, one terminal {@code /*} is removed, then every trailing {@code /}. The result is
     * checked by {@link #violatedRule(String)}.
     *
     * @param value the path value as written; must not be {@code null}
     * @return the normalized path, starting with {@code /} and never ending with a trailing
     *     {@code /} other than the root path itself
     */
    public static String normalize(String value) {
        return normalizeEnd(normalizeStart(value));
    }

    /**
     * Step 3: checks the normalized path against a fixed, ordered list of rules and returns the
     * first one it violates, in this order: {@code wildcard} (contains an asterisk); {@code
     * router pattern} (contains a colon or a curly brace); {@code query} (contains a question
     * mark); {@code fragment} (contains a hash sign); {@code repeated separator} (contains
     * {@code //}); {@code dot segment} (a {@code /}-separated segment equal to {@code .} or
     * {@code ..}); {@code encoded separator} (contains {@code %2F}, {@code %2f}, {@code %5C}, or
     * {@code %5c}); {@code unsupported character} (any character other than {@code /} outside
     * {@code A-Z a-z 0-9 . _ ~ -}, including any other percent sign).
     *
     * @param normalizedPath the result of {@link #normalize(String)}; must not be {@code null}
     * @return the first violated rule's name; or {@code null} when {@code normalizedPath}
     *     violates none of them
     */
    public static String violatedRule(String normalizedPath) {
        if (normalizedPath.indexOf('*') >= 0) {
            return "wildcard";
        }
        if (hasAny(normalizedPath, ':', '{', '}')) {
            return "router pattern";
        }
        if (normalizedPath.indexOf('?') >= 0) {
            return "query";
        }
        if (normalizedPath.indexOf('#') >= 0) {
            return "fragment";
        }
        if (normalizedPath.contains("//")) {
            return "repeated separator";
        }
        if (hasDotSegment(normalizedPath)) {
            return "dot segment";
        }
        if (hasEncodedSeparator(normalizedPath)) {
            return "encoded separator";
        }
        if (hasUnsupportedCharacter(normalizedPath)) {
            return "unsupported character";
        }
        return null;
    }

    // --- Internal helpers ---

    private static boolean hasAny(String v, char... chars) {
        for (char c : chars) {
            if (v.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** A {@code /}-separated segment equal to {@code .} or {@code ..}. */
    private static boolean hasDotSegment(String v) {
        for (String segment : v.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEncodedSeparator(String v) {
        return v.contains("%2F") || v.contains("%2f") || v.contains("%5C") || v.contains("%5c");
    }

    /** Any character other than {@code /} outside {@code A-Z a-z 0-9 . _ ~ -}. */
    private static boolean hasUnsupportedCharacter(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '/') {
                continue;
            }
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || c == '-';
            if (!unreserved) {
                return true;
            }
        }
        return false;
    }

    /** Step 1: an empty value becomes {@code "/"}; a value without a leading {@code /} gets one. */
    private static String normalizeStart(String v) {
        String s = v.isEmpty() ? "/" : v;
        return s.startsWith("/") ? s : "/" + s;
    }

    /**
     * Step 2: one terminal {@code /*} is removed, then every trailing {@code /}; an empty result
     * becomes {@code "/"}.
     */
    private static String normalizeEnd(String v) {
        String s = v;
        if (s.endsWith("/*")) {
            s = s.substring(0, s.length() - 2);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "/" : s;
    }
}
