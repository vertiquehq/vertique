// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleLinkBuilderTest {

    @Test
    @DisplayName("build with uri and rel produces a correct link")
    void shouldBuildLinkWithUriAndRel() {
        Link link = new SimpleLinkBuilder()
                .uri(URI.create("http://example.com/items"))
                .rel("items")
                .build();
        assertEquals(URI.create("http://example.com/items"), link.getUri());
        assertEquals("items", link.getRel());
    }

    @Test
    @DisplayName("build with title, type, and custom param stores all parameters")
    void shouldBuildLinkWithTitleTypeAndCustomParam() {
        Link link = new SimpleLinkBuilder()
                .uri(URI.create("http://example.com/next"))
                .rel("next")
                .title("Next Page")
                .type("application/json")
                .param("hreflang", "en")
                .build();
        assertEquals("Next Page", link.getTitle());
        assertEquals("application/json", link.getType());
        assertEquals("en", link.getParams().get("hreflang"));
    }

    @Test
    @DisplayName("rel appends to existing rel with a space separator")
    void shouldAppendRelWithSpaceSeparator() {
        Link link = new SimpleLinkBuilder()
                .uri(URI.create("http://example.com"))
                .rel("next")
                .rel("last")
                .build();
        assertEquals("next last", link.getRel());
    }

    @Test
    @DisplayName("uri(String) parses the URI string")
    void shouldParseUriFromString() {
        Link link = new SimpleLinkBuilder()
                .uri("http://example.com/items")
                .rel("items")
                .build();
        assertEquals(URI.create("http://example.com/items"), link.getUri());
    }

    @Test
    @DisplayName("baseUri resolves relative URI against base")
    void shouldResolveRelativeUriAgainstBaseUri() {
        Link link = new SimpleLinkBuilder()
                .baseUri("http://example.com/api/")
                .uri("items")
                .rel("items")
                .build();
        assertEquals(URI.create("http://example.com/api/items"), link.getUri());
    }

    @Test
    @DisplayName("link(Link) copies uri and all params from existing link")
    void shouldCopyUriAndParamsFromExistingLink() {
        Link original = new SimpleLinkBuilder()
                .uri(URI.create("http://example.com/next"))
                .rel("next")
                .title("Next Page")
                .build();
        Link copy = new SimpleLinkBuilder().link(original).build();
        assertEquals(original.getUri(), copy.getUri());
        assertEquals(original.getRel(), copy.getRel());
        assertEquals(original.getTitle(), copy.getTitle());
    }

    @Test
    @DisplayName("link(String) parses RFC 5988 format with rel and title")
    void shouldParseRfc5988LinkHeader() {
        Link link = new SimpleLinkBuilder()
                .link("<http://example.com/next>; rel=\"next\"; title=\"Next Page\"")
                .build();
        assertEquals(URI.create("http://example.com/next"), link.getUri());
        assertEquals("next", link.getRel());
        assertEquals("Next Page", link.getTitle());
    }

    @Test
    @DisplayName("link(String) preserves semicolons inside quoted parameter values")
    void shouldPreserveSemicolonsInQuotedValues() {
        Link link = new SimpleLinkBuilder()
                .link("<http://example.com/x>; title=\"A;B\"; rel=\"next\"")
                .build();
        assertEquals("A;B", link.getTitle());
        assertEquals("next", link.getRel());
    }

    @Test
    @DisplayName("link(String) with invalid format throws IllegalArgumentException")
    void shouldThrowOnInvalidLinkHeaderFormat() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleLinkBuilder().link("not-a-valid-link-header"));
    }

    @Test
    @DisplayName("build without setting URI throws IllegalStateException")
    void shouldThrowWhenUriNotSet() {
        assertThrows(
                IllegalStateException.class,
                () -> new SimpleLinkBuilder().rel("next").build());
    }

    @Test
    @DisplayName("buildRelativized relativizes the built URI against the given base URI")
    void shouldRelativizeUriAgainstGivenBase() {
        Link link = new SimpleLinkBuilder()
                .uri(URI.create("http://example.com/api/items"))
                .rel("items")
                .buildRelativized(URI.create("http://example.com/api/"));
        assertEquals(URI.create("items"), link.getUri());
        assertEquals("items", link.getRel());
    }

    @Test
    @DisplayName("uriBuilder(UriBuilder) sets URI from the builder")
    void shouldAcceptUriBuilder() {
        UriBuilder ub = UriBuilder.fromUri("http://example.com/next");
        Link link = new SimpleLinkBuilder().uriBuilder(ub).rel("next").build();
        assertEquals(URI.create("http://example.com/next"), link.getUri());
        assertEquals("next", link.getRel());
    }

    // --- Template support ---

    @Test
    @DisplayName("uri(String) with template does not throw and build resolves template")
    void shouldBuildLinkWithTemplateInUri() {
        Link link = new SimpleLinkBuilder().uri("/items/{id}").rel("item").build("42");
        assertEquals(URI.create("/items/42"), link.getUri());
    }

    @Test
    @DisplayName("build with multiple template parameters resolves all of them")
    void shouldResolveMultipleTemplates() {
        Link link = new SimpleLinkBuilder()
                .uri("/api/{version}/items/{id}")
                .rel("item")
                .build("v1", "42");
        assertEquals(URI.create("/api/v1/items/42"), link.getUri());
    }

    @Test
    @DisplayName("uriBuilder with unresolved template defers resolution to build")
    void shouldDeferUriBuilderResolutionToBuild() {
        UriBuilder ub = UriBuilder.fromUri("/items/{id}");
        Link link = new SimpleLinkBuilder().uriBuilder(ub).rel("item").build("42");
        assertEquals(URI.create("/items/42"), link.getUri());
    }

    @Test
    @DisplayName("buildRelativized resolves templates before relativizing")
    void shouldResolveTemplatesBeforeRelativizing() {
        Link link = new SimpleLinkBuilder()
                .uri("http://example.com/api/items/{id}")
                .rel("item")
                .buildRelativized(URI.create("http://example.com/api/"), "42");
        assertEquals(URI.create("items/42"), link.getUri());
    }

    @Test
    @DisplayName("baseUri resolves relative templated URI after template substitution")
    void shouldResolveBaseUriWithTemplates() {
        Link link = new SimpleLinkBuilder()
                .baseUri("http://example.com/api/")
                .uri("items/{id}")
                .rel("item")
                .build("42");
        assertEquals(URI.create("http://example.com/api/items/42"), link.getUri());
    }

    // --- Escaped quote handling ---

    @Test
    @DisplayName("link(String) handles escaped quotes inside quoted parameter values")
    void shouldHandleEscapedQuotesInQuotedValues() {
        Link link = new SimpleLinkBuilder()
                .link("<http://example.com/x>; title=\"A \\\"B;C\\\"\"; rel=\"next\"")
                .build();
        assertEquals("A \"B;C\"", link.getTitle());
        assertEquals("next", link.getRel());
    }

    @Test
    @DisplayName("link(String) unescapes backslash-escaped backslashes in quoted values")
    void shouldUnescapeBackslashesInQuotedValues() {
        Link link = new SimpleLinkBuilder()
                .link("<http://example.com/x>; title=\"A\\\\B\"; rel=\"next\"")
                .build();
        assertEquals("A\\B", link.getTitle());
    }

    @Test
    @DisplayName("Link round-trip preserves quotes in parameter values")
    void shouldRoundTripQuotedParamValues() {
        Link original = Link.fromUri("/x").title("A \"B\"").build();
        String serialized = original.toString();
        Link parsed = Link.valueOf(serialized);
        assertEquals("A \"B\"", parsed.getTitle());
    }
}
