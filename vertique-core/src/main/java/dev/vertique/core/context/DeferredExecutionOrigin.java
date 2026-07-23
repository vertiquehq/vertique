// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;

/**
 * Provenance marker proving that a service dispatch originates from a deferred-execution boundary
 * (a durable delayed job, a cron trigger, or an outbox relay) rather than from an ordinary inline
 * dispatch that merely happens to carry no security context.
 *
 * <p>A deferred-execution boundary binds this value into its FQCN-keyed dispatch-context map so that
 * the receive-side identity reconstruction initializer can, on a context-empty dispatch, distinguish
 * <em>proven</em> deferred execution — which is allowed to mint a SYSTEM security context — from an
 * ordinary context-empty dispatch, which must fail closed and bind nothing. Absent this marker the
 * initializer has no evidence that a SYSTEM mint is warranted.
 *
 * <h2>Construction paths</h2>
 *
 * <p>The {@code reference} component flows into the minted SYSTEM security context's {@code
 * system.reason} attribute and onward into the audit trail. Because a deferred boundary identifier
 * can be influenced by attacker-controlled input (a job type, an event type), {@code reference} is
 * bounded to {@value #MAX_REFERENCE_LENGTH} characters and must contain no control characters, per
 * ADR-0165's security invariant. Two construction paths enforce this:
 *
 * <ul>
 *   <li>The <strong>compact constructor</strong> (direct {@code new}) is <em>strict</em>: it is the
 *       defense-in-depth guard and rejects a blank, oversized, or control-character-bearing {@code
 *       reference} with an exception.
 *   <li>The {@link #of(String, String)} <strong>factory</strong> is the <em>boundary-facing safe
 *       path</em>: it sanitizes rather than rejects (strips control characters, truncates to the
 *       length bound, and falls back to {@code kind} for a blank result), so a deferred boundary
 *       cannot throw synchronously out of its async dispatch path over a malformed reference.
 * </ul>
 *
 * @param kind      provenance category proving the dispatch is deferred execution; non-null,
 *                  non-blank, at most {@value #MAX_REFERENCE_LENGTH} characters, and free of control
 *                  characters (same bound as {@code reference}, below). One of {@code "delayed-job"},
 *                  {@code "cron"}, or {@code "outbox-relay"}.
 * @param reference the specific job-type, cron-name, or event-type that triggered the deferred
 *                  execution; non-null, non-blank, at most {@value #MAX_REFERENCE_LENGTH}
 *                  characters, and free of control characters. Becomes the reason attributed to the
 *                  minted SYSTEM security context and flows into the audit trail.
 */
public record DeferredExecutionOrigin(String kind, String reference) implements ContextValue {

    /**
     * Maximum permitted length of {@code kind} and {@code reference}, bounding what flows into
     * audit/system.reason.
     */
    private static final int MAX_REFERENCE_LENGTH = 256;

    /**
     * Compact constructor — the strict defense-in-depth guard for direct {@code new}. Validates that
     * both {@code kind} and {@code reference} are non-null, non-blank, at most
     * {@value #MAX_REFERENCE_LENGTH} characters, and contain no control character — where "control
     * character" means a code point whose Unicode general category is {@code CONTROL} (the C0 range
     * including tab/CR/LF, the C1 range including NEL U+0085, and DEL U+007F), {@code LINE_SEPARATOR}
     * (U+2028), or {@code PARAGRAPH_SEPARATOR} (U+2029). For the boundary-facing sanitizing path use
     * {@link #of(String, String)} instead.
     *
     * @throws NullPointerException     if {@code kind} or {@code reference} is {@code null}
     * @throws IllegalArgumentException if {@code kind} or {@code reference} is blank, exceeds
     *                                  {@value #MAX_REFERENCE_LENGTH} characters, or contains a
     *                                  control, line-separator, or paragraph-separator character
     */
    public DeferredExecutionOrigin {
        Objects.requireNonNull(kind, "kind");
        validateBounded(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        validateBounded(reference, "reference");
    }

    /**
     * Boundary-facing safe factory — never throws for a {@code kind} whose sanitized form is
     * non-blank. Sanitizes both {@code kind} and {@code reference} rather than rejecting them, so a
     * deferred-execution boundary cannot throw synchronously out of its async dispatch path over a
     * malformed identifier: control/separator characters (per the compact constructor's definition
     * above) are stripped and the result is truncated to {@value #MAX_REFERENCE_LENGTH} characters
     * without splitting a surrogate pair. A blank sanitized {@code reference} falls back to the
     * sanitized {@code kind}.
     *
     * @param kind      provenance category proving the dispatch is deferred execution; non-null; may
     *                  be oversized or contain control/separator characters, which are sanitized away
     * @param reference the raw, possibly attacker-influenced boundary identifier to sanitize; may be
     *                  {@code null}, blank, oversized, or contain control/separator characters
     * @return a {@code DeferredExecutionOrigin} whose {@code kind} and {@code reference} are both
     *     sanitized and bounded
     * @throws NullPointerException     if {@code kind} is {@code null}
     * @throws IllegalArgumentException if {@code kind} sanitizes to a blank string
     */
    public static DeferredExecutionOrigin of(String kind, String reference) {
        Objects.requireNonNull(kind, "kind");
        String sanitizedKind = sanitize(kind);
        String sanitizedReference = reference == null ? "" : sanitize(reference);
        if (sanitizedReference.isBlank()) {
            sanitizedReference = sanitizedKind;
        }
        return new DeferredExecutionOrigin(sanitizedKind, sanitizedReference);
    }

    /**
     * Strips every code point in the {@code CONTROL}, {@code LINE_SEPARATOR}, or
     * {@code PARAGRAPH_SEPARATOR} Unicode general category from {@code value} and truncates the
     * result to at most {@value #MAX_REFERENCE_LENGTH} {@code char}s, never splitting a surrogate
     * pair (a whole code point is appended only while doing so keeps the running length within the
     * bound).
     *
     * @param value the raw string to sanitize
     * @return the sanitized, length-bounded, surrogate-pair-safe result
     */
    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(Math.min(value.length(), MAX_REFERENCE_LENGTH));
        value.codePoints()
                .filter(cp -> !isControlOrSeparator(cp))
                .takeWhile(cp -> builder.length() + Character.charCount(cp) <= MAX_REFERENCE_LENGTH)
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    /**
     * Validates that {@code value} is non-blank, at most {@value #MAX_REFERENCE_LENGTH} characters,
     * and free of control/separator characters, naming {@code field} in any thrown exception.
     *
     * @param value the already non-null string to validate
     * @param field the field name to report in the exception message
     * @throws IllegalArgumentException if {@code value} fails any of the checks above
     */
    private static void validateBounded(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_REFERENCE_LENGTH + " characters");
        }
        if (value.codePoints().anyMatch(DeferredExecutionOrigin::isControlOrSeparator)) {
            throw new IllegalArgumentException(
                    field + " must not contain control, line-separator, or paragraph-separator characters");
        }
    }

    /**
     * Reports whether {@code codePoint}'s Unicode general category is {@code CONTROL},
     * {@code LINE_SEPARATOR}, or {@code PARAGRAPH_SEPARATOR}.
     *
     * @param codePoint the code point to classify
     * @return {@code true} if the code point is a control character or a line/paragraph separator
     */
    private static boolean isControlOrSeparator(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
