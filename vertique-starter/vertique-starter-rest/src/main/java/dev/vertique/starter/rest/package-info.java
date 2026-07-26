// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Mechanism-neutral REST application starter.
 *
 * <p>This package holds a single public Dagger aggregate,
 * {@link dev.vertique.starter.rest.RestApplicationModule}, that composes the core application
 * starter with the JAX-RS routing runtime, the annotation-driven request-validation strategy, the
 * security runtime and enforcement bindings, and the management endpoint bindings. Applications
 * name the aggregate in their {@code @Component} instead of repeating those framework modules.
 *
 * <p>The starter is mechanism-neutral: it contributes no concrete authentication mechanism such as
 * JWT. HTTP and management deployment entries, generated JAX-RS modules, launcher choice, and test
 * libraries remain application-owned.
 */
package dev.vertique.starter.rest;
