// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LocalizationDurableNamespace}.
 *
 * <p>Verifies that the namespace constant and all field-name constants hold the exact string
 * values required by the durable encoding contract.
 */
class LocalizationDurableNamespaceTest {

    @Test
    @DisplayName("NAMESPACE constant equals 'localization'")
    void namespaceConstant() {
        assertEquals("localization", LocalizationDurableNamespace.NAMESPACE);
    }

    @Test
    @DisplayName("LOCALE field constant equals 'locale'")
    void localeFieldConstant() {
        assertEquals("locale", LocalizationDurableNamespace.LOCALE);
    }

    @Test
    @DisplayName("ZONE field constant equals 'zone'")
    void zoneFieldConstant() {
        assertEquals("zone", LocalizationDurableNamespace.ZONE);
    }

    @Test
    @DisplayName("LOCALE_SOURCE field constant equals 'localeSource'")
    void localeSourceFieldConstant() {
        assertEquals("localeSource", LocalizationDurableNamespace.LOCALE_SOURCE);
    }

    @Test
    @DisplayName("ZONE_SOURCE field constant equals 'zoneSource'")
    void zoneSourceFieldConstant() {
        assertEquals("zoneSource", LocalizationDurableNamespace.ZONE_SOURCE);
    }

    @Test
    @DisplayName("CURRENCY field constant equals 'currency'")
    void currencyFieldConstant() {
        assertEquals("currency", LocalizationDurableNamespace.CURRENCY);
    }

    @Test
    @DisplayName("CALENDAR field constant equals 'calendar'")
    void calendarFieldConstant() {
        assertEquals("calendar", LocalizationDurableNamespace.CALENDAR);
    }

    @Test
    @DisplayName("NUMBERING field constant equals 'numbering'")
    void numberingFieldConstant() {
        assertEquals("numbering", LocalizationDurableNamespace.NUMBERING);
    }
}
