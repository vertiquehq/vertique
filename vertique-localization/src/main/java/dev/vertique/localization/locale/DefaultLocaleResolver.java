// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import dev.vertique.localization.config.LocalizationConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Default implementation of {@link LocaleResolver} backed by the localization configuration.
 *
 * <p>The supported locales and default locale are extracted from {@link LocalizationConfig} at
 * construction time — both are already validated as non-empty / non-null by the config parser.
 *
 * <p>Two resolution paths are provided:
 * <ul>
 *   <li><b>Language-range path</b> — uses {@code Locale.LanguageRange.parse(String)} and
 *       {@code Locale.lookup(List, Collection)} per RFC 4647.</li>
 *   <li><b>Language-tag path</b> — normalises whitespace-separated RFC 5646 tags into a
 *       comma-separated form and then reuses the range parse/lookup mechanism.</li>
 * </ul>
 *
 * <p>The {@link LocaleResolver} interface provides {@code default} forwarder methods for
 * {@code resolveX(value)} and {@code requireX(value)}, so this class only needs to implement
 * the two explicit-fallback overloads.
 *
 * @see LocaleResolver
 * @see LocaleResolutionException
 * @see LocalizationConfig
 */
@Singleton
public class DefaultLocaleResolver implements LocaleResolver {

    /**
     * Matches one or more separator characters (whitespace OR comma). Used to normalise
     * RFC 5646 language-tag list inputs that mix whitespace and comma separators (e.g.
     * the Accept-Language-style {@code "de-DE, fr, en"}) into the comma-only form
     * {@code Locale.LanguageRange.parse} expects.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");

    private final Locale defaultLocale;
    private final List<Locale> supportedLocales;

    /**
     * Constructs a new resolver from the given localization configuration.
     *
     * @param config the localization configuration; must not be {@code null}; both
     *               {@code defaultLocale} and {@code supportedLocales} must already be validated
     *               and non-null/non-empty by the config parser
     */
    @Inject
    public DefaultLocaleResolver(LocalizationConfig config) {
        this.defaultLocale = config.defaultLocale();
        // supportedLocales is already an immutable List.copyOf() from the record's compact ctor
        this.supportedLocales = config.supportedLocales();
    }

    // --- Language-range path (RFC 4647) ---

    /**
     * {@inheritDoc}
     *
     * <p>Implementation detail: catches only {@link IllegalArgumentException} from
     * {@code Locale.LanguageRange.parse(String)} — this is the sole documented failure mode for
     * a non-null input. {@code NullPointerException} from {@code parse(null)} is never reached
     * because null is checked before calling {@code parse}.
     */
    @Override
    public Locale resolveLanguageRange(String value, Locale fallbackLocale) {
        if (value == null || value.isBlank()) {
            return fallbackOrThrow(
                    fallbackLocale, () -> LocaleResolutionException.missingLanguageRange(value, supportedLocales));
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(value);
        } catch (IllegalArgumentException parseEx) {
            return fallbackOrThrow(
                    fallbackLocale,
                    () -> LocaleResolutionException.malformedLanguageRange(value, supportedLocales, parseEx));
        }
        return matchOrFallback(
                ranges,
                fallbackLocale,
                () -> LocaleResolutionException.unsupportedLanguageRange(value, supportedLocales));
    }

    // --- Language-tag path (RFC 5646) ---

    /**
     * {@inheritDoc}
     *
     * <p>Whitespace normalisation: after a null/blank check, the input is trimmed and internal
     * runs of whitespace are replaced with a single comma so the result is a valid RFC 4647
     * priority list that can be passed to {@code Locale.LanguageRange.parse}. The original raw
     * {@code value} string is used in all {@link LocaleResolutionException} throw sites — the
     * normalised form is never surfaced to callers.
     */
    @Override
    public Locale resolveLanguageTags(String value, Locale fallbackLocale) {
        if (value == null || value.isBlank()) {
            return fallbackOrThrow(
                    fallbackLocale, () -> LocaleResolutionException.missingLanguageTag(value, supportedLocales));
        }
        // Collapse runs of whitespace and/or commas into a single comma so mixed-separator
        // inputs like "de-DE, fr, en" produce "de-DE,fr,en" instead of "de-DE,,fr,,en"
        // (which Locale.LanguageRange.parse rejects as an empty range token).
        String normalized = SEPARATORS.matcher(value).replaceAll(",");
        // Strip leading/trailing commas left by leading/trailing whitespace or commas in the input.
        int start = 0;
        int end = normalized.length();
        while (start < end && normalized.charAt(start) == ',') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == ',') {
            end--;
        }
        normalized = normalized.substring(start, end);
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(normalized);
        } catch (IllegalArgumentException parseEx) {
            // value() receives the RAW input, not the comma-substituted form
            return fallbackOrThrow(
                    fallbackLocale,
                    () -> LocaleResolutionException.malformedLanguageTag(value, supportedLocales, parseEx));
        }
        return matchOrFallback(
                ranges,
                fallbackLocale,
                () -> LocaleResolutionException.unsupportedLanguageTag(value, supportedLocales));
    }

    // --- Shared decision helpers ---

    /**
     * Returns the fallback locale if non-null, otherwise throws the supplied exception.
     */
    private static Locale fallbackOrThrow(
            Locale fallbackLocale, Supplier<LocaleResolutionException> onMissingFallback) {
        if (fallbackLocale != null) {
            return fallbackLocale;
        }
        throw onMissingFallback.get();
    }

    /**
     * Looks up the parsed ranges against {@link #supportedLocales}; on a non-null match returns
     * the matched locale, otherwise delegates to {@link #fallbackOrThrow}. The exception supplier is
     * evaluated lazily, so no {@link LocaleResolutionException} is constructed on the success path
     * (the common case for a valid, supported header).
     */
    private Locale matchOrFallback(
            List<Locale.LanguageRange> ranges, Locale fallbackLocale, Supplier<LocaleResolutionException> onNoMatch) {
        Locale matched = Locale.lookup(ranges, supportedLocales);
        if (matched != null) {
            return matched;
        }
        return fallbackOrThrow(fallbackLocale, onNoMatch);
    }

    // --- Accessors ---

    /**
     * {@inheritDoc}
     *
     * <p>Returns the same immutable list that was stored from the configuration at construction
     * time. The same list instance is used in every {@link LocaleResolutionException} thrown by
     * this resolver.
     */
    @Override
    public List<Locale> supportedLocales() {
        return supportedLocales;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Locale defaultLocale() {
        return defaultLocale;
    }
}
