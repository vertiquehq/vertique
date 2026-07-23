// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;

/**
 * Durable metadata encoder for {@link LocalizationContext}.
 *
 * <p>Serialises a {@link LocalizationContext} into a single-namespace
 * {@link DurableMetadata} document under the {@link LocalizationDurableNamespace#NAMESPACE
 * localization} namespace. The JSON body contains:
 * <ul>
 *   <li>{@link LocalizationDurableNamespace#LOCALE} — BCP 47 language tag (always present).</li>
 *   <li>{@link LocalizationDurableNamespace#ZONE} — IANA time-zone identifier (always present).</li>
 *   <li>{@link LocalizationDurableNamespace#LOCALE_SOURCE} — diagnostic source field (always
 *       present).</li>
 *   <li>{@link LocalizationDurableNamespace#ZONE_SOURCE} — diagnostic source field (always
 *       present).</li>
 *   <li>{@link LocalizationDurableNamespace#CURRENCY} — optional ISO 4217 currency code;
 *       omitted when {@link LocalizationContext#currency()} is empty.</li>
 *   <li>{@link LocalizationDurableNamespace#CALENDAR} — optional BCP 47 calendar subtag;
 *       omitted when {@link LocalizationContext#calendar()} is empty.</li>
 *   <li>{@link LocalizationDurableNamespace#NUMBERING} — optional BCP 47 numbering-system
 *       subtag; omitted when {@link LocalizationContext#numberingSystem()} is empty.</li>
 * </ul>
 *
 * <p>Registered into the Dagger {@code Set<DurableContextMetadataEncoder<?>>} multibinding via
 * {@link dev.vertique.localization.LocalizationModule}.
 */
public final class LocalizationContextDurableEncoder implements DurableContextMetadataEncoder<LocalizationContext> {

    /** Constructs the encoder. */
    public LocalizationContextDurableEncoder() {}

    /** {@inheritDoc} */
    @Override
    public Class<LocalizationContext> type() {
        return LocalizationContext.class;
    }

    /** {@inheritDoc} */
    @Override
    public String namespace() {
        return LocalizationDurableNamespace.NAMESPACE;
    }

    /**
     * Encodes the given {@link LocalizationContext} into a single-namespace
     * {@link DurableMetadata} document.
     *
     * <p>Required fields ({@code locale}, {@code zone}, {@code localeSource}, {@code zoneSource})
     * are always written. Optional fields ({@code currency}, {@code calendar}, {@code numbering})
     * are written only when their corresponding {@link java.util.Optional} is present.
     *
     * @param value   the context to encode; never {@code null}
     * @param context the encode context; not used
     * @return a single-namespace {@link DurableMetadata} document; never {@code null}
     */
    @Override
    public DurableMetadata encode(LocalizationContext value, DurableEncodeContext context) {
        JsonObject body = new JsonObject();
        body.put(LocalizationDurableNamespace.LOCALE, value.languageTag());
        body.put(LocalizationDurableNamespace.ZONE, value.zone().getId());
        body.put(LocalizationDurableNamespace.LOCALE_SOURCE, value.localeSource());
        body.put(LocalizationDurableNamespace.ZONE_SOURCE, value.zoneSource());
        value.currency().ifPresent(c -> body.put(LocalizationDurableNamespace.CURRENCY, c));
        value.calendar().ifPresent(c -> body.put(LocalizationDurableNamespace.CALENDAR, c));
        value.numberingSystem().ifPresent(n -> body.put(LocalizationDurableNamespace.NUMBERING, n));
        return DurableMetadata.of(LocalizationDurableNamespace.NAMESPACE, body);
    }
}
