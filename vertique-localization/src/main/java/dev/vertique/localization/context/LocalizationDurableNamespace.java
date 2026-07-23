// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

/**
 * Constant definitions for the {@code localization} durable-metadata namespace.
 *
 * <p>All field-name constants in this class are the keys used within the JSON body stored under
 * the {@link #NAMESPACE} key in a {@link dev.vertique.core.context.DurableMetadata} document.
 * Centralising the constants here avoids duplication between the encoder, decoder, and any future
 * migration code.
 *
 * <p>This class is a stateless constant container — it cannot be instantiated.
 */
public final class LocalizationDurableNamespace {

    /** The durable-metadata namespace name owned by {@link LocalizationContext}. */
    public static final String NAMESPACE = "localization";

    /** JSON field name for the BCP 47 language tag of the locale (required). */
    public static final String LOCALE = "locale";

    /** JSON field name for the IANA time-zone identifier (required). */
    public static final String ZONE = "zone";

    /** JSON field name for the {@link LocalizationContext#localeSource()} diagnostic field. */
    public static final String LOCALE_SOURCE = "localeSource";

    /** JSON field name for the {@link LocalizationContext#zoneSource()} diagnostic field. */
    public static final String ZONE_SOURCE = "zoneSource";

    /** JSON field name for the optional ISO 4217 currency code. */
    public static final String CURRENCY = "currency";

    /** JSON field name for the optional BCP 47 {@code -u-ca} calendar subtag. */
    public static final String CALENDAR = "calendar";

    /** JSON field name for the optional BCP 47 {@code -u-nu} numbering-system subtag. */
    public static final String NUMBERING = "numbering";

    private LocalizationDurableNamespace() {}
}
