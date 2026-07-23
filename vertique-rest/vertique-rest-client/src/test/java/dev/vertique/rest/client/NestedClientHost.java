// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.Future;
import jakarta.ws.rs.GET;

/**
 * Host for a nested {@code @RestClient} fixture exercising flattened companion-name selection in
 * {@link RestClientBuilder}.
 *
 * <p>The nested interface {@link NestedClient} has a hand-written stand-in proxy named
 * {@code NestedClientHost_NestedClient_RestClientProxy} (matching what
 * {@link dev.vertique.core.util.GeneratedNames#companionFqn} produces for
 * {@code NestedClientHost$NestedClient}). The builder must select it over the JDK dynamic
 * proxy, proving the name derivation uses {@code $}-to-{@code _} flattening.
 */
interface NestedClientHost {

    /**
     * Nested rest-client interface whose generated companion name is
     * {@code NestedClientHost_NestedClient_RestClientProxy}.
     */
    @RestClient("http://localhost")
    interface NestedClient {

        /** Single-method contract — just needs to compile. */
        @GET
        Future<String> get();
    }
}
