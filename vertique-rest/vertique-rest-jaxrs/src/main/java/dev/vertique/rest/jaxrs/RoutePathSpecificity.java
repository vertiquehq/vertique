// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import java.util.Comparator;

/**
 * Computes a deterministic, total-order <em>specificity</em> ordering over JAX-RS {@code @Path}
 * templates so that more-specific routes register on the plain Vert.x {@link io.vertx.ext.web.Router}
 * <em>before</em> less-specific ones.
 *
 * <p>This exists to prevent <strong>route shadowing</strong>. Vert.x evaluates routes of equal
 * {@code order} in <em>add order</em> and runs the first match. A path-parameter route such as
 * {@code /hello/{name}} therefore matches {@code /hello/secured} when it was added first, so a request
 * to the secured static path resolves to the unsecured {@code {name}} route and its authentication
 * handler never runs. Registering the more-specific {@code /hello/secured} first restores the
 * specificity-before-parameter precedence the pre-rewrite OpenAPI router supplied automatically.
 *
 * <p><strong>Segment classification.</strong> Each template is split into {@code /}-delimited
 * segments, and each segment is classified as one of:
 * <ul>
 *   <li>{@link SegmentKind#LITERAL} — static text with no variable, e.g. {@code secured};</li>
 *   <li>{@link SegmentKind#REGEX} — a variable carrying a regex constraint, e.g. {@code {id:\d+}};</li>
 *   <li>{@link SegmentKind#PARAM} — a plain unconstrained variable, e.g. {@code {name}} (or the
 *       Vert.x colon form {@code :name}).</li>
 * </ul>
 *
 * <p><strong>Specificity rank.</strong> {@code LITERAL} &gt; {@code REGEX} &gt; {@code PARAM}: a static
 * segment is the most specific, a regex-constrained variable is more specific than a plain variable.
 *
 * <p><strong>Comparison.</strong> Two templates are compared segment-by-segment from the left. At the
 * first index where the segment kinds differ, the template with the higher-ranked kind is more
 * specific (sorts earlier). If all shared positions tie, the template with <em>more</em> segments is
 * more specific (sorts earlier). The final tie-break is the route template string compared
 * lexicographically, which makes the order total and stable.
 *
 * <p>The {@link #MOST_SPECIFIC_FIRST} comparator orders templates descending by specificity (most
 * specific first); sorting a list with it yields the correct Vert.x registration order.
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

    /**
     * Comparator ordering JAX-RS {@code @Path} templates most-specific-first, suitable for
     * determining the Vert.x route registration order. See the class javadoc for the full ordering
     * contract (segment-by-segment kind comparison, longer-path tie-break, then lexicographic
     * template tie-break).
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
        if (segA.length != segB.length) {
            // More segments is more specific → should sort earlier → negative.
            return Integer.compare(segB.length, segA.length);
        }
        // Fully tied on structure: fall back to the template string for a stable total order.
        return a.compareTo(b);
    }

    /**
     * Splits a path template into its {@code /}-delimited segments, dropping empty segments produced
     * by a leading slash or a trailing slash.
     *
     * @param path the JAX-RS path template
     * @return the non-empty path segments in order
     */
    private static String[] segments(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
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
