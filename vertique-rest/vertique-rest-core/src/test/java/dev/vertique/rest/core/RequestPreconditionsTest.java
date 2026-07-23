// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.request.RequestPreconditions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestPreconditions}.
 *
 * <p>Verifies RFC 9110 §13.2.2 precondition evaluation order:
 * If-Match (§13.1.1) → If-Unmodified-Since (§13.1.4) → If-None-Match (§13.1.2)
 * → If-Modified-Since (§13.1.3), as well as HEAD method handling, wildcard matching,
 * weak/strong ETag comparison, date parsing, and caching in {@link RoutingContext#data()}.
 */
class RequestPreconditionsTest {

    // --- Factory and caching ---

    @Test
    @DisplayName("Should create RequestPreconditions from RoutingContext headers")
    void shouldCreateFromRoutingContext() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);

        RequestPreconditions preconditions = RequestPreconditions.from(ctx);

        assertNotNull(preconditions);
        assertTrue(preconditions.hasConditions());
    }

    @Test
    @DisplayName("Should cache RequestPreconditions in ctx.data() and return same instance")
    void shouldCacheInContextData() {
        Map<String, Object> data = new HashMap<>();
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);
        when(ctx.data()).thenReturn(data);

        RequestPreconditions first = RequestPreconditions.from(ctx);
        RequestPreconditions second = RequestPreconditions.from(ctx);

        assertSame(first, second);
    }

    // --- hasConditions ---

    @Test
    @DisplayName("Should return false for hasConditions() when no conditional headers present")
    void shouldHaveNoConditionsWhenEmpty() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, null, null);

        RequestPreconditions preconditions = RequestPreconditions.from(ctx);

        assertFalse(preconditions.hasConditions());
    }

    @Test
    @DisplayName("Should return true for hasConditions() when If-None-Match is present")
    void shouldHaveConditionsWhenIfNoneMatchPresent() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);

        assertTrue(RequestPreconditions.from(ctx).hasConditions());
    }

    // --- isHead ---

    @Test
    @DisplayName("Should return true for isHead() on HEAD requests")
    void shouldDetectHeadMethod() {
        RoutingContext ctx = mockContext(HttpMethod.HEAD, null, null, null, null);

        assertTrue(RequestPreconditions.from(ctx).isHead());
    }

    @Test
    @DisplayName("Should return false for isHead() on GET requests")
    void shouldNotDetectHeadForGet() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, null, null);

        assertFalse(RequestPreconditions.from(ctx).isHead());
    }

    // --- evaluate(EntityTag) — If-None-Match ---

    @Test
    @DisplayName("Should return 304 for GET with If-None-Match matching ETag (weak comparison)")
    void shouldReturn304WhenIfNoneMatchMatches() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should return 304 for HEAD with If-None-Match matching ETag")
    void shouldReturn304ForHeadWithMatchingIfNoneMatch() {
        RoutingContext ctx = mockContext(HttpMethod.HEAD, "\"abc\"", null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should return null when If-None-Match does not match ETag")
    void shouldReturnNullWhenIfNoneMatchDoesNotMatch() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"xyz\"", null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return 412 for PUT with If-None-Match matching ETag")
    void shouldReturn412ForPutWithMatchingIfNoneMatch() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, "\"abc\"", null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should match weak ETag using weak comparison for If-None-Match")
    void shouldMatchWeakETagWithIfNoneMatch() {
        // If-None-Match: W/"abc" — weak ETag in header
        RoutingContext ctx = mockContext(HttpMethod.GET, "W/\"abc\"", null, null, null);
        EntityTag etag = new EntityTag("abc"); // strong resource ETag

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should match weak resource ETag using weak comparison for If-None-Match")
    void shouldMatchWeakResourceETagWithIfNoneMatch() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);
        EntityTag etag = new EntityTag("abc", true); // weak resource ETag

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    // --- evaluate(EntityTag) — If-Match ---

    @Test
    @DisplayName("Should return null when If-Match matches strong ETag (request proceeds)")
    void shouldReturnNullWhenIfMatchMatchesStrongEtag() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "\"abc\"", null, null);
        EntityTag etag = new EntityTag("abc"); // strong

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return 412 when If-Match does not match ETag")
    void shouldReturn412WhenIfMatchDoesNotMatch() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "\"xyz\"", null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should return 412 when If-Match is present and ETag is null")
    void shouldReturn412WhenIfMatchPresentAndEtagNull() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "\"abc\"", null, null);

        Response result = RequestPreconditions.from(ctx).evaluate((EntityTag) null);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should return 412 when If-Match is '*' but ETag is null")
    void shouldReturn412WhenIfMatchWildcardAndEtagNull() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "*", null, null);

        Response result = RequestPreconditions.from(ctx).evaluate((EntityTag) null);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should return null when If-Match is '*' and ETag is a strong ETag")
    void shouldReturnNullWhenIfMatchWildcardAndStrongEtag() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "*", null, null);
        EntityTag etag = new EntityTag("anything");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return 412 when If-Match is '*' but ETag is weak")
    void shouldReturn412WhenIfMatchWildcardAndWeakEtag() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "*", null, null);
        EntityTag etag = new EntityTag("abc", true); // weak — fails strong comparison

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should return 412 when If-Match lists strong ETag but resource has weak ETag")
    void shouldReturn412WhenIfMatchRequiresStrongButResourceIsWeak() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "\"abc\"", null, null);
        EntityTag etag = new EntityTag("abc", true); // weak — fails strong comparison

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    // --- evaluate(EntityTag, Instant) — If-Modified-Since / If-Unmodified-Since ---

    @Test
    @DisplayName("Should return 304 for GET when resource not modified since threshold")
    void shouldReturn304WhenNotModifiedSince() {
        // If-Modified-Since: Thu, 01 Jan 2015 00:00:00 GMT
        // Resource last modified: 2014 (before threshold) → 304
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, "Thu, 01 Jan 2015 00:00:00 GMT", null);
        Instant lastModified = Instant.parse("2014-06-15T12:00:00Z");

        Response result = RequestPreconditions.from(ctx).evaluate(null, lastModified);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should return null for GET when resource modified after threshold")
    void shouldReturnNullWhenModifiedAfterIfModifiedSince() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, "Thu, 01 Jan 2015 00:00:00 GMT", null);
        Instant lastModified = Instant.parse("2016-01-01T00:00:00Z");

        Response result = RequestPreconditions.from(ctx).evaluate(null, lastModified);

        assertNull(result);
    }

    @Test
    @DisplayName("Should return 412 for PUT when resource modified after If-Unmodified-Since")
    void shouldReturn412WhenModifiedAfterIfUnmodifiedSince() {
        // If-Unmodified-Since: Thu, 01 Jan 2015 00:00:00 GMT
        // Resource last modified: 2016 (after threshold) → 412
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, null, null, "Thu, 01 Jan 2015 00:00:00 GMT");
        Instant lastModified = Instant.parse("2016-01-01T00:00:00Z");

        Response result = RequestPreconditions.from(ctx).evaluate(null, lastModified);

        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should return null when resource not modified since If-Unmodified-Since threshold")
    void shouldReturnNullWhenNotModifiedBeforeIfUnmodifiedSince() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, null, null, "Thu, 01 Jan 2015 00:00:00 GMT");
        Instant lastModified = Instant.parse("2014-06-15T12:00:00Z");

        Response result = RequestPreconditions.from(ctx).evaluate(null, lastModified);

        assertNull(result);
    }

    @Test
    @DisplayName("Should ignore If-Modified-Since for non-GET/HEAD methods")
    void shouldIgnoreIfModifiedSinceForPut() {
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, null, "Thu, 01 Jan 2015 00:00:00 GMT", null);
        Instant lastModified = Instant.parse("2014-06-15T12:00:00Z");

        Response result = RequestPreconditions.from(ctx).evaluate(null, lastModified);

        // PUT ignores If-Modified-Since
        assertNull(result);
    }

    // --- Wildcard If-None-Match ---

    @Test
    @DisplayName("Should return 304 when If-None-Match is '*' and ETag exists")
    void shouldReturn304WhenIfNoneMatchWildcardAndEtagExists() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "*", null, null, null);
        EntityTag etag = new EntityTag("anything");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should return null when If-None-Match is '*' but no ETag exists")
    void shouldReturnNullWhenIfNoneMatchWildcardAndNoEtag() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "*", null, null, null);

        Response result = RequestPreconditions.from(ctx).evaluate((EntityTag) null);

        assertNull(result);
    }

    // --- RFC 9110 §13.2.2 evaluation order ---

    @Test
    @DisplayName("Should evaluate If-Match before If-None-Match (step 1 before step 3)")
    void shouldEvaluateIfMatchBeforeIfNoneMatch() {
        // If-Match: "wrong" → 412 (step 1)
        // If-None-Match: "abc" → would give 304 (step 3) if reached
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", "\"wrong\"", null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        // Step 1 (If-Match) fires first → 412
        assertNotNull(result);
        assertEquals(412, result.getStatus());
    }

    @Test
    @DisplayName("Should skip If-Unmodified-Since when If-Match is present (step 2 skipped)")
    void shouldSkipIfUnmodifiedSinceWhenIfMatchPresent() {
        // If-Match: "abc" → matches (passes)
        // If-Unmodified-Since: resource is modified (would give 412 in step 2 if no If-Match)
        // Result: step 2 skipped because If-Match is present → null (proceed)
        RoutingContext ctx = mockContext(HttpMethod.PUT, null, "\"abc\"", null, "Thu, 01 Jan 2015 00:00:00 GMT");
        EntityTag etag = new EntityTag("abc");
        Instant lastModified = Instant.parse("2016-01-01T00:00:00Z"); // after threshold

        Response result = RequestPreconditions.from(ctx).evaluate(etag, lastModified);

        assertNull(result); // If-Match matched → proceed; If-Unmodified-Since skipped
    }

    // --- No conditions ---

    @Test
    @DisplayName("Should return null when no conditional headers are present")
    void shouldReturnNullWhenNoConditions() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNull(result);
    }

    // --- evaluate(Response) ---

    @Test
    @DisplayName("Should evaluate preconditions from Response ETag header")
    void shouldEvaluateFromResponseEtag() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"abc\"", null, null, null);
        // Use a mocked Response so we can control getEntityTag() independently of the stub runtime
        Response response = mock(Response.class);
        when(response.getEntityTag()).thenReturn(new EntityTag("abc"));
        when(response.getStringHeaders()).thenReturn(new jakarta.ws.rs.core.MultivaluedHashMap<>());

        Response result = RequestPreconditions.from(ctx).evaluate(response);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    @Test
    @DisplayName("Should return null from evaluate(Response) when no ETag and no conditions")
    void shouldReturnNullFromEvaluateResponseWhenNoConditions() {
        RoutingContext ctx = mockContext(HttpMethod.GET, null, null, null, null);
        Response response = mock(Response.class);
        when(response.getEntityTag()).thenReturn(null);
        when(response.getStringHeaders()).thenReturn(new jakarta.ws.rs.core.MultivaluedHashMap<>());

        Response result = RequestPreconditions.from(ctx).evaluate(response);

        assertNull(result);
    }

    // --- Multiple ETags in If-None-Match list ---

    @Test
    @DisplayName("Should match any ETag in a comma-separated If-None-Match list")
    void shouldMatchAnyETagInList() {
        RoutingContext ctx = mockContext(HttpMethod.GET, "\"xyz\", \"abc\", W/\"def\"", null, null, null);
        EntityTag etag = new EntityTag("abc");

        Response result = RequestPreconditions.from(ctx).evaluate(etag);

        assertNotNull(result);
        assertEquals(304, result.getStatus());
    }

    // --- Helpers ---

    /**
     * Builds a mocked {@link RoutingContext} with the specified HTTP method and conditional headers.
     *
     * @param method            the HTTP method
     * @param ifNoneMatch       value for the {@code If-None-Match} header, or {@code null}
     * @param ifMatch           value for the {@code If-Match} header, or {@code null}
     * @param ifModifiedSince   value for the {@code If-Modified-Since} header, or {@code null}
     * @param ifUnmodifiedSince value for the {@code If-Unmodified-Since} header, or {@code null}
     * @return the mocked routing context
     */
    private RoutingContext mockContext(
            HttpMethod method, String ifNoneMatch, String ifMatch, String ifModifiedSince, String ifUnmodifiedSince) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.method()).thenReturn(method);
        when(request.getHeader("If-None-Match")).thenReturn(ifNoneMatch);
        when(request.getHeader("If-Match")).thenReturn(ifMatch);
        when(request.getHeader("If-Modified-Since")).thenReturn(ifModifiedSince);
        when(request.getHeader("If-Unmodified-Since")).thenReturn(ifUnmodifiedSince);
        when(ctx.data()).thenReturn(new HashMap<>());
        return ctx;
    }
}
