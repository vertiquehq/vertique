// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocalizationContextDurableEncoder}.
 *
 * <p>Verifies that the encoder correctly serialises {@link LocalizationContext} fields into
 * a single-namespace {@link DurableMetadata} document under the {@code localization} namespace,
 * including optional-field presence and absence.
 */
class LocalizationContextDurableEncoderTest {

    private static final DurableEncodeContext ENCODE_CTX = new DurableEncodeContext("outbox");

    private final LocalizationContextDurableEncoder encoder = new LocalizationContextDurableEncoder();

    // --- type() / namespace() ---

    @Test
    @DisplayName("type() returns LocalizationContext.class")
    void typeReturnsLocalizationContextClass() {
        assertEquals(LocalizationContext.class, encoder.type());
    }

    @Test
    @DisplayName("namespace() returns 'localization'")
    void namespaceReturnsLocalization() {
        assertEquals("localization", encoder.namespace());
    }

    // --- required fields ---

    @Nested
    @DisplayName("required fields")
    class RequiredFieldTests {

        @Test
        @DisplayName("encode writes locale as BCP 47 language tag")
        void encodesLocaleAsLanguageTag() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("fi"),
                    ZoneId.of("Europe/Helsinki"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertEquals("fi", body.getString(LocalizationDurableNamespace.LOCALE));
        }

        @Test
        @DisplayName("encode writes zone as zone id string")
        void encodesZoneAsId() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("sv"),
                    ZoneId.of("Europe/Stockholm"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertEquals("Europe/Stockholm", body.getString(LocalizationDurableNamespace.ZONE));
        }

        @Test
        @DisplayName("encode always writes localeSource and zoneSource")
        void encodesSourceFields() {
            LocalizationContext ctx = new LocalizationContext(
                    Locale.forLanguageTag("en"),
                    ZoneId.of("UTC"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    "rest-accept-language",
                    "default-zone");

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertEquals("rest-accept-language", body.getString(LocalizationDurableNamespace.LOCALE_SOURCE));
            assertEquals("default-zone", body.getString(LocalizationDurableNamespace.ZONE_SOURCE));
        }
    }

    // --- optional fields ---

    @Nested
    @DisplayName("optional fields")
    class OptionalFieldTests {

        @Test
        @DisplayName("encode omits currency when absent")
        void omitsCurrencyWhenAbsent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("en"),
                    ZoneId.of("UTC"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertFalse(body.containsKey(LocalizationDurableNamespace.CURRENCY), "currency must be absent");
        }

        @Test
        @DisplayName("encode includes currency when present")
        void includesCurrencyWhenPresent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("fi"),
                    ZoneId.of("Europe/Helsinki"),
                    Optional.of("EUR"),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertTrue(body.containsKey(LocalizationDurableNamespace.CURRENCY), "currency must be present");
            assertEquals("EUR", body.getString(LocalizationDurableNamespace.CURRENCY));
        }

        @Test
        @DisplayName("encode omits calendar when absent")
        void omitsCalendarWhenAbsent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("en"),
                    ZoneId.of("UTC"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertFalse(body.containsKey(LocalizationDurableNamespace.CALENDAR), "calendar must be absent");
        }

        @Test
        @DisplayName("encode includes calendar when present")
        void includesCalendarWhenPresent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("fi"),
                    ZoneId.of("Europe/Helsinki"),
                    Optional.empty(),
                    Optional.of("gregory"),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertEquals("gregory", body.getString(LocalizationDurableNamespace.CALENDAR));
        }

        @Test
        @DisplayName("encode omits numbering system when absent")
        void omitsNumberingWhenAbsent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("en"),
                    ZoneId.of("UTC"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertFalse(body.containsKey(LocalizationDurableNamespace.NUMBERING), "numbering must be absent");
        }

        @Test
        @DisplayName("encode includes numbering system when present")
        void includesNumberingWhenPresent() {
            LocalizationContext ctx = makeContext(
                    Locale.forLanguageTag("ar"),
                    ZoneId.of("UTC"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("latn"));

            JsonObject body = extractBody(encoder.encode(ctx, ENCODE_CTX));

            assertEquals("latn", body.getString(LocalizationDurableNamespace.NUMBERING));
        }
    }

    // --- metadata shape ---

    @Test
    @DisplayName("encode returns DurableMetadata with exactly the 'localization' namespace")
    void returnsCorrectNamespace() {
        LocalizationContext ctx = makeContext(
                Locale.forLanguageTag("en"), ZoneId.of("UTC"), Optional.empty(), Optional.empty(), Optional.empty());

        DurableMetadata metadata = encoder.encode(ctx, ENCODE_CTX);

        assertTrue(metadata.has("localization"), "metadata must have 'localization' namespace");
        assertEquals(1, metadata.namespaces().size(), "must have exactly one namespace");
    }

    // --- Helpers ---

    private static LocalizationContext makeContext(
            Locale locale,
            ZoneId zone,
            Optional<String> currency,
            Optional<String> calendar,
            Optional<String> numbering) {
        return new LocalizationContext(locale, zone, currency, calendar, numbering, "test", "test");
    }

    private static JsonObject extractBody(DurableMetadata metadata) {
        return metadata.body(LocalizationDurableNamespace.NAMESPACE)
                .orElseThrow(() -> new AssertionError("'localization' namespace body not present"));
    }
}
