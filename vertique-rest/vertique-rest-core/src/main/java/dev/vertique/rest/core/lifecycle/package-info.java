// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Lifecycle hook interfaces for the REST router construction phase.
 *
 * <p>{@link dev.vertique.rest.core.lifecycle.RouterLifecycleHook} provides callbacks for the
 * router construction phase: before/after auth setup, and after router creation.
 *
 * <p>Per-request interceptors (operation, request, error) have been moved to the
 * {@link dev.vertique.rest.core.interceptor} package as part of the interceptor SPI alignment.
 *
 * <p>Hooks are contributed via Dagger multibinding sets declared in
 * {@link dev.vertique.rest.core.dagger.RestCoreModule} and sorted by a {@code priority()}
 * method before execution.
 */
package dev.vertique.rest.core.lifecycle;
