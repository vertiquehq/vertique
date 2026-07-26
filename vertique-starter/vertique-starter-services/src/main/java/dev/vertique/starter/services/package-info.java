// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Headless event-bus services application starter.
 *
 * <p>This package holds a single public Dagger aggregate,
 * {@link dev.vertique.starter.services.ServicesApplicationModule}, that composes the core
 * application starter with the event-bus service dispatch runtime and the management endpoint
 * bindings. Applications name the aggregate in their {@code @Component} instead of repeating those
 * framework modules.
 *
 * <p>The starter is headless: it contributes no HTTP or REST surface. Management deployment
 * entries, generated services modules, launcher choice, test libraries, and worker opt-in for
 * genuinely blocking service implementations remain application-owned; the default execution model
 * is the Vert.x event loop.
 */
package dev.vertique.starter.services;
