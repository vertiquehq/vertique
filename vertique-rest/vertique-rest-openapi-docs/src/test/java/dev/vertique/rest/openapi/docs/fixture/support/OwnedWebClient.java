// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.support;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.web.client.WebClient;

/**
 * A {@link WebClient} on a test-owned Vert.x instance, wrapped around a raw client so its close can be
 * awaited before the instance closes. {@link WebClient#close()} returns nothing to await, and closing
 * the instance while the client still closes on its event loop fails with an
 * {@code event executor terminated} rejection. A test that uses the extension-injected instance does
 * not need this.
 *
 * @param client the client the test sends with; requests name their host explicitly
 * @param raw the wrapped raw client, whose close is awaitable
 */
public record OwnedWebClient(WebClient client, HttpClient raw) {

    /**
     * Creates the raw client with the given options and wraps it.
     *
     * @param owner the test-owned Vert.x instance
     * @param options the raw client's options, such as whether connections are kept alive
     * @return the client
     */
    public static OwnedWebClient create(Vertx owner, HttpClientOptions options) {
        HttpClient raw = owner.createHttpClient(options);
        return new OwnedWebClient(WebClient.wrap(raw), raw);
    }

    /**
     * Closes the raw client, and with it the wrapping client.
     *
     * @return the close's completion
     */
    public Future<Void> close() {
        return raw.close();
    }
}
