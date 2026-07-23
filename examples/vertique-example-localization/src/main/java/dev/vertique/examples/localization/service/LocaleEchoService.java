// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Contract interface for the locale-echo service.
 *
 * <p>The single operation reads the {@link dev.vertique.localization.context.LocalizationContext}
 * that was propagated via {@link dev.vertique.services.dispatch.DispatchContext} and echoes back
 * its language tag and source label. This proves that the locale bound by
 * {@link dev.vertique.rest.localization.RequestLocaleInterceptor} at REST inbound propagates
 * through real event-bus service dispatch.
 */
@ServiceContract(namespace = "integration", value = "locale-echo-service")
public interface LocaleEchoService {

    /**
     * Echoes the locale that was propagated through the service dispatch context.
     *
     * @return a future containing the resolved locale's language tag and source label
     */
    @ServiceOperation("echoLocale")
    Future<LocaleEchoResponse> echoLocale();
}
