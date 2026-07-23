// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Service contract interfaces and implementations for the example-localization application.
 *
 * <p>Contains {@link dev.vertique.examples.localization.service.LocaleEchoService}, the service
 * contract annotated with {@code @ServiceContract}; and
 * {@link dev.vertique.examples.localization.service.LocaleEchoServiceHandler}, which reads the
 * propagated {@link dev.vertique.localization.context.LocalizationContext} via
 * {@link dev.vertique.services.dispatch.DispatchContext} and echoes back the locale information.
 */
package dev.vertique.examples.localization.service;
