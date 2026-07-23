// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.localization.locale.LocaleResolver;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable metadata decoder for {@link LocalizationContext}.
 *
 * <p>Reads the {@link LocalizationDurableNamespace#NAMESPACE localization} namespace body written
 * by {@link LocalizationContextDurableEncoder} and rebuilds a {@link LocalizationContext} with
 * {@code localeSource} and {@code zoneSource} both set to {@code "persisted-metadata"}.
 *
 * <h2>Decode contract</h2>
 *
 * <ol>
 *   <li><b>Absent namespace</b> — returns {@link ContextDecodeResult#empty()} (no warnings).</li>
 *   <li><b>Missing required fields</b> ({@code locale} or {@code zone}) — returns
 *       {@link ContextDecodeResult#failure(List) failure} with one
 *       {@link ContextDecodeWarning} per missing field (reason {@code "required-field-missing"}).
 *       No partial binding is produced.</li>
 *   <li><b>Required fields present</b> — parsed strictly:
 *     <ul>
 *       <li>Locale: {@code new Locale.Builder().setLanguageTag(tag).build()} (strict BCP 47;
 *           rejects legacy underscore-separated tags such as {@code "en_US"} with reason
 *           {@code "unparseable-language-tag"}). The parsed locale must also appear in
 *           {@link LocaleResolver#supportedLocales()}; if not, reason is
 *           {@code "unsupported-locale"}.</li>
 *       <li>Zone: {@link ZoneId#of(String)}; on failure, reason is
 *           {@code "invalid-zone-id"}.</li>
 *     </ul>
 *     If either required parse fails, returns {@code failure(allRequiredWarnings)} — no partial
 *     bind and no default fallback.
 *   </li>
 *   <li><b>Optional fields</b> ({@code currency}, {@code calendar}, {@code numbering}) — read
 *       leniently via {@link JsonObject#getString(String)}. A non-string value (e.g., a JSON
 *       number) causes the field to be dropped and a warning with reason
 *       {@code "unparseable-"} + field name to be collected. The optional failure stays on the
 *       success path — the result carries both the populated {@link LocalizationContext} and the
 *       warnings.</li>
 * </ol>
 *
 * <p>Registered into the Dagger {@code Set<DurableContextMetadataDecoder<?>>} multibinding via
 * {@link dev.vertique.localization.LocalizationModule}.
 */
@Singleton
public final class LocalizationContextDurableDecoder implements DurableContextMetadataDecoder<LocalizationContext> {

    private static final String PERSISTED_METADATA = "persisted-metadata";

    private final LocaleResolver localeResolver;

    /**
     * Constructs the decoder.
     *
     * @param localeResolver the locale resolver used to validate that the decoded locale is in the
     *                       application's supported locale list; must not be {@code null}
     */
    @Inject
    public LocalizationContextDurableDecoder(LocaleResolver localeResolver) {
        this.localeResolver = Objects.requireNonNull(localeResolver, "localeResolver");
    }

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
     * Decodes the {@code localization} namespace body from {@code metadata}.
     *
     * @param metadata the full durable metadata document; never {@code null}
     * @param context  the decode context; not used
     * @return the decode result; never {@code null}
     */
    @Override
    public ContextDecodeResult<LocalizationContext> decode(DurableMetadata metadata, DurableDecodeContext context) {
        Optional<JsonObject> bodyOpt = metadata.body(LocalizationDurableNamespace.NAMESPACE);
        if (bodyOpt.isEmpty()) {
            return ContextDecodeResult.empty();
        }
        JsonObject body = bodyOpt.get();

        // --- Required field presence check ---
        List<ContextDecodeWarning> requiredWarnings = new ArrayList<>();
        String localeTag = body.getString(LocalizationDurableNamespace.LOCALE);
        String zoneId = body.getString(LocalizationDurableNamespace.ZONE);

        if (localeTag == null || localeTag.isBlank()) {
            requiredWarnings.add(
                    new ContextDecodeWarning(LocalizationDurableNamespace.LOCALE, null, "required-field-missing"));
        }
        if (zoneId == null || zoneId.isBlank()) {
            requiredWarnings.add(
                    new ContextDecodeWarning(LocalizationDurableNamespace.ZONE, null, "required-field-missing"));
        }
        if (!requiredWarnings.isEmpty()) {
            return ContextDecodeResult.failure(requiredWarnings);
        }

        // --- Strict required field parse ---
        Locale locale = parseLocaleStrict(localeTag, requiredWarnings);
        ZoneId zone = parseZoneStrict(zoneId, requiredWarnings);

        if (!requiredWarnings.isEmpty()) {
            return ContextDecodeResult.failure(requiredWarnings);
        }

        // --- Optional fields (lenient) ---
        List<ContextDecodeWarning> optionalWarnings = new ArrayList<>();
        Optional<String> currency = readOptionalString(body, LocalizationDurableNamespace.CURRENCY, optionalWarnings);
        Optional<String> calendar = readOptionalString(body, LocalizationDurableNamespace.CALENDAR, optionalWarnings);
        Optional<String> numbering = readOptionalString(body, LocalizationDurableNamespace.NUMBERING, optionalWarnings);

        // FR-LOC-223: a rehydrated context's provenance is the durable metadata itself, regardless
        // of the original source the producer recorded in the body (which is retained on the wire
        // for forensics only). Both sources are therefore always "persisted-metadata" on decode.
        LocalizationContext ctx = new LocalizationContext(
                locale, zone, currency, calendar, numbering, PERSISTED_METADATA, PERSISTED_METADATA);

        // Return value with preserved optional warnings (if any)
        if (optionalWarnings.isEmpty()) {
            return ContextDecodeResult.of(ctx);
        }
        return new ContextDecodeResult<>(Optional.of(ctx), optionalWarnings);
    }

    // --- Private helpers ---

    /**
     * Parses a BCP 47 language tag strictly using {@link Locale.Builder#setLanguageTag(String)}.
     * On {@link IllformedLocaleException}, adds a warning and returns {@code null}.
     * If parsed successfully, validates against the supported locale list.
     *
     * @param tag      the language tag string to parse
     * @param warnings the list to append any failure warnings to
     * @return the parsed locale, or {@code null} if parsing or validation failed
     */
    private Locale parseLocaleStrict(String tag, List<ContextDecodeWarning> warnings) {
        Locale locale;
        try {
            locale = new Locale.Builder().setLanguageTag(tag).build();
        } catch (IllformedLocaleException e) {
            warnings.add(
                    new ContextDecodeWarning(LocalizationDurableNamespace.LOCALE, tag, "unparseable-language-tag"));
            return null;
        }
        if (!localeResolver.supportedLocales().contains(locale)) {
            warnings.add(new ContextDecodeWarning(LocalizationDurableNamespace.LOCALE, tag, "unsupported-locale"));
            return null;
        }
        return locale;
    }

    /**
     * Parses an IANA time-zone identifier using {@link ZoneId#of(String)}.
     * On exception, adds a warning and returns {@code null}.
     *
     * @param zoneId   the zone identifier string to parse
     * @param warnings the list to append any failure warnings to
     * @return the parsed zone, or {@code null} if parsing failed
     */
    private static ZoneId parseZoneStrict(String zoneId, List<ContextDecodeWarning> warnings) {
        try {
            return ZoneId.of(zoneId);
        } catch (Exception e) {
            warnings.add(new ContextDecodeWarning(LocalizationDurableNamespace.ZONE, zoneId, "invalid-zone-id"));
            return null;
        }
    }

    /**
     * Reads an optional string field from the JSON body leniently. If the field is present but
     * not a string (i.e., {@link JsonObject#getString(String)} returns {@code null} despite the
     * key being present), the field is dropped and a warning with reason
     * {@code "unparseable-" + fieldName} is added.
     *
     * @param body      the JSON body
     * @param fieldName the field name to read
     * @param warnings  the list to append any failure warnings to
     * @return the field value wrapped in {@link Optional}, or empty if absent or unparseable
     */
    private static Optional<String> readOptionalString(
            JsonObject body, String fieldName, List<ContextDecodeWarning> warnings) {
        if (!body.containsKey(fieldName)) {
            return Optional.empty();
        }
        Object raw = body.getValue(fieldName);
        if (raw instanceof String s) {
            return Optional.of(s);
        }
        // Field present but not a string — drop with a warning
        warnings.add(
                new ContextDecodeWarning(fieldName, raw == null ? null : raw.toString(), "unparseable-" + fieldName));
        return Optional.empty();
    }
}
