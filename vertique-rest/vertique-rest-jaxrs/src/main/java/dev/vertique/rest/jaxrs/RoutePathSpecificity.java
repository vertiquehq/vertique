// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.router.PathTemplateVariables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders JAX-RS {@code @Path} templates so that the more specific route registers on the plain Vert.x
 * {@link io.vertx.ext.web.Router} <em>before</em> the less specific one, following the JAX-RS 3.1
 * (section 3.7.2) precedence key.
 *
 * <p>This exists to prevent <strong>route shadowing</strong>. Vert.x evaluates routes of equal
 * {@code order} in <em>add order</em> and runs the first match. A route such as {@code /hello/{name}}
 * therefore matches {@code /hello/secured} when it was added first, so the secured static route's
 * authentication handler never runs. Registering the more specific route first restores the
 * precedence the pre-rewrite OpenAPI router supplied automatically.
 *
 * <p><strong>Rank key.</strong> Tokens come from {@link PathTemplateVariables#VARIABLE}, the grammar
 * {@link JaxRsPathTemplate} uses to build the registered route. A template's key is:
 * <ul>
 *   <li><em>L</em>, the literal characters: every character outside a variable, separators
 *       included;</li>
 *   <li><em>V</em>, the variables: brace variables, plus a Vert.x colon segment ({@code :name}) when the
 *       template has no constrained variable (a regex template quotes it, so it is literal there);</li>
 *   <li><em>R</em>, the variables that declare a constraint, even {@code [^/]+}.</li>
 * </ul>
 * A constraint that contains a required literal path separator ({@code {slug:[^/]+/admin}}) counts
 * piece by piece: each separator and each plain piece count as literal characters, and every other piece
 * counts as one constrained variable.
 *
 * <p><strong>Order.</strong> L descending, then V descending, then R descending. Ties fall back to a
 * segment-by-segment comparison from the left ({@link SegmentKind#LITERAL} before
 * {@link SegmentKind#REGEX} before {@link SegmentKind#PARAM}, then the template with more segments), and
 * finally to the template string, which makes the order total and stable. Templates that tie on the key
 * and the segment comparison have <em>equal rank</em>: their relative order is by name only and is not a
 * contract.
 *
 * <p><strong>Alternation.</strong> A constraint with a top-level {@code |} matches several shapes that
 * rank differently, so {@link #rankedVariants} ranks each alternative separately: the registrar registers
 * the operation's full template at its least specific alternative's rank and one more route for each
 * more specific alternative. A constraint containing {@code \Q}, a numeric backreference or an inline
 * flag group is never split, because splitting could change what an alternative matches.
 *
 * <p>The {@link #MOST_SPECIFIC_FIRST} comparator orders templates descending by specificity.
 */
final class RoutePathSpecificity {

    private RoutePathSpecificity() {}

    /**
     * Classification of a single {@code /}-delimited path segment, ordered from most to least specific
     * by declaration order ({@code LITERAL} first). The enum {@code ordinal()} is <em>not</em> used for
     * ranking; {@link #rank()} supplies the explicit specificity rank instead.
     */
    enum SegmentKind {
        /** A plain, unconstrained path variable ({@code {name}} or {@code :name}) — least specific. */
        PARAM(0),
        /** A path variable carrying a regex constraint ({@code {id:\d+}}) — more specific than a plain param. */
        REGEX(1),
        /** Static literal text with no variable ({@code secured}) — most specific. */
        LITERAL(2);

        private final int rank;

        SegmentKind(int rank) {
            this.rank = rank;
        }

        /**
         * Returns the specificity rank of this kind; higher is more specific.
         *
         * @return the specificity rank ({@code LITERAL > REGEX > PARAM})
         */
        int rank() {
            return rank;
        }
    }

    /** Matches a Vert.x colon-form variable ({@code :name}) in the literal text of a plain template. */
    private static final Pattern COLON_VARIABLE = Pattern.compile(":\\w+");

    /** Matches the start of an inline flag group such as {@code (?i)} or {@code (?-i:}. */
    private static final Pattern INLINE_FLAG = Pattern.compile("\\(\\?[a-zA-Z-]");

    /**
     * The rank key of a template, or the contribution of one variable to it: literal characters,
     * variables, and constrained variables.
     *
     * @param literals          the literal characters
     * @param variables         the variables
     * @param regexVariables    the variables that declare a constraint
     */
    record Key(int literals, int variables, int regexVariables) implements Comparable<Key> {

        Key plus(Key other) {
            return new Key(
                    literals + other.literals, variables + other.variables, regexVariables + other.regexVariables);
        }

        /** Orders the more specific key first: L descending, then V, then R. */
        @Override
        public int compareTo(Key other) {
            int byLiterals = Integer.compare(other.literals, literals);
            if (byLiterals != 0) {
                return byLiterals;
            }
            int byVariables = Integer.compare(other.variables, variables);
            if (byVariables != 0) {
                return byVariables;
            }
            return Integer.compare(other.regexVariables, regexVariables);
        }
    }

    /**
     * One rank of a template: the template text to sort by and its key. An alternation template has one
     * rank per alternative (see {@link #rankedVariants}).
     *
     * @param template the template text the rank belongs to; the segment and string tie-breaks read it
     * @param key      the rank key
     */
    record Rank(String template, Key key) {

        /**
         * Orders ranks most specific first, without the final string tie-break: the key, then the segment
         * comparison. Zero means equal rank.
         */
        static int compareRank(Rank a, Rank b) {
            int byKey = a.key.compareTo(b.key);
            return byKey != 0 ? byKey : segmentOrder(a.template, b.template);
        }

        /** Orders ranks most specific first, then by template string, a total order. */
        static int compareTotal(Rank a, Rank b) {
            int byRank = compareRank(a, b);
            return byRank != 0 ? byRank : a.template.compareTo(b.template);
        }
    }

    /**
     * Comparator ordering JAX-RS {@code @Path} templates most-specific-first, suitable for
     * determining the Vert.x route registration order. See the class javadoc for the full ordering
     * contract (rank key, segment tie-break, then lexicographic template tie-break).
     */
    static final Comparator<String> MOST_SPECIFIC_FIRST = RoutePathSpecificity::compare;

    /**
     * Compares two path templates by specificity. A negative result means {@code a} is more specific
     * than {@code b} (so {@code a} sorts earlier under {@link #MOST_SPECIFIC_FIRST}).
     *
     * @param a the first JAX-RS path template
     * @param b the second JAX-RS path template
     * @return a negative value if {@code a} is more specific, positive if {@code b} is more specific,
     *     zero only when the templates are equal strings
     */
    static int compare(String a, String b) {
        return Rank.compareTotal(rankOf(a), rankOf(b));
    }

    /**
     * Returns whether two templates have equal rank: the same key and the same segment comparison, so
     * only their name decides their order.
     *
     * @param a the first JAX-RS path template
     * @param b the second JAX-RS path template
     * @return {@code true} when neither is more specific than the other
     */
    static boolean equalRank(String a, String b) {
        return Rank.compareRank(rankOf(a), rankOf(b)) == 0;
    }

    /**
     * Returns the rank of a template as written, with each constraint counted as one piece.
     *
     * @param template the JAX-RS path template
     * @return the template with its natural key
     */
    static Rank rankOf(String template) {
        return new Rank(template, keyOf(template, null));
    }

    /** Segment-by-segment comparison of today's kinds; zero when the two tie. */
    private static int segmentOrder(String a, String b) {
        String[] segA = segments(a);
        String[] segB = segments(b);
        int shared = Math.min(segA.length, segB.length);
        for (int i = 0; i < shared; i++) {
            int rankA = classify(segA[i]).rank();
            int rankB = classify(segB[i]).rank();
            if (rankA != rankB) {
                // Higher rank is more specific → should sort earlier → negative.
                return Integer.compare(rankB, rankA);
            }
        }
        // More segments is more specific → should sort earlier → negative.
        return Integer.compare(segB.length, segA.length);
    }

    /**
     * Computes the rank key of a template.
     *
     * @param template  the JAX-RS path template
     * @param overrides per variable (in {@link PathTemplateVariables#VARIABLE} match order) the
     *                  contribution to use instead of the natural one, or {@code null} for none
     */
    private static Key keyOf(String template, Key[] overrides) {
        boolean constrained = false;
        Matcher scan = PathTemplateVariables.VARIABLE.matcher(template);
        while (scan.find()) {
            constrained |= scan.group(2) != null;
        }
        Key key = new Key(0, 0, 0);
        Matcher matcher = PathTemplateVariables.VARIABLE.matcher(template);
        int last = 0;
        int index = 0;
        while (matcher.find()) {
            key = key.plus(literalText(template.substring(last, matcher.start()), !constrained));
            Key chosen = overrides != null ? overrides[index] : null;
            if (chosen != null) {
                key = key.plus(chosen);
            } else if (matcher.group(2) == null) {
                key = key.plus(new Key(0, 1, 0));
            } else {
                key = key.plus(contribution(matcher.group(2).trim()));
            }
            last = matcher.end();
            index++;
        }
        return key.plus(literalText(template.substring(last), !constrained));
    }

    /** Counts the literal text between variables; a colon segment is a variable in a plain template. */
    private static Key literalText(String text, boolean colonVariables) {
        int literals = text.length();
        int variables = 0;
        if (colonVariables) {
            Matcher colon = COLON_VARIABLE.matcher(text);
            while (colon.find()) {
                literals -= colon.end() - colon.start();
                variables++;
            }
        }
        return new Key(literals, variables, 0);
    }

    /**
     * The contribution of one constraint: when it splits at required separators, each separator and
     * each plain piece count as literal characters and every other piece as one constrained variable;
     * otherwise it is one constrained variable.
     */
    private static Key contribution(String constraint) {
        Split split = splitConstraint(constraint);
        if (split.pieces().size() < 2) {
            return new Key(0, 1, 1);
        }
        int literals = split.separators();
        int variables = 0;
        for (String piece : split.pieces()) {
            if (isPlainLiteral(piece)) {
                literals += piece.length();
            } else {
                variables++;
            }
        }
        return new Key(literals, variables, variables);
    }

    /**
     * Returns the ranked variants of a template, most specific first. A constraint with alternatives
     * (a top-level {@code |}, see {@link #alternatives}) splits into one variant per alternative rank;
     * alternatives of one variable that contribute equally to the key merge into one alternation, so
     * equal-rank alternatives do not multiply the routes. A template with no splittable alternation has
     * one variant, itself, at its natural key.
     *
     * @param template the JAX-RS path template
     * @return the variants with their ranks, most specific first; never empty
     */
    static List<Rank> rankedVariants(String template) {
        List<VariableSpan> variables = new ArrayList<>();
        Matcher matcher = PathTemplateVariables.VARIABLE.matcher(template);
        boolean splittable = false;
        while (matcher.find()) {
            List<Choice> choices = null;
            if (matcher.group(2) != null) {
                choices = choicesOf(matcher.group(1), matcher.group(2));
            }
            splittable |= choices != null;
            variables.add(new VariableSpan(matcher.start(), matcher.end(), choices));
        }
        if (!splittable) {
            return List.of(rankOf(template));
        }
        List<Rank> result = new ArrayList<>();
        expand(template, variables, 0, new Choice[variables.size()], result);
        result.sort(Rank::compareTotal);
        return List.copyOf(result);
    }

    /**
     * Returns the variants of a template, most specific first; see {@link #rankedVariants}.
     *
     * @param template the JAX-RS path template
     * @return the variant templates; {@code List.of(template)} when nothing splits
     */
    static List<String> variants(String template) {
        return rankedVariants(template).stream().map(Rank::template).toList();
    }

    private record VariableSpan(int start, int end, List<Choice> choices) {}

    /** One merged group of alternatives: the constraint text to substitute and its contribution. */
    private record Choice(String variable, Key contribution) {}

    private static List<Choice> choicesOf(String name, String rawConstraint) {
        List<String> alternatives = alternatives(rawConstraint.trim());
        if (alternatives.isEmpty()) {
            return null;
        }
        Map<Key, List<String>> groups = new LinkedHashMap<>();
        for (String alternative : alternatives) {
            groups.computeIfAbsent(contribution(alternative), k -> new ArrayList<>())
                    .add(alternative);
        }
        List<Choice> choices = new ArrayList<>();
        groups.forEach((key, group) -> choices.add(new Choice("{" + name + ":" + String.join("|", group) + "}", key)));
        return choices;
    }

    private static void expand(String template, List<VariableSpan> variables, int at, Choice[] chosen, List<Rank> out) {
        if (at == variables.size()) {
            StringBuilder text = new StringBuilder();
            Key[] overrides = new Key[variables.size()];
            int last = 0;
            for (int i = 0; i < variables.size(); i++) {
                VariableSpan variable = variables.get(i);
                text.append(template, last, variable.start());
                if (chosen[i] != null) {
                    text.append(chosen[i].variable());
                    overrides[i] = chosen[i].contribution();
                } else {
                    text.append(template, variable.start(), variable.end());
                }
                last = variable.end();
            }
            text.append(template, last, template.length());
            out.add(new Rank(text.toString(), keyOf(template, overrides)));
            return;
        }
        List<Choice> choices = variables.get(at).choices();
        if (choices == null) {
            expand(template, variables, at + 1, chosen, out);
            return;
        }
        for (Choice choice : choices) {
            chosen[at] = choice;
            expand(template, variables, at + 1, chosen, out);
        }
        chosen[at] = null;
    }

    /**
     * Splits a constraint at each top-level {@code |}: one outside a character class and a group and
     * not escaped. Returns an empty list (no split) when the constraint has no such {@code |}, contains
     * {@code \Q}, a numeric backreference or an inline flag group, or would produce an empty
     * alternative, because splitting could then change what an alternative matches.
     */
    private static List<String> alternatives(String constraint) {
        if (constraint.indexOf("\\Q") >= 0 || INLINE_FLAG.matcher(constraint).find()) {
            return List.of();
        }
        List<String> alternatives = new ArrayList<>();
        int start = 0;
        int groups = 0;
        boolean inClass = false;
        for (int i = 0; i < constraint.length(); i++) {
            char c = constraint.charAt(i);
            if (c == '\\') {
                if (i + 1 < constraint.length() && constraint.charAt(i + 1) >= '1' && constraint.charAt(i + 1) <= '9') {
                    return List.of();
                }
                i++; // skip the escaped character
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                groups++;
            } else if (c == ')') {
                groups = Math.max(0, groups - 1);
            } else if (c == '|' && groups == 0) {
                alternatives.add(constraint.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (alternatives.isEmpty()) {
            return List.of();
        }
        alternatives.add(constraint.substring(start).trim());
        return alternatives.stream().anyMatch(String::isEmpty) ? List.of() : List.copyOf(alternatives);
    }

    /**
     * Splits a path template into its segments on every {@code /} that is outside braces, dropping
     * empty segments produced by a leading or trailing slash. A {@code /} inside {@code {...}} (for
     * example the character class in {@code {name:[^/]+}}, or a constraint with nested braces such as
     * {@code {name:[a-z/]{1,3}}}) does not split, so the whole variable stays one segment. A segment
     * that is exactly one constrained variable whose constraint contains a literal path separator
     * (outside a character class or group), such as {@code {slug:[^/]+/admin}}, counts as
     * one segment per separated piece; a piece without regex metacharacters ({@code admin}) counts as
     * a literal.
     *
     * <p>Unbalanced input never throws: an opening brace with no matching close is treated as ordinary
     * text and the remainder of the template is split normally.
     *
     * @param path the JAX-RS path template
     * @return the non-empty path segments in order
     */
    private static String[] segments(String path) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        int open = -1;
        int i = 0;
        int length = path.length();
        while (i < length) {
            char c = path.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    open = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == '/' && depth == 0) {
                addSegment(result, path, start, i);
                start = i + 1;
            }
            i++;
            if (i == length && depth > 0) {
                // The outermost brace never closed: treat it as plain text and rescan after it.
                i = open + 1;
                depth = 0;
                open = -1;
            }
        }
        addSegment(result, path, start, length);
        return result.toArray(String[]::new);
    }

    private static void addSegment(List<String> result, String path, int from, int to) {
        if (to <= from) {
            return;
        }
        String segment = path.substring(from, to);
        int colon = wholeVariableColon(segment);
        if (colon < 0) {
            result.add(segment);
            return;
        }
        // A constraint that itself contains a path separator matches several request segments; rank
        // it as one piece per separator so that a literal part of the constraint (the "admin" of
        // {slug:[^/]+/admin}) still counts as a literal against a sibling's plain variable.
        String name = segment.substring(1, colon);
        List<String> pieces = splitConstraint(segment.substring(colon + 1, segment.length() - 1))
                .pieces();
        if (pieces.size() < 2) {
            result.add(segment);
            return;
        }
        for (String piece : pieces) {
            result.add(isPlainLiteral(piece) ? piece : "{" + name + ":" + piece + "}");
        }
    }

    /**
     * Returns the index of the {@code :} that introduces the constraint when {@code segment} is exactly
     * one constrained brace variable ({@code {name:constraint}}), otherwise {@code -1}.
     */
    private static int wholeVariableColon(String segment) {
        if (segment.length() < 3 || segment.charAt(0) != '{' || segment.charAt(segment.length() - 1) != '}') {
            return -1;
        }
        int depth = 0;
        int colon = -1;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && i != segment.length() - 1) {
                    return -1; // the first variable closes before the end: a mixed segment
                }
            } else if (c == ':' && depth == 1 && colon < 0) {
                colon = i;
            }
        }
        return depth == 0 ? colon : -1;
    }

    /**
     * Splits a regex constraint on each {@code /} (or an escaped {@code \/}) that matches a required
     * literal path separator: one outside a character class and a group and not followed by a
     * quantifier. A constraint without such a separator, or with a top-level alternation or an
     * optional separator (either of which can let it match fewer segments), is returned whole.
     */
    private static Split splitConstraint(String constraint) {
        List<String> pieces = new ArrayList<>();
        int separators = 0;
        int start = 0;
        int groups = 0;
        boolean inClass = false;
        for (int i = 0; i < constraint.length(); i++) {
            char c = constraint.charAt(i);
            if (c == '\\') {
                if (!inClass && groups == 0 && i + 1 < constraint.length() && constraint.charAt(i + 1) == '/') {
                    if (quantified(constraint, i + 2)) {
                        return whole(constraint);
                    }
                    addPiece(pieces, constraint, start, i); // an escaped slash is still a separator
                    separators++;
                    start = i + 2;
                }
                i++; // skip the escaped character
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                groups++;
            } else if (c == ')') {
                groups = Math.max(0, groups - 1);
            } else if (c == '|' && groups == 0) {
                return whole(constraint); // an alternative may match a single segment
            } else if (c == '/' && groups == 0) {
                if (quantified(constraint, i + 1)) {
                    return whole(constraint);
                }
                addPiece(pieces, constraint, start, i);
                separators++;
                start = i + 1;
            }
        }
        addPiece(pieces, constraint, start, constraint.length());
        if (pieces.isEmpty()) {
            pieces.add(constraint);
        }
        return new Split(pieces, separators);
    }

    private static Split whole(String constraint) {
        return new Split(List.of(constraint), 0);
    }

    /** The pieces of a constraint split at required separators, and how many separators it split at. */
    private record Split(List<String> pieces, int separators) {}

    /** Returns whether the character at {@code index} quantifies the separator before it. */
    private static boolean quantified(String constraint, int index) {
        return index < constraint.length() && "?*{".indexOf(constraint.charAt(index)) >= 0;
    }

    private static void addPiece(List<String> pieces, String constraint, int from, int to) {
        String piece = constraint.substring(from, to).trim();
        if (!piece.isEmpty()) {
            pieces.add(piece);
        }
    }

    /** Returns whether a constraint piece has no regex metacharacter, so it matches only itself. */
    private static boolean isPlainLiteral(String piece) {
        for (int i = 0; i < piece.length(); i++) {
            if ("\\.[]{}()*+?^$|".indexOf(piece.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Classifies a single path segment as {@link SegmentKind#LITERAL}, {@link SegmentKind#REGEX}, or
     * {@link SegmentKind#PARAM}.
     *
     * <p>A segment is a variable when it is a JAX-RS brace form ({@code {…}}) or a Vert.x colon form
     * ({@code :…}). A brace variable is {@code REGEX} when it carries a {@code :}-introduced constraint
     * inside the braces ({@code {id:\d+}}), otherwise {@code PARAM}. A colon-form segment is always a
     * plain {@code PARAM} (the colon form cannot carry a per-segment constraint). Everything else —
     * including a multi-token segment that merely contains a brace variable amid literal text — is
     * treated as {@code LITERAL} for ranking, because such a segment still constrains the match by its
     * literal portion.
     *
     * @param segment a single, non-empty path segment
     * @return the segment classification
     */
    private static SegmentKind classify(String segment) {
        if (segment.startsWith("{") && segment.endsWith("}")) {
            // A brace variable that spans the whole segment. REGEX iff it carries a `:`-constraint.
            String inner = segment.substring(1, segment.length() - 1);
            return inner.indexOf(':') >= 0 ? SegmentKind.REGEX : SegmentKind.PARAM;
        }
        if (segment.startsWith(":")) {
            // Vert.x colon form — always an unconstrained plain param.
            return SegmentKind.PARAM;
        }
        return SegmentKind.LITERAL;
    }
}
