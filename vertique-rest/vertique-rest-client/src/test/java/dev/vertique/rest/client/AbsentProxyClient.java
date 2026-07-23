// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.Future;
import jakarta.ws.rs.GET;

/**
 * Top-level fixture client with NO generated proxy stand-in on the test classpath, so
 * {@link RestClientBuilder} must fall back silently to the JDK dynamic proxy.
 */
@RestClient("http://localhost:9999")
interface AbsentProxyClient {

    /** Single method. */
    @GET
    Future<String> get();
}
