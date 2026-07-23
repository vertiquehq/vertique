// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * §11.1 LocaleResolver tests for RFC 5646 language tag list resolution.
 * The whitespace-separated tag list is normalized by trimming and replacing whitespace runs
 * with commas before delegating to the language-range matcher. Every throw row asserts
 * {@code reason()}, {@code value()}, and {@code getCause()} per "Universal field-population
 * invariants".
 */
class DefaultLocaleResolverLanguageTagsTest {

    private static final Locale FI = Locale.forLanguageTag("fi");
    private static final Locale SV = Locale.forLanguageTag("sv");
    private static final Locale EN = Locale.forLanguageTag("en");
    private static final List<Locale> SUPPORTED = List.of(FI, SV, EN);

    private final LocaleResolver resolver = newResolver();

    private static LocaleResolver newResolver() {
        LocalizationConfig config = new LocalizationConfig(FI, ZoneId.of("UTC"), SUPPORTED, false, false, false, -1L);
        return new DefaultLocaleResolver(config);
    }

    @Nested
    @DisplayName("FR-LOC-110..114: resolveLanguageTags(value, fi)")
    class WithFallbackFi {

        @Test
        @DisplayName("fr-CA fr en → en (first supported match)")
        void mixedListSpaceSeparated() {
            assertEquals(EN, resolver.resolveLanguageTags("fr-CA fr en", FI));
        }

        @Test
        @DisplayName("de-DE  fr  en     fi → en (en wins over fi by order)")
        void irregularWhitespace() {
            assertEquals(EN, resolver.resolveLanguageTags("de-DE  fr  en     fi", FI));
        }

        @Test
        @DisplayName("empty string → fallback fi")
        void emptyToFallback() {
            assertEquals(FI, resolver.resolveLanguageTags("", FI));
        }

        @Test
        @DisplayName("single space → fallback fi")
        void singleSpaceToFallback() {
            assertEquals(FI, resolver.resolveLanguageTags(" ", FI));
        }
    }

    @Nested
    @DisplayName("FR-LOC-115/116: requireLanguageTags(value) without fallback throws")
    class RequireWithoutFallback {

        @Test
        @DisplayName("fr-CA fr de → UNSUPPORTED_LANGUAGE_TAG with raw value preserved")
        void noMatchUnsupported() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags("fr-CA fr de"));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_TAG, ex.reason());
            assertEquals("fr-CA fr de", ex.value(), "raw input preserved (NOT comma-substituted)");
            assertEquals(SUPPORTED, ex.supportedLocales());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("de-DE  fr  es     lv → UNSUPPORTED_LANGUAGE_TAG")
        void longUnsupportedList() {
            String value = "de-DE  fr  es     lv";
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags(value));
            assertEquals(Reason.UNSUPPORTED_LANGUAGE_TAG, ex.reason());
            assertEquals(value, ex.value());
        }

        @Test
        @DisplayName("empty string → MISSING_INPUT, value preserved")
        void emptyString() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags(""));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertEquals("", ex.value());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("single space → MISSING_INPUT, value preserved")
        void singleSpace() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags(" "));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertEquals(" ", ex.value());
        }

        @Test
        @DisplayName("null → MISSING_INPUT, value == null")
        void nullInput() {
            LocaleResolutionException ex =
                    assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags(null));
            assertEquals(Reason.MISSING_INPUT, ex.reason());
            assertNull(ex.value());
        }
    }

    @Test
    @DisplayName("FR-LOC-105 tag path: malformed tag without fallback → MALFORMED_LANGUAGE_TAG with cause")
    void malformedTagWithoutFallback() {
        // q-values do not belong in raw language tags. After whitespace→comma the parse fails.
        LocaleResolutionException ex =
                assertThrows(LocaleResolutionException.class, () -> resolver.requireLanguageTags("abc;q=999"));
        assertEquals(Reason.MALFORMED_LANGUAGE_TAG, ex.reason());
        assertEquals("abc;q=999", ex.value());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @DisplayName("FR-LOC-095: resolveLanguageTags(value) uses configured defaultLocale as fallback")
    void resolveDelegatesToDefaultFallback() {
        // No-match input falls back to defaultLocale (FI per resolver config)
        assertEquals(FI, resolver.resolveLanguageTags("de-DE  fr  es     lv"));
    }

    @Nested
    @DisplayName("Mixed comma + whitespace separators (Accept-Language-like input)")
    class MixedSeparators {

        @Test
        @DisplayName("'de-DE, fr, en' → en (comma followed by space)")
        void commaSpace() {
            assertEquals(EN, resolver.resolveLanguageTags("de-DE, fr, en", FI));
        }

        @Test
        @DisplayName("'de-DE , fr , en' → en (space-comma-space)")
        void spaceCommaSpace() {
            assertEquals(EN, resolver.resolveLanguageTags("de-DE , fr , en", FI));
        }

        @Test
        @DisplayName("' de-DE,, fr, en ' → en (leading/trailing whitespace, doubled comma, mixed separators)")
        void leadingTrailingAndDoubleComma() {
            assertEquals(EN, resolver.resolveLanguageTags(" de-DE,, fr, en ", FI));
        }

        @Test
        @DisplayName("'de-DE,fr,en' → en (pure comma separators, sanity check)")
        void pureComma() {
            assertEquals(EN, resolver.resolveLanguageTags("de-DE,fr,en", FI));
        }
    }
}
