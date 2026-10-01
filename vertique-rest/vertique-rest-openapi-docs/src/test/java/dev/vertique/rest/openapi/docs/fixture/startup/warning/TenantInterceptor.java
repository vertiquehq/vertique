// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.warning;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;

/** A request interceptor that overrides {@code beforeRequest} and admits every request. */
public final class TenantInterceptor implements RequestInterceptor {

    /** Creates the interceptor. */
    public TenantInterceptor() {}

    @Override
    public Future<Void> beforeRequest(RoutingContext rc) {
        return Future.succeededFuture();
    }
}
