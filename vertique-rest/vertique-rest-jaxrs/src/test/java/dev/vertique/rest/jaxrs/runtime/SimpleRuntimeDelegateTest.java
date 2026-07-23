// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleRuntimeDelegateTest {

    // --- CacheControl ---

    @Test
    @DisplayName("CacheControl.valueOf parses no-cache, no-store, and max-age directives")
    void shouldParseNoCacheNoStoreAndMaxAge() {
        CacheControl cc = CacheControl.valueOf("no-cache, no-store, max-age=3600");
        assertTrue(cc.isNoCache());
        assertTrue(cc.isNoStore());
        assertEquals(3600, cc.getMaxAge());
    }

    @Test
    @DisplayName("CacheControl round-trip: create, toString, fromString preserves all fields")
    void shouldRoundTripCacheControl() {
        CacheControl original = new CacheControl();
        original.setNoCache(true);
        original.setNoStore(true);
        original.setMaxAge(600);

        String serialized = original.toString();
        CacheControl parsed = CacheControl.valueOf(serialized);

        assertTrue(parsed.isNoCache());
        assertTrue(parsed.isNoStore());
        assertEquals(600, parsed.getMaxAge());
    }

    @Test
    @DisplayName("CacheControl.valueOf parses s-maxage, must-revalidate, proxy-revalidate, private")
    void shouldParseSMaxAgeMustRevalidateProxyRevalidatePrivate() {
        CacheControl cc = CacheControl.valueOf("private, must-revalidate, proxy-revalidate, s-maxage=120");
        assertTrue(cc.isPrivate());
        assertTrue(cc.isMustRevalidate());
        assertTrue(cc.isProxyRevalidate());
        assertEquals(120, cc.getSMaxAge());
    }

    @Test
    @DisplayName("CacheControl.valueOf defaults no-transform to false when the directive is absent")
    void shouldDefaultNoTransformToFalseWhenAbsent() {
        CacheControl cc = CacheControl.valueOf("no-cache");
        assertFalse(cc.isNoTransform(), "no-transform should be false when not present in the header value");
    }

    @Test
    @DisplayName("CacheControl.valueOf parses cache extension with quoted value")
    void shouldParseCacheExtension() {
        CacheControl cc = CacheControl.valueOf("no-cache, custom-ext=\"value\"");
        assertTrue(cc.isNoCache());
        assertEquals("value", cc.getCacheExtension().get("custom-ext"));
    }

    @Test
    @DisplayName("CacheControl.valueOf preserves case of extension values")
    void shouldPreserveCaseOfExtensionValues() {
        CacheControl cc = CacheControl.valueOf("custom-ext=AbCdEf");
        assertEquals("AbCdEf", cc.getCacheExtension().get("custom-ext"));
    }

    @Test
    @DisplayName("CacheControl.valueOf is case-insensitive for directive names")
    void shouldBeCaseInsensitiveForDirectiveNames() {
        CacheControl cc = CacheControl.valueOf("No-Cache, No-Store");
        assertTrue(cc.isNoCache());
        assertTrue(cc.isNoStore());
    }

    @Test
    @DisplayName("CacheControl round-trip preserves mixed-case extension value")
    void shouldRoundTripMixedCaseExtensionValue() {
        CacheControl original = new CacheControl();
        original.getCacheExtension().put("x-custom", "MiXeD");
        String serialized = original.toString();
        CacheControl parsed = CacheControl.valueOf(serialized);
        assertEquals("MiXeD", parsed.getCacheExtension().get("x-custom"));
    }

    @Test
    @DisplayName("CacheControl.valueOf preserves commas inside quoted extension values")
    void shouldPreserveCommasInQuotedExtensionValues() {
        CacheControl cc = CacheControl.valueOf("custom-ext=\"A,B\"");
        assertEquals("A,B", cc.getCacheExtension().get("custom-ext"));
    }

    @Test
    @DisplayName("CacheControl.valueOf unescapes quoted-pair in extension values")
    void shouldUnescapeQuotedPairInExtensionValues() {
        CacheControl cc = CacheControl.valueOf("x=\"A \\\"B\\\"\"");
        assertEquals("A \"B\"", cc.getCacheExtension().get("x"));
    }

    @Test
    @DisplayName("CacheControl.valueOf unescapes backslash-escaped backslashes in extension values")
    void shouldUnescapeBackslashesInExtensionValues() {
        CacheControl cc = CacheControl.valueOf("x=\"A\\\\B\"");
        assertEquals("A\\B", cc.getCacheExtension().get("x"));
    }

    @Test
    @DisplayName("CacheControl.valueOf parses private with field-name list")
    void shouldParsePrivateWithFieldNames() {
        CacheControl cc = CacheControl.valueOf("private=\"Set-Cookie\"");
        assertTrue(cc.isPrivate());
        assertTrue(cc.getPrivateFields().contains("Set-Cookie"));
    }

    @Test
    @DisplayName("CacheControl.valueOf parses no-cache with field-name list")
    void shouldParseNoCacheWithFieldNames() {
        CacheControl cc = CacheControl.valueOf("no-cache=\"Authorization\"");
        assertTrue(cc.isNoCache());
        assertTrue(cc.getNoCacheFields().contains("Authorization"));
    }

    @Test
    @DisplayName("CacheControl.valueOf parses no-cache with multiple field names")
    void shouldParseNoCacheWithMultipleFieldNames() {
        CacheControl cc = CacheControl.valueOf("no-cache=\"Authorization, Set-Cookie\"");
        assertTrue(cc.isNoCache());
        assertTrue(cc.getNoCacheFields().contains("Authorization"));
        assertTrue(cc.getNoCacheFields().contains("Set-Cookie"));
    }

    @Test
    @DisplayName("CacheControl.valueOf(null) throws IllegalArgumentException")
    void shouldThrowOnNullCacheControl() {
        assertThrows(IllegalArgumentException.class, () -> CacheControl.valueOf(null));
    }

    // --- EntityTag ---

    @Test
    @DisplayName("EntityTag.valueOf parses a strong (quoted) entity tag")
    void shouldParseStrongEntityTag() {
        EntityTag tag = EntityTag.valueOf("\"abc\"");
        assertEquals("abc", tag.getValue());
        assertFalse(tag.isWeak());
    }

    @Test
    @DisplayName("EntityTag.valueOf parses a weak entity tag prefixed with W/")
    void shouldParseWeakEntityTag() {
        EntityTag tag = EntityTag.valueOf("W/\"abc\"");
        assertEquals("abc", tag.getValue());
        assertTrue(tag.isWeak());
    }

    @Test
    @DisplayName("EntityTag round-trip: create, toString, valueOf preserves value and weak flag")
    void shouldRoundTripEntityTag() {
        EntityTag original = new EntityTag("v42", true);
        String serialized = original.toString();
        EntityTag parsed = EntityTag.valueOf(serialized);
        assertEquals("v42", parsed.getValue());
        assertTrue(parsed.isWeak());
    }

    @Test
    @DisplayName("EntityTag.valueOf(null) throws IllegalArgumentException")
    void shouldThrowOnNullEntityTag() {
        assertThrows(IllegalArgumentException.class, () -> EntityTag.valueOf(null));
    }

    // --- Link builder ---

    @Test
    @DisplayName("createLinkBuilder returns a builder that produces a working link")
    void shouldReturnWorkingLinkBuilder() {
        SimpleRuntimeDelegate delegate = new SimpleRuntimeDelegate();
        Link link = delegate.createLinkBuilder()
                .uri("http://example.com/next")
                .rel("next")
                .build();
        assertEquals("next", link.getRel());
        assertEquals("http://example.com/next", link.getUri().toString());
    }

    // --- UriBuilder ---

    @Test
    @DisplayName("createUriBuilder returns a working UriBuilder")
    void shouldCreateUriBuilder() {
        UriBuilder builder = new SimpleRuntimeDelegate().createUriBuilder();
        assertNotNull(builder);
        URI uri = builder.scheme("http").host("example.com").path("/api").build();
        assertEquals("http://example.com/api", uri.toString());
    }

    // --- Response builder ---

    @Test
    @DisplayName("createResponseBuilder returns a builder that can produce a 200 response")
    void shouldReturnWorkingResponseBuilder() {
        SimpleRuntimeDelegate delegate = new SimpleRuntimeDelegate();
        Response r = delegate.createResponseBuilder().status(200).entity("ok").build();
        assertEquals(200, r.getStatus());
        assertEquals("ok", r.getEntity());
    }

    // --- NewCookie serialization ---

    @Test
    @DisplayName("NewCookie toString includes SameSite=Lax when set")
    void shouldSerializeSameSiteLax() {
        NewCookie cookie = new NewCookie.Builder("sid")
                .value("abc123")
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        String result = cookie.toString();
        assertTrue(result.contains("; SameSite=Lax"), "should contain SameSite=Lax but was: " + result);
    }

    @Test
    @DisplayName("NewCookie toString includes SameSite=Strict when set")
    void shouldSerializeSameSiteStrict() {
        NewCookie cookie = new NewCookie.Builder("sid")
                .value("abc123")
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
        String result = cookie.toString();
        assertTrue(result.contains("; SameSite=Strict"), "should contain SameSite=Strict but was: " + result);
    }

    @Test
    @DisplayName("NewCookie toString includes SameSite=None when set")
    void shouldSerializeSameSiteNone() {
        NewCookie cookie = new NewCookie.Builder("sid")
                .value("abc123")
                .sameSite(NewCookie.SameSite.NONE)
                .secure(true)
                .build();
        String result = cookie.toString();
        assertTrue(result.contains("; SameSite=None"), "should contain SameSite=None but was: " + result);
    }

    @Test
    @DisplayName("NewCookie toString omits SameSite when not set")
    void shouldOmitSameSiteWhenNull() {
        NewCookie cookie = new NewCookie.Builder("sid").value("abc123").build();
        String result = cookie.toString();
        assertFalse(result.contains("SameSite"), "should not contain SameSite but was: " + result);
    }

    @Test
    @DisplayName("NewCookie toString includes Expires in RFC 1123 format when set")
    void shouldSerializeExpiry() {
        Date expiry = Date.from(Instant.parse("2025-01-01T00:00:00Z"));
        NewCookie cookie =
                new NewCookie.Builder("sid").value("abc123").expiry(expiry).build();
        String result = cookie.toString();
        assertTrue(result.contains("; Expires="), "should contain Expires= but was: " + result);
        assertTrue(result.contains("2025"), "should contain year 2025 but was: " + result);
    }

    @Test
    @DisplayName("NewCookie toString omits Expires when not set")
    void shouldOmitExpiryWhenNull() {
        NewCookie cookie = new NewCookie.Builder("sid").value("abc123").build();
        String result = cookie.toString();
        assertFalse(result.contains("Expires"), "should not contain Expires but was: " + result);
    }

    // --- VariantListBuilder ---

    @Test
    @DisplayName("Variant.mediaTypes produces one variant per media type")
    void shouldBuildVariantsFromMediaTypes() {
        List<Variant> variants = Variant.mediaTypes(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
                .build();
        assertEquals(2, variants.size());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, variants.get(0).getMediaType());
        assertEquals(MediaType.APPLICATION_XML_TYPE, variants.get(1).getMediaType());
    }

    @Test
    @DisplayName("VariantListBuilder computes Cartesian product of media types and encodings")
    void shouldComputeCartesianProduct() {
        List<Variant> variants = Variant.VariantListBuilder.newInstance()
                .mediaTypes(MediaType.APPLICATION_JSON_TYPE, MediaType.APPLICATION_XML_TYPE)
                .encodings("gzip", "identity")
                .build();
        assertEquals(4, variants.size());
    }

    @Test
    @DisplayName("VariantListBuilder add() accumulates multiple batches")
    void shouldAccumulateMultipleBatches() {
        List<Variant> variants = Variant.VariantListBuilder.newInstance()
                .languages(Locale.ENGLISH, Locale.FRENCH)
                .encodings("gzip", "identity")
                .add()
                .languages(Locale.GERMAN)
                .mediaTypes(MediaType.TEXT_PLAIN_TYPE)
                .build();
        // First batch: 2 languages * 2 encodings = 4; second batch: 1 type * 1 language = 1
        assertEquals(5, variants.size());
    }

    @Test
    @DisplayName("VariantListBuilder build() implicitly adds current batch")
    void shouldImplicitlyAddOnBuild() {
        List<Variant> variants = Variant.VariantListBuilder.newInstance()
                .mediaTypes(MediaType.APPLICATION_JSON_TYPE)
                .build();
        assertEquals(1, variants.size());
    }

    @Test
    @DisplayName("Variant.languages produces one variant per locale")
    void shouldBuildVariantsFromLanguages() {
        List<Variant> variants =
                Variant.languages(Locale.ENGLISH, Locale.FRENCH).build();
        assertEquals(2, variants.size());
        assertEquals(Locale.ENGLISH, variants.get(0).getLanguage());
        assertEquals(Locale.FRENCH, variants.get(1).getLanguage());
    }

    @Test
    @DisplayName("Variant.encodings produces one variant per encoding")
    void shouldBuildVariantsFromEncodings() {
        List<Variant> variants = Variant.encodings("gzip", "identity").build();
        assertEquals(2, variants.size());
    }

    @Test
    @DisplayName("createVariantListBuilder returns a working builder")
    void shouldReturnWorkingVariantListBuilder() {
        Variant.VariantListBuilder builder = new SimpleRuntimeDelegate().createVariantListBuilder();
        assertNotNull(builder);
    }

    // --- NewCookie serialization (continued) ---

    @Test
    @DisplayName("NewCookie toString serializes all attributes together")
    void shouldSerializeAllCookieAttributes() {
        Date expiry = Date.from(Instant.parse("2025-06-01T12:00:00Z"));
        NewCookie cookie = new NewCookie.Builder("session")
                .value("xyz")
                .domain(".example.com")
                .path("/")
                .maxAge(3600)
                .secure(true)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .expiry(expiry)
                .build();
        String result = cookie.toString();
        assertTrue(result.startsWith("session=xyz"), "should start with name=value");
        assertTrue(result.contains("; Domain=.example.com"));
        assertTrue(result.contains("; Path=/"));
        assertTrue(result.contains("; Max-Age=3600"));
        assertTrue(result.contains("; Secure"));
        assertTrue(result.contains("; HttpOnly"));
        assertTrue(result.contains("; SameSite=Strict"));
        assertTrue(result.contains("; Expires="));
    }
}
