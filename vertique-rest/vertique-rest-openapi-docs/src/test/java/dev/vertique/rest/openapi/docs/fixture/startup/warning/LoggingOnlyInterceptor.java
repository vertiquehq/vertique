// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.ext.web.RoutingContext;

/**
 * A request interceptor that overrides only the {@code onRequest} observer, keeping the inherited
 * {@code beforeRequest}.
 */
public final class LoggingOnlyInterceptor implements RequestInterceptor {

    /** Creates the interceptor. */
    public LoggingOnlyInterceptor() {}

    @Override
    public void onRequest(RoutingContext rc) {
        // Observes nothing: the fixture exists to be left out of the warning.
    }
}
