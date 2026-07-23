// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HTTP server composition and route registration for the REST layer.
 *
 * <p>{@link dev.vertique.rest.core.router.HttpVerticle} is the generic Vert.x verticle that
 * creates the main HTTP server, applies {@link dev.vertique.rest.core.middleware.Middleware}
 * handlers, runs {@link dev.vertique.rest.core.router.RouterCustomizer} hooks, and mounts
 * {@link dev.vertique.rest.core.router.RouterMount} sub-routers (each optionally customized
 * via {@link dev.vertique.rest.core.router.MountCustomizer}).
 *
 * <p>Per-operation extension is provided by
 * {@link dev.vertique.rest.core.router.OperationHandlerContributor}, which allows
 * handlers to be injected into the OpenAPI operation chain (e.g., for authorization checks).
 * {@link dev.vertique.rest.core.router.OperationRegistrationContext} carries the metadata
 * needed during route registration, while {@link dev.vertique.rest.core.router.MountMeta}
 * describes the path and priority of each mount.
 */
package dev.vertique.rest.core.router;
