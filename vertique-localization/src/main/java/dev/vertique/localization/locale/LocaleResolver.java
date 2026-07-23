// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import java.util.List;
import java.util.Locale;

/**
 * SPI for deterministic locale negotiation from RFC 4647 language ranges and RFC 5646 language
 * tag lists.
 *
 * <p>Two resolution paths are provided:
 * <ul>
 *   <li><b>Language-range path</b> ({@code resolveLanguageRange} / {@code requireLanguageRange})
 *       — accepts RFC 4647 {@code Accept-Language} strings such as
 *       {@code "en-ca,en;q=0.8,en-us;q=0.6,de-de;q=0.4,de;q=0.2"}.</li>
 *   <li><b>Language-tag path</b> ({@code resolveLanguageTags} / {@code requireLanguageTags})
 *       — accepts whitespace- or comma-separated RFC 5646 language tags such as
 *       {@code "de-DE fr en"}; whitespace runs are normalised to commas before matching.</li>
 * </ul>
 *
 * <p>Each path comes in three overload flavors:
 * <ol>
 *   <li><b>Default-fallback</b> ({@code resolveX(value)}) — delegates to
 *       {@code resolveX(value, defaultLocale())}; never throws for missing/malformed/unmatched
 *       input because the configured default locale is always available.</li>
 *   <li><b>Explicit-fallback</b> ({@code resolveX(value, fallbackLocale)}) — returns
 *       {@code fallbackLocale} when the input is missing, malformed, or matches no supported
 *       locale. Only throws when {@code fallbackLocale} is {@code null} and the input cannot be
 *       resolved.</li>
 *   <li><b>Strict</b> ({@code requireX(value)}) — delegates to
 *       {@code resolveX(value, null)}; throws {@link LocaleResolutionException} for any failure
 *       (missing, malformed, or unmatched input).</li>
 * </ol>
 *
 * <p>The default-fallback and strict overloads are provided as {@code default} methods on this
 * interface so implementations only need to override the explicit-fallback overloads.
 *
 * @see DefaultLocaleResolver
 * @see LocaleResolutionException
 */
public interface LocaleResolver {

    // --- Language-range path (RFC 4647) ---

    /**
     * Resolves the best-matching supported locale from an RFC 4647 language-range string,
     * using the configured {@link #defaultLocale()} as a fallback.
     *
     * <p>Delegates to {@link #resolveLanguageRange(String, Locale)} with
     * {@code fallbackLocale = defaultLocale()}.
     *
     * @param value an RFC 4647 language-range string (e.g. {@code "en-ca,en;q=0.8"}); may be
     *              {@code null} or blank — both cause the default locale to be returned
     * @return the best-matching supported locale, or the configured default locale if the input
     *         is missing, malformed, or matches no supported locale; never {@code null}
     */
    default Locale resolveLanguageRange(String value) {
        return resolveLanguageRange(value, defaultLocale());
    }

    /**
     * Resolves the best-matching supported locale from an RFC 4647 language-range string,
     * returning the given fallback locale when the input cannot be resolved.
     *
     * <p>Resolution steps:
     * <ol>
     *   <li>If {@code value} is {@code null} or blank: return {@code fallbackLocale} if non-null,
     *       else throw {@link LocaleResolutionException} with
     *       {@link LocaleResolutionException.Reason#MISSING_INPUT}.</li>
     *   <li>Try {@code Locale.LanguageRange.parse(value)}. On {@code IllegalArgumentException}:
     *       return {@code fallbackLocale} if non-null, else throw with
     *       {@link LocaleResolutionException.Reason#MALFORMED_LANGUAGE_RANGE} and the parse
     *       exception set as the cause.</li>
     *   <li>Call {@code Locale.lookup(ranges, supportedLocales())}. If a match is found, return
     *       it. If {@code null} (no match): return {@code fallbackLocale} if non-null, else throw
     *       with {@link LocaleResolutionException.Reason#UNSUPPORTED_LANGUAGE_RANGE}.</li>
     * </ol>
     *
     * @param value          an RFC 4647 language-range string; may be {@code null} or blank
     * @param fallbackLocale the locale to return when resolution fails; {@code null} means "throw"
     * @return the best-matching supported locale, or {@code fallbackLocale}; never {@code null}
     *         when {@code fallbackLocale} is non-null
     * @throws LocaleResolutionException if {@code fallbackLocale} is {@code null} and the input
     *                                   is missing, malformed, or matches no supported locale
     */
    Locale resolveLanguageRange(String value, Locale fallbackLocale);

    /**
     * Resolves the best-matching supported locale from an RFC 4647 language-range string,
     * throwing if the input cannot be resolved (strict mode — no fallback).
     *
     * <p>Delegates to {@link #resolveLanguageRange(String, Locale)} with
     * {@code fallbackLocale = null}.
     *
     * @param value an RFC 4647 language-range string; must not be {@code null}, blank, malformed,
     *              or unmatched against the supported locales
     * @return the best-matching supported locale; never {@code null}
     * @throws LocaleResolutionException if the input is missing, malformed, or matches no
     *                                   supported locale
     */
    default Locale requireLanguageRange(String value) {
        return resolveLanguageRange(value, null);
    }

    // --- Language-tag path (RFC 5646) ---

    /**
     * Resolves the best-matching supported locale from a whitespace- or comma-separated RFC 5646
     * language tag list, using the configured {@link #defaultLocale()} as a fallback.
     *
     * <p>Delegates to {@link #resolveLanguageTags(String, Locale)} with
     * {@code fallbackLocale = defaultLocale()}.
     *
     * @param value a space- or comma-separated RFC 5646 language tag list (e.g.
     *              {@code "de-DE fr en"}); may be {@code null} or blank — both cause the default
     *              locale to be returned
     * @return the best-matching supported locale, or the configured default locale if the input
     *         is missing, malformed, or matches no supported locale; never {@code null}
     */
    default Locale resolveLanguageTags(String value) {
        return resolveLanguageTags(value, defaultLocale());
    }

    /**
     * Resolves the best-matching supported locale from a whitespace- or comma-separated RFC 5646
     * language tag list, returning the given fallback locale when the input cannot be resolved.
     *
     * <p>Resolution steps:
     * <ol>
     *   <li>If {@code value} is {@code null} or blank: return {@code fallbackLocale} if non-null,
     *       else throw {@link LocaleResolutionException} with
     *       {@link LocaleResolutionException.Reason#MISSING_INPUT}.</li>
     *   <li>Normalise: trim the input, then replace runs of whitespace with a single comma.
     *       The original raw {@code value} is preserved separately for error reporting.</li>
     *   <li>Try {@code Locale.LanguageRange.parse(normalized)}. On {@code IllegalArgumentException}:
     *       return {@code fallbackLocale} if non-null, else throw with
     *       {@link LocaleResolutionException.Reason#MALFORMED_LANGUAGE_TAG} using the raw
     *       {@code value} (not the normalised form) and the parse exception as cause.</li>
     *   <li>Call {@code Locale.lookup(ranges, supportedLocales())}. If a match is found, return
     *       it. If {@code null} (no match): return {@code fallbackLocale} if non-null, else throw
     *       with {@link LocaleResolutionException.Reason#UNSUPPORTED_LANGUAGE_TAG} using the raw
     *       {@code value}.</li>
     * </ol>
     *
     * @param value          a space- or comma-separated RFC 5646 language tag list; may be
     *                       {@code null} or blank
     * @param fallbackLocale the locale to return when resolution fails; {@code null} means "throw"
     * @return the best-matching supported locale, or {@code fallbackLocale}; never {@code null}
     *         when {@code fallbackLocale} is non-null
     * @throws LocaleResolutionException if {@code fallbackLocale} is {@code null} and the input
     *                                   is missing, malformed, or matches no supported locale
     */
    Locale resolveLanguageTags(String value, Locale fallbackLocale);

    /**
     * Resolves the best-matching supported locale from a whitespace- or comma-separated RFC 5646
     * language tag list, throwing if the input cannot be resolved (strict mode — no fallback).
     *
     * <p>Delegates to {@link #resolveLanguageTags(String, Locale)} with
     * {@code fallbackLocale = null}.
     *
     * @param value a space- or comma-separated RFC 5646 language tag list; must not be
     *              {@code null}, blank, malformed, or unmatched against the supported locales
     * @return the best-matching supported locale; never {@code null}
     * @throws LocaleResolutionException if the input is missing, malformed, or matches no
     *                                   supported locale
     */
    default Locale requireLanguageTags(String value) {
        return resolveLanguageTags(value, null);
    }

    // --- Accessors ---

    /**
     * Returns the ordered list of locales that this resolver will match against.
     *
     * <p>The list is immutable and preserves the order specified in the localization configuration.
     * Every throw site within this resolver uses this same list as the {@code supportedLocales}
     * field in any {@link LocaleResolutionException} it constructs.
     *
     * @return an immutable, non-empty list of supported locales; never {@code null}
     */
    List<Locale> supportedLocales();

    /**
     * Returns the locale used as a fallback by the no-argument {@link #resolveLanguageRange(String)}
     * and {@link #resolveLanguageTags(String)} overloads.
     *
     * @return the configured default locale; never {@code null}
     */
    Locale defaultLocale();
}
