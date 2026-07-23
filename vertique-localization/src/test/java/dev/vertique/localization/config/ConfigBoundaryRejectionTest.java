// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Negative startup cases for {@link LocalizationConfigParser}. Every failure path produces a
 * {@link ConfigurationException} from {@code vertique-core}.
 */
class ConfigBoundaryRejectionTest {

    @Test
    @DisplayName("FR-LOC-043: blank defaultLocale tag rejected")
    void blankDefaultLocaleRejected() {
        JsonObject input = wrap(new JsonObject().put("defaultLocale", "  "));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-043: blank tag inside supportedLocales rejected")
    void blankSupportedLocaleRejected() {
        JsonObject input = wrap(new JsonObject().put("supportedLocales", List.of("en", "")));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-044: empty supportedLocales rejected")
    void emptySupportedLocalesRejected() {
        JsonObject input = wrap(new JsonObject().put("supportedLocales", List.of()));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-045: defaultLocale not in supportedLocales rejected")
    void defaultLocaleNotInSupportedRejected() {
        JsonObject input =
                wrap(new JsonObject().put("defaultLocale", "de").put("supportedLocales", List.of("fi", "sv", "en")));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-041a: defaultZone present but blank rejected")
    void blankDefaultZoneRejected() {
        JsonObject input = wrap(new JsonObject().put("defaultZone", "   "));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-041a: defaultZone present but empty string rejected")
    void emptyDefaultZoneRejected() {
        JsonObject input = wrap(new JsonObject().put("defaultZone", ""));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-041a: invalid IANA TZID rejected")
    void invalidDefaultZoneRejected() {
        JsonObject input = wrap(new JsonObject().put("defaultZone", "Not/AZone"));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("FR-LOC-041a: defaultZone absent applies UTC (no exception)")
    void absentDefaultZoneAppliesUtc() {
        JsonObject input = wrap(new JsonObject().put("defaultLocale", "en").put("supportedLocales", List.of("en")));
        LocalizationConfig config = LocalizationConfigParser.parse(input);
        assertEquals(ZoneId.of("UTC"), config.defaultZone());
    }

    @Test
    @DisplayName("FR-LOC-052: cacheTtlSeconds < -1 rejected")
    void negativeCacheTtlRejected() {
        JsonObject input = new JsonObject()
                .put("localization", new JsonObject().put("messages", new JsonObject().put("cacheTtlSeconds", -2)));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("cacheTtlSeconds with fractional value rejected (Double / non-integer)")
    void fractionalCacheTtlRejected() {
        JsonObject input = new JsonObject()
                .put("localization", new JsonObject().put("messages", new JsonObject().put("cacheTtlSeconds", 0.5)));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    @Test
    @DisplayName("cacheTtlSeconds with string value rejected")
    void stringCacheTtlRejected() {
        JsonObject input = new JsonObject()
                .put("localization", new JsonObject().put("messages", new JsonObject().put("cacheTtlSeconds", "60")));
        assertThrows(ConfigurationException.class, () -> LocalizationConfigParser.parse(input));
    }

    private JsonObject wrap(JsonObject localization) {
        return new JsonObject().put("localization", localization);
    }
}
