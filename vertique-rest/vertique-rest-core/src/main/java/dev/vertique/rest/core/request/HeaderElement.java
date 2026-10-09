// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One typed element of a comma-separated HTTP header value of the shape
 * {@code value *( ";" parameter )} with an optional {@code q} weight, such as an
 * {@code Accept}, {@code Accept-Language} or {@code Accept-Encoding} entry.
 *
 * <p>{@code value} is the token before the first {@code ;}, trimmed. {@code parameters} holds
 * the remaining {@code name=value} parameters in header order, with lowercase names and
 * quoted-string values already unquoted and unescaped; the {@code q} parameter is excluded
 * and surfaces as {@code quality} instead (default {@code 1.0}).
 *
 * <p>Instances are created by {@link #parseList(String)} and {@link #parse(String)}, which
 * follow RFC 9110: elements are split on {@code ,} and parameters on {@code ;} only outside
 * double-quoted strings, and inside a quoted string a backslash escapes the next character.
 * Input that cannot be read unambiguously is malformed and yields no element, so that a
 * malformed entry can never make anything acceptable:
 * <ul>
 *   <li>an element containing an unterminated quoted string,</li>
 *   <li>a quoted-string parameter value followed by further characters,</li>
 *   <li>a {@code q} that is not a valid qvalue ({@code 0}, {@code 1}, {@code 0.xxx} or
 *       {@code 1.000}, at most three decimals) — including a quoted {@code q}, a negative
 *       value and a value above {@code 1}, none of which is clamped,</li>
 *   <li>an element with an empty value.</li>
 * </ul>
 * A parameter without {@code =}, or with an empty name, is ignored. When a parameter name
 * repeats, the last occurrence wins.
 *
 * @param value      the element token before the first {@code ;}, trimmed; never {@code null} or empty
 * @param parameters the parameters excluding {@code q}, lowercase names, in header order; immutable
 * @param quality    the {@code q} weight in the range [0.0, 1.0]
 */
public record HeaderElement(String value, Map<String, String> parameters, double quality) {

    /**
     * The maximum number of non-empty elements {@link #parseList(String)} considers; later
     * elements are ignored. This bounds the work an excessively long header can cause.
     */
    public static final int MAX_ELEMENTS = 50;

    /**
     * Creates an element.
     *
     * @param value      the element token; must not be {@code null}
     * @param parameters the parameters, excluding {@code q}; names are lowercased; must not be {@code null}
     * @param quality    the weight in the range [0.0, 1.0]
     * @throws NullPointerException     if {@code value} or {@code parameters} is {@code null}
     * @throws IllegalArgumentException if {@code quality} is outside [0.0, 1.0] or {@code parameters}
     *                                  contains {@code q}
     */
    public HeaderElement {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (!(quality >= 0.0 && quality <= 1.0)) {
            throw new IllegalArgumentException("quality must be within [0.0, 1.0]: " + quality);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        parameters.forEach((name, paramValue) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if ("q".equals(key)) {
                throw new IllegalArgumentException("parameters must not contain q; use quality");
            }
            copy.put(key, paramValue);
        });
        parameters = Collections.unmodifiableMap(copy);
    }

    // --- Parsing ---

    /**
     * Parses a comma-separated header value into its elements, in header order.
     *
     * <p>Empty elements (such as {@code a,,b} or a trailing comma) are skipped, as are malformed
     * ones. At most the first {@value #MAX_ELEMENTS} non-empty elements are considered, counting
     * malformed ones. This method never throws.
     *
     * @param headerValue the raw header value; may be {@code null}
     * @return an unmodifiable list of the well-formed elements; empty for {@code null} or blank input
     */
    public static List<HeaderElement> parseList(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        split(headerValue, ',', texts);
        List<HeaderElement> elements = new ArrayList<>();
        int considered = 0;
        for (String text : texts) {
            if (text.isBlank()) {
                continue;
            }
            if (considered == MAX_ELEMENTS) {
                break;
            }
            considered++;
            HeaderElement element = parse(text);
            if (element != null) {
                elements.add(element);
            }
        }
        return List.copyOf(elements);
    }

    /**
     * Parses a single element, treating the whole input as one element: a {@code ,} is an
     * ordinary character, not a separator. Use {@link #parseList(String)} for a header value that
     * may hold several elements.
     *
     * <p>This method never throws.
     *
     * @param element the raw element text, such as {@code application/json;charset=utf-8;q=0.8}; may be {@code null}
     * @return the parsed element, or {@code null} if the input is {@code null}, blank or malformed
     */
    public static HeaderElement parse(String element) {
        if (element == null || element.isBlank()) {
            return null;
        }
        List<String> segments = splitOutsideQuotes(element, ';');
        if (segments == null) {
            return null;
        }
        String value = segments.get(0).trim();
        if (value.isEmpty()) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        double quality = 1.0;
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i).trim();
            int eq = segment.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = segment.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (name.indexOf('"') >= 0) {
                return null;
            }
            String raw = segment.substring(eq + 1).trim();
            boolean quoted = raw.startsWith("\"");
            String paramValue = unquote(raw);
            if (paramValue == null) {
                return null;
            }
            if ("q".equals(name)) {
                double q = quoted ? Double.NaN : parseQuality(paramValue);
                if (Double.isNaN(q)) {
                    return null;
                }
                quality = q;
            } else {
                parameters.put(name, paramValue);
            }
        }
        return new HeaderElement(value, parameters, quality);
    }

    // --- Tokenizing ---

    /**
     * Splits {@code text} on {@code delimiter} wherever the delimiter is outside a double-quoted
     * string. Inside a quoted string a backslash escapes the next character, so an escaped quote
     * does not end it; outside quotes a backslash is literal. Segments are returned exactly as
     * they appear, quote characters and whitespace included; none is trimmed, and empty segments
     * (such as the one after a trailing delimiter) are kept.
     *
     * <p>This is the tokenizer {@link #parse(String)} and {@link #parseList(String)} use, exposed
     * for header grammars that are not {@code value *( ";" parameter )} — such as the
     * comma-separated directives of {@code Cache-Control} or the {@code ;}-separated parameters
     * that follow the {@code <uri>} of a {@code Link} value — so they quote-split exactly as
     * every other header here does.
     *
     * @param text      the text to split; must not be {@code null}
     * @param delimiter the separator character; must not be {@code "} or {@code \}
     * @return the segments, unmodifiable and never empty, or {@code null} if a quoted string is
     *     not terminated, in which case the text cannot be split unambiguously
     * @throws NullPointerException     if {@code text} is {@code null}
     * @throws IllegalArgumentException if {@code delimiter} is {@code "} or {@code \}
     */
    public static List<String> splitOutsideQuotes(String text, char delimiter) {
        Objects.requireNonNull(text, "text must not be null");
        if (delimiter == '"' || delimiter == '\\') {
            throw new IllegalArgumentException("delimiter must not be a double quote or a backslash");
        }
        List<String> segments = new ArrayList<>();
        return split(text, delimiter, segments) ? List.copyOf(segments) : null;
    }

    /**
     * Unquotes a header parameter value. A value that does not start with {@code "} is returned
     * unchanged. A value that starts with {@code "} must be one complete quoted string: the
     * surrounding quotes are removed and each backslash escape is replaced by the character it
     * escapes, so {@code "a\"b"} yields {@code a"b}.
     *
     * @param value the raw parameter value, already trimmed; must not be {@code null}
     * @return the unquoted value, or {@code null} if the value starts with {@code "} but is not
     *     one complete quoted string: it has no closing quote (including a final escaped quote or
     *     a trailing backslash), or characters follow the closing quote
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String unquote(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isEmpty() || value.charAt(0) != '"') {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int length = value.length();
        for (int i = 1; i < length; i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (i + 1 >= length) {
                    return null;
                }
                out.append(value.charAt(++i));
            } else if (c == '"') {
                return i == length - 1 ? out.toString() : null;
            } else {
                out.append(c);
            }
        }
        return null;
    }

    /**
     * Appends the segments of {@code text} to {@code out}; returns {@code false} when a quoted
     * string is unterminated, in which case the last segment holds the rest of the text.
     */
    private static boolean split(String text, char delimiter, List<String> out) {
        int start = 0;
        boolean inQuote = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inQuote = false;
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == delimiter) {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return !inQuote;
    }

    /**
     * Parses an RFC 9110 qvalue ({@code 0}, {@code 1}, {@code 0.xxx}, {@code 1.000}); returns
     * {@link Double#NaN} for anything else.
     */
    private static double parseQuality(String text) {
        int length = text.length();
        if (length == 0 || length > 5) {
            return Double.NaN;
        }
        char whole = text.charAt(0);
        if (whole != '0' && whole != '1') {
            return Double.NaN;
        }
        if (length == 1) {
            return whole - '0';
        }
        if (text.charAt(1) != '.') {
            return Double.NaN;
        }
        for (int i = 2; i < length; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9' || (whole == '1' && c != '0')) {
                return Double.NaN;
            }
        }
        return Double.parseDouble(text);
    }
}
