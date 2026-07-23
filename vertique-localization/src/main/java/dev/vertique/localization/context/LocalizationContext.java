// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Request-scoped, immutable typed value carrying the locale, time zone, and optional
 * formatting overrides (currency, calendar, numbering system) for a unit of work.
 *
 * <p>Flows with a request across in-process service calls and durable async boundaries via the
 * context-propagation substrate. The companion adapters are shipped and registered as
 * {@code @IntoSet} in
 * {@link dev.vertique.localization.LocalizationModule}: an identity
 * {@link LocalizationContextServiceDispatchEncoder}/{@link LocalizationContextServiceDispatchDecoder}
 * pair for in-process dispatch, and {@link LocalizationContextDurableEncoder}/
 * {@link LocalizationContextDurableDecoder} carrying the {@link LocalizationDurableNamespace#NAMESPACE
 * localization} namespace through durable boundaries.
 *
 * <h2>Source-field provenance (low-cardinality, diagnostic)</h2>
 *
 * <p>{@code localeSource} and {@code zoneSource} are independent diagnostic fields — a
 * context can legitimately have its locale from {@code Accept-Language} and its zone from
 * configuration in the same binding. Framework-produced values:
 *
 * <ul>
 *   <li>{@code localeSource}: {@code rest-accept-language}, {@code default-locale},
 *       {@code persisted-metadata}, {@code unspecified}.</li>
 *   <li>{@code zoneSource}: {@code default-zone}, {@code persisted-metadata},
 *       {@code unspecified}.</li>
 * </ul>
 *
 * <p>Applications MAY add additional values for application-produced contexts (e.g.
 * {@code user-profile}). The lists above are the framework's vocabulary, not a constraint
 * on consumers.
 *
 * <h2>Optional sub-fields</h2>
 *
 * <p>{@link #currency}, {@link #calendar}, and {@link #numberingSystem} are optional
 * overrides. When absent, formatting code derives values from the {@link Locale}'s BCP 47
 * extension subtags or from configuration defaults. When present, they take precedence over
 * both subtags and configuration.
 *
 * @param locale            the request locale (never {@code null})
 * @param zone              the request time zone (never {@code null}; never derived from
 *                          {@link java.util.TimeZone#getDefault()} for framework-produced
 *                          contexts — see PRD FR-LOC-203)
 * @param currency          optional ISO 4217 currency override (e.g. {@code "EUR"})
 * @param calendar          optional BCP 47 {@code -u-ca} subtag override (e.g.
 *                          {@code "gregory"})
 * @param numberingSystem   optional BCP 47 {@code -u-nu} subtag override (e.g.
 *                          {@code "latn"})
 * @param localeSource      low-cardinality diagnostic naming the source of {@link #locale};
 *                          {@code null}/blank values normalize to {@code "unspecified"}
 * @param zoneSource        low-cardinality diagnostic naming the source of {@link #zone};
 *                          {@code null}/blank values normalize to {@code "unspecified"}
 */
@DispatchContextValue
public record LocalizationContext(
        Locale locale,
        ZoneId zone,
        Optional<String> currency,
        Optional<String> calendar,
        Optional<String> numberingSystem,
        String localeSource,
        String zoneSource)
        implements ContextValue {

    /**
     * Compact constructor enforcing FR-LOC-200 invariants:
     * <ul>
     *   <li>Reject {@code null} locale and {@code null} zone with {@link NullPointerException}.</li>
     *   <li>Normalize {@code null} {@link Optional} sub-fields to {@link Optional#empty()}.</li>
     *   <li>Normalize {@code null} or blank {@link #localeSource} / {@link #zoneSource} to
     *       {@code "unspecified"}.</li>
     * </ul>
     */
    public LocalizationContext {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(zone, "zone");
        currency = currency == null ? Optional.empty() : currency;
        calendar = calendar == null ? Optional.empty() : calendar;
        numberingSystem = numberingSystem == null ? Optional.empty() : numberingSystem;
        localeSource = (localeSource == null || localeSource.isBlank()) ? "unspecified" : localeSource;
        zoneSource = (zoneSource == null || zoneSource.isBlank()) ? "unspecified" : zoneSource;
    }

    /**
     * Returns the BCP 47 language tag for {@link #locale} (e.g. {@code "sv-FI"}).
     *
     * @return the locale's language tag
     */
    public String languageTag() {
        return locale.toLanguageTag();
    }
}
