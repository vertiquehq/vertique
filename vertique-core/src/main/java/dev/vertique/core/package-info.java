// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Core types and Dagger DI wiring for the Vert.x framework. Provides the {@code VertxModule}
 * that binds the {@link io.vertx.core.Vertx} instance and the {@code @VertxConfig} qualifier for
 * the deployment {@link io.vertx.core.json.JsonObject}; the
 * {@link dev.vertique.core.failure.FailureMapper} context-aware hierarchy-aware translator registry
 * engine used by layer-specific exception mappers and directly by application code; and the
 * {@code Result} monad for returning paired value/metadata from async operations without checked
 * exceptions. Security types ({@code SecurityContext}, {@code AuthMethod}, the authz SPIs, and
 * related event types) live in the {@code vertique-security} family under
 * {@code dev.vertique.security.*}.
 */
package dev.vertique.core;
