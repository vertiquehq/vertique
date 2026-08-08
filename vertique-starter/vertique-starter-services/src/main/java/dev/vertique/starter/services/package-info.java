// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Contract-based service execution application starter.
 *
 * <p>This package holds a single public Dagger aggregate,
 * {@link dev.vertique.starter.services.ServicesApplicationModule}, that composes the core
 * application starter with the typed service dispatch runtime and management bindings. Applications
 * name the aggregate in their {@code @Component} instead of repeating those framework modules.
 *
 * <p>Generated services modules, launcher choice, test libraries, and worker opt-in for genuinely
 * blocking service implementations remain application-owned. The generated module registers
 * service implementations and makes eligible typed contracts injectable; the default execution
 * model is the Vert.x event loop.
 */
package dev.vertique.starter.services;
