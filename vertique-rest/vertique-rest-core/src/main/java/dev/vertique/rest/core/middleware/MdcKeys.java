// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import dev.vertique.correlation.CorrelationMdcKeys;

/**
 * Canonical MDC key names emitted by framework middlewares.
 *
 * <p>Centralising these constants prevents key drift between middlewares — a renamed key in one
 * place would otherwise silently diverge from log-aggregation expectations elsewhere.
 *
 * <p>Application code may emit additional MDC keys; these constants only cover the keys produced
 * by framework-owned middlewares ({@code ContextualLoggingMiddleware},
 * {@code CorrelationIngressMiddleware}, {@code IdentityResolutionMiddleware}).
 */
public final class MdcKeys {

    /**
     * Per-request correlation identifier (also exposed via the {@code X-Request-Id} response
     * header by {@code CorrelationIngressMiddleware} when echoing is enabled). Delegates to
     * {@link CorrelationMdcKeys#REQUEST_ID} so the literal value lives in exactly one place
     * across the framework.
     */
    public static final String REQUEST_ID = CorrelationMdcKeys.REQUEST_ID;

    /** HTTP request method (uppercase, e.g. {@code GET}). */
    public static final String METHOD = "method";

    /** HTTP request path. */
    public static final String PATH = "path";

    /** Authenticated user identifier, when present in the {@code SecurityContext}. */
    public static final String USER_ID = "userId";

    /** Authenticated client identifier, when present in the {@code SecurityContext}. */
    public static final String CLIENT_ID = "clientId";

    /**
     * Authentication method identifier, when the request is authenticated. Omitted for anonymous
     * requests (where {@code AuthMethodKind} is {@code NONE}).
     */
    public static final String AUTH_METHOD = "authMethod";

    private MdcKeys() {}
}
