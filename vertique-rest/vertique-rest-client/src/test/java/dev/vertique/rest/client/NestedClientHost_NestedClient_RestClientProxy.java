// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.Future;
import java.util.Map;

/**
 * Hand-written stand-in for the generated companion of the nested
 * {@link NestedClientHost.NestedClient} interface.
 *
 * <p>Its name uses the flattened {@code Outer_Inner} form matching
 * {@link dev.vertique.core.util.GeneratedNames#companionFqn} for
 * {@code NestedClientHost$NestedClient}. {@link RestClientBuilder} selects this class only if it
 * derives the lookup name via {@code GeneratedNames.companionFqn} (replacing {@code $} with
 * {@code _}); a regression to {@code clientInterface.getName() + suffix} would yield
 * {@code NestedClientHost$NestedClient_RestClientProxy}, which does not match this class name.
 *
 * <p>The constructor signature matches what {@link RestClientBuilder} expects for the generated
 * proxy: {@code (RestClientDispatcher, BeanParamAccessorRegistry, Map)}.
 */
public final class NestedClientHost_NestedClient_RestClientProxy implements NestedClientHost.NestedClient {

    /**
     * Matches the generated proxy constructor signature used by {@link RestClientBuilder}.
     *
     * @param dispatcher            the REST client dispatcher (unused in this stand-in)
     * @param beanParamAccessorRegistry the accessor registry (unused in this stand-in)
     * @param methodMetas           the method metadata map (unused in this stand-in)
     */
    public NestedClientHost_NestedClient_RestClientProxy(
            RestClientDispatcher dispatcher,
            BeanParamAccessorRegistry beanParamAccessorRegistry,
            Map<?, ?> methodMetas) {
        // No-op stand-in — only the constructor shape matters for selection tests.
    }

    @Override
    public Future<String> get() {
        return Future.succeededFuture("nested-stand-in");
    }
}
