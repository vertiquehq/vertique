// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Service contract interfaces and implementations for the example-services application.
 *
 * <p>Contains {@link dev.vertique.examples.services.service.UserService}, the service
 * contract annotated with {@code @ServiceContract} and resilience policy annotations;
 * {@link dev.vertique.examples.services.service.UserServiceImpl}, an in-memory implementation
 * seeded with sample users; and
 * {@link dev.vertique.examples.services.service.UserServiceSandbox}, a canned-response mock
 * activated when {@code sandboxEnabled=true} in the application configuration.
 */
package dev.vertique.examples.services.service;
