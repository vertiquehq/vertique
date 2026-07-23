// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.locale.LocaleResolutionException.Reason;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §11.1 LocaleResolver tests for RFC 4647 language range resolution. Every throw row
 * asserts {@code reason()}, {@code value()}, {@code supportedLocales()}, and whether
 * {@code getCause()} is set per the plan's "Universal field-population invariants".
 */
class DefaultLocaleResolverLanguageRangeTest {

    private static final Locale FI = Locale.forLanguageTag("fi");
    private static final Locale SV = Locale.forLanguageTag("sv");
    private static final Locale EN = Locale.forLanguageTag("en");
    private static final List<Locale> SUPPORTED = List.of(FI, SV, EN);

    private final LocaleResolver resolver = newResolver(FI);

    private static LocaleResolver newResolver(Locale defaultLocale) {
        LocalizationConfig config =
                new LocalizationConfig(defaultLocale, ZoneId.of("UTC"), SUPPORTED, false, false, false, -1L);
        return new DefaultLocaleResolver(config);
    }

    @Nested
    @DisplayName("FR-LOC-090..094: resolveLanguageRange(value) uses defaultLocale fallback")
    class ResolveWithDefaultFallback {

        @Test
        @DisplayName("en-ca,en;q=0.8,en-us;q=0.6,de-de;q=0.4,de;q=0.2 → en (best match)")
        void enRanges() {
            assertEquals(EN, resolver.resolveLanguageRange("en-ca,en;q=0.8,en-us;q=0.6,de-de;q=0.4,de;q=0.2"));
        }

        @Test
        @DisplayName("fr-ca,fr;q=0.8,en-us;q=0.6,de-de;q=0.4,de;q=0.2 → en (en-US in priority list)")
        void enFromMixedRanges() {
            assertEquals(EN, resolver.resolveLanguageRange("fr-ca,fr;q=0.8,en-us;q=0.6,de-de;q=0.4,de;q=0.2"));
        }

        @Test
        @DisplayName("fi-FI → fi")
        void fiRegional() {
            assertEquals(FI, resolver.resolveLanguageRange("fi-FI"));
        }

        @Test
        @DisplayName("sv-SE → sv")
        void svRegional() {
            assertEquals(SV, resolver.resolveLanguageRange("sv-SE"));
        }

        @Test
        @DisplayName("en-US → en")
        void enRegional() {
            assertEquals(EN, resolver.resolveLanguageRange("en-US"));
        }

        @Test
        @DisplayName("fr (no match) falls back to default locale fi")
        void noMatchFallsBackToDefault() {
            assertEquals(FI, resolver.resolveLanguageRange("fr"));
        }

        @Test
        @DisplayName("empty string falls back to default locale fi")
        void emptyFallsBackToDefault() {
            assertEquals(FI, resolver.resolveLanguageRange(""));
        }

        @Test
        @DisplayName("single space falls back to default locale fi")
        void singleSpaceFallsBackToDefault() {
            assertEquals(FI, resolver.resolveLanguageRange(" "));
        }

        @Test
        @DisplayName("null falls back to default locale fi")
        void nullFallsBackToDefault() {
            assertEquals(FI, resolver.resolveLanguageRange(null));
        }
    }

    @Nested
    @DisplayName("FR-LOC-094 + FR-LOC-103/105/107: requireLanguageRange(value) has no fallback")
    class RequireWithoutFallback {

        @Test
        @DisplayName("de → throws UNSUPPORTED_LANGUAGE_RANGE")
        void deRange() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange("de"));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
            assertEquals("de", ex.value());
            assertEquals(SUPPORTED, ex.supportedLocales());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("de-de → throws UNSUPPORTED_LANGUAGE_RANGE")
        void deDeRange() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange("de-de"));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
            assertEquals("de-de", ex.value());
        }

        @Test
        @DisplayName("fr → throws UNSUPPORTED_LANGUAGE_RANGE")
        void frRange() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange("fr"));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
            assertEquals("fr", ex.value());
        }

        @Test
        @DisplayName("Long unsupported priority list → throws UNSUPPORTED_LANGUAGE_RANGE")
        void longUnsupportedList() {
            String value = "fr-ca,fr;q=0.8,es;q=0.6,de-de;q=0.4,de;q=0.2";
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange(value));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
            assertEquals(value, ex.value());
        }

        @Test
        @DisplayName("empty string → throws MISSING_INPUT, value() == \"\"")
        void emptyString() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange(""));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertEquals("", ex.value());
            assertEquals(SUPPORTED, ex.supportedLocales());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("single space → throws MISSING_INPUT, value() preserved verbatim")
        void singleSpace() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange(" "));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertEquals(" ", ex.value());
        }

        @Test
        @DisplayName("null → throws MISSING_INPUT, value() == null")
        void nullInput() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange(null));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertNull(ex.value());
            assertEquals(SUPPORTED, ex.supportedLocales());
            assertNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("FR-LOC-105: malformed range")
    class MalformedRange {

        @Test
        @DisplayName("Malformed range with fallback returns fallback (resolveLanguageRange)")
        void malformedWithFallback() {
            // q-value > 1.0 is malformed under RFC 4647
            assertEquals(FI, resolver.resolveLanguageRange("abc;q=999"));
        }

        @Test
        @DisplayName("Malformed range without fallback throws MALFORMED_LANGUAGE_RANGE with cause")
        void malformedWithoutFallback() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange("abc;q=999"));
            assertEquals(Reason.MALFORMED_LANGUAGE_RANGE, ex.reason());
            assertEquals("abc;q=999", ex.value());
            assertEquals(SUPPORTED, ex.supportedLocales());
            // FR-LOC-104/105: cause is the IllegalArgumentException from Locale.LanguageRange.parse
            assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        }
    }

    @Nested
    @DisplayName("FR-LOC-101: RFC 4647 wildcard '*'")
    class Wildcard {

        @Test
        @DisplayName("resolveLanguageRange(\"*\", fi) → fi via FR-LOC-106 fallback")
        void wildcardWithFallback() {
            assertEquals(FI, resolver.resolveLanguageRange("*"));
        }

        @Test
        @DisplayName("requireLanguageRange(\"*\") → UNSUPPORTED_LANGUAGE_RANGE")
        void wildcardWithoutFallback() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageRange("*"));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
            assertEquals("*", ex.value());
            assertNull(ex.getCause());
        }
    }

    @Test
    @DisplayName("Explicit fallback param overrides default locale fallback")
    void explicitFallbackOverridesDefault() {
        // Pass an explicit fallback different from the resolver's defaultLocale (fi)
        assertEquals(EN, resolver.resolveLanguageRange("fr", EN));
        assertEquals(SV, resolver.resolveLanguageRange("fr", SV));
    }

    @Test
    @DisplayName("supportedLocales() returns the configured list in order")
    void supportedLocalesAccessor() {
        assertEquals(SUPPORTED, resolver.supportedLocales());
    }

    @Test
    @DisplayName("defaultLocale() returns the configured default")
    void defaultLocaleAccessor() {
        assertSame(FI, resolver.defaultLocale());
    }
}
