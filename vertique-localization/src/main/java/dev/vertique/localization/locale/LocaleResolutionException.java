// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import dev.vertique.localization.LocalizationException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Structured exception thrown when locale negotiation fails.
 *
 * <p>This exception is {@code final} — no further subclassing is permitted. The {@link Reason}
 * enum allows boundary modules (REST, durable-boundary error mappers) to switch on the failure
 * kind without catching by class hierarchy.
 *
 * <p>Carry semantics:
 * <ul>
 *   <li>{@link #reason()} — the specific failure category from the {@link Reason} enum.</li>
 *   <li>{@link #value()} — the raw input string as received; never normalized (null stays null,
 *       blank stays blank, whitespace-separated tag lists are NOT comma-substituted).</li>
 *   <li>{@link #supportedLocales()} — a snapshot of the resolver's full supported-locale list at
 *       throw time; always immutable, never null, never filtered.</li>
 *   <li>{@link #getCause()} — set only for parse-failure paths ({@link Reason#MALFORMED_LANGUAGE_RANGE}
 *       and {@link Reason#MALFORMED_LANGUAGE_TAG}); {@code null} for all other reasons.</li>
 * </ul>
 *
 * @see LocaleResolver
 * @see LocalizationException
 */
public final class LocaleResolutionException extends LocalizationException {

    /**
     * Categorizes the specific kind of locale resolution failure.
     *
     * <p>Boundary modules switch on this to decide HTTP status and {@code ProblemDetail}
     * extension fields without parsing the exception message string.
     */
    public enum Reason {
        /** The input was null or blank and no fallback locale was available. */
        MISSING_INPUT,

        /** The language-range string failed RFC 4647 {@code LanguageRange.parse()}. */
        MALFORMED_LANGUAGE_RANGE,

        /** The language-tag list string failed parsing after whitespace normalization. */
        MALFORMED_LANGUAGE_TAG,

        /** A well-formed language range matched none of the configured supported locales. */
        UNSUPPORTED_LANGUAGE_RANGE,

        /** A well-formed language tag list matched none of the configured supported locales. */
        UNSUPPORTED_LANGUAGE_TAG
    }

    private final Reason reason;
    private final String value;
    private final List<Locale> supportedLocales;

    /**
     * Constructs a new locale resolution exception.
     *
     * @param reason           the specific failure category; must not be {@code null}
     * @param message          the human-readable detail message (verbatim from FR-LOC requirements)
     * @param value            the raw input string as received — {@code null} and blank are preserved
     *                         verbatim; whitespace-separated tag lists are NOT comma-substituted
     * @param supportedLocales the resolver's full configured supported-locale snapshot; {@code null}
     *                         normalizes to an empty list; a defensive immutable copy is made
     * @param cause            the underlying parse exception; set only for
     *                         {@link Reason#MALFORMED_LANGUAGE_RANGE} and
     *                         {@link Reason#MALFORMED_LANGUAGE_TAG}; {@code null} otherwise
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public LocaleResolutionException(
            Reason reason, String message, String value, List<Locale> supportedLocales, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.value = value;
        this.supportedLocales = supportedLocales == null ? List.of() : List.copyOf(supportedLocales);
    }

    /**
     * Returns the specific failure category.
     *
     * @return the {@link Reason} enum constant; never {@code null}
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns the raw input string as received by the resolver.
     *
     * <p>Null and blank inputs are preserved verbatim. For the language-tag path, this is the
     * original whitespace-separated string, NOT the comma-substituted form used internally.
     *
     * @return the raw input; may be {@code null} if the caller passed {@code null}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the resolver's full configured supported-locale list at the time of the throw.
     *
     * <p>The list is always immutable, never filtered, and never re-ordered relative to the
     * resolver's configuration. The same instance is returned for every throw site within one
     * resolver.
     *
     * @return an immutable, non-null snapshot of the supported locales; may be empty if the
     *         constructor was called with a {@code null} or empty list
     */
    public List<Locale> supportedLocales() {
        return supportedLocales;
    }

    // --- Static factories — one per (Reason, message wording) pair ---

    /**
     * Builds a {@link Reason#MISSING_INPUT} exception for the language-range path (FR-LOC-103).
     *
     * @param value             the raw input string (preserved verbatim, including {@code null})
     * @param supportedLocales  the resolver's supported-locale snapshot
     * @return a new exception with {@code cause == null}
     */
    public static LocaleResolutionException missingLanguageRange(String value, List<Locale> supportedLocales) {
        return new LocaleResolutionException(
                Reason.MISSING_INPUT,
                "Language range must not be null without default locale",
                value,
                supportedLocales,
                null);
    }

    /**
     * Builds a {@link Reason#MISSING_INPUT} exception for the language-tag path (FR-LOC-115).
     */
    public static LocaleResolutionException missingLanguageTag(String value, List<Locale> supportedLocales) {
        return new LocaleResolutionException(
                Reason.MISSING_INPUT,
                "Language tag must not be null without default locale",
                value,
                supportedLocales,
                null);
    }

    /**
     * Builds a {@link Reason#MALFORMED_LANGUAGE_RANGE} exception (FR-LOC-105).
     */
    public static LocaleResolutionException malformedLanguageRange(
            String value, List<Locale> supportedLocales, Throwable cause) {
        return new LocaleResolutionException(
                Reason.MALFORMED_LANGUAGE_RANGE, "Unsupported language range " + value, value, supportedLocales, cause);
    }

    /**
     * Builds a {@link Reason#MALFORMED_LANGUAGE_TAG} exception (FR-LOC-105 tag path).
     */
    public static LocaleResolutionException malformedLanguageTag(
            String value, List<Locale> supportedLocales, Throwable cause) {
        return new LocaleResolutionException(
                Reason.MALFORMED_LANGUAGE_TAG, "Unsupported language tag " + value, value, supportedLocales, cause);
    }

    /**
     * Builds a {@link Reason#UNSUPPORTED_LANGUAGE_RANGE} exception (FR-LOC-107).
     */
    public static LocaleResolutionException unsupportedLanguageRange(String value, List<Locale> supportedLocales) {
        return new LocaleResolutionException(
                Reason.UNSUPPORTED_LANGUAGE_RANGE,
                "Unsupported language range " + value,
                value,
                supportedLocales,
                null);
    }

    /**
     * Builds a {@link Reason#UNSUPPORTED_LANGUAGE_TAG} exception (FR-LOC-116).
     */
    public static LocaleResolutionException unsupportedLanguageTag(String value, List<Locale> supportedLocales) {
        return new LocaleResolutionException(
                Reason.UNSUPPORTED_LANGUAGE_TAG, "Unsupported language tag " + value, value, supportedLocales, null);
    }
}
