// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization;

import dev.vertique.localization.locale.LocaleResolutionException;
import dev.vertique.localization.locale.LocaleResolver;
import dev.vertique.rest.localization.LocaleSource;
import dev.vertique.rest.localization.ResolvedLocale;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom {@link LocaleSource} that resolves the request locale from the {@code ?lang=} query
 * parameter.
 *
 * <p>This source runs at the default priority {@code 0}, which is numerically lower than the
 * built-in {@link dev.vertique.rest.localization.AcceptLanguageLocaleSource#PRIORITY} ({@code 1000}),
 * so it takes precedence over the {@code Accept-Language} header. It demonstrates C12 — a
 * pre-authentication source that overrides the browser-supplied header.
 *
 * <p>Resolution:
 * <ul>
 *   <li>If the {@code lang} query parameter is absent or blank, returns {@link Optional#empty()}
 *       to defer to the next source.</li>
 *   <li>If the parameter value matches a supported locale (via
 *       {@link LocaleResolver#requireLanguageTags}), returns
 *       {@code ResolvedLocale.of(locale, "query-param")}.</li>
 *   <li>If the value is present but not supported or malformed, catches
 *       {@link LocaleResolutionException} and defers (returns empty) so the chain continues.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class QueryParamLocaleSource implements LocaleSource {

    /** Diagnostic source label written to {@code LocalizationContext.localeSource}. */
    public static final String SOURCE = "query-param";

    /** Query parameter name used to supply the desired locale. */
    public static final String PARAM_NAME = "lang";

    private final LocaleResolver resolver;

    /**
     * Creates the query-param locale source.
     *
     * @param resolver the locale resolver used to validate and match the parameter value
     */
    @Inject
    public QueryParamLocaleSource(LocaleResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Resolves the locale from the {@code ?lang=} query parameter.
     *
     * @param rc the current routing context
     * @return the resolved locale with source {@code "query-param"}, or {@link Optional#empty()}
     *         when the parameter is absent, blank, unsupported, or malformed
     */
    @Override
    public Optional<ResolvedLocale> resolve(RoutingContext rc) {
        String lang = rc.request().getParam(PARAM_NAME);
        if (lang == null || lang.isBlank()) {
            return Optional.empty();
        }
        try {
            Locale locale = resolver.requireLanguageTags(lang);
            return Optional.of(ResolvedLocale.of(locale, SOURCE));
        } catch (LocaleResolutionException e) {
            log.debug("?lang={} could not be resolved (reason={}); deferring to next source", lang, e.reason());
            return Optional.empty();
        }
    }
}
