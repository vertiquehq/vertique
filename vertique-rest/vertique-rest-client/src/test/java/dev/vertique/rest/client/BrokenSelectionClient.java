// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;

/**
 * Top-level fixture client whose companion stand-in ({@link BrokenSelectionClient_RestClientProxy})
 * exists on the test classpath but has a broken (missing) constructor, so {@link RestClientBuilder}
 * must throw {@link RestClientConfigurationException} loudly instead of silently degrading.
 */
@RestClient("http://localhost:9999")
interface BrokenSelectionClient {

    /** Single method. */
    @GET
    Future<String> get();
}
