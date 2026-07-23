// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Happy-path tests for {@link LocalizationConfigParser}. Negative cases live in
 * {@code ConfigBoundaryRejectionTest}.
 */
class LocalizationConfigParserTest {

    @Test
    @DisplayName("FR-LOC-041: empty input applies all defaults")
    void emptyConfigReturnsDefaults() {
        LocalizationConfig config = LocalizationConfigParser.parse(new JsonObject());

        assertEquals(Locale.forLanguageTag("en"), config.defaultLocale());
        assertEquals(ZoneId.of("UTC"), config.defaultZone());
        assertEquals(List.of(Locale.forLanguageTag("en")), config.supportedLocales());
        assertEquals(false, config.fallbackToSystemLocale());
        assertEquals(false, config.useCodeAsDefaultMessage());
        assertEquals(false, config.alwaysUseMessageFormat());
        assertEquals(-1L, config.cacheTtlSeconds());
    }

    @Test
    @DisplayName("FR-LOC-041: missing 'localization' object also applies defaults")
    void missingLocalizationKeyAppliesDefaults() {
        LocalizationConfig config = LocalizationConfigParser.parse(new JsonObject().put("other", "ignored"));

        assertEquals(Locale.forLanguageTag("en"), config.defaultLocale());
        assertEquals(ZoneId.of("UTC"), config.defaultZone());
    }

    @Test
    @DisplayName("FR-LOC-042: defaultLocale and supportedLocales parse via Locale.forLanguageTag")
    void parsesLocalesFromLanguageTags() {
        JsonObject input = new JsonObject()
                .put(
                        "localization",
                        new JsonObject().put("defaultLocale", "fi").put("supportedLocales", List.of("fi", "sv", "en")));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        assertEquals(Locale.forLanguageTag("fi"), config.defaultLocale());
        assertEquals(
                List.of(Locale.forLanguageTag("fi"), Locale.forLanguageTag("sv"), Locale.forLanguageTag("en")),
                config.supportedLocales());
    }

    @Test
    @DisplayName("FR-LOC-041a: missing defaultZone defaults to UTC (does NOT fail)")
    void missingDefaultZoneFallsBackToUtc() {
        JsonObject input = new JsonObject().put("localization", new JsonObject());

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        assertEquals(ZoneId.of("UTC"), config.defaultZone());
    }

    @Test
    @DisplayName("FR-LOC-041a: explicit defaultZone parses with ZoneId.of")
    void defaultZoneFromLocalization() {
        JsonObject input = new JsonObject().put("localization", new JsonObject().put("defaultZone", "Europe/Helsinki"));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        assertEquals(ZoneId.of("Europe/Helsinki"), config.defaultZone());
    }

    @Nested
    @DisplayName("FR-LOC-047..051: cacheTtlSeconds mapping")
    class CacheTtlSeconds {

        @Test
        @DisplayName("cacheTtlSeconds = -1 stored as -1L (TTL_NO_EXPIRATION_CONTROL)")
        void minusOne() {
            JsonObject input = wrap(new JsonObject().put("cacheTtlSeconds", -1));
            assertEquals(-1L, LocalizationConfigParser.parse(input).cacheTtlSeconds());
        }

        @Test
        @DisplayName("cacheTtlSeconds = 0 stored as 0L (TTL_DONT_CACHE)")
        void zero() {
            JsonObject input = wrap(new JsonObject().put("cacheTtlSeconds", 0));
            assertEquals(0L, LocalizationConfigParser.parse(input).cacheTtlSeconds());
        }

        @Test
        @DisplayName("cacheTtlSeconds = 60 stored verbatim as 60L")
        void positive() {
            JsonObject input = wrap(new JsonObject().put("cacheTtlSeconds", 60));
            assertEquals(60L, LocalizationConfigParser.parse(input).cacheTtlSeconds());
        }

        @Test
        @DisplayName("Long-typed JSON values are accepted")
        void longTypedValue() {
            JsonObject input = wrap(new JsonObject().put("cacheTtlSeconds", 90L));
            assertEquals(90L, LocalizationConfigParser.parse(input).cacheTtlSeconds());
        }

        private JsonObject wrap(JsonObject messages) {
            return new JsonObject().put("localization", new JsonObject().put("messages", messages));
        }
    }

    @Test
    @DisplayName("FR-LOC-046: legacy 'cacheSeconds' field is silently ignored")
    void legacyCacheSecondsIgnored() {
        JsonObject input = new JsonObject()
                .put("localization", new JsonObject().put("messages", new JsonObject().put("cacheSeconds", 60)));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        // cacheSeconds is not the canonical name; parser must not read it. Default -1 wins.
        assertEquals(-1L, config.cacheTtlSeconds());
    }

    @Test
    @DisplayName("messages.useCodeAsDefaultMessage and alwaysUseMessageFormat flags")
    void messagesFlags() {
        JsonObject input = new JsonObject()
                .put(
                        "localization",
                        new JsonObject()
                                .put(
                                        "messages",
                                        new JsonObject()
                                                .put("useCodeAsDefaultMessage", true)
                                                .put("alwaysUseMessageFormat", true)));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        assertTrue(config.useCodeAsDefaultMessage());
        assertTrue(config.alwaysUseMessageFormat());
    }

    @Test
    @DisplayName("supportedLocales list order is preserved (matching policy)")
    void supportedLocalesOrderPreserved() {
        JsonObject input = new JsonObject()
                .put(
                        "localization",
                        new JsonObject().put("defaultLocale", "sv").put("supportedLocales", List.of("fi", "sv", "en")));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        assertEquals(
                List.of(Locale.forLanguageTag("fi"), Locale.forLanguageTag("sv"), Locale.forLanguageTag("en")),
                config.supportedLocales());
    }

    @Test
    @DisplayName("supportedLocales is immutable")
    void supportedLocalesImmutable() {
        JsonObject input =
                new JsonObject().put("localization", new JsonObject().put("supportedLocales", List.of("en")));

        LocalizationConfig config = LocalizationConfigParser.parse(input);

        // Returned list does not support add/remove. Defensive copy or unmodifiable view.
        List<Locale> locales = config.supportedLocales();
        assertThrows(UnsupportedOperationException.class, () -> locales.add(Locale.GERMAN));
    }

    @Test
    @DisplayName("Identity: same JsonObject input produces equal config")
    void deterministicParse() {
        JsonObject input = new JsonObject()
                .put(
                        "localization",
                        new JsonObject().put("defaultLocale", "fi").put("supportedLocales", List.of("fi", "en")));

        LocalizationConfig a = LocalizationConfigParser.parse(input);
        LocalizationConfig b = LocalizationConfigParser.parse(input);

        // Records compare by value
        assertEquals(a, b);
        assertSame(a.defaultLocale().getLanguage(), b.defaultLocale().getLanguage());
    }
}
