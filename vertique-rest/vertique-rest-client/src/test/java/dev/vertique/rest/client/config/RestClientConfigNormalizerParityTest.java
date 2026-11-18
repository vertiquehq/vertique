// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.internal.WebClientOptionsNormalizer;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RestClientConfig#normalizeWebClientKeys(JsonObject)} and the HTTP client module's
 * {@code WebClientOptionsNormalizer} agree on every input, because the REST normalizer delegates
 * to the module's one (FR-017).
 */
class RestClientConfigNormalizerParityTest {

    /** Explicit-unit key, then the Vert.x name it becomes. */
    private static final Map<String, String> DURATION_KEYS = Map.of(
            "connectTimeoutMs", "connectTimeout",
            "idleTimeoutSeconds", "idleTimeout",
            "readIdleTimeoutSeconds", "readIdleTimeout",
            "writeIdleTimeoutSeconds", "writeIdleTimeout",
            "keepAliveTimeoutSeconds", "keepAliveTimeout",
            "http2KeepAliveTimeoutSeconds", "http2KeepAliveTimeout",
            "sslHandshakeTimeoutSeconds", "sslHandshakeTimeout");

    @Test
    @DisplayName("The REST normalizer delegates to the client module normalizer")
    void restNormalizerDelegatesToTheClientModuleNormalizer() {
        // Given a bag with every explicit-unit duration key and pass-through keys
        JsonObject bag =
                new JsonObject().put("ssl", true).put("verifyHost", true).put("keepAlive", false);
        DURATION_KEYS.keySet().forEach(key -> bag.put(key, 5));
        JsonObject before = bag.copy();

        // When both normalizers translate it
        JsonObject rest = RestClientConfig.normalizeWebClientKeys(bag);
        JsonObject module = WebClientOptionsNormalizer.normalize(bag);

        // Then the results are equal, renamed to the Vert.x names, and the input is untouched
        assertThat(rest).isEqualTo(module);
        DURATION_KEYS.forEach((explicit, vertx) -> {
            assertThat(rest.containsKey(explicit)).as(explicit).isFalse();
            assertThat(rest.getInteger(vertx)).as(vertx).isEqualTo(5);
        });
        assertThat(rest.getBoolean("ssl")).isTrue();
        assertThat(bag).isEqualTo(before);
        assertThat(RestClientConfig.normalizeWebClientKeys(null)).isNull();
        assertThat(WebClientOptionsNormalizer.normalize(null)).isNull();

        // And a raw Vert.x duration key is rejected by both with the same message; the REST failure
        // is a RestClientConfigurationException that carries the module's own failure as its cause
        for (String raw : List.of(
                "connectTimeout",
                "idleTimeout",
                "readIdleTimeout",
                "writeIdleTimeout",
                "keepAliveTimeout",
                "http2KeepAliveTimeout",
                "sslHandshakeTimeout")) {
            JsonObject rawBag = new JsonObject().put(raw, 1);
            HttpClientConfigurationException moduleFailure = null;
            try {
                WebClientOptionsNormalizer.normalize(rawBag);
            } catch (HttpClientConfigurationException e) {
                moduleFailure = e;
            }
            assertThat(moduleFailure).as(raw).isNotNull();
            HttpClientConfigurationException expected = moduleFailure;
            assertThatThrownBy(() -> RestClientConfig.normalizeWebClientKeys(rawBag))
                    .as(raw)
                    .isExactlyInstanceOf(RestClientConfigurationException.class)
                    .hasMessage(expected.getMessage())
                    .hasCauseInstanceOf(HttpClientConfigurationException.class);
        }
    }
}
