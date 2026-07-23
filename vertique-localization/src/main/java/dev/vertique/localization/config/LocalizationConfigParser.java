// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.config;

import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Static utility that parses a raw Vert.x {@link JsonObject} into a validated
 * {@link LocalizationConfig}.
 *
 * <p>All validation errors throw {@link ConfigurationException} so the application fails fast
 * at startup with a descriptive message. The parser never falls back silently on invalid input.
 *
 * <h2>Expected structure</h2>
 * <pre>{@code
 * {
 *   "localization": {
 *     "defaultLocale": "en",
 *     "defaultZone":   "UTC",
 *     "supportedLocales": ["en", "fi"],
 *     "fallbackToSystemLocale": false,
 *     "messages": {
 *       "useCodeAsDefaultMessage": false,
 *       "alwaysUseMessageFormat":  false,
 *       "cacheTtlSeconds":         -1
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Missing keys at any level apply the documented defaults. Present-but-invalid values always
 * throw {@link ConfigurationException}.
 */
public final class LocalizationConfigParser {

    private static final String DEFAULT_LOCALE_TAG = "en";
    private static final String DEFAULT_ZONE_ID = "UTC";
    private static final long DEFAULT_CACHE_TTL = -1L;

    private LocalizationConfigParser() {}

    // --- Public API ---

    /**
     * Parses the top-level application {@link JsonObject} into a {@link LocalizationConfig}.
     *
     * <p>Reads the {@code "localization"} key from {@code root}. If the key is absent or
     * {@code null}, an empty object is assumed and all defaults apply.
     *
     * @param root the top-level application configuration object; must not be {@code null}
     * @return a validated, immutable {@link LocalizationConfig}
     * @throws ConfigurationException if any present value fails validation
     */
    public static LocalizationConfig parse(JsonObject root) {
        // navigateObject returns an empty object when keys are absent and throws
        // ConfigurationException when a key is present but bound to a non-object value.
        JsonObject loc = JsonConfigPaths.navigateObject(root, "localization");
        JsonObject messages = JsonConfigPaths.navigateObject(loc, "messages");

        Locale defaultLocale = parseDefaultLocale(loc);
        ZoneId defaultZone = parseDefaultZone(loc);
        List<Locale> supportedLocales = parseSupportedLocales(loc, defaultLocale);
        boolean fallbackToSystemLocale = loc.getBoolean("fallbackToSystemLocale", false);

        boolean useCodeAsDefaultMessage = messages.getBoolean("useCodeAsDefaultMessage", false);
        boolean alwaysUseMessageFormat = messages.getBoolean("alwaysUseMessageFormat", false);
        long cacheTtlSeconds = parseCacheTtlSeconds(messages);

        return new LocalizationConfig(
                defaultLocale,
                defaultZone,
                supportedLocales,
                fallbackToSystemLocale,
                useCodeAsDefaultMessage,
                alwaysUseMessageFormat,
                cacheTtlSeconds);
    }

    // --- Private helpers ---

    /**
     * Parses the {@code defaultLocale} field. Defaults to {@code "en"} when absent.
     * Rejects blank strings.
     *
     * @param loc the {@code localization} sub-object
     * @return the parsed {@link Locale}
     * @throws ConfigurationException if the value is blank
     */
    private static Locale parseDefaultLocale(JsonObject loc) {
        String tag = loc.getString("defaultLocale", DEFAULT_LOCALE_TAG);
        if (tag.isBlank()) {
            throw new ConfigurationException("localization.defaultLocale must not be blank");
        }
        return Locale.forLanguageTag(tag);
    }

    /**
     * Parses the {@code defaultZone} field. Defaults to {@code "UTC"} when the key is absent.
     * Rejects blank/empty strings. Rejects unrecognized IANA time zone IDs.
     *
     * @param loc the {@code localization} sub-object
     * @return the parsed {@link ZoneId}
     * @throws ConfigurationException if the value is blank or not a valid IANA zone ID
     */
    private static ZoneId parseDefaultZone(JsonObject loc) {
        if (!loc.containsKey("defaultZone")) {
            return ZoneId.of(DEFAULT_ZONE_ID);
        }
        String zone = loc.getString("defaultZone");
        if (zone == null || zone.isBlank()) {
            throw new ConfigurationException("localization.defaultZone must not be blank when present");
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ConfigurationException("localization.defaultZone is not a valid IANA time zone ID: " + zone, e);
        }
    }

    /**
     * Parses the {@code supportedLocales} list. Defaults to {@code ["en"]} when absent.
     * Rejects an empty list and any blank tag within the list.
     * Validates that {@code defaultLocale} is a member of the resulting list.
     *
     * @param loc           the {@code localization} sub-object
     * @param defaultLocale the already-parsed default locale (for membership check)
     * @return an immutable, non-empty list of {@link Locale} values preserving input order
     * @throws ConfigurationException if the list is empty, contains a blank tag, or the default
     *                                locale is absent from the list
     */
    private static List<Locale> parseSupportedLocales(JsonObject loc, Locale defaultLocale) {
        if (!loc.containsKey("supportedLocales")) {
            // default list implicitly contains the default locale
            return List.of(defaultLocale);
        }

        JsonArray array = loc.getJsonArray("supportedLocales");
        if (array.isEmpty()) {
            throw new ConfigurationException("localization.supportedLocales must not be empty");
        }

        List<Locale> locales = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String tag = array.getString(i);
            if (tag == null || tag.isBlank()) {
                throw new ConfigurationException("localization.supportedLocales must not contain blank entries");
            }
            locales.add(Locale.forLanguageTag(tag));
        }

        if (!locales.contains(defaultLocale)) {
            throw new ConfigurationException("localization.defaultLocale '"
                    + defaultLocale.toLanguageTag()
                    + "' must be a member of localization.supportedLocales");
        }

        return locales;
    }

    /**
     * Parses {@code cacheTtlSeconds} from the {@code messages} sub-object using
     * {@link JsonObject#getValue} to enforce integer-only typing.
     *
     * <p>Only {@link Integer} and {@link Long} are accepted. Any other type (e.g. {@link Double},
     * {@link String}) is rejected with a {@link ConfigurationException}. Values less than
     * {@code -1} are also rejected.
     *
     * @param messages the {@code localization.messages} sub-object
     * @return the cache TTL in seconds, or {@code -1} if the key is absent
     * @throws ConfigurationException if the value has a non-integer type or is less than {@code -1}
     */
    private static long parseCacheTtlSeconds(JsonObject messages) {
        Object raw = messages.getValue("cacheTtlSeconds");
        if (raw == null) {
            return DEFAULT_CACHE_TTL;
        }

        long value;
        if (raw instanceof Integer i) {
            value = i.longValue();
        } else if (raw instanceof Long l) {
            value = l;
        } else {
            throw new ConfigurationException("localization.messages.cacheTtlSeconds must be an integer; got "
                    + raw.getClass().getSimpleName());
        }

        if (value < -1L) {
            throw new ConfigurationException("localization.messages.cacheTtlSeconds must be >= -1; got " + value);
        }

        return value;
    }
}
