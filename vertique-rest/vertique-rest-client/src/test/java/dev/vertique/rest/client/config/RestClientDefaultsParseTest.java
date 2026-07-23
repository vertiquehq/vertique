// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reserved {@code restClient.defaults} sub-object parse.
 *
 * <p>The {@code defaults} key is a reserved sub-object in the {@code restClient} section that must
 * be excluded from the keyed client map produced by
 * {@link RestClientConfig#indexFromConfig(JsonObject, ConfigParser)} and instead read separately via
 * {@link RestClientConfig#defaultsFromConfig(JsonObject, ConfigParser)}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link RestClientConfig#defaultsFromConfig} returns a {@link RestClientDefaults} carrying the
 *       {@code jsonProfile} string from {@code restClient.defaults.jsonProfile} when present;</li>
 *   <li>the {@code defaults} key is excluded from the keyed client map — it does not appear as a
 *       client named {@code "defaults"};</li>
 *   <li>{@link RestClientConfig#defaultsFromConfig} returns {@link RestClientDefaults#defaults()} (with
 *       a {@code null} profile) when no {@code restClient.defaults} sub-object is present.</li>
 * </ul>
 */
@DisplayName("RestClientConfig — reserved defaults sub-object parse")
class RestClientDefaultsParseTest {

    // --- Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- defaultsFromConfig ---

    @Test
    @DisplayName("defaults parsed — restClient.defaults.jsonProfile is returned in typed record")
    void defaultsParsed() {
        // GIVEN: restClient section with a defaults sub-object carrying jsonProfile, plus a real client
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put("defaults", new JsonObject().put("jsonProfile", "vertique"))
                                .put("foo", new JsonObject().put("baseUrl", "http://foo")));

        // WHEN
        RestClientDefaults defaults = RestClientConfig.defaultsFromConfig(root, configParser());

        // THEN: the defaults.jsonProfile value is returned in the typed record
        assertEquals(
                "vertique",
                defaults.jsonProfile(),
                "defaultsFromConfig must return a RestClientDefaults carrying the defaults.jsonProfile value");
    }

    @Test
    @DisplayName("defaults excluded from clients — indexFromConfig keys contain only real clients")
    void defaultsExcludedFromClients() {
        // GIVEN: restClient section with defaults + a real client named 'foo'
        JsonObject root = new JsonObject()
                .put(
                        "restClient",
                        new JsonObject()
                                .put("defaults", new JsonObject().put("jsonProfile", "vertique"))
                                .put("foo", new JsonObject().put("baseUrl", "http://foo")));

        // WHEN
        Map<String, RestClientConfig> index = RestClientConfig.indexFromConfig(root, configParser());

        // THEN: 'defaults' must NOT appear as a client entry; only 'foo'
        Set<String> keys = index.keySet();
        assertEquals(
                Set.of("foo"), keys, "indexFromConfig must exclude the reserved 'defaults' key from the client map");
    }

    @Test
    @DisplayName(
            "no defaults ⇒ null profile — defaultsFromConfig returns defaults() when restClient.defaults is absent")
    void noDefaultsReturnsNull() {
        // GIVEN: restClient section with only a real client and no defaults sub-object
        JsonObject root = new JsonObject()
                .put("restClient", new JsonObject().put("foo", new JsonObject().put("baseUrl", "http://foo")));

        // WHEN
        RestClientDefaults defaults = RestClientConfig.defaultsFromConfig(root, configParser());

        // THEN: null jsonProfile when no defaults
        assertNull(
                defaults.jsonProfile(),
                "defaultsFromConfig must return RestClientDefaults.defaults() (null jsonProfile) when restClient.defaults is absent");
    }
}
