// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Pre-Vertx bootstrap configuration loading.
 *
 * <p>Provides {@link dev.vertique.config.bootstrap.BootstrapConfigLoader} for synchronous
 * phase-1 configuration loading before the main Vert.x instance and Dagger component are
 * created. A temporary, minimal Vert.x instance is used to run the
 * {@link dev.vertique.config.ConfigBootstrap#defaultOptions()} chain, after which the
 * temporary instance is fully shut down.
 *
 * <p>{@link dev.vertique.config.bootstrap.BootstrapConfigException} is thrown on any
 * unrecoverable failure during the load or timeout.
 */
package dev.vertique.config.bootstrap;
