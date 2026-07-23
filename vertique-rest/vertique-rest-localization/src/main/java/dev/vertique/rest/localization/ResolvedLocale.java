// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import java.util.Locale;
import java.util.Objects;

/**
 * The locale chosen by a {@link LocaleSource}, paired with the low-cardinality source label that the
 * interceptor writes to {@code LocalizationContext.localeSource} for diagnostics.
 *
 * @param locale the resolved locale (never {@code null})
 * @param source a low-cardinality diagnostic label (e.g. {@code "rest-accept-language"}); {@code null}
 *               or blank normalizes to {@code "unspecified"}
 */
public record ResolvedLocale(Locale locale, String source) {

    /**
     * Compact constructor: rejects a {@code null} locale and normalizes a {@code null}/blank source
     * to {@code "unspecified"}.
     */
    public ResolvedLocale {
        Objects.requireNonNull(locale, "locale");
        source = (source == null || source.isBlank()) ? "unspecified" : source;
    }

    /**
     * Factory mirroring the canonical constructor.
     *
     * @param locale the resolved locale (never {@code null})
     * @param source the source label
     * @return a new {@link ResolvedLocale}
     */
    public static ResolvedLocale of(Locale locale, String source) {
        return new ResolvedLocale(locale, source);
    }
}
