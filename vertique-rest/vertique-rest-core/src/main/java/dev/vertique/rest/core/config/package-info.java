// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Configuration value objects for the REST HTTP stack.
 *
 * <p>Each class is a Jackson-deserialized, Lombok-built value object mapping a section of the
 * application config JSON: {@link dev.vertique.rest.core.config.HttpConfig} ({@code "http"}),
 * {@link dev.vertique.rest.core.config.CorsConfig} ({@code "cors"}),
 * {@link dev.vertique.rest.core.config.SslConfig} (nested under {@code "http.ssl"}),
 * {@link dev.vertique.rest.core.config.JaxRsConfig} ({@code "jaxrs"}), and
 * {@link dev.vertique.rest.core.config.DefaultHeadersConfig} ({@code "jaxrs.defaultHeaders"}).
 * All fields provide sensible defaults so applications only need to override what they change.
 *
 * <p>Instances are created by {@link dev.vertique.rest.core.dagger.RestCoreModule} via
 * {@code @Provides @Singleton} methods and injected into the HTTP server infrastructure.
 */
package dev.vertique.rest.core.config;
