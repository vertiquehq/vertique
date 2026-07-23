// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.rest.client.config.RestClientConfig;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JSON profile selection surface on {@link RestClientBuilder}, {@link RestClient}
 * annotation, and {@link RestClientConfig} introduced in slice 3.1.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link RestClientBuilder#jsonProfile(JsonProfileId)} stores the id and is retrievable via
 *       the package-private {@link RestClientBuilder#jsonProfileId()} accessor.</li>
 *   <li>{@link RestClientConfig} round-trips {@code jsonProfile} through the config boundary parser;
 *       absent yields {@code null}.</li>
 * </ul>
 */
@DisplayName("RestClient jsonProfile selection surface")
class RestClientJsonProfileTest {

    // --- Shared Vert.x instance ---

    static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- RestClientBuilder.jsonProfile(JsonProfileId) ---

    @Nested
    @DisplayName("RestClientBuilder.jsonProfile")
    class BuilderJsonProfile {

        @Test
        @DisplayName("builderJsonProfile_setsField — stores the JsonProfileId and returns it via accessor")
        void builderJsonProfile_setsField() {
            JsonProfileId id = JsonProfileId.of("legacy-crm");
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonProfile(id);
            assertEquals(id, builder.jsonProfileId(), "jsonProfileId() should return the set id");
        }

        @Test
        @DisplayName("default builder has null jsonProfileId")
        void builderJsonProfile_defaultIsNull() {
            RestClientBuilder builder = RestClientBuilder.create(vertx);
            assertNull(builder.jsonProfileId(), "jsonProfileId() should be null by default");
        }
    }

    // --- RestClientConfig config parsing ---

    @Nested
    @DisplayName("RestClientConfig jsonProfile config parsing")
    class ConfigJsonProfile {

        /**
         * Parses the {@code restClient} section into the typed index, exercising the boundary
         * parser exactly as the Dagger provider does.
         *
         * @param restClientSection the {@code restClient} section JSON
         * @return the immutable name -&gt; RestClientConfig index
         */
        /**
         * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
         *
         * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
         */
        private static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }

        private static Map<String, RestClientConfig> parse(JsonObject restClientSection) {
            return RestClientConfig.indexFromConfig(
                    new JsonObject().put("restClient", restClientSection), configParser());
        }

        @Test
        @DisplayName("restClientConfig_parsesJsonProfile — 'jsonProfile' field parsed correctly")
        void restClientConfig_parsesJsonProfile() {
            JsonObject section = new JsonObject()
                    .put(
                            "crm",
                            new JsonObject().put("jsonProfile", "legacy-crm").put("baseUrl", "http://crm"));
            RestClientConfig cfg = parse(section).get("crm");
            assertEquals("legacy-crm", cfg.jsonProfile(), "jsonProfile() should return 'legacy-crm'");
        }

        @Test
        @DisplayName("restClientConfig_jsonProfileAbsent — absent jsonProfile yields null")
        void restClientConfig_jsonProfileAbsent() {
            JsonObject section = new JsonObject().put("svc", new JsonObject().put("baseUrl", "http://svc"));
            RestClientConfig cfg = parse(section).get("svc");
            assertNull(cfg.jsonProfile(), "jsonProfile() should be null when not configured");
        }
    }
}
