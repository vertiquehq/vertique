// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spring-faithful placeholder grammar parser for the vertique-config bootstrap engine.
 *
 * <p>Performs a single left-to-right forward scan over the input string using a
 * char-indexed loop and a {@link StringBuilder} for accumulating literal text. No
 * regular expressions are used — the scan is deterministic and has no backtracking.
 *
 * <h2>Supported grammar forms</h2>
 * <ul>
 *   <li>{@code ${key}} — placeholder reference; {@link Placeholder#defaultValue()} is
 *       {@code null} when no colon is present.</li>
 *   <li>{@code ${key:default}} — bare-colon default: the <em>first top-level colon</em>
 *       (a colon at brace-depth 1 — inside the outer placeholder but not inside any
 *       nested {@code ${}}) splits the key from the default. Everything after that colon
 *       (including further colons) is the default text, so
 *       {@code ${endpoint:https://collector:4317}} has key {@code endpoint} and default
 *       {@code https://collector:4317}.</li>
 *   <li>{@code ${key:}} — empty-string default.</li>
 *   <li>{@code \${...}} — escape: the three-character sequence {@code \${} emits the
 *       literal text {@code ${} and advances the scan cursor by 3. A {@code \} not
 *       immediately followed by {@code ${} is treated as a literal backslash.</li>
 * </ul>
 *
 * <h2>Blank-key rule</h2>
 * <p>A placeholder whose key is blank (empty or whitespace-only before the first
 * top-level colon) is reported as a {@link Malformed} segment. Whitespace inside
 * a non-blank key is preserved as-is — keys come from disciplined config and are
 * never silently trimmed.
 *
 * <h2>Unterminated placeholder rule</h2>
 * <p>If the closing {@code }} at depth 0 is never reached by end-of-input, the
 * entire remaining text from the opening {@code ${} is reported as a {@link Malformed}
 * segment.
 *
 * <h2>Package-private visibility</h2>
 * <p>This class is package-private and internal to the engine. The resolution engine
 * and its callers access it directly; it is not part of the public API of
 * {@code vertique-config}.
 *
 * @see Segment
 * @see Literal
 * @see Placeholder
 * @see Malformed
 */
final class PlaceholderParser {

    private PlaceholderParser() {}

    // --- Public API ---

    /**
     * Parses {@code input} into an ordered list of segments according to the placeholder grammar.
     *
     * <p>An empty input yields an empty list. The segment list is immutable.
     *
     * @param input the string to parse; must not be {@code null}
     * @return immutable ordered list of {@link Segment} instances
     */
    static List<Segment> parse(String input) {
        if (input.isEmpty()) {
            return List.of();
        }

        List<Segment> segments = new ArrayList<>();
        StringBuilder literalBuf = new StringBuilder();
        int len = input.length();
        int i = 0;

        while (i < len) {
            char c = input.charAt(i);

            // --- Escape: \${ → emit literal '${' ---
            if (c == '\\' && i + 2 < len && input.charAt(i + 1) == '$' && input.charAt(i + 2) == '{') {
                literalBuf.append("${");
                i += 3;
                continue;
            }

            // --- Placeholder start: ${ ---
            if (c == '$' && i + 1 < len && input.charAt(i + 1) == '{') {
                // Flush accumulated literal before this placeholder
                flushLiteral(literalBuf, segments);

                // Scan forward to find the matching closing brace, tracking brace depth.
                // We start inside the outer ${, so depth starts at 1.
                int start = i; // position of the '$'
                i += 2; // skip past '${'
                int depth = 1;
                boolean foundClose = false;

                // To find the first top-level colon we need to track position
                int colonPos = -1; // index within the 'content' StringBuilder where first top-level colon sits

                StringBuilder content = new StringBuilder();

                while (i < len && depth > 0) {
                    char ch = input.charAt(i);

                    // Nested escape inside placeholder content: \${ → emit literal ${ into content
                    if (ch == '\\' && i + 2 < len && input.charAt(i + 1) == '$' && input.charAt(i + 2) == '{') {
                        content.append("${");
                        i += 3;
                        continue;
                    }

                    if (ch == '$' && i + 1 < len && input.charAt(i + 1) == '{') {
                        // Nested ${: increase depth, include in content
                        content.append("${");
                        depth++;
                        i += 2;
                        continue;
                    }

                    if (ch == '}') {
                        depth--;
                        if (depth == 0) {
                            // Found the matching close
                            foundClose = true;
                            i++; // advance past '}'
                            break;
                        }
                        // depth > 0: closing a nested ${}, include in content
                        content.append(ch);
                        i++;
                        continue;
                    }

                    // Record position of first top-level colon (depth == 1 and colon not yet found)
                    if (ch == ':' && depth == 1 && colonPos == -1) {
                        colonPos = content.length();
                    }

                    content.append(ch);
                    i++;
                }

                if (!foundClose) {
                    // Unterminated placeholder — report as Malformed
                    String rawText = input.substring(start, i);
                    segments.add(new Malformed(rawText));
                    // Do not advance i further; it's already at end-of-string
                    break;
                }

                // We have the content between ${ and the matching }
                String contentStr = content.toString();

                String key;
                String defaultValue;

                if (colonPos == -1) {
                    // No top-level colon → whole content is the key, no default
                    key = contentStr;
                    defaultValue = null;
                } else {
                    // Split at the first top-level colon
                    key = contentStr.substring(0, colonPos);
                    defaultValue = contentStr.substring(colonPos + 1);
                }

                // Blank-key check: blank before colon means Malformed
                if (key.isBlank()) {
                    // Reconstruct the raw text for the Malformed segment
                    String rawText = input.substring(start, i);
                    segments.add(new Malformed(rawText));
                } else {
                    segments.add(new Placeholder(key, defaultValue));
                }
                continue;
            }

            // --- Ordinary literal character ---
            literalBuf.append(c);
            i++;
        }

        // Flush any remaining literal text
        flushLiteral(literalBuf, segments);

        return Collections.unmodifiableList(segments);
    }

    /**
     * Returns {@code true} if the input contains at least one {@link Placeholder} or
     * {@link Malformed} segment — i.e., if placeholder resolution needs to be performed.
     *
     * <p>An escaped {@code \${...}} does NOT count; it produces only a {@link Literal}.
     *
     * <p>Uses a fast {@link String#indexOf(String)} pre-check: if the input contains no
     * {@code ${} sequence it cannot contain any placeholder or malformed segment and
     * {@code false} is returned immediately without invoking the full parser.
     *
     * @param input the string to inspect; must not be {@code null}
     * @return {@code true} if any non-literal segment exists
     */
    static boolean containsPlaceholder(String input) {
        if (input.indexOf("${") < 0) {
            return false;
        }
        return parse(input).stream().anyMatch(s -> !(s instanceof Literal));
    }

    // --- Private helpers ---

    /**
     * Flushes the accumulated literal buffer into the segment list if non-empty,
     * then resets the buffer.
     *
     * @param buf      the literal accumulator
     * @param segments the segment list being built
     */
    private static void flushLiteral(StringBuilder buf, List<Segment> segments) {
        if (!buf.isEmpty()) {
            segments.add(new Literal(buf.toString()));
            buf.setLength(0);
        }
    }

    // --- Segment types ---

    /**
     * A parsed segment of a placeholder string.
     *
     * <p>The sealed hierarchy is closed to three cases:
     * <ul>
     *   <li>{@link Literal} — plain text with no placeholder syntax.</li>
     *   <li>{@link Placeholder} — a well-formed {@code ${key}} or {@code ${key:default}}
     *       reference.</li>
     *   <li>{@link Malformed} — an unterminated or blank-key placeholder that cannot be
     *       resolved.</li>
     * </ul>
     */
    sealed interface Segment permits Literal, Placeholder, Malformed {}

    /**
     * A literal text segment containing no placeholder syntax.
     *
     * <p>The {@code text} is exactly the original characters (no unescaping is needed
     * beyond what the scanner has already applied — escape sequences become their
     * literal equivalents during scanning).
     *
     * @param text the literal text; never {@code null}, never empty
     */
    record Literal(String text) implements Segment {}

    /**
     * A well-formed placeholder reference parsed from a {@code ${key}} or
     * {@code ${key:default}} expression.
     *
     * <p>Key invariants:
     * <ul>
     *   <li>{@link #key()} is never blank (a blank key produces a {@link Malformed}).</li>
     *   <li>Whitespace inside a non-blank key is preserved as-is — keys are not trimmed.</li>
     *   <li>{@link #defaultValue()} is {@code null} when no top-level colon was present;
     *       an empty string when the colon was present but nothing follows it
     *       ({@code ${key:}}).</li>
     *   <li>{@link #defaultValue()} may contain nested {@code ${...}} text; the parser
     *       does not recurse — nested resolution is the engine's responsibility.</li>
     * </ul>
     *
     * @param key          the placeholder key; never blank
     * @param defaultValue the default value text, or {@code null} if no default was declared;
     *                     may be empty ({@code ""}) for an explicit empty default
     */
    record Placeholder(String key, String defaultValue) implements Segment {}

    /**
     * A malformed placeholder segment that cannot be resolved.
     *
     * <p>Two cases produce a {@code Malformed}:
     * <ol>
     *   <li>An unterminated {@code ${...}} where the matching {@code }} at brace depth 0 is
     *       never reached by end-of-input. The {@link #rawText()} is the original text from
     *       the opening {@code ${} to end-of-string.</li>
     *   <li>A placeholder whose key part (text before the first top-level colon, or the
     *       entire content if no colon) is blank (empty or whitespace-only).</li>
     * </ol>
     *
     * @param rawText the unterminated or blank-key placeholder text starting at {@code ${};
     *                never {@code null}
     */
    record Malformed(String rawText) implements Segment {}
}
