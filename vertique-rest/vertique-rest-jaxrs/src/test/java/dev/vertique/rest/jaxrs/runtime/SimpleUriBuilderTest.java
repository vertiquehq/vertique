// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SimpleUriBuilderTest {

    // --- Test helper ---

    @jakarta.ws.rs.Path("/resources")
    private static class TestResource {
        @jakarta.ws.rs.Path("/items")
        public void items() {}
    }

    // --- Factory methods ---

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("fromUri(URI) preserves scheme, path, query, and fragment")
        void shouldPreserveAllComponentsFromUri() {
            URI source = URI.create("http://example.com/path?q=1#frag");
            URI result = UriBuilder.fromUri(source).build();
            assertEquals("http", result.getScheme());
            assertEquals("example.com", result.getHost());
            assertEquals("/path", result.getPath());
            assertEquals("q=1", result.getQuery());
            assertEquals("frag", result.getFragment());
        }

        @Test
        @DisplayName("fromUri(String) creates builder from URI string")
        void shouldCreateBuilderFromUriString() {
            URI result = UriBuilder.fromUri("http://example.com/path").build();
            assertEquals(URI.create("http://example.com/path"), result);
        }

        @Test
        @DisplayName("fromPath(String) creates a path-only builder")
        void shouldCreatePathOnlyBuilder() {
            URI result = UriBuilder.fromPath("/api/items").build();
            assertEquals(URI.create("/api/items"), result);
        }

        @Test
        @DisplayName("newInstance() returns a non-null builder")
        void shouldReturnNonNullFromNewInstance() {
            assertNotNull(UriBuilder.newInstance());
        }
    }

    // --- Component setters ---

    @Nested
    @DisplayName("Component setters")
    class ComponentSetters {

        @Test
        @DisplayName("scheme(String) sets the URI scheme")
        void shouldSetScheme() {
            URI result =
                    UriBuilder.newInstance().scheme("https").host("example.com").build();
            assertEquals("https", result.getScheme());
        }

        @Test
        @DisplayName("scheme(null) clears the scheme")
        void shouldClearSchemeWithNull() {
            URI result =
                    UriBuilder.fromUri("http://example.com/path").scheme(null).build();
            assertNull(result.getScheme());
        }

        @Test
        @DisplayName("host(String) sets the URI host")
        void shouldSetHost() {
            URI result =
                    UriBuilder.newInstance().scheme("http").host("example.com").build();
            assertEquals("example.com", result.getHost());
        }

        @Test
        @DisplayName("host(null) clears the host")
        void shouldClearHostWithNull() {
            URI result = UriBuilder.fromUri("http://example.com/path")
                    .host(null)
                    .path("/path")
                    .build();
            assertNull(result.getHost());
        }

        @Test
        @DisplayName("port(int) sets the URI port")
        void shouldSetPort() {
            URI result = UriBuilder.newInstance()
                    .scheme("http")
                    .host("example.com")
                    .port(8080)
                    .build();
            assertEquals(8080, result.getPort());
        }

        @Test
        @DisplayName("port(-1) clears an explicit port")
        void shouldClearPortWithMinusOne() {
            URI result =
                    UriBuilder.fromUri("http://example.com:8080/path").port(-1).build();
            assertEquals(-1, result.getPort());
        }

        @Test
        @DisplayName("userInfo(String) sets userInfo component")
        void shouldSetUserInfo() {
            URI result = UriBuilder.newInstance()
                    .scheme("http")
                    .userInfo("user:pass")
                    .host("example.com")
                    .build();
            assertEquals("user:pass", result.getUserInfo());
        }

        @Test
        @DisplayName("fragment(String) sets the fragment")
        void shouldSetFragment() {
            URI result = UriBuilder.fromUri("http://example.com/path")
                    .fragment("section1")
                    .build();
            assertEquals("section1", result.getFragment());
        }

        @Test
        @DisplayName("fragment(null) clears the fragment")
        void shouldClearFragmentWithNull() {
            URI result = UriBuilder.fromUri("http://example.com/path#old")
                    .fragment(null)
                    .build();
            assertNull(result.getFragment());
        }

        @Test
        @DisplayName("scheme + host + port + path build a complete URI")
        void shouldBuildFullUri() {
            URI result = UriBuilder.newInstance()
                    .scheme("http")
                    .host("example.com")
                    .port(8080)
                    .path("/api")
                    .build();
            assertEquals("http://example.com:8080/api", result.toString());
        }
    }

    // --- Path operations ---

    @Nested
    @DisplayName("Path operations")
    class PathOperations {

        @Test
        @DisplayName("path(a).path(b) appends with a slash separator")
        void shouldAppendPathWithSlash() {
            URI result = UriBuilder.newInstance().path("/api").path("items").build();
            assertEquals("/api/items", result.getPath());
        }

        @Test
        @DisplayName("path('/api/') + path('/items') avoids double slash")
        void shouldAvoidDoubleSlash() {
            URI result = UriBuilder.newInstance().path("/api/").path("/items").build();
            assertEquals("/api/items", result.getPath());
        }

        @Test
        @DisplayName("replacePath(String) replaces the entire path")
        void shouldReplaceEntirePath() {
            URI result = UriBuilder.fromUri("http://example.com/old/path")
                    .replacePath("/new")
                    .build();
            assertEquals("/new", result.getPath());
        }

        @Test
        @DisplayName("replacePath(null) clears the path")
        void shouldClearPathWithNull() {
            URI result = UriBuilder.fromUri("http://example.com/old/path")
                    .replacePath(null)
                    .build();
            assertTrue(result.getPath() == null || result.getPath().isEmpty());
        }

        @Test
        @DisplayName("segment(a, b) appended after a root path produces /root/a/b")
        void shouldAppendSegments() {
            URI result = UriBuilder.fromPath("/root").segment("a", "b").build();
            assertEquals("/root/a/b", result.getPath());
        }

        @Test
        @DisplayName("segment encodes literal slashes within a segment value")
        void shouldEncodeSlashInSegment() {
            URI result = UriBuilder.fromPath("/root").segment("a/b").build();
            assertEquals("/root/a%2Fb", result.getRawPath());
        }
    }

    // --- Query parameters ---

    @Nested
    @DisplayName("Query parameters")
    class QueryParameters {

        @Test
        @DisplayName("queryParam(key, value) adds a query parameter")
        void shouldAddQueryParam() {
            URI result = UriBuilder.fromUri("http://example.com")
                    .queryParam("key", "value")
                    .build();
            assertEquals("key=value", result.getRawQuery());
        }

        @Test
        @DisplayName("multiple queryParam calls accumulate all parameters")
        void shouldAccumulateMultipleQueryParams() {
            URI result = UriBuilder.fromUri("http://example.com")
                    .queryParam("a", "1")
                    .queryParam("b", "2")
                    .build();
            String query = result.getRawQuery();
            assertTrue(query.contains("a=1"), "should contain a=1");
            assertTrue(query.contains("b=2"), "should contain b=2");
        }

        @Test
        @DisplayName("queryParam(key, v1, v2) adds two separate entries for the same key")
        void shouldAddMultipleValuesForSameKey() {
            URI result = UriBuilder.fromUri("http://example.com")
                    .queryParam("tag", "v1", "v2")
                    .build();
            String query = result.getRawQuery();
            assertTrue(query.contains("tag=v1"), "should contain tag=v1");
            assertTrue(query.contains("tag=v2"), "should contain tag=v2");
        }

        @Test
        @DisplayName("replaceQueryParam(key, newValue) replaces existing entries")
        void shouldReplaceQueryParam() {
            URI result = UriBuilder.fromUri("http://example.com?key=old")
                    .replaceQueryParam("key", "new")
                    .build();
            assertEquals("key=new", result.getRawQuery());
        }

        @Test
        @DisplayName("replaceQueryParam(key, null) removes the parameter")
        void shouldRemoveQueryParamWithNull() {
            URI result = UriBuilder.fromUri("http://example.com?key=old")
                    .replaceQueryParam("key", (Object[]) null)
                    .build();
            assertNull(result.getRawQuery());
        }

        @Test
        @DisplayName("replaceQuery(String) replaces all query parameters")
        void shouldReplaceAllQueryParams() {
            URI result = UriBuilder.fromUri("http://example.com?old=1")
                    .replaceQuery("a=1&b=2")
                    .build();
            String query = result.getRawQuery();
            assertTrue(query.contains("a=1"), "should contain a=1");
            assertTrue(query.contains("b=2"), "should contain b=2");
            assertFalse(query.contains("old=1"), "should not contain old=1");
        }

        @Test
        @DisplayName("replaceQuery(null) clears all query parameters")
        void shouldClearQueryWithNull() {
            URI result = UriBuilder.fromUri("http://example.com?old=1")
                    .replaceQuery(null)
                    .build();
            assertNull(result.getRawQuery());
        }
    }

    // --- Matrix parameters ---

    @Nested
    @DisplayName("Matrix parameters")
    class MatrixParameters {

        @Test
        @DisplayName("matrixParam appends matrix parameter to the last path segment")
        void shouldAppendMatrixParam() {
            URI result =
                    UriBuilder.fromPath("/items").matrixParam("sort", "name").build();
            assertTrue(result.toString().contains("/items;sort=name"), "path should contain ;sort=name");
        }

        @Test
        @DisplayName("replaceMatrix(null) removes matrix parameters from the last segment")
        void shouldRemoveMatrixParamsWithNull() {
            URI result =
                    UriBuilder.fromPath("/items;sort=name").replaceMatrix(null).build();
            assertFalse(
                    result.toString().contains(";"), "path should not contain matrix params after replaceMatrix(null)");
        }

        @Test
        @DisplayName("matrixParam attaches to the segment it was called after")
        void shouldAttachMatrixParamsToCorrectSegment() {
            String raw = UriBuilder.newInstance()
                    .path("/items")
                    .matrixParam("a", "1")
                    .path("/sub")
                    .matrixParam("b", "2")
                    .build()
                    .toString();
            assertTrue(raw.contains("/items;a=1"), "items segment should have a=1");
            assertTrue(raw.contains("/sub;b=2"), "sub segment should have b=2");
        }
    }

    // --- Template resolution ---

    @Nested
    @DisplayName("Template resolution")
    class TemplateResolution {

        @Test
        @DisplayName("resolveTemplate(name, value) substitutes a named template")
        void shouldResolveNamedTemplate() {
            URI result = UriBuilder.fromPath("/{name}")
                    .resolveTemplate("name", "items")
                    .build();
            assertEquals("/items", result.getPath());
        }

        @Test
        @DisplayName("template with regex is resolved using build(Object...)")
        void shouldResolveTemplateWithRegex() {
            URI result = UriBuilder.fromPath("/{id : [0-9]+}").build("42");
            assertEquals("/42", result.getPath());
        }

        @Test
        @DisplayName("resolveTemplate with encodeSlashInPath=true encodes slash in value")
        void shouldEncodeSlashWhenFlagIsTrue() {
            URI result = UriBuilder.fromPath("/{name}")
                    .resolveTemplate("name", "a/b", true)
                    .build();
            assertTrue(result.getRawPath().contains("a%2Fb"), "slash should be encoded as %2F");
        }

        @Test
        @DisplayName("resolveTemplate with encodeSlashInPath=false preserves slash in value")
        void shouldPreserveSlashWhenFlagIsFalse() {
            URI result = UriBuilder.fromPath("/{name}")
                    .resolveTemplate("name", "a/b", false)
                    .build();
            assertEquals("/a/b", result.getPath());
        }

        @Test
        @DisplayName("resolveTemplateFromEncoded preserves already-encoded sequences")
        void shouldPreserveEncodedSequences() {
            URI result = UriBuilder.fromPath("/{name}")
                    .resolveTemplateFromEncoded("name", "a%20b")
                    .build();
            assertTrue(result.getRawPath().contains("a%20b"), "encoded sequence should be preserved");
        }

        @Test
        @DisplayName("resolveTemplates(Map) resolves multiple named templates")
        void shouldResolveMultipleTemplates() {
            URI result = UriBuilder.fromPath("/{a}/{b}")
                    .resolveTemplates(Map.of("a", "x", "b", "y"))
                    .build();
            assertEquals("/x/y", result.getPath());
        }
    }

    // --- Build methods ---

    @Nested
    @DisplayName("Build methods")
    class BuildMethods {

        @Test
        @DisplayName("build(Object...) substitutes templates positionally")
        void shouldBuildPositionally() {
            URI result = UriBuilder.fromPath("/{a}/{b}").build("x", "y");
            assertEquals("/x/y", result.getPath());
        }

        @Test
        @DisplayName("build(Object...) with repeated template name uses one positional value")
        void shouldUseSameValueForRepeatedTemplateName() {
            URI result = UriBuilder.fromPath("/{a}/{a}").build("x");
            assertEquals("/x/x", result.getPath());
        }

        @Test
        @DisplayName("buildFromMap(Map) substitutes templates by name")
        void shouldBuildFromMap() {
            URI result = UriBuilder.fromPath("/{a}").buildFromMap(Map.of("a", "x"));
            assertEquals("/x", result.getPath());
        }

        @Test
        @DisplayName("buildFromEncoded(Object...) preserves percent-encoded values")
        void shouldPreserveEncodingInBuildFromEncoded() {
            URI result = UriBuilder.fromPath("/{name}").buildFromEncoded("a%20b");
            assertTrue(result.getRawPath().contains("a%20b"), "should preserve %20 encoding");
        }

        @Test
        @DisplayName("build with missing template value throws IllegalArgumentException")
        void shouldThrowOnMissingTemplateValue() {
            UriBuilder builder = UriBuilder.fromPath("/{a}/{b}");
            assertThrows(IllegalArgumentException.class, () -> builder.build("onlyOne"));
        }
    }

    // --- Encoding ---

    @Nested
    @DisplayName("Encoding")
    class Encoding {

        @Test
        @DisplayName("space in path is percent-encoded as %20")
        void shouldEncodeSpaceInPath() {
            URI result = UriBuilder.fromPath("/hello world").build();
            assertEquals("/hello%20world", result.getRawPath());
        }

        @Test
        @DisplayName("already percent-encoded sequence is not double-encoded")
        void shouldNotDoubleEncode() {
            URI result = UriBuilder.fromPath("/hello%20world").build();
            assertEquals("/hello%20world", result.getRawPath());
        }

        @Test
        @DisplayName("space in query param value is percent-encoded")
        void shouldEncodeSpaceInQueryParam() {
            URI result = UriBuilder.fromUri("http://example.com")
                    .queryParam("q", "hello world")
                    .build();
            assertTrue(result.getRawQuery().contains("q=hello%20world"), "space should be encoded in query");
        }

        @Test
        @DisplayName("non-ASCII unicode character in path is percent-encoded")
        void shouldEncodeUnicodeInPath() {
            URI result = UriBuilder.fromPath("/café").build();
            String rawPath = result.getRawPath();
            assertFalse(rawPath.contains("é"), "non-ASCII character should be percent-encoded");
            assertTrue(rawPath.startsWith("/caf"), "path prefix should be preserved");
        }
    }

    // --- clone ---

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone produces an independent copy — modifying clone does not affect original")
        void shouldProduceIndependentCopy() {
            UriBuilder original = UriBuilder.fromUri("http://example.com/path");
            UriBuilder cloned = original.clone();
            cloned.path("/extra").queryParam("x", "1");

            URI originalUri = original.build();
            URI clonedUri = cloned.build();

            assertEquals("http://example.com/path", originalUri.toString());
            assertNotEquals(originalUri, clonedUri);
        }
    }

    // --- toTemplate ---

    @Nested
    @DisplayName("toTemplate")
    class ToTemplate {

        @Test
        @DisplayName("toTemplate returns the URI template string with placeholders intact")
        void shouldReturnUriTemplateString() {
            String template = UriBuilder.newInstance()
                    .scheme("http")
                    .host("example.com")
                    .path("/{id}")
                    .toTemplate();
            assertEquals("http://example.com/{id}", template);
        }
    }

    // --- uri(String) with templates ---

    @Nested
    @DisplayName("uri(String) with templates")
    class UriWithTemplates {

        @Test
        @DisplayName("fromUri with template variables in path resolves positionally via build")
        void shouldResolveTemplateVariablesInPath() {
            URI result = UriBuilder.fromUri("http://example.com/api/{version}/{resource}")
                    .build("v1", "items");
            assertEquals("http://example.com/api/v1/items", result.toString());
        }
    }

    // --- Integration with @Path annotation ---

    @Nested
    @DisplayName("Integration with @Path annotation")
    class PathAnnotation {

        @Test
        @DisplayName("fromResource returns builder initialized from class-level @Path")
        void shouldCreateBuilderFromResource() {
            URI result = UriBuilder.fromResource(TestResource.class).build();
            assertEquals("/resources", result.toString());
        }

        @Test
        @DisplayName("fromMethod returns builder initialized from method-level @Path")
        void shouldCreateBuilderFromMethod() {
            URI result = UriBuilder.fromMethod(TestResource.class, "items").build();
            assertEquals("/items", result.toString());
        }

        @Test
        @DisplayName("path(Class).path(Class, method) combines class and method @Path values")
        void shouldCombineClassAndMethodPath() {
            URI result = UriBuilder.newInstance()
                    .path(TestResource.class)
                    .path(TestResource.class, "items")
                    .build();
            assertEquals("/resources/items", result.toString());
        }
    }

    // --- Host templates ---

    @Nested
    @DisplayName("Host templates")
    class HostTemplates {

        @Test
        @DisplayName("uri(String) with template in host preserves template in toTemplate()")
        void shouldPreserveHostTemplate() {
            String template =
                    UriBuilder.fromUri("http://{tenant}.example.com/path").toTemplate();
            assertEquals("http://{tenant}.example.com/path", template);
        }

        @Test
        @DisplayName("uri(String) with template in host resolves via build")
        void shouldResolveHostTemplateViaBuild() {
            URI result = UriBuilder.fromUri("http://{tenant}.example.com/path").build("acme");
            assertEquals("http://acme.example.com/path", result.toString());
        }

        @Test
        @DisplayName("uri(String) with host template and port preserves both")
        void shouldPreservePortWithHostTemplate() {
            URI result =
                    UriBuilder.fromUri("http://{tenant}.example.com:8080/path").build("acme");
            assertEquals("http://acme.example.com:8080/path", result.toString());
        }

        @Test
        @DisplayName("uri(String) with host template and userInfo preserves both")
        void shouldPreserveUserInfoWithHostTemplate() {
            URI result = UriBuilder.fromUri("http://user:pass@{tenant}.example.com/path")
                    .build("acme");
            assertEquals("http://user:pass@acme.example.com/path", result.toString());
        }

        @Test
        @DisplayName("host and path templates resolve together")
        void shouldResolveBothHostAndPathTemplates() {
            URI result =
                    UriBuilder.fromUri("http://{tenant}.example.com/{resource}").build("acme", "items");
            assertEquals("http://acme.example.com/items", result.toString());
        }
    }

    // --- Scheme templates ---

    @Nested
    @DisplayName("Scheme templates")
    class SchemeTemplates {

        @Test
        @DisplayName("fromUri with scheme template resolves via build")
        void shouldResolveSchemeTemplate() {
            URI result = UriBuilder.fromUri("{scheme}://example.com/path").build("https");
            assertEquals("https://example.com/path", result.toString());
        }

        @Test
        @DisplayName("toTemplate preserves scheme template")
        void shouldPreserveSchemeTemplateInToTemplate() {
            String template = UriBuilder.fromUri("{scheme}://example.com/path").toTemplate();
            assertEquals("{scheme}://example.com/path", template);
        }

        @Test
        @DisplayName("scheme and path templates resolve together")
        void shouldResolveSchemeAndPathTemplates() {
            URI result = UriBuilder.fromUri("{scheme}://example.com/{resource}").build("https", "items");
            assertEquals("https://example.com/items", result.toString());
        }
    }

    // --- Regex-containing templates ---

    @Nested
    @DisplayName("Regex templates")
    class RegexTemplates {

        @Test
        @DisplayName("build resolves template with regex quantifier braces")
        void shouldResolveTemplateWithRegexQuantifier() {
            URI result = UriBuilder.fromUri("/users/{id:[0-9]{1,3}}").build("42");
            assertEquals("/users/42", result.toString());
        }

        @Test
        @DisplayName("toTemplate preserves full regex template including nested braces")
        void shouldPreserveRegexTemplateInToTemplate() {
            String template = UriBuilder.fromUri("/users/{id:[0-9]{1,3}}").toTemplate();
            assertEquals("/users/{id:[0-9]{1,3}}", template);
        }

        @Test
        @DisplayName("multiple regex templates resolve correctly")
        void shouldResolveMultipleRegexTemplates() {
            URI result = UriBuilder.fromUri("/{a:[0-9]+}/{b:[a-z]{2}}").build("123", "ab");
            assertEquals("/123/ab", result.toString());
        }

        @Test
        @DisplayName("mixed regex and simple templates resolve correctly")
        void shouldResolveMixedRegexAndSimpleTemplates() {
            URI result = UriBuilder.fromUri("/{id:[0-9]{1,3}}/{name}").build("42", "foo");
            assertEquals("/42/foo", result.toString());
        }
    }

    // --- Opaque URIs ---

    @Nested
    @DisplayName("Opaque URIs")
    class OpaqueUris {

        @Test
        @DisplayName("fromUri(URI) preserves mailto opaque URI")
        void shouldPreserveMailtoUri() {
            URI result =
                    UriBuilder.fromUri(URI.create("mailto:user@example.com")).build();
            assertEquals("mailto:user@example.com", result.toString());
        }

        @Test
        @DisplayName("fromUri(String) preserves urn opaque URI")
        void shouldPreserveUrnUri() {
            URI result = UriBuilder.fromUri("urn:isbn:0451450523").build();
            assertEquals("urn:isbn:0451450523", result.toString());
        }

        @Test
        @DisplayName("fromUri(URI) with scheme override works for opaque URI")
        void shouldAllowSchemeOverrideOnOpaqueUri() {
            URI result = UriBuilder.fromUri(URI.create("mailto:user@example.com"))
                    .scheme("mailto")
                    .build();
            assertEquals("mailto:user@example.com", result.toString());
        }

        @Test
        @DisplayName("fromUri(URI) preserves opaque URI with fragment")
        void shouldPreserveOpaqueUriWithFragment() {
            URI result = UriBuilder.fromUri(URI.create("urn:isbn:123#sec1")).build();
            assertEquals("urn:isbn:123#sec1", result.toString());
        }

        @Test
        @DisplayName("fromUri preserves question mark in mailto SSP")
        void shouldPreserveQuestionMarkInMailtoSsp() {
            URI result = UriBuilder.fromUri(URI.create("mailto:user@example.com?subject=Hi"))
                    .build();
            assertEquals("mailto:user@example.com?subject=Hi", result.toString());
        }

        @Test
        @DisplayName("build resolves template in opaque SSP")
        void shouldResolveTemplateInOpaqueSsp() {
            URI result = UriBuilder.fromUri("mailto:{user}@example.com").build("alice");
            assertEquals("mailto:alice@example.com", result.toString());
        }
    }

    // --- Unicode encoding ---

    @Nested
    @DisplayName("Unicode encoding")
    class UnicodeEncoding {

        @Test
        @DisplayName("supplementary character (emoji) is encoded as 4-byte UTF-8")
        void shouldEncodeEmojiAs4ByteUtf8() {
            URI result = UriBuilder.fromUri("http://example.com")
                    .path("/hello/\uD83D\uDE00")
                    .build();
            assertTrue(
                    result.getRawPath().contains("%F0%9F%98%80"),
                    "emoji should be encoded as %F0%9F%98%80 but was: " + result.getRawPath());
        }

        @Test
        @DisplayName("supplementary character in query is encoded correctly")
        void shouldEncodeSupplementaryCharInQuery() {
            URI result = UriBuilder.fromUri("http://example.com/search")
                    .queryParam("q", "\uD835\uDD73ello")
                    .build();
            String rawQuery = result.getRawQuery();
            assertTrue(
                    rawQuery.contains("%F0%9D%95%B3"),
                    "supplementary char should be %F0%9D%95%B3 but was: " + rawQuery);
        }

        @Test
        @DisplayName("BMP non-ASCII character is encoded correctly")
        void shouldEncodeBmpNonAscii() {
            URI result =
                    UriBuilder.fromUri("http://example.com").path("/caf\u00E9").build();
            assertTrue(
                    result.getRawPath().contains("caf%C3%A9"),
                    "BMP char should be encoded as caf%C3%A9 but was: " + result.getRawPath());
        }
    }

    // --- URI shape switching ---

    @Nested
    @DisplayName("URI shape switching")
    class UriShapeSwitching {

        @Test
        @DisplayName("switching from hierarchical to opaque clears query")
        void shouldClearQueryWhenSwitchingToOpaque() {
            URI result = UriBuilder.fromUri("http://example.com/x?a=1")
                    .uri("mailto:user@example.com")
                    .build();
            assertEquals("mailto:user@example.com", result.toString());
        }

        @Test
        @DisplayName("switching from opaque to hierarchical clears ssp")
        void shouldClearSspWhenSwitchingToHierarchical() {
            URI result = UriBuilder.fromUri("mailto:user@example.com")
                    .uri("http://example.com/x")
                    .build();
            assertEquals("http://example.com/x", result.toString());
        }

        @Test
        @DisplayName("switching from hierarchical to opaque via URI object clears host and path")
        void shouldClearHierarchicalFieldsWhenSwitchingToOpaqueViaUri() {
            URI result = UriBuilder.fromUri(URI.create("http://example.com/path"))
                    .uri(URI.create("mailto:user@example.com"))
                    .build();
            assertEquals("mailto:user@example.com", result.toString());
        }

        @Test
        @DisplayName("switching from opaque to hierarchical via URI object clears ssp")
        void shouldClearSspWhenSwitchingToHierarchicalViaUri() {
            URI result = UriBuilder.fromUri(URI.create("mailto:user@example.com"))
                    .uri(URI.create("http://example.com/x"))
                    .build();
            assertEquals("http://example.com/x", result.toString());
        }
    }

    // --- Template marker collision ---

    @Nested
    @DisplayName("Template marker collision")
    class TemplateMarkerCollision {

        @Test
        @DisplayName("literal tpl0x in path is preserved when templates are present")
        void shouldPreserveLiteralMarkerInPath() {
            URI result = UriBuilder.fromUri("http://example.com/tpl0x/{id}").build("42");
            assertEquals("http://example.com/tpl0x/42", result.toString());
        }

        @Test
        @DisplayName("literal tpl1x in path is preserved with multiple templates")
        void shouldPreserveLiteralMarkerWithMultipleTemplates() {
            URI result = UriBuilder.fromUri("http://example.com/tpl1x/{a}/{b}").build("1", "2");
            assertEquals("http://example.com/tpl1x/1/2", result.toString());
        }

        @Test
        @DisplayName("template name containing marker-like string is preserved")
        void shouldPreserveTemplateNameContainingMarkerString() {
            String template =
                    UriBuilder.fromUri("http://example.com/{tpl1x}/{b}").toTemplate();
            assertEquals("http://example.com/{tpl1x}/{b}", template);
        }

        @Test
        @DisplayName("build resolves templates whose names look like markers")
        void shouldResolveTemplatesWhoseNamesLookLikeMarkers() {
            URI result = UriBuilder.fromUri("http://example.com/{tpl1x}/{b}").build("A", "B");
            assertEquals("http://example.com/A/B", result.toString());
        }
    }
}
