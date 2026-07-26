// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Host-neutral core application starter.
 *
 * <p>This package holds a single public Dagger aggregate,
 * {@link dev.vertique.starter.core.CoreApplicationModule}, that composes the lifecycle foundation
 * every Vertique application needs: the Vert.x/runtime seam, config parsing, verticle deployment,
 * and the core lifecycle steps. Applications name the aggregate in their {@code @Component}
 * instead of repeating those framework modules.
 *
 * <p>The starter contributes no host, transport, test, or generated code. Deployment entries,
 * generated modules, launcher choice, and test libraries remain application-owned.
 */
package dev.vertique.starter.core;
