// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.Future;

/**
 * Hand-written broken stand-in for the generated companion of {@link BrokenSelectionClient}.
 *
 * <p>This class is present on the test classpath but has the <em>wrong</em> constructor — it
 * lacks the expected {@code (RestClientDispatcher, BeanParamAccessorRegistry, Map)} signature.
 * {@link RestClientBuilder} must detect this and throw {@link RestClientConfigurationException}
 * loudly rather than silently falling back to the JDK dynamic proxy.
 */
public final class BrokenSelectionClient_RestClientProxy implements BrokenSelectionClient {

    /**
     * Intentionally wrong constructor — omits all required parameters so that
     * {@link dev.vertique.core.util.GeneratedCompanions#instantiate} triggers the loud-fail path.
     */
    public BrokenSelectionClient_RestClientProxy() {
        // Intentionally broken — wrong constructor signature.
    }

    @Override
    public Future<String> get() {
        return Future.succeededFuture();
    }
}
