// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization.service;

import dev.vertique.localization.context.LocalizationContext;
import dev.vertique.services.ServiceHandler;
import dev.vertique.services.dispatch.DispatchContext;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler-pattern implementation of {@link LocaleEchoService}.
 *
 * <p>Reads the {@link LocalizationContext} propagated via {@link DispatchContext} and returns the
 * resolved locale's BCP 47 language tag and source label. Falls back to {@code "und"} (undetermined)
 * and {@code "none"} when no context is present in the dispatch envelope.
 *
 * @see ServiceHandler
 * @see DispatchContext
 */
@Slf4j
public class LocaleEchoServiceHandler implements ServiceHandler<LocaleEchoService> {

    /**
     * Creates a new locale-echo service handler.
     */
    @Inject
    LocaleEchoServiceHandler() {}

    /**
     * Echoes back the locale from the service dispatch context.
     *
     * @return a future containing the locale language tag and source label read from
     *         {@link DispatchContext}; falls back to {@code languageTag="und"} and
     *         {@code localeSource="none"} when the context is absent
     */
    public Future<LocaleEchoResponse> echoLocale() {
        Optional<LocalizationContext> current = DispatchContext.current(LocalizationContext.class);
        LocaleEchoResponse response = current.map(ctx -> new LocaleEchoResponse(ctx.languageTag(), ctx.localeSource()))
                .orElseGet(() -> new LocaleEchoResponse("und", "none"));
        log.debug("echoLocale: languageTag={}, source={}", response.languageTag(), response.localeSource());
        return Future.succeededFuture(response);
    }
}
