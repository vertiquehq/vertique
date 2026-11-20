// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.config.JaxRsConfig;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that unknown keys in the {@code jaxrs.defaultHeaders} section become custom response
 * headers, parsed through the framework's real {@link ConfigParser}.
 */
class DefaultHeadersConfigProviderTest {

    private static JaxRsConfig parse(String json) {
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());
        return RestCoreModule.jaxRsConfig(new JsonObject(json), parser);
    }

    @Test
    @DisplayName("Should bind an unknown defaultHeaders key as a custom header")
    void shouldBindUnknownKeyAsCustomHeader() {
        JaxRsConfig config = parse("""
                {"jaxrs": {"defaultHeaders": {
                  "X-Custom-Header": "custom-value",
                  "Content-Security-Policy": "default-src 'self'"
                }}}
                """);

        var headers = config.defaultHeaders().toHeaderMap();

        assertEquals("custom-value", headers.get("X-Custom-Header"));
        assertEquals("default-src 'self'", headers.get("Content-Security-Policy"));
    }

    @Test
    @DisplayName("Should keep known fields and their defaults alongside custom headers")
    void shouldKeepKnownFieldsAlongsideCustomHeaders() {
        JaxRsConfig config = parse("""
                {"jaxrs": {"defaultHeaders": {
                  "frameOptions": "SAMEORIGIN",
                  "X-Custom-Header": "custom-value"
                }}}
                """);

        var headers = config.defaultHeaders().toHeaderMap();

        assertEquals("SAMEORIGIN", headers.get("X-Frame-Options"));
        assertEquals("no-store", headers.get("Cache-Control"));
        assertEquals("custom-value", headers.get("X-Custom-Header"));
    }

    @Test
    @DisplayName("Should ignore a custom header configured as null")
    void shouldIgnoreNullCustomHeader() {
        JaxRsConfig config = parse("""
                {"jaxrs": {"defaultHeaders": {"X-Custom-Header": null}}}
                """);

        assertFalse(config.defaultHeaders().toHeaderMap().containsKey("X-Custom-Header"));
    }

    @Test
    @DisplayName("Should apply no custom headers when only known fields are configured")
    void shouldHaveNoCustomHeadersWhenOnlyKnownFields() {
        JaxRsConfig config = parse("""
                {"jaxrs": {"defaultHeaders": {"cacheControl": "no-cache"}}}
                """);

        assertEquals(3, config.defaultHeaders().toHeaderMap().size());
    }
}
