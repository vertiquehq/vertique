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

    // --- Quote-aware parsing ---

    @Test
    @DisplayName("quoted comma in a parameter does not hide a following q=0 (naive split accepted the type)")
    void quotedCommaDoesNotHideQZero() {
        String result = AcceptNegotiator.negotiate("application/json;profile=\"a,b\";q=0", List.of("application/json"));
        assertNull(result);
    }

    @Test
    @DisplayName("quoted semicolon in a parameter does not hide a following q=0")
    void quotedSemicolonDoesNotHideQZero() {
        String result = AcceptNegotiator.negotiate("application/json;profile=\"a;b\";q=0", List.of("application/json"));
        assertNull(result);
    }

    @Test
    @DisplayName("quoted comma in a parameter still selects the type when it is acceptable")
    void quotedCommaStillSelects() {
        String result = AcceptNegotiator.negotiate(
                "application/xml;q=0.5, application/json;profile=\"a,b\";q=0.9",
                List.of("application/xml", "application/json"));
        assertEquals("application/json", result);
    }

    @Test
    @DisplayName("quoted comma does not create a phantom entry that matches another server type")
    void quotedCommaDoesNotCreatePhantomEntry() {
        // A naive split reads the tail of the quoted value as its own entry "text/plain".
        String result = AcceptNegotiator.negotiate(
                "application/json;profile=\"x, text/plain\";q=0", List.of("application/json", "text/plain"));
        assertNull(result);
    }

    @Test
    @DisplayName("parseAcceptHeader unquotes parameter values and keeps the q of an entry with a quoted comma")
    void parseAcceptHeaderKeepsQuotedEntryWhole() {
        List<MediaType> parsed = AcceptNegotiator.parseAcceptHeader("application/json;profile=\"a,b\";q=0.3");
        assertEquals(1, parsed.size());
        assertEquals("a,b", parsed.get(0).parameters().get("profile"));
        assertEquals(0.3, parsed.get(0).qualityFactor(), 0.0001);
    }

    @Test
    @DisplayName("an entry with an unterminated quote or an invalid q is dropped, others are kept")
    void malformedEntriesAreDropped() {
        List<MediaType> parsed = AcceptNegotiator.parseAcceptHeader(
                "text/csv;q=oops, text/plain;q=2, application/json;x=\"open, text/html");
        assertEquals(0, parsed.size());
        assertEquals(
                1,
                AcceptNegotiator.parseAcceptHeader("text/csv;q=oops, text/html").size());
    }

    @Test
    @DisplayName("parseAcceptHeader considers at most the first 50 entries")
    void parseAcceptHeaderCapsAtFifty() {
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            header.append("type/s").append(i).append(',');
        }
        assertEquals(50, AcceptNegotiator.parseAcceptHeader(header.toString()).size());
    }

    // --- Specificity tiebreak ---

    @Test
    @DisplayName("equal effective q is broken by the specificity of the matching client entry")
    void equalQualityPrefersMoreSpecificMatch() {
        // json matches the exact entry (specificity 2), xml only the wildcard (specificity 1), both at q=0.8
        String result = AcceptNegotiator.negotiate(
                "application/*;q=0.8, application/json;q=0.8", List.of("application/xml", "application/json"));
        assertEquals("application/json", result);
    }

    // --- effectiveQuality ---

    @Test
    @DisplayName("effectiveQuality uses the most specific compatible entry")
    void effectiveQualityMostSpecific() {
        String accept = "*/*;q=0.1, application/*;q=0.5, application/json;q=0.9";
        assertEquals(0.9, AcceptNegotiator.effectiveQuality(accept, "application/json"), 0.0);
        assertEquals(0.5, AcceptNegotiator.effectiveQuality(accept, "application/xml"), 0.0);
        assertEquals(0.1, AcceptNegotiator.effectiveQuality(accept, "text/plain"), 0.0);
    }

    @Test
    @DisplayName("effectiveQuality is 1.0 for an entry without q and ignores server-side parameters")
    void effectiveQualityDefaultAndParameters() {
        assertEquals(1.0, AcceptNegotiator.effectiveQuality("application/json", "application/json;charset=utf-8"), 0.0);
    }

    @Test
    @DisplayName("effectiveQuality is 0.0 when no entry is compatible")
    void effectiveQualityNoMatch() {
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("text/html, application/xml", "application/json"), 0.0);
    }

    @Test
    @DisplayName("effectiveQuality honours an explicit q=0 over a wildcard")
    void effectiveQualityExplicitZero() {
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("*/*, application/json;q=0", "application/json"), 0.0);
        assertEquals(1.0, AcceptNegotiator.effectiveQuality("*/*, application/json;q=0", "application/xml"), 0.0);
    }

    @Test
    @DisplayName("effectiveQuality respects a quoted comma before q=0")
    void effectiveQualityQuotedComma() {
        assertEquals(
                0.0,
                AcceptNegotiator.effectiveQuality("application/json;profile=\"a,b\";q=0", "application/json"),
                0.0);
        assertEquals(
                0.4,
                AcceptNegotiator.effectiveQuality("application/json;profile=\"a,b\";q=0.4", "application/json"),
                0.0);
    }

    @Test
    @DisplayName("effectiveQuality never fails open on a missing, blank or garbage Accept header")
    void effectiveQualityGarbageIsNotAcceptable() {
        for (String accept : new String[] {
            null, "", "   ", ",", "garbage", "application/json;q=oops", "application/json;x=\"open", "\"", "*/*;q=2"
        }) {
            assertEquals(
                    0.0,
                    AcceptNegotiator.effectiveQuality(accept, "application/json"),
                    "Accept: " + accept + " must not make a type acceptable");
        }
    }

    @Test
    @DisplayName("effectiveQuality is 0.0 for an unparsable server media type")
    void effectiveQualityUnparsableServerType() {
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("*/*", "notamediatype"), 0.0);
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("*/*", null), 0.0);
    }

    @Test
    @DisplayName("effectiveQuality over parsed entries matches the string form")
    void effectiveQualityParsedEntries() {
        List<MediaType> client = AcceptNegotiator.parseAcceptHeader("application/*;q=0.5, application/json;q=0.2");
        assertEquals(0.2, AcceptNegotiator.effectiveQuality(client, MediaType.parse("application/json")), 0.0);
        assertEquals(0.0, AcceptNegotiator.effectiveQuality(client, MediaType.parse("text/plain")), 0.0);
        assertEquals(0.0, AcceptNegotiator.effectiveQuality(List.of(), MediaType.parse("text/plain")), 0.0);
    }

    // --- Dropped malformed entries ---

    @Test
    @DisplayName("negotiate returns null when every entry was dropped as malformed")
    void negotiateFailsClosedWhenEveryEntryIsMalformed() {
        List<String> server = List.of("application/json");
        assertNull(AcceptNegotiator.negotiate("application/xml;x=\"", server));
        assertNull(AcceptNegotiator.negotiate("application/json;q=abc", server));
        assertNull(AcceptNegotiator.negotiate("application/json;q=\"1\"", server));
        assertNull(AcceptNegotiator.negotiate("application/json;q=2", server));
        assertNull(AcceptNegotiator.negotiate("application/json;q=0;q=1", server));
    }

    @Test
    @DisplayName("negotiate still serves the first type for tokens that merely lack a slash")
    void negotiateKeepsFirstTypeForSlashlessTokens() {
        assertEquals("application/json", AcceptNegotiator.negotiate("garbage", List.of("application/json")));
    }

    @Test
    @DisplayName("negotiate ignores a dropped malformed entry when a usable entry remains")
    void negotiateUsesRemainingEntries() {
        assertEquals(
                "application/json",
                AcceptNegotiator.negotiate("application/xml;q=abc, application/json", List.of("application/json")));
    }

    @Test
    @DisplayName("negotiate fails closed when a malformed entry falls after the 50-element cap")
    void negotiateFailsClosedWhenMalformedEntryIsPastTheCap() {
        String header = "garbage,".repeat(50) + "text/html;q=abc";
        assertNull(AcceptNegotiator.negotiate(header, List.of("application/json")));
        assertNull(AcceptNegotiator.negotiate(header, List.of("text/html")));
    }

    @Test
    @DisplayName("negotiate fails closed when a well-formed entry falls after the cap and nothing before it is usable")
    void negotiateFailsClosedWhenUsableEntryIsPastTheCap() {
        String header = "garbage,".repeat(50) + "text/html";
        assertNull(AcceptNegotiator.negotiate(header, List.of("text/html")));
    }

    @Test
    @DisplayName("negotiate keeps the first type when exactly 50 slashless tokens fill the cap")
    void negotiateKeepsFirstTypeForExactlyFiftySlashlessTokens() {
        String header = "garbage,".repeat(49) + "garbage";
        assertEquals("application/json", AcceptNegotiator.negotiate(header, List.of("application/json")));
        assertEquals(
                "application/json",
                AcceptNegotiator.negotiate(header + ", ,,  ,", List.of("application/json")),
                "trailing empty elements are not elements");
    }

    @Test
    @DisplayName("negotiate still uses a usable entry inside the cap when elements follow it")
    void negotiateUsesUsableEntryInsideTheCap() {
        String header = "text/html," + "garbage,".repeat(60);
        assertEquals("text/html", AcceptNegotiator.negotiate(header, List.of("text/html")));
    }

    @Test
    @DisplayName("negotiate fails closed for an entry with a slash but an empty type or subtype")
    void negotiateFailsClosedForEmptyTypeOrSubtype() {
        List<String> server = List.of("application/json");
        assertNull(AcceptNegotiator.negotiate("text/", server));
        assertNull(AcceptNegotiator.negotiate("/json", server));
        assertNull(AcceptNegotiator.negotiate("/", server));
        assertNull(AcceptNegotiator.negotiate("text/ ;q=0.5", server));
        assertEquals("application/json", AcceptNegotiator.negotiate("text/, application/json", server));
    }

    @Test
    @DisplayName("negotiate fails closed for an entry whose type carries a control character")
    void negotiateFailsClosedForControlCharacterInType() {
        assertNull(AcceptNegotiator.negotiate("te" + (char) 1 + "xt/html", List.of("text/html")));
    }

    @Test
    @DisplayName("parseAcceptHeader excludes an entry with an empty type or subtype")
    void parseAcceptHeaderExcludesEmptyTypeOrSubtype() {
        assertEquals(List.of(), AcceptNegotiator.parseAcceptHeader("text/, /json"));
    }

    @Test
    @DisplayName("an Accept entry with a wildcard type and a concrete subtype is dropped, not read as */*")
    void wildcardTypeWithConcreteSubtypeIsDropped() {
        List<String> server = List.of("application/xml");
        assertEquals(List.of(), AcceptNegotiator.parseAcceptHeader("*/xml"));
        assertNull(AcceptNegotiator.negotiate("*/xml", server));
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("*/xml", "application/xml"), 0.0);
        assertEquals("application/xml", AcceptNegotiator.negotiate("*/xml, application/xml", server));
    }

    @Test
    @DisplayName("effectiveQuality treats an Accept entry with an unusable q as not acceptable")
    void effectiveQualityRejectsUnusableQ() {
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("image/png;q=abc", "image/png"), 0.0);
        assertEquals(0.0, AcceptNegotiator.effectiveQuality("image/png;q=0;q=1", "image/png"), 0.0);
    }

    @Test
    @DisplayName("a server type with an unusable q is read leniently")
    void serverTypeQualityIsLenient() {
        assertEquals("application/json", AcceptNegotiator.negotiate("*/*", List.of("application/json;q=abc")));
    }
}
