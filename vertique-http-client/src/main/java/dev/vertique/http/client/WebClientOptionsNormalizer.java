// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpClientConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * INTERNAL, framework use only. Translates the explicit-unit duration keys of a {@code webClient}
 * configuration bag to the names Vert.x {@code WebClientOptions} reads. This type is outside the
 * compatibility promise of the module.
 *
 * <p>Seven keys are renamed: {@code connectTimeoutMs}, {@code idleTimeoutSeconds}, {@code
 * readIdleTimeoutSeconds}, {@code writeIdleTimeoutSeconds}, {@code keepAliveTimeoutSeconds}, {@code
 * http2KeepAliveTimeoutSeconds} and {@code sslHandshakeTimeoutSeconds}. A raw Vert.x duration name is
 * rejected, whether or not its explicit-unit key is also present, because its unit is not visible in
 * the name.
 */
public final class WebClientOptionsNormalizer {

    /** Explicit-unit key to the Vert.x name it becomes, with the raw name as the key to refuse. */
    private static final Map<String, String> DURATION_KEYS = Map.of(
            "connectTimeout", "connectTimeoutMs",
            "idleTimeout", "idleTimeoutSeconds",
            "readIdleTimeout", "readIdleTimeoutSeconds",
            "writeIdleTimeout", "writeIdleTimeoutSeconds",
            "keepAliveTimeout", "keepAliveTimeoutSeconds",
            "http2KeepAliveTimeout", "http2KeepAliveTimeoutSeconds",
            "sslHandshakeTimeout", "sslHandshakeTimeoutSeconds");

    private WebClientOptionsNormalizer() {}

    /**
     * Returns a copy of the bag with the explicit-unit duration keys renamed to the Vert.x names; the
     * input is not changed and every other key passes through.
     *
     * @param bag the configured {@code webClient} bag, or {@code null}
     * @return the normalized copy, or {@code null} when {@code bag} is {@code null}
     * @throws HttpClientConfigurationException when the bag holds a raw Vert.x duration name; the
     *     message names the raw key and the key to use, never a value
     */
    @Nullable
    public static JsonObject normalize(@Nullable JsonObject bag) {
        if (bag == null) {
            return null;
        }
        for (Map.Entry<String, String> key : DURATION_KEYS.entrySet()) {
            if (bag.containsKey(key.getKey())) {
                throw new HttpClientConfigurationException("webClient duration key '" + key.getKey()
                        + "' is a raw Vert.x key; use the explicit-unit key '" + key.getValue() + "' instead");
            }
        }
        JsonObject translated = bag.copy();
        for (Map.Entry<String, String> key : DURATION_KEYS.entrySet()) {
            if (translated.containsKey(key.getValue())) {
                translated.put(key.getKey(), translated.getValue(key.getValue()));
                translated.remove(key.getValue());
            }
        }
        return translated;
    }
}
