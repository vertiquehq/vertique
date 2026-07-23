// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleLinkTest {

    @Test
    @DisplayName("toString produces RFC 5988 format with single rel param")
    void shouldProduceRfc5988FormatWithSingleParam() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com/items"), Map.of(Link.REL, "items"));
        assertEquals("<http://example.com/items>; rel=\"items\"", link.toString());
    }

    @Test
    @DisplayName("toString with multiple params includes all in insertion order")
    void shouldProduceRfc5988FormatWithMultipleParams() {
        // LinkedHashMap preserves insertion order for deterministic output
        Map<String, String> params = new LinkedHashMap<>();
        params.put(Link.REL, "next");
        params.put(Link.TITLE, "Next Page");
        params.put(Link.TYPE, "application/json");
        SimpleLink link = new SimpleLink(URI.create("http://example.com/next"), params);
        assertEquals(
                "<http://example.com/next>; rel=\"next\"; title=\"Next Page\"; type=\"application/json\"",
                link.toString());
    }

    @Test
    @DisplayName("getRel returns the rel param value")
    void shouldReturnRelParam() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com"), Map.of(Link.REL, "self"));
        assertEquals("self", link.getRel());
    }

    @Test
    @DisplayName("getRels splits whitespace-separated rel values into a list")
    void shouldSplitWhitespaceSeparatedRels() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com"), Map.of(Link.REL, "next last"));
        assertEquals(2, link.getRels().size());
        assertTrue(link.getRels().contains("next"));
        assertTrue(link.getRels().contains("last"));
    }

    @Test
    @DisplayName("getRels returns empty list when rel param is absent")
    void shouldReturnEmptyListWhenNoRel() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com"), Map.of(Link.TYPE, "application/json"));
        assertTrue(link.getRels().isEmpty());
    }

    @Test
    @DisplayName("getTitle returns the title param value")
    void shouldReturnTitleParam() {
        SimpleLink link =
                new SimpleLink(URI.create("http://example.com"), Map.of(Link.REL, "item", Link.TITLE, "My Item"));
        assertEquals("My Item", link.getTitle());
    }

    @Test
    @DisplayName("getType returns the type param value")
    void shouldReturnTypeParam() {
        SimpleLink link = new SimpleLink(
                URI.create("http://example.com"), Map.of(Link.REL, "item", Link.TYPE, "application/json"));
        assertEquals("application/json", link.getType());
    }

    @Test
    @DisplayName("getParams returns an unmodifiable map")
    void shouldReturnUnmodifiableParams() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com"), Map.of(Link.REL, "self"));
        assertThrows(UnsupportedOperationException.class, () -> link.getParams().put("extra", "value"));
    }

    @Test
    @DisplayName("getUri returns the URI supplied at construction")
    void shouldReturnUri() {
        URI uri = URI.create("http://example.com/items");
        SimpleLink link = new SimpleLink(uri, Map.of(Link.REL, "items"));
        assertEquals(uri, link.getUri());
    }

    @Test
    @DisplayName("equals and hashCode: two links with same URI and params are equal")
    void shouldBeEqualWhenSameUriAndParams() {
        URI uri = URI.create("http://example.com/next");
        SimpleLink a = new SimpleLink(uri, Map.of(Link.REL, "next"));
        SimpleLink b = new SimpleLink(uri, Map.of(Link.REL, "next"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals: different URI means not equal")
    void shouldNotBeEqualWhenDifferentUri() {
        SimpleLink a = new SimpleLink(URI.create("http://example.com/a"), Map.of(Link.REL, "next"));
        SimpleLink b = new SimpleLink(URI.create("http://example.com/b"), Map.of(Link.REL, "next"));
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("getUriBuilder returns a UriBuilder initialized from the link URI")
    void shouldReturnUriBuilder() {
        SimpleLink link = new SimpleLink(URI.create("http://example.com/items"), Map.of());
        UriBuilder builder = link.getUriBuilder();
        assertNotNull(builder);
        assertEquals(URI.create("http://example.com/items"), builder.build());
    }

    @Test
    @DisplayName("toString escapes quotes in parameter values")
    void shouldEscapeQuotesInParameterValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("title", "A \"B\" C");
        SimpleLink link = new SimpleLink(URI.create("http://example.com"), params);
        String result = link.toString();
        assertTrue(result.contains("title=\"A \\\"B\\\" C\""), "quotes should be escaped but was: " + result);
    }
}
