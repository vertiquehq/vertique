// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.localization.LocalizationException;
import dev.vertique.localization.locale.LocaleResolutionException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Constructor invariants and getter behavior for {@link LocaleResolutionException}.
 * Throw-site mapping (which reason fires when) is exercised by the resolver tests.
 */
class LocaleResolutionExceptionTest {

    private static final List<Locale> SUPPORTED =
            List.of(Locale.forLanguageTag("fi"), Locale.forLanguageTag("sv"), Locale.forLanguageTag("en"));

    @Test
    @DisplayName("Extends LocalizationException (framework-rooted, not raw IAE)")
    void extendsLocalizationException() {
        assertTrue(LocalizationException.class.isAssignableFrom(LocaleResolutionException.class));
    }

    @Test
    @DisplayName("Final class — no further subclassing")
    void isFinal() {
        assertTrue(java.lang.reflect.Modifier.isFinal(LocaleResolutionException.class.getModifiers()));
    }

    @Test
    @DisplayName("All five Reason values exist")
    void allReasonsDefined() {
        Reason[] all = Reason.values();
        assertEquals(5, all.length);
        // Defensive — assert each by name so a rename is caught
        Reason.valueOf("MISSING_INPUT");
        Reason.valueOf("MALFORMED_LANGUAGE_RANGE");
        Reason.valueOf("MALFORMED_LANGUAGE_TAG");
        Reason.valueOf("UNSUPPORTED_LANGUAGE_RANGE");
        Reason.valueOf("UNSUPPORTED_LANGUAGE_TAG");
    }

    @Test
    @DisplayName("Constructor rejects null Reason with NullPointerException")
    void nullReasonRejected() {
        assertThrows(
                NullPointerException.class, () -> new LocaleResolutionException(null, "msg", "value", SUPPORTED, null));
    }

    @Test
    @DisplayName("Getters return what the constructor received")
    void gettersHappyPath() {
        Throwable cause = new IllegalArgumentException("root");
        LocaleResolutionException ex = new LocaleResolutionException(
                Reason.UNSUPPORTED_LANGUAGE_RANGE, "Unsupported language range fr", "fr", SUPPORTED, cause);

        assertEquals(Reason.UNSUPPORTED_LANGUAGE_RANGE, ex.reason());
        assertEquals("Unsupported language range fr", ex.getMessage());
        assertEquals("fr", ex.value());
        assertEquals(SUPPORTED, ex.supportedLocales());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("value() preserves null raw input verbatim")
    void nullValuePreserved() {
        LocaleResolutionException ex =
                new LocaleResolutionException(Reason.MISSING_INPUT, "msg", null, SUPPORTED, null);
        assertNull(ex.value());
    }

    @Test
    @DisplayName("value() preserves blank raw input verbatim")
    void blankValuePreserved() {
        LocaleResolutionException ex =
                new LocaleResolutionException(Reason.MISSING_INPUT, "msg", "  ", SUPPORTED, null);
        assertEquals("  ", ex.value());
    }

    @Test
    @DisplayName("supportedLocales() is defensively copied")
    void supportedLocalesDefensivelyCopied() {
        List<Locale> mutable = new ArrayList<>(SUPPORTED);

        LocaleResolutionException ex =
                new LocaleResolutionException(Reason.UNSUPPORTED_LANGUAGE_RANGE, "msg", "fr", mutable, null);

        mutable.add(Locale.GERMAN);

        // Mutation of the input list must not affect the exception's view
        assertEquals(3, ex.supportedLocales().size());
        assertNotSame(mutable, ex.supportedLocales());
    }

    @Test
    @DisplayName("supportedLocales() returns immutable view")
    void supportedLocalesImmutable() {
        LocaleResolutionException ex =
                new LocaleResolutionException(Reason.UNSUPPORTED_LANGUAGE_RANGE, "msg", "fr", SUPPORTED, null);
        assertThrows(
                UnsupportedOperationException.class, () -> ex.supportedLocales().add(Locale.GERMAN));
    }

    @Test
    @DisplayName("supportedLocales() normalizes null to List.of()")
    void nullSupportedLocalesNormalized() {
        LocaleResolutionException ex = new LocaleResolutionException(Reason.MISSING_INPUT, "msg", null, null, null);
        assertEquals(List.of(), ex.supportedLocales());
    }

    @Test
    @DisplayName("getCause() is null when no cause supplied")
    void nullCausePreserved() {
        LocaleResolutionException ex =
                new LocaleResolutionException(Reason.MISSING_INPUT, "msg", null, SUPPORTED, null);
        assertNull(ex.getCause());
    }
}
