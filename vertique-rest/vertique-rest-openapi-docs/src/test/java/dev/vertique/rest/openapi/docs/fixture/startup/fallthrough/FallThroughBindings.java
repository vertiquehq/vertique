// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.fallthrough;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.router.RouterMount;

/**
 * Contributes the fall-through fixture's request-side extensions, all counting into the component's
 * {@link FallThroughCounters}: a {@link CountingRequestInterceptor}, an {@code API}-scoped and a
 * {@code ROOT}-scoped {@link CountingMiddleware}, and the {@link DocsUiMount}.
 */
@Module
public final class FallThroughBindings {

    private FallThroughBindings() {}

    /**
     * Contributes the counting request interceptor.
     *
     * @param counters the component's counters
     * @return a new interceptor
     */
    @Provides
    @IntoSet
    static RequestInterceptor countingRequestInterceptor(FallThroughCounters counters) {
        return new CountingRequestInterceptor(counters);
    }

    /**
     * Contributes the counting {@code API}-scoped middleware.
     *
     * @param counters the component's counters
     * @return a new middleware
     */
    @Provides
    @IntoSet
    static Middleware countingApiMiddleware(FallThroughCounters counters) {
        return new CountingMiddleware(MiddlewareScope.API, counters);
    }

    /**
     * Contributes the counting {@code ROOT}-scoped middleware.
     *
     * @param counters the component's counters
     * @return a new middleware
     */
    @Provides
    @IntoSet
    static Middleware countingRootMiddleware(FallThroughCounters counters) {
        return new CountingMiddleware(MiddlewareScope.ROOT, counters);
    }

    /**
     * Contributes the documentation UI mount.
     *
     * @return a new mount
     */
    @Provides
    @IntoSet
    static RouterMount docsUiMount() {
        return new DocsUiMount();
    }
}
