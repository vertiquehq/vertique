// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Composable HTTP server with JAX-RS routing on Vert.x and OpenAPI validation.
 *
 * <p>{@link dev.vertique.rest.core.router.HttpVerticle} is the generic HTTP server verticle that composes a
 * main router from {@link dev.vertique.rest.core.router.RouterMount} sub-routers. The default JAX-RS mount,
 * {@code JaxRsRouterMount}, scans JAX-RS-annotated resource classes,
 * resolves operationIds from {@code @Operation} annotations, and maps each operation to its OpenAPI
 * route via {@code JaxRsRouteRegistrar}. At runtime,
 * {@code ResourceMethodInvoker} extracts path, query, header, cookie, and
 * body parameters from the validated request, performs type coercion, invokes the JAX-RS method, and
 * serializes the response through a registry of {@code ResponseProducer} handlers. Failures flow
 * through the error pipeline: {@code RestExceptionMapper} for Throwable-to-Throwable
 * pre-translation, then {@code ExceptionMapperRegistry} for Throwable-to-
 * {@code Response} mapping using JAX-RS {@code ExceptionMapper<T>} implementations.
 *
 * <p>Extension points include {@link dev.vertique.rest.core.router.RouterMount} for providing sub-routers at
 * specific paths, {@link dev.vertique.rest.core.router.MountCustomizer} for per-mount customization,
 * {@link dev.vertique.rest.core.router.RouterCustomizer} for main router customization (before or after
 * mounts), {@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook},
 * {@link dev.vertique.rest.core.interceptor.OperationInterceptor}, {@link dev.vertique.rest.core.interceptor.ErrorInterceptor},
 * {@link dev.vertique.rest.core.middleware.Middleware} for router-level handlers,
 * {@link dev.vertique.rest.core.router.OperationHandlerContributor} for per-operation handlers inside the
 * OpenAPI chain, {@link dev.vertique.rest.core.interceptor.RequestInterceptor} for request interception,
 * response transformation, and serialization observation,
 * {@link dev.vertique.rest.core.response.ResponseBodyEncoder} for pluggable response body encoding,
 * {@link dev.vertique.rest.core.response.ResponseSerializer} for custom serialization orchestration,
 * and {@code ExceptionMapper} multibinding for custom error responses.
 */
package dev.vertique.rest.core;
