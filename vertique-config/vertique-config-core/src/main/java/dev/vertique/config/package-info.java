// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Vert.x {@link io.vertx.config.ConfigRetriever} integration with Dagger DI. Provides
 * {@link dev.vertique.config.ConfigBootstrap} for async multi-source configuration loading
 * before the Dagger component is created: sources are merged in priority order from an optional
 * JSON file ({@code conf/config.json}), environment variables, system properties, and the
 * deployment config (highest priority). {@link dev.vertique.config.ConfigModule} is a
 * concrete Dagger module that binds the {@code ConfigRetriever} and makes the merged
 * {@link io.vertx.core.json.JsonObject} available for injection.
 */
package dev.vertique.config;
