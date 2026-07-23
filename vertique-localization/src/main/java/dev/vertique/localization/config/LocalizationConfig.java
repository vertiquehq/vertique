// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.config;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * Immutable configuration record for the localization module.
 *
 * <p>Instances are produced by {@link LocalizationConfigParser#parse(io.vertx.core.json.JsonObject)}
 * at startup and are valid for the lifetime of the application.
 *
 * @param defaultLocale            the locale used when no other locale can be resolved; never
 *                                 {@code null}; must be a member of {@link #supportedLocales}
 * @param defaultZone              the default time zone applied when zone information is absent from
 *                                 a request; never falls back to the JVM system default — the
 *                                 parser rejects a missing or blank {@code defaultZone} value and
 *                                 uses {@code UTC} when the key is absent entirely
 * @param supportedLocales         ordered list of locales the application explicitly supports;
 *                                 always immutable and non-empty; defines the negotiation candidate
 *                                 set for {@code LocaleResolver}
 * @param fallbackToSystemLocale   when {@code true}, the resolver may fall back to the JVM
 *                                 system locale after exhausting all configured locales; defaults
 *                                 to {@code false}
 * @param useCodeAsDefaultMessage  when {@code true}, a missing message code is returned as-is
 *                                 instead of throwing {@link dev.vertique.localization.message.NoSuchMessageException};
 *                                 defaults to {@code false}
 * @param alwaysUseMessageFormat   when {@code true}, every message is processed through
 *                                 {@link java.text.MessageFormat} even when no arguments are
 *                                 supplied; defaults to {@code false}
 * @param cacheTtlSeconds          message bundle cache lifetime in seconds; {@code -1} means
 *                                 "no expiration control" (leave cache eviction to the JVM),
 *                                 {@code 0} means "do not cache"; must be {@code >= -1}
 */
public record LocalizationConfig(
        Locale defaultLocale,
        ZoneId defaultZone,
        List<Locale> supportedLocales,
        boolean fallbackToSystemLocale,
        boolean useCodeAsDefaultMessage,
        boolean alwaysUseMessageFormat,
        long cacheTtlSeconds) {

    /**
     * Compact constructor that defensively copies {@code supportedLocales} to an immutable view.
     */
    public LocalizationConfig {
        supportedLocales = List.copyOf(supportedLocales);
    }
}
