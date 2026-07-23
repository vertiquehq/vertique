// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable pattern matched against an {@link ActionRef} when evaluating a policy statement.
 *
 * <p>A pattern is one of two shapes:
 *
 * <ul>
 *   <li><strong>Exact canonical</strong> — three valid {@link ActionRef} segments, e.g.
 *       {@code "cms.content.read"}. Matches the identical action only.</li>
 *   <li><strong>Trailing-suffix wildcard</strong> — one or more valid leading segments followed by
 *       a terminal {@code "*"}, e.g. {@code "cms.content.*"} (any verb) or {@code "cms.*"} (any
 *       resource and verb). The {@code "*"} matches all remaining trailing segments.</li>
 * </ul>
 *
 * <p>The {@code "*"} is only legal as the <em>last</em> segment, and it must be preceded by at least
 * one real leading segment. A bare {@code "*"} (a single terminal wildcard with no leading segment)
 * is rejected: it would otherwise match every action and amount to a blanket allow. A {@code "*"} in
 * any non-terminal position, a blank value, an empty segment, or a non-wildcard segment that violates
 * the {@link ActionRef} grammar are all rejected by the compact constructor.
 *
 * <p><strong>Segment count is bounded by {@link ActionRef}'s fixed 3-segment shape.</strong> Every
 * {@link ActionRef} has exactly {@code subsystem.resource.verb} — three segments — so a pattern with
 * a different total segment count can never match any action, regardless of what
 * {@link #matches(ActionRef)} does with it: an exact (non-wildcard) pattern with a segment count
 * other than 3, or a wildcard pattern with more than 2 leading concrete segments (i.e. a total
 * segment count over 3, such as {@code "a.b.c.*"}), would silently gate nothing — a no-op policy
 * grant or assurance rule that never fires. The compact constructor rejects both shapes outright
 * rather than accepting a pattern that can never match, so a malformed policy/assurance action
 * pattern fails fast at construction time instead of silently under-gating at evaluation time.
 *
 * @param value the canonical-or-wildcard pattern string; validated by the compact constructor
 */
public record ActionPattern(String value) {

    /** Grammar for a single non-wildcard segment, identical to the {@link ActionRef} segment grammar. */
    private static final Pattern SEGMENT = Pattern.compile("^[a-z][a-z0-9]*$");

    /** The wildcard token, legal only as the terminal segment. */
    private static final String WILDCARD = "*";

    /**
     * The fixed segment count of every {@link ActionRef} ({@code subsystem.resource.verb}). An exact
     * pattern must have exactly this many segments; a wildcard pattern must have at most this many
     * (i.e. at most {@code ACTION_SEGMENT_COUNT - 1} leading concrete segments before the terminal
     * {@code "*"}) — see the class javadoc's "Segment count is bounded" section.
     */
    private static final int ACTION_SEGMENT_COUNT = 3;

    /**
     * Compact constructor — validates the pattern shape and segment grammar.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank, has an empty segment, is a bare
     *                                  {@code "*"} (terminal wildcard with no leading segment),
     *                                  contains a {@code "*"} in a non-terminal position, has a
     *                                  non-wildcard segment that violates the grammar, is an exact
     *                                  pattern whose segment count is not exactly
     *                                  {@value #ACTION_SEGMENT_COUNT}, or is a wildcard pattern with
     *                                  more than {@code ACTION_SEGMENT_COUNT - 1} leading segments —
     *                                  any of which could never match an {@link ActionRef}
     */
    public ActionPattern {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        String[] segments = value.split("\\.", -1);
        int last = segments.length - 1;
        // A bare "*" (one terminal wildcard segment, no leading segments) would match every action —
        // a blanket allow. Require at least one real leading segment before a terminal wildcard.
        if (last == 0 && WILDCARD.equals(segments[0])) {
            throw new IllegalArgumentException(
                    "bare wildcard \"*\" is not allowed; a wildcard requires at least one leading segment"
                            + " (e.g. \"a.*\" or \"a.b.*\")");
        }
        boolean isWildcardPattern = WILDCARD.equals(segments[last]);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (WILDCARD.equals(segment)) {
                if (i != last) {
                    throw new IllegalArgumentException(
                            "wildcard '*' is only allowed as the final segment but was at position " + i + " in: \""
                                    + value + "\"");
                }
            } else if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("pattern segment must match " + SEGMENT.pattern()
                        + " (or be a trailing '*') but was: \"" + segment + "\" in: \"" + value + "\"");
            }
        }
        // Every ActionRef has exactly ACTION_SEGMENT_COUNT segments, so a pattern whose segment count
        // can never line up with that — an exact pattern of the wrong length, or a wildcard with more
        // than ACTION_SEGMENT_COUNT - 1 leading segments — could never match anything. Reject it here
        // rather than silently accepting a policy/assurance rule that never fires (see class javadoc).
        if (isWildcardPattern) {
            if (segments.length > ACTION_SEGMENT_COUNT) {
                throw new IllegalArgumentException("wildcard pattern has too many leading segments (" + (last)
                        + "); an ActionRef has only " + ACTION_SEGMENT_COUNT + " segments, so this pattern could"
                        + " never match anything: \"" + value + "\"");
            }
        } else if (segments.length != ACTION_SEGMENT_COUNT) {
            throw new IllegalArgumentException("exact pattern must have exactly " + ACTION_SEGMENT_COUNT
                    + " segments (subsystem.resource.verb) but had " + segments.length
                    + "; this pattern could never match an ActionRef: \"" + value + "\"");
        }
    }

    /**
     * Reports whether this pattern is a trailing-suffix wildcard (its final segment is {@code "*"})
     * rather than an exact canonical action.
     *
     * <p>Provided so callers (e.g. the policy sources that validate patterns against the
     * {@link ActionRegistry}) can branch on exact-vs-wildcard without re-parsing the value.
     *
     * @return {@code true} if the final dot-separated segment is the wildcard token {@code "*"};
     *     {@code false} for an exact canonical pattern
     */
    public boolean isWildcard() {
        int lastDot = value.lastIndexOf('.');
        String lastSegment = lastDot < 0 ? value : value.substring(lastDot + 1);
        return WILDCARD.equals(lastSegment);
    }

    /**
     * Tests whether this pattern matches the given action.
     *
     * <p>The pattern's segments are compared positionally against the action's
     * {@code subsystem}/{@code resource}/{@code verb}. A terminal {@code "*"} matches every
     * remaining trailing action segment; non-wildcard segments must be equal. A pattern with more
     * segments than the action's three never matches.
     *
     * @param action the action to test; must not be {@code null}
     * @return {@code true} if this pattern matches {@code action}, {@code false} otherwise
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public boolean matches(ActionRef action) {
        Objects.requireNonNull(action, "action");
        String[] patternSegments = value.split("\\.", -1);
        String[] actionSegments = {action.subsystem(), action.resource(), action.verb()};
        if (patternSegments.length > actionSegments.length) {
            return false;
        }
        for (int i = 0; i < patternSegments.length; i++) {
            String patternSegment = patternSegments[i];
            if (WILDCARD.equals(patternSegment)) {
                // terminal wildcard absorbs this and all remaining action segments
                return true;
            }
            if (!patternSegment.equals(actionSegments[i])) {
                return false;
            }
        }
        // every pattern segment matched exactly; lengths must be equal for an exact match
        return patternSegments.length == actionSegments.length;
    }
}
