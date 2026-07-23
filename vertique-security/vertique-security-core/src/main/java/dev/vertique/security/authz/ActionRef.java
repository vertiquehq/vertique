// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, canonical reference to a framework <em>action</em> — the unit a subsystem contributes
 * and a policy grants.
 *
 * <p>An action is identified by three lowercase segments: a {@code subsystem}, a {@code resource},
 * and a {@code verb}. The canonical string form is {@code "<subsystem>.<resource>.<verb>"} (see
 * {@link #value()}), with {@code '.'} as the only separator.
 *
 * <p><strong>Segment grammar (frozen).</strong> Each segment must match the regular expression
 * {@code ^[a-z][a-z0-9]*$} — a lowercase letter followed by zero or more lowercase letters or
 * digits. This rejects empty segments, uppercase characters, leading digits, and any character
 * outside {@code [a-z0-9]} (including the separator itself). The compact constructor enforces this
 * grammar; {@link #of(String, String, String)} and {@link #parse(String)} both funnel through it.
 *
 * @param subsystem the contributing subsystem (e.g. {@code "cms"}); must match the segment grammar
 * @param resource  the resource within the subsystem (e.g. {@code "content"}); must match the
 *                  segment grammar
 * @param verb      the action verb (e.g. {@code "read"}); must match the segment grammar
 */
public record ActionRef(String subsystem, String resource, String verb) {

    /** Frozen per-segment grammar: a lowercase letter followed by lowercase letters or digits. */
    private static final Pattern SEGMENT = Pattern.compile("^[a-z][a-z0-9]*$");

    /**
     * Compact constructor — validates each segment against the frozen grammar.
     *
     * @throws NullPointerException     if any segment is {@code null}
     * @throws IllegalArgumentException if any segment does not match {@code ^[a-z][a-z0-9]*$}
     */
    public ActionRef {
        validateSegment(subsystem, "subsystem");
        validateSegment(resource, "resource");
        validateSegment(verb, "verb");
    }

    /**
     * Validates a single segment against the frozen grammar.
     *
     * @param segment the segment value to validate
     * @param name    the segment's role name, used in the failure message
     * @throws NullPointerException     if {@code segment} is {@code null}
     * @throws IllegalArgumentException if {@code segment} does not match the grammar
     */
    private static void validateSegment(String segment, String name) {
        Objects.requireNonNull(segment, name);
        if (!SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException(name + " must match " + SEGMENT.pattern()
                    + " (lowercase, leading letter) but was: \"" + segment + "\"");
        }
    }

    /**
     * Returns the canonical string form of this action.
     *
     * @return {@code "<subsystem>.<resource>.<verb>"}
     */
    public String value() {
        return subsystem + "." + resource + "." + verb;
    }

    /**
     * Creates an {@code ActionRef} from its three segments.
     *
     * @param subsystem the contributing subsystem; must match the segment grammar
     * @param resource  the resource within the subsystem; must match the segment grammar
     * @param verb      the action verb; must match the segment grammar
     * @return a validated {@code ActionRef}
     * @throws NullPointerException     if any segment is {@code null}
     * @throws IllegalArgumentException if any segment does not match the grammar
     */
    public static ActionRef of(String subsystem, String resource, String verb) {
        return new ActionRef(subsystem, resource, verb);
    }

    /**
     * Parses a canonical action string of the form {@code "<subsystem>.<resource>.<verb>"}.
     *
     * <p>The input must contain exactly three {@code '.'}-separated segments, each matching the
     * frozen grammar. Blank input, the wrong number of segments, uppercase characters, leading
     * digits, and non-{@code '.'} separators are all rejected.
     *
     * @param canonical the canonical action string
     * @return the parsed {@code ActionRef}
     * @throws NullPointerException     if {@code canonical} is {@code null}
     * @throws IllegalArgumentException if {@code canonical} is blank, has other than three segments,
     *                                  or any segment violates the grammar
     */
    public static ActionRef parse(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.isBlank()) {
            throw new IllegalArgumentException("canonical action must not be blank");
        }
        // limit -1 keeps trailing empty segments so "a.b." and "a..b" are rejected by segment count
        // or by the grammar rather than being silently trimmed.
        String[] segments = canonical.split("\\.", -1);
        if (segments.length != 3) {
            throw new IllegalArgumentException(
                    "canonical action must have exactly 3 dot-separated segments but was: \"" + canonical + "\"");
        }
        return of(segments[0], segments[1], segments[2]);
    }
}
