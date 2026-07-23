// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleResponseBuilderTest {

    @Test
    @DisplayName("Response.status(400).build() returns 400 with no entity")
    void shouldBuild400WithNoEntity() {
        Response r = Response.status(400).build();
        assertEquals(400, r.getStatus());
        assertFalse(r.hasEntity());
        assertNull(r.getEntity());
    }

    @Test
    @DisplayName("Response.ok(\"test\").build() returns 200 with entity")
    void shouldBuildOkWithStringEntity() {
        Response r = Response.ok("test").build();
        assertEquals(200, r.getStatus());
        assertTrue(r.hasEntity());
        assertEquals("test", r.getEntity());
    }

    @Test
    @DisplayName("Response.ok().entity(obj).build() returns 200 with entity")
    void shouldBuildOkWithEntitySet() {
        Object obj = new Object();
        Response r = Response.ok().entity(obj).build();
        assertEquals(200, r.getStatus());
        assertTrue(r.hasEntity());
        assertSame(obj, r.getEntity());
    }

    @Test
    @DisplayName("Response.status(201).entity(obj).header(...).build() sets status, entity, header")
    void shouldBuildCreatedWithEntityAndHeader() {
        Object obj = new Object();
        Response r =
                Response.status(201).entity(obj).header("Location", "/items/1").build();
        assertEquals(201, r.getStatus());
        assertSame(obj, r.getEntity());
        assertEquals("/items/1", r.getHeaderString("Location"));
    }

    @Test
    @DisplayName("Response.noContent().build() returns 204 with no entity")
    void shouldBuildNoContent() {
        Response r = Response.noContent().build();
        assertEquals(204, r.getStatus());
        assertFalse(r.hasEntity());
    }

    @Test
    @DisplayName("Response.serverError().build() returns 500")
    void shouldBuildServerError() {
        Response r = Response.serverError().build();
        assertEquals(500, r.getStatus());
    }

    @Test
    @DisplayName("Response.created(URI) returns 201 with Location header")
    void shouldBuildCreatedWithLocation() {
        URI location = URI.create("/items/1");
        Response r = Response.created(location).build();
        assertEquals(201, r.getStatus());
        URI responseLocation = r.getLocation();
        assertNotNull(responseLocation);
        assertEquals("/items/1", responseLocation.toString());
    }

    @Test
    @DisplayName("new WebApplicationException(msg, 404) does not throw and getResponse() returns 404")
    void shouldConstructWebApplicationExceptionWithStatusCode() {
        WebApplicationException ex = new WebApplicationException("not found", 404);
        assertNotNull(ex.getResponse());
        assertEquals(404, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("new NotFoundException(msg) does not throw and getResponse() returns 404")
    void shouldConstructNotFoundException() {
        NotFoundException ex = new NotFoundException("missing");
        assertNotNull(ex.getResponse());
        assertEquals(404, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("new BadRequestException(msg) does not throw and getResponse() returns 400")
    void shouldConstructBadRequestException() {
        BadRequestException ex = new BadRequestException("invalid");
        assertNotNull(ex.getResponse());
        assertEquals(400, ex.getResponse().getStatus());
    }

    @Test
    @DisplayName("getStatusInfo() returns correct family and reason phrase")
    void shouldReturnCorrectStatusInfo() {
        Response r = Response.status(404).build();
        Response.StatusType info = r.getStatusInfo();
        assertEquals(404, info.getStatusCode());
        assertEquals(Response.Status.Family.CLIENT_ERROR, info.getFamily());
        assertEquals("Not Found", info.getReasonPhrase());

        Response r5 = Response.status(500).build();
        Response.StatusType info5 = r5.getStatusInfo();
        assertEquals(Response.Status.Family.SERVER_ERROR, info5.getFamily());
        assertEquals("Internal Server Error", info5.getReasonPhrase());
    }

    @Test
    @DisplayName("getStringHeaders() converts header values to strings")
    void shouldConvertHeadersToStrings() {
        Response r = Response.status(200).header("X-Count", 42).build();
        var stringHeaders = r.getStringHeaders();
        assertNotNull(stringHeaders);
        assertEquals("42", stringHeaders.getFirst("X-Count"));
    }

    @Test
    @DisplayName("getHeaderString() joins multiple values with \", \"")
    void shouldJoinMultipleHeaderValues() {
        Response r = Response.status(200)
                .header("Accept", "application/json")
                .header("Accept", "text/plain")
                .build();
        String headerString = r.getHeaderString("Accept");
        assertEquals("application/json, text/plain", headerString);
    }

    @Test
    @DisplayName("type(MediaType) sets Content-Type correctly")
    void shouldSetContentTypeWithMediaType() {
        Response r = Response.ok("body").type(MediaType.APPLICATION_JSON_TYPE).build();
        String contentType = r.getHeaderString("Content-Type");
        assertNotNull(contentType);
        assertTrue(contentType.contains("application/json"));
    }

    @Test
    @DisplayName("cookie() stores NewCookie as Set-Cookie header")
    void shouldStoreCookieAsHeader() {
        NewCookie cookie = new NewCookie.Builder("sid").value("abc123").build();
        Response r = Response.ok().cookie(cookie).build();
        Map<String, NewCookie> cookies = r.getCookies();
        assertEquals(1, cookies.size());
        assertTrue(cookies.containsKey("sid"));
        assertEquals("abc123", cookies.get("sid").getValue());
    }

    @Test
    @DisplayName("cacheControl() sets Cache-Control header")
    void shouldSetCacheControlHeader() {
        CacheControl cc = new CacheControl();
        cc.setMaxAge(3600);
        cc.setNoCache(true);
        Response r = Response.ok().cacheControl(cc).build();
        String value = r.getHeaderString("Cache-Control");
        assertNotNull(value);
        assertTrue(value.contains("no-cache"));
    }

    @Test
    @DisplayName("expires() sets Expires header as HTTP date")
    void shouldSetExpiresHeader() {
        Date date = new Date(0); // epoch
        Response r = Response.ok().expires(date).build();
        String value = r.getHeaderString("Expires");
        assertNotNull(value);
        assertTrue(value.contains("1970"));
    }

    @Test
    @DisplayName("lastModified() sets Last-Modified header and getLastModified() parses it back")
    void shouldSetAndGetLastModified() {
        // Use a date with second precision (HTTP dates don't include millis)
        long epochSeconds = 1700000000L;
        Date date = new Date(epochSeconds * 1000);
        Response r = Response.ok().lastModified(date).build();
        Date parsed = r.getLastModified();
        assertNotNull(parsed);
        assertEquals(epochSeconds * 1000, parsed.getTime());
    }

    @Test
    @DisplayName("tag(EntityTag) sets ETag header")
    void shouldSetEntityTagHeader() {
        EntityTag etag = new EntityTag("abc123");
        Response r = Response.ok().tag(etag).build();
        EntityTag parsed = r.getEntityTag();
        assertNotNull(parsed);
        assertEquals("abc123", parsed.getValue());
        assertFalse(parsed.isWeak());
    }

    @Test
    @DisplayName("tag(String) sets quoted ETag header")
    void shouldSetStringTagHeader() {
        Response r = Response.ok().tag("v1").build();
        String value = r.getHeaderString("ETag");
        assertNotNull(value);
        assertEquals("\"v1\"", value);
    }

    @Test
    @DisplayName("allow(String...) sets Allow header")
    void shouldSetAllowMethods() {
        Response r = Response.ok().allow("GET", "POST").build();
        Set<String> methods = r.getAllowedMethods();
        assertEquals(Set.of("GET", "POST"), methods);
    }

    @Test
    @DisplayName("replaceAll() clears and replaces headers")
    void shouldReplaceAllHeaders() {
        MultivaluedMap<String, Object> newHeaders = new MultivaluedHashMap<>();
        newHeaders.add("X-Custom", "value");
        Response r =
                Response.ok().header("Old-Header", "old").replaceAll(newHeaders).build();
        assertNotNull(r.getHeaderString("X-Custom"));
        assertNull(r.getHeaderString("Old-Header"));
    }

    @Test
    @DisplayName("encoding(String) sets Content-Encoding header")
    void shouldSetEncodingHeader() {
        Response r = Response.ok().encoding("gzip").build();
        assertEquals("gzip", r.getHeaderString("Content-Encoding"));
    }

    @Test
    @DisplayName("encoding(null) removes Content-Encoding header")
    void shouldRemoveEncodingHeaderWhenNull() {
        Response r = Response.ok().encoding("gzip").encoding(null).build();
        assertNull(r.getHeaderString("Content-Encoding"));
    }

    @Test
    @DisplayName("language(String) sets Content-Language header")
    void shouldSetLanguageStringHeader() {
        Response r = Response.ok().language("en-US").build();
        assertEquals("en-US", r.getHeaderString("Content-Language"));
        assertEquals(Locale.forLanguageTag("en-US"), r.getLanguage());
    }

    @Test
    @DisplayName("language(Locale) sets Content-Language header using toLanguageTag()")
    void shouldSetLanguageLocaleHeader() {
        Response r = Response.ok().language(Locale.GERMAN).build();
        assertEquals("de", r.getHeaderString("Content-Language"));
        assertEquals(Locale.GERMAN, r.getLanguage());
    }

    @Test
    @DisplayName("language(null) removes Content-Language header")
    void shouldRemoveLanguageHeaderWhenNull() {
        Response r = Response.ok().language("en").language((String) null).build();
        assertNull(r.getHeaderString("Content-Language"));
    }

    @Test
    @DisplayName("variant() sets type, language, and encoding from Variant")
    void shouldSetHeadersFromVariant() {
        Variant v = new Variant(MediaType.APPLICATION_JSON_TYPE, Locale.FRENCH, "gzip");
        Response r = Response.ok().variant(v).build();
        assertNotNull(r.getHeaderString("Content-Type"));
        assertTrue(r.getHeaderString("Content-Type").contains("application/json"));
        assertEquals("fr", r.getHeaderString("Content-Language"));
        assertEquals("gzip", r.getHeaderString("Content-Encoding"));
    }

    @Test
    @DisplayName("variant(null) clears type, language, and encoding headers")
    void shouldClearHeadersWhenVariantIsNull() {
        Response r = Response.ok()
                .type(MediaType.APPLICATION_JSON_TYPE)
                .language("en")
                .encoding("gzip")
                .variant(null)
                .build();
        assertNull(r.getHeaderString("Content-Type"));
        assertNull(r.getHeaderString("Content-Language"));
        assertNull(r.getHeaderString("Content-Encoding"));
    }

    @Test
    @DisplayName("links() sets Link headers and links(null) clears them")
    void shouldSetAndClearLinkHeaders() {
        Response r = Response.ok().links().build();
        assertNull(r.getHeaderString("Link"));
    }

    @Test
    @DisplayName("link(URI, String) adds a Link header")
    void shouldAddLinkUriHeader() {
        Response r = Response.ok().link(URI.create("/next"), "next").build();
        assertNotNull(r.getHeaderString("Link"));
    }

    @Test
    @DisplayName("getLanguage returns null when Content-Language header is absent")
    void shouldReturnNullLanguageWhenHeaderAbsent() {
        Response r = Response.ok().build();
        assertNull(r.getLanguage());
    }

    @Test
    @DisplayName("getLinks returns links stored by the builder")
    void shouldReturnStoredLinks() {
        Link a = new SimpleLinkBuilder().uri("/prev").rel("prev").build();
        Link b = new SimpleLinkBuilder().uri("/next").rel("next").build();
        Response r = Response.ok().links(a, b).build();
        Set<Link> links = r.getLinks();
        assertEquals(2, links.size());
        assertTrue(links.contains(a));
        assertTrue(links.contains(b));
    }

    @Test
    @DisplayName("getLinks returns empty set when no links have been added")
    void shouldReturnEmptyLinksWhenNoneAdded() {
        Response r = Response.ok().build();
        assertTrue(r.getLinks().isEmpty());
    }

    @Test
    @DisplayName("hasLink returns true for a relation present in stored links")
    void shouldReturnTrueForMatchingRelation() {
        Response r = Response.ok().link(URI.create("/next"), "next").build();
        assertTrue(r.hasLink("next"));
    }

    @Test
    @DisplayName("hasLink returns false for a relation not present in stored links")
    void shouldReturnFalseForNonMatchingRelation() {
        Response r = Response.ok().link(URI.create("/next"), "next").build();
        assertFalse(r.hasLink("prev"));
    }

    @Test
    @DisplayName("getLink returns the matching link by relation")
    void shouldReturnMatchingLinkByRelation() {
        Link next = new SimpleLinkBuilder().uri("/next").rel("next").build();
        Response r = Response.ok().links(next).build();
        Link found = r.getLink("next");
        assertNotNull(found);
        assertEquals("/next", found.getUri().toString());
    }

    @Test
    @DisplayName("getLink returns null for a relation that does not match any stored link")
    void shouldReturnNullForNonMatchingGetLink() {
        Response r = Response.ok().link(URI.create("/next"), "next").build();
        assertNull(r.getLink("prev"));
    }

    @Test
    @DisplayName("getLinkBuilder returns a builder initialized from the matching link")
    void shouldReturnLinkBuilderInitializedFromMatchingLink() {
        Link original = new SimpleLinkBuilder()
                .uri("/items")
                .rel("items")
                .title("All Items")
                .build();
        Response r = Response.ok().links(original).build();
        Link.Builder builder = r.getLinkBuilder("items");
        assertNotNull(builder);
        Link rebuilt = builder.build();
        assertEquals(original.getUri(), rebuilt.getUri());
        assertEquals(original.getRel(), rebuilt.getRel());
        assertEquals(original.getTitle(), rebuilt.getTitle());
    }

    @Test
    @DisplayName("getLinkBuilder returns null when the relation is not found")
    void shouldReturnNullLinkBuilderWhenRelationNotFound() {
        Response r = Response.ok().build();
        assertNull(r.getLinkBuilder("missing"));
    }

    @Test
    @DisplayName("links(Link...) replaces all previously added links")
    void shouldReplaceAllLinksWithNewOnes() {
        Link first = new SimpleLinkBuilder().uri("/first").rel("first").build();
        Link replacement = new SimpleLinkBuilder().uri("/only").rel("only").build();
        Response r = Response.ok().links(first).links(replacement).build();
        Set<Link> links = r.getLinks();
        assertEquals(1, links.size());
        assertTrue(links.contains(replacement));
        assertFalse(links.contains(first));
    }

    @Test
    @DisplayName("link(String, String) adds a Link header from a string URI")
    void shouldAddLinkHeaderFromStringUri() {
        Response r = Response.ok().link("/next", "next").build();
        assertTrue(r.hasLink("next"));
        assertEquals("/next", r.getLink("next").getUri().toString());
    }

    @Test
    @DisplayName("getLinks parses string-backed Link headers")
    void shouldParseStringBackedLinkHeaders() {
        Response r = Response.ok().header("Link", "</next>; rel=\"next\"").build();
        assertTrue(r.hasLink("next"));
        assertEquals(URI.create("/next"), r.getLink("next").getUri());
    }

    @Test
    @DisplayName("getLinks returns both Link objects and parsed string headers")
    void shouldReturnBothLinkObjectsAndParsedStrings() {
        Link linkObj = new SimpleLinkBuilder().uri("/prev").rel("prev").build();
        Response r = Response.ok()
                .links(linkObj)
                .header("Link", "</next>; rel=\"next\"")
                .build();
        assertEquals(2, r.getLinks().size());
        assertTrue(r.hasLink("prev"));
        assertTrue(r.hasLink("next"));
    }

    @Test
    @DisplayName("getLinks splits comma-combined Link header into separate links")
    void shouldSplitCommaCombinedLinkHeader() {
        Response r = Response.ok()
                .header("Link", "</prev>; rel=\"prev\", </next>; rel=\"next\"")
                .build();
        assertEquals(2, r.getLinks().size());
        assertTrue(r.hasLink("prev"));
        assertTrue(r.hasLink("next"));
        assertEquals(URI.create("/prev"), r.getLink("prev").getUri());
        assertEquals(URI.create("/next"), r.getLink("next").getUri());
    }

    @Test
    @DisplayName("getLinks splits combined header without breaking URIs that contain commas")
    void shouldSplitCombinedLinkHeaderPreservingCommasInUri() {
        Response r = Response.ok()
                .header("Link", "</items/a,b>; rel=\"next\", </other>; rel=\"prev\"")
                .build();
        assertEquals(2, r.getLinks().size());
        assertTrue(r.hasLink("next"));
        assertTrue(r.hasLink("prev"));
        assertEquals(URI.create("/items/a,b"), r.getLink("next").getUri());
        assertEquals(URI.create("/other"), r.getLink("prev").getUri());
    }

    @Test
    @DisplayName("variants(Variant...) throws UnsupportedOperationException")
    void shouldThrowOnVariantsVarargs() {
        assertThrows(UnsupportedOperationException.class, () -> Response.ok().variants(new Variant[0]));
    }

    @Test
    @DisplayName("variants(List) throws UnsupportedOperationException")
    void shouldThrowOnVariantsList() {
        assertThrows(UnsupportedOperationException.class, () -> Response.ok().variants(List.of()));
    }
}
