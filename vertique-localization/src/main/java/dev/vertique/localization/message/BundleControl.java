// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import java.time.Duration;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Package-private {@link ResourceBundle.Control} extension that suppresses Java's built-in
 * system-locale fallback and maps the framework's {@code cacheTtlSeconds} configuration to the
 * JDK cache-lifetime constants.
 *
 * <p><strong>Cache-key trap — why this class must be stateless:</strong> Java's
 * {@link ResourceBundle#getBundle(String, Locale, ClassLoader, ResourceBundle.Control)} caches
 * loaded bundles keyed by {@code (classloader, basename, locale, control)}. If a new
 * {@code BundleControl} instance were passed on every call the cache key would never match a prior
 * entry, silently bypassing all caching regardless of the configured TTL. One instance is therefore
 * constructed per {@link DefaultMessageSource} and shared across all lookups performed by that
 * source. The only mutable state lives in the JDK's internal bundle cache, which is thread-safe.
 *
 * @see ResourceBundle.Control
 * @see DefaultMessageSource
 */
final class BundleControl extends ResourceBundle.Control {

    /** Cache lifetime in seconds as configured; mapped to JDK constants in {@link #getTimeToLive}. */
    private final long cacheTtlSeconds;

    /**
     * Constructs a {@code BundleControl} for the given cache TTL.
     *
     * @param cacheTtlSeconds the configured cache TTL in seconds; {@code -1} means no-expiration
     *                        control, {@code 0} means do-not-cache, positive values are converted
     *                        to milliseconds; values {@code < -1} are rejected at config parse time
     *                        and must not reach this constructor
     */
    BundleControl(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    /**
     * Returns {@code null} to disable Java's own system-locale fallback mechanism.
     *
     * <p>The framework controls the candidate-locale chain explicitly in
     * {@link DefaultMessageSource}. Delegating fallback to the JDK would introduce a second,
     * hidden fallback path that bypasses the configured {@code defaultLocale} and
     * {@code fallbackToSystemLocale} settings (FR-LOC-067).
     *
     * @param baseName the resource bundle base name
     * @param locale   the locale for which no bundle was found
     * @return always {@code null}
     */
    @Override
    public Locale getFallbackLocale(String baseName, Locale locale) {
        return null;
    }

    /**
     * Maps the configured {@code cacheTtlSeconds} to the appropriate JDK cache-lifetime value.
     *
     * <ul>
     *   <li>{@code -1} → {@link ResourceBundle.Control#TTL_NO_EXPIRATION_CONTROL} (FR-LOC-049)</li>
     *   <li>{@code 0}  → {@link ResourceBundle.Control#TTL_DONT_CACHE} (FR-LOC-050)</li>
     *   <li>{@code > 0} → {@code Duration.ofSeconds(cacheTtlSeconds).toMillis()} (FR-LOC-051)</li>
     * </ul>
     *
     * @param baseName the resource bundle base name
     * @param locale   the locale of the bundle being cached
     * @return the cache lifetime in milliseconds, or one of the JDK sentinel constants
     */
    @Override
    public long getTimeToLive(String baseName, Locale locale) {
        if (cacheTtlSeconds == -1L) {
            return ResourceBundle.Control.TTL_NO_EXPIRATION_CONTROL;
        } else if (cacheTtlSeconds == 0L) {
            return ResourceBundle.Control.TTL_DONT_CACHE;
        } else {
            return Duration.ofSeconds(cacheTtlSeconds).toMillis();
        }
    }
}
