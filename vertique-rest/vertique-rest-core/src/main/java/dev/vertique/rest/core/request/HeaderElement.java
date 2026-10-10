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
 * repeats, the last occurrence wins; a repeated {@code q} is not a repeated name but a
 * malformed element. An element with more than {@value #MAX_PARAMETERS} non-empty parameters
 * is malformed too: the extra parameters are never silently ignored.
 *
 * <p>The {@code q} rules above are the {@code Accept}-style rules of {@link #parseList(String)}
 * and {@link #parse(String)}. A {@code Content-Type} is not an {@code Accept} entry, and
 * {@code q} is an ordinary parameter there; {@link MediaType#parse(String)} therefore reads an
 * unusable {@code q} leniently (clamped to [0, 1], or ignored when unparsable) and never treats
 * it as malformed, while keeping the quote-aware splitting, the unquoting and the caps.
 *
 * @param value      the element token before the first {@code ;}, trimmed; never {@code null} or empty
 * @param parameters the parameters excluding {@code q}, lowercase names, in header order; immutable
 * @param quality    the {@code q} weight in the range [0.0, 1.0]
 */
public record HeaderElement(String value, Map<String, String> parameters, double quality) {

    /**
     * The maximum number of non-empty elements {@link #parseList(String)} considers; later
     * elements are not parsed. This bounds the work an excessively long header can cause.
     */
    public static final int MAX_ELEMENTS = 50;

    /**
     * The maximum number of non-empty parameters one element may carry; an element with more is
     * malformed. This bounds the work an element with thousands of parameters can cause.
     */
    public static final int MAX_PARAMETERS = 32;

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
     * malformed ones; scanning stops once they have been found, so the rest of an excessively
     * long header is never parsed. This method never throws.
     *
     * <p>Elements past the cap are not returned, and this method does not report that they
     * existed. A caller that guards a trust boundary with the list must not read a header that
     * held more than {@value #MAX_ELEMENTS} elements as complete; {@link AcceptNegotiator} counts
     * such a header as holding a malformed entry.
     *
     * @param headerValue the raw header value; may be {@code null}
     * @return an unmodifiable list of the well-formed elements; empty for {@code null} or blank input
     */
    public static List<HeaderElement> parseList(String headerValue) {
        return parseListChecked(headerValue).elements();
    }

    /**
     * A parsed element list together with the number of non-empty elements that were dropped as
     * malformed and whether further non-empty elements followed the {@value #MAX_ELEMENTS}
     * considered ones, so a caller can tell "nothing usable was sent" from "something unreadable
     * was sent".
     */
    record ParsedList(List<HeaderElement> elements, int malformed, boolean truncated) {}

    /** Same as {@link #parseList(String)}, additionally counting the dropped malformed elements. */
    static ParsedList parseListChecked(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return new ParsedList(List.of(), 0, false);
        }
        List<String> texts = new ArrayList<>();
        int[] stoppedAt = new int[1];
        boolean truncated = scan(headerValue, ',', MAX_ELEMENTS, false, texts, stoppedAt) == Scan.LIMIT
                && hasElementAfter(headerValue, stoppedAt[0]);
        List<HeaderElement> elements = new ArrayList<>(texts.size());
        int malformed = 0;
        for (String text : texts) {
            HeaderElement element = parse(text, true);
            if (element != null) {
                elements.add(element);
            } else {
                malformed++;
            }
        }
        return new ParsedList(List.copyOf(elements), malformed, truncated);
    }

    /**
     * Returns whether {@code text} holds a non-empty element at or after {@code from}, a position
     * just past a delimiter and so outside any quoted string. Nothing is allocated.
     */
    private static boolean hasElementAfter(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ',' && !Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a single element, treating the whole input as one element: a {@code ,} is an
     * ordinary character, not a separator. Use {@link #parseList(String)} for a header value that
     * may hold several elements.
     *
     * <p>This applies the {@code Accept}-style rules: an unusable or repeated {@code q} makes the
     * element malformed. This method never throws.
     *
     * @param element the raw element text, such as {@code application/json;charset=utf-8;q=0.8}; may be {@code null}
     * @return the parsed element, or {@code null} if the input is {@code null}, blank or malformed
     */
    public static HeaderElement parse(String element) {
        return parse(element, true);
    }

    /**
     * Parses one element the way a {@code Content-Type} is read: identical to
     * {@link #parse(String)} except that {@code q} is an ordinary parameter name with a lenient
     * value. A {@code q} that parses as a number is clamped to [0, 1]; one that does not, or that
     * is quoted or {@code NaN}, is ignored and leaves the weight at {@code 1.0}. Nothing about
     * {@code q} makes the element malformed, and the last usable {@code q} wins.
     */
    static HeaderElement parseLenient(String element) {
        return parse(element, false);
    }

    private static HeaderElement parse(String element, boolean strictQuality) {
        if (element == null || element.isBlank()) {
            return null;
        }
        int first = 0;
        while (Character.isWhitespace(element.charAt(first))) {
            first++;
        }
        if (element.charAt(first) == ';') {
            return null;
        }
        // The value plus MAX_PARAMETERS parameters; reaching one more means too many parameters.
        List<String> segments = new ArrayList<>();
        if (scan(element, ';', MAX_PARAMETERS + 2, false, segments, null) != Scan.COMPLETE) {
            return null;
        }
        String value = segments.get(0).trim();
        if (value.isEmpty()) {
            return null;
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        double quality = 1.0;
        boolean qualitySeen = false;
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
            if ("q".equals(name) && !strictQuality) {
                quality = lenientQuality(raw, quality);
                continue;
            }
            boolean quoted = raw.startsWith("\"");
            String paramValue = unquote(raw);
            if (paramValue == null) {
                return null;
            }
            if ("q".equals(name)) {
                double q = quoted ? Double.NaN : parseQuality(paramValue);
                if (Double.isNaN(q) || qualitySeen) {
                    return null;
                }
                qualitySeen = true;
                quality = q;
            } else {
                parameters.put(name, paramValue);
            }
        }
        return new HeaderElement(value, parameters, quality);
    }

    /**
     * Reads {@code q} as a {@code Content-Type} parameter: a number is clamped to [0, 1]; anything
     * else, including a quoted value, keeps {@code current}.
     */
    private static double lenientQuality(String raw, double current) {
        try {
            double q = Double.parseDouble(raw);
            if (!Double.isNaN(q)) {
                return Math.max(0.0, Math.min(1.0, q));
            }
        } catch (NumberFormatException ignored) {
            // an unusable q leaves the weight unchanged
        }
        return current;
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
     * <p><strong>Callers MUST check for {@code null}.</strong> A {@code null} result means a
     * quoted string is not terminated, so where one segment ends and the next begins cannot be
     * known; a caller that reads such a value anyway has accepted input it could not tokenize.
     * Treat it as malformed and reject, or fail closed, never as "no segments".
     *
     * @param text      the text to split; must not be {@code null}
     * @param delimiter the separator character; must not be {@code "} or {@code \}
     * @return the segments, unmodifiable and never empty, or {@code null} if a quoted string is
     *     not terminated; the caller MUST check for {@code null}
     * @throws NullPointerException     if {@code text} is {@code null}
     * @throws IllegalArgumentException if {@code delimiter} is {@code "} or {@code \}
     */
    public static List<String> splitOutsideQuotes(String text, char delimiter) {
        Objects.requireNonNull(text, "text must not be null");
        if (delimiter == '"' || delimiter == '\\') {
            throw new IllegalArgumentException("delimiter must not be a double quote or a backslash");
        }
        List<String> segments = new ArrayList<>();
        return scan(text, delimiter, Integer.MAX_VALUE, true, segments, null) == Scan.COMPLETE
                ? List.copyOf(segments)
                : null;
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
     *     a trailing backslash), or characters follow the closing quote; the caller MUST check
     *     for {@code null} and treat it as a malformed value
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

    /** How a {@link #scan} ended. */
    private enum Scan {
        /** The whole text was scanned and every quoted string was terminated. */
        COMPLETE,
        /** A quoted string was not terminated; the last segment holds the rest of the text. */
        UNTERMINATED,
        /** The requested number of non-blank segments was found and scanning stopped. */
        LIMIT
    }

    /**
     * Appends the segments of {@code text} to {@code out}. Unless {@code keepBlank}, blank
     * segments are skipped without being materialized and scanning stops as soon as
     * {@code maxNonBlank} segments have been appended, so the work is bounded by the segments
     * kept rather than by the length of the text. When it stops for that reason and
     * {@code stoppedAt} is not {@code null}, {@code stoppedAt[0]} receives the position at which
     * scanning would resume.
     */
    private static Scan scan(
            String text, char delimiter, int maxNonBlank, boolean keepBlank, List<String> out, int[] stoppedAt) {
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
                if (keepBlank || !isBlank(text, start, i)) {
                    out.add(text.substring(start, i));
                    if (!keepBlank && out.size() >= maxNonBlank) {
                        if (stoppedAt != null) {
                            stoppedAt[0] = i + 1;
                        }
                        return Scan.LIMIT;
                    }
                }
                start = i + 1;
            }
        }
        if (keepBlank || !isBlank(text, start, length)) {
            out.add(text.substring(start));
            if (!keepBlank && out.size() >= maxNonBlank) {
                if (stoppedAt != null) {
                    stoppedAt[0] = length;
                }
                return Scan.LIMIT;
            }
        }
        return inQuote ? Scan.UNTERMINATED : Scan.COMPLETE;
    }

    private static boolean isBlank(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
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
