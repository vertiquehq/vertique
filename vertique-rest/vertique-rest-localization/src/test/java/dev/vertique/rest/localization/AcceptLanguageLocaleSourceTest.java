// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.locale.DefaultLocaleResolver;
import dev.vertique.localization.locale.LocaleResolver;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AcceptLanguageLocaleSourceTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale SV = Locale.forLanguageTag("sv");
    private static final Locale FI = Locale.forLanguageTag("fi");

    private static AcceptLanguageLocaleSource newSource() {
        LocalizationConfig config =
                new LocalizationConfig(EN, ZoneId.of("UTC"), List.of(EN, SV, FI), false, false, false, -1L);
        LocaleResolver resolver = new DefaultLocaleResolver(config);
        return new AcceptLanguageLocaleSource(resolver);
    }

    private static RoutingContext rcWithHeader(String value) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn(value);
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.request()).thenReturn(request);
        return rc;
    }

    @Test
    void priorityIsLateSoAppSourcesWinByDefault() {
        assertEquals(AcceptLanguageLocaleSource.PRIORITY, newSource().priority());
        assertEquals(1000, AcceptLanguageLocaleSource.PRIORITY);
    }

    @Test
    void supportedHeaderResolvesToSupportedLocaleWithSourceLabel() {
        Optional<ResolvedLocale> resolved = newSource().resolve(rcWithHeader("sv-SE"));
        assertTrue(resolved.isPresent());
        assertEquals(SV, resolved.get().locale());
        assertEquals("rest-accept-language", resolved.get().source());
    }

    @Test
    void unsupportedHeaderDefers() {
        assertTrue(newSource().resolve(rcWithHeader("fr-FR")).isEmpty());
    }

    @Test
    void malformedHeaderDefers() {
        assertTrue(newSource().resolve(rcWithHeader(";")).isEmpty());
    }

    @Test
    void wildcardOnlyHeaderDefers() {
        AcceptLanguageLocaleSource source = newSource();
        // "*" means "any language acceptable" — it defers (and must not warn as unsupported).
        assertTrue(source.resolve(rcWithHeader("*")).isEmpty());
        assertTrue(source.resolve(rcWithHeader("*;q=0.9")).isEmpty());
    }

    @Test
    void concreteLanguageAlongsideWildcardStillResolves() {
        Optional<ResolvedLocale> resolved = newSource().resolve(rcWithHeader("sv, *;q=0.1"));
        assertTrue(resolved.isPresent());
        assertEquals(SV, resolved.get().locale());
    }

    @Test
    void absentOrBlankHeaderDefers() {
        AcceptLanguageLocaleSource source = newSource();
        assertTrue(source.resolve(rcWithHeader(null)).isEmpty());
        assertTrue(source.resolve(rcWithHeader("   ")).isEmpty());
    }
}
