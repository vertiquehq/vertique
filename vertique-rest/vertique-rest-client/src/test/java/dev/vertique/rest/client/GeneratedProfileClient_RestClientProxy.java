// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.Future;
import java.util.Map;

/**
 * Hand-written stand-in for the generated companion of {@link GeneratedProfileClient}.
 *
 * <p>Placed on the test classpath so {@link RestClientBuilder} selects it over the JDK dynamic
 * proxy. The constructor signature matches what {@link RestClientBuilder} expects via
 * {@link dev.vertique.core.util.GeneratedCompanions}: {@code (RestClientDispatcher,
 * BeanParamAccessorRegistry, Map)}.
 */
public final class GeneratedProfileClient_RestClientProxy implements GeneratedProfileClient {

    /**
     * Matches the generated proxy constructor signature used by {@link RestClientBuilder}.
     *
     * @param dispatcher the REST client dispatcher (unused in this stand-in)
     * @param beanParamAccessorRegistry the accessor registry (unused in this stand-in)
     * @param methodMetas the method metadata map (unused in this stand-in)
     */
    public GeneratedProfileClient_RestClientProxy(
            RestClientDispatcher dispatcher,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            Map<?, ?> methodMetas) {
        // No-op stand-in.
    }

    @Override
    public Future<String> get() {
        return Future.succeededFuture("generated-profile-stand-in");
    }
}
