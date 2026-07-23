// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

/**
 * Scope for middleware application.
 */
public enum MiddlewareScope {
    /**
     * Applied to all requests (mounted on the main router).
     */
    ROOT,

    /**
     * Applied only to OpenAPI-validated routes (mounted on the API sub-router).
     */
    API
}
