// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.core.request.AcceptNegotiator;
import dev.vertique.rest.core.request.MediaType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AcceptNegotiator} content negotiation.
 */
class AcceptNegotiatorTest {

    // --- Simple match ---

    @Test
    @DisplayName("exact accept match returns that server type")
    void shouldMatchExact() {
        String result = AcceptNegotiator.negotiate("application/json", List.of("application/json"));
        assertEquals("application/json", result);
    }

    // --- Multiple server types ---

    @Test
    @DisplayName("selects matching server type when multiple server types offered")
    void shouldSelectMatchingServerType() {
        String result = AcceptNegotiator.negotiate("application/xml", List.of("application/json", "application/xml"));
        assertEquals("application/xml", result);
    }

    // --- Q-value preference ---

    @Test
    @DisplayName("q-value preference selects highest-quality server type")
    void shouldRespectQValuePreference() {
        String result = AcceptNegotiator.negotiate(
                "application/xml;q=0.9, application/json;q=1.0", List.of("application/json", "application/xml"));
        assertEquals("application/json", result);
    }

    // --- Wildcard match ---

    @Test
    @DisplayName("Accept: */* matches first server type")
    void wildcardMatchesFirstServerType() {
        String result = AcceptNegotiator.negotiate("*/*", List.of("application/json"));
        assertEquals("application/json", result);
    }

    // --- Type wildcard ---

    @Test
    @DisplayName("Accept: application/* matches application/json")
    void typeWildcardMatchesSubtype() {
        String result = AcceptNegotiator.negotiate("application/*", List.of("application/json"));
        assertEquals("application/json", result);
    }

    // --- No match (406) ---

    @Test
    @DisplayName("no matching server type returns null (caller should send 406)")
    void shouldReturnNullWhenNoMatch() {
        String result = AcceptNegotiator.negotiate("application/xml", List.of("application/json"));
        assertNull(result);
    }

    // --- No Accept header ---

    @Test
    @DisplayName("null accept header returns first server type")
    void nullAcceptHeaderReturnsFirst() {
        String result = AcceptNegotiator.negotiate(null, List.of("application/json", "text/plain"));
        assertEquals("application/json", result);
    }

    @Test
    @DisplayName("empty accept header returns first server type")
    void emptyAcceptHeaderReturnsFirst() {
        String result = AcceptNegotiator.negotiate("", List.of("application/json", "text/plain"));
        assertEquals("application/json", result);
    }

    @Test
    @DisplayName("blank accept header returns first server type")
    void blankAcceptHeaderReturnsFirst() {
        String result = AcceptNegotiator.negotiate("   ", List.of("application/json", "text/plain"));
        assertEquals("application/json", result);
    }

    // --- Empty server types ---

    @Test
    @DisplayName("empty server types returns null regardless of accept")
    void emptyServerTypesReturnsNull() {
        String result = AcceptNegotiator.negotiate("application/json", List.of());
        assertNull(result);
    }

    // --- Multiple Accept with mixed q-values ---

    @Test
    @DisplayName("multiple Accept types with mixed q-values selects highest-q compatible type")
    void shouldHandleMixedQValues() {
        String result = AcceptNegotiator.negotiate(
                "text/html;q=0.5, application/json;q=1.0, application/xml;q=0.9",
                List.of("application/xml", "application/json"));
        assertEquals("application/json", result);
    }

    @Test
    @DisplayName("multiple Accept types with mixed q-values falls back to next when first not available")
    void shouldFallBackToNextBestMatch() {
        String result = AcceptNegotiator.negotiate(
                "text/html;q=0.5, application/json;q=1.0, application/xml;q=0.9",
                List.of("application/xml", "text/plain"));
        assertEquals("application/xml", result);
    }

    // --- Malformed entries skipped ---

    @Test
    @DisplayName("malformed entry in Accept header is skipped; valid entry still matched")
    void shouldSkipMalformedEntries() {
        String result = AcceptNegotiator.negotiate("invalid, application/json", List.of("application/json"));
        assertEquals("application/json", result);
    }

    @Test
    @DisplayName("all-malformed Accept header falls back to first server type")
    void allMalformedFallsBackToFirstServerType() {
        String result = AcceptNegotiator.negotiate("invalid, alsobad", List.of("application/json"));
        assertEquals("application/json", result);
    }

    // --- Specificity overrides wildcard quality ---

    @Test
    @DisplayName("specific entry overrides wildcard quality per RFC 9110")
    void shouldUseSpecificEntryOverWildcard() {
        // application/* has q=0.9 but application/json has a more specific entry at q=0.8
        // So application/json effective q = 0.8, application/xml effective q = 0.9 (from wildcard)
        String result = AcceptNegotiator.negotiate(
                "application/*;q=0.9, application/json;q=0.8", List.of("application/json", "application/xml"));
        assertEquals("application/xml", result);
    }

    @Test
    @DisplayName("q=0 excludes a type even when wildcard matches")
    void shouldExcludeQZeroType() {
        // application/xml explicitly excluded with q=0, but application/* has q=0.9
        String result = AcceptNegotiator.negotiate(
                "application/*;q=0.9, application/xml;q=0", List.of("application/xml", "application/json"));
        assertEquals("application/json", result);
    }

    // --- parseAcceptHeader ---

    @Test
    @DisplayName("parseAcceptHeader returns entries sorted by q desc then specificity desc")
    void shouldSortParsedAcceptHeader() {
        List<MediaType> parsed = AcceptNegotiator.parseAcceptHeader("text/html;q=0.5, */*;q=0.1, application/json");
        assertEquals(3, parsed.size());
        assertEquals("application/json", parsed.get(0).withoutParameters()); // q=1.0, spec=2
        assertEquals("text/html", parsed.get(1).withoutParameters()); // q=0.5
        assertEquals("*/*", parsed.get(2).withoutParameters()); // q=0.1
    }

    @Test
    @DisplayName("parseAcceptHeader with same q uses specificity as tiebreaker")
    void shouldUseSpcificityAsTiebreaker() {
        List<MediaType> parsed =
                AcceptNegotiator.parseAcceptHeader("*/*;q=0.8, application/*;q=0.8, application/json;q=0.8");
        assertEquals(3, parsed.size());
        assertEquals("application/json", parsed.get(0).withoutParameters()); // spec=2
        assertEquals("application/*", parsed.get(1).withoutParameters()); // spec=1
        assertEquals("*/*", parsed.get(2).withoutParameters()); // spec=0
    }
}
