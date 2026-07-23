// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.DispatchContextValue;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §11.3 constructor-normalization cases for {@link LocalizationContext}. The companion propagation
 * adapters (holder facade, service-dispatch codec, durable codec) are covered by their own tests in
 * this package; this class focuses on the record's own invariants.
 */
class LocalizationContextTest {

    private static final Locale FI = Locale.forLanguageTag("fi");
    private static final ZoneId ZONE = ZoneId.of("Europe/Helsinki");

    @Test
    @DisplayName("FR-LOC-200: type is annotated with @DispatchContextValue")
    void annotatedAsDispatchContextValue() {
        assertTrue(LocalizationContext.class.isAnnotationPresent(DispatchContextValue.class));
    }

    @Test
    @DisplayName("Constructor rejects null locale with NullPointerException")
    void nullLocaleRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new LocalizationContext(
                        null, ZONE, Optional.empty(), Optional.empty(), Optional.empty(), "x", "x"));
    }

    @Test
    @DisplayName("Constructor rejects null zone with NullPointerException")
    void nullZoneRejected() {
        assertThrows(
                NullPointerException.class,
                () -> new LocalizationContext(
                        FI, null, Optional.empty(), Optional.empty(), Optional.empty(), "x", "x"));
    }

    @Test
    @DisplayName("Null Optional sub-fields normalize to Optional.empty()")
    void nullOptionalsNormalized() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, "src", "src");

        assertEquals(Optional.empty(), ctx.currency());
        assertEquals(Optional.empty(), ctx.calendar());
        assertEquals(Optional.empty(), ctx.numberingSystem());
    }

    @Test
    @DisplayName("Present Optional sub-fields preserved verbatim")
    void presentOptionalsPreserved() {
        LocalizationContext ctx = new LocalizationContext(
                FI,
                ZONE,
                Optional.of("EUR"),
                Optional.of("gregory"),
                Optional.of("latn"),
                "rest-accept-language",
                "default-zone");

        assertEquals(Optional.of("EUR"), ctx.currency());
        assertEquals(Optional.of("gregory"), ctx.calendar());
        assertEquals(Optional.of("latn"), ctx.numberingSystem());
    }

    @Test
    @DisplayName("Null localeSource normalizes to 'unspecified'")
    void nullLocaleSourceUnspecified() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, null, "ok");
        assertEquals("unspecified", ctx.localeSource());
        assertEquals("ok", ctx.zoneSource());
    }

    @Test
    @DisplayName("Blank localeSource normalizes to 'unspecified'")
    void blankLocaleSourceUnspecified() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, "   ", "ok");
        assertEquals("unspecified", ctx.localeSource());
        assertEquals("ok", ctx.zoneSource());
    }

    @Test
    @DisplayName("Null zoneSource normalizes to 'unspecified'")
    void nullZoneSourceUnspecified() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, "ok", null);
        assertEquals("ok", ctx.localeSource());
        assertEquals("unspecified", ctx.zoneSource());
    }

    @Test
    @DisplayName("Blank zoneSource normalizes to 'unspecified'")
    void blankZoneSourceUnspecified() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, "ok", "   ");
        assertEquals("ok", ctx.localeSource());
        assertEquals("unspecified", ctx.zoneSource());
    }

    @Test
    @DisplayName("Both source fields blank → both 'unspecified'")
    void bothSourcesBlank() {
        LocalizationContext ctx = new LocalizationContext(FI, ZONE, null, null, null, " ", " ");
        assertEquals("unspecified", ctx.localeSource());
        assertEquals("unspecified", ctx.zoneSource());
    }

    @Test
    @DisplayName("languageTag() returns the locale's BCP 47 tag")
    void languageTag() {
        LocalizationContext ctx = new LocalizationContext(
                Locale.forLanguageTag("sv-FI"),
                ZONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "src",
                "src");

        assertEquals("sv-FI", ctx.languageTag());
    }

    @Test
    @DisplayName("Records compare by value (equality + hashCode)")
    void recordValueEquality() {
        LocalizationContext a =
                new LocalizationContext(FI, ZONE, Optional.of("EUR"), Optional.empty(), Optional.empty(), "x", "y");
        LocalizationContext b =
                new LocalizationContext(FI, ZONE, Optional.of("EUR"), Optional.empty(), Optional.empty(), "x", "y");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("Accessors return the stored values")
    void accessorsHappyPath() {
        LocalizationContext ctx = new LocalizationContext(
                FI,
                ZONE,
                Optional.of("EUR"),
                Optional.of("gregory"),
                Optional.of("latn"),
                "rest-accept-language",
                "persisted-metadata");

        assertSame(FI, ctx.locale());
        assertSame(ZONE, ctx.zone());
        assertEquals("rest-accept-language", ctx.localeSource());
        assertEquals("persisted-metadata", ctx.zoneSource());
    }
}
