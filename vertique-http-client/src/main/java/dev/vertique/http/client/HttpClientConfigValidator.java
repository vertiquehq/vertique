// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.http.client.config.HttpClientConfig;
import java.util.Map;

/** Fails construction, and so startup, when a configured value is one the builder rejects. */
final class HttpClientConfigValidator implements ComposeValidator {

    private HttpClientConfigValidator(Map<String, HttpClientConfig> index) {
        index.values().forEach(HttpClientBuilder::validate);
    }

    static HttpClientConfigValidator of(Map<String, HttpClientConfig> index) {
        return new HttpClientConfigValidator(index);
    }
}
