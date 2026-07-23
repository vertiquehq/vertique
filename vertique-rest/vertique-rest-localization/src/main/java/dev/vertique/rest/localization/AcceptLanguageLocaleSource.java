// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import dev.vertique.context.WarningThrottle;
import dev.vertique.localization.locale.LocaleResolutionException;
import dev.vertique.localization.locale.LocaleResolver;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Built-in {@link LocaleSource} that negotiates the HTTP {@code Accept-Language} header against the
 * configured supported locales.
 *
 * <p>Runs at a late {@link #PRIORITY} so application-contributed pre-auth sources (default priority
 * {@code 0}) take precedence over the browser header. Resolution uses the strict
 * {@link LocaleResolver#requireLanguageRange(String)} so the source can distinguish "header honored"
 * from "no usable header":
 *
 * <ul>
 *   <li>absent or blank header → {@link Optional#empty()} (defer), no log;</li>
 *   <li>valid supported header → {@code Optional.of(ResolvedLocale.of(locale, "rest-accept-language"))};</li>
 *   <li>malformed or unsupported header → {@link Optional#empty()} (defer) plus a WARN, throttled to
 *       at most once per {@link LocaleResolutionException.Reason} for this source instance.</li>
 * </ul>
 *
 * <p>The WARN throttle is a {@link WarningThrottle} keyed by {@link LocaleResolutionException.Reason},
 * held for the application-component lifetime of this {@code @Singleton}: each reason logs at most once.
 */
@Slf4j
@Singleton
public final class AcceptLanguageLocaleSource implements LocaleSource {

    /** Diagnostic source label written to {@code LocalizationContext.localeSource}. */
    public static final String SOURCE = "rest-accept-language";

    /**
     * Chain priority. Late on purpose: application pre-auth sources at the default priority {@code 0}
     * resolve before the browser-supplied {@code Accept-Language} header.
     */
    public static final int PRIORITY = 1000;

    private final LocaleResolver resolver;

    /** Once-per-{@link LocaleResolutionException.Reason} WARN throttle for this source instance. */
    private final WarningThrottle warnThrottle = new WarningThrottle();

    /**
     * Creates the source.
     *
     * @param resolver the locale resolver (never {@code null})
     */
    @Inject
    public AcceptLanguageLocaleSource(LocaleResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Optional<ResolvedLocale> resolve(RoutingContext rc) {
        String header = rc.request().getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (header == null || header.isBlank() || isWildcardOnly(header)) {
            // Absent, blank, or a bare "*" ("any language is acceptable", RFC 9110 §12.5.4): express
            // no preference and defer, so the interceptor falls back to the configured default locale.
            return Optional.empty();
        }
        try {
            Locale locale = resolver.requireLanguageRange(header);
            return Optional.of(ResolvedLocale.of(locale, SOURCE));
        } catch (LocaleResolutionException e) {
            warnThrottle.once(
                    e.reason().name(),
                    key -> log.warn(
                            "Accept-Language negotiation failed (reason={}, header~=\"{}\"); deferring to the next "
                                    + "locale source. Further failures with this reason are suppressed.",
                            e.reason(),
                            sanitizeForLog(header)));
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} when the header expresses only the wildcard range {@code *} (optionally
     * weighted), i.e. "any language is acceptable" with no concrete preference. Such headers defer to
     * the configured default rather than warning as unsupported. A malformed header returns
     * {@code false} so it flows into the normal (warned) resolution path.
     */
    private static boolean isWildcardOnly(String header) {
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            return !ranges.isEmpty() && ranges.stream().allMatch(range -> "*".equals(range.getRange()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Sanitizes an untrusted header for safe single-line logging: replaces control characters (CR/LF
     * and others, which could forge log lines) and bounds the length.
     *
     * @param header the raw header value
     * @return a control-character-free, length-bounded rendering safe to log
     */
    private static String sanitizeForLog(String header) {
        String cleaned = header.replaceAll("\\p{Cntrl}", "?");
        int max = 64;
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }
}
