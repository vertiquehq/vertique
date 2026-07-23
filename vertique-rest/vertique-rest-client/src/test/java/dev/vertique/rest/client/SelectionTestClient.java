// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.Future;
import jakarta.ws.rs.GET;

/**
 * Top-level fixture client whose generated proxy stand-in ({@link SelectionTestClient_RestClientProxy})
 * is present on the test classpath, so {@link RestClientBuilder} should select it over the JDK
 * dynamic proxy.
 */
@RestClient("http://localhost:9999")
interface SelectionTestClient {

    /** Single method — only the signature shape matters for selection tests. */
    @GET
    Future<String> get();
}
