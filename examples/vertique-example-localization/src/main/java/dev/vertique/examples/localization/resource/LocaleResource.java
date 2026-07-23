// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.localization.resource;

import dev.vertique.examples.localization.service.LocaleEchoResponse;
import dev.vertique.examples.localization.service.LocaleEchoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST resource exposing the locale-echo endpoint.
 *
 * <p>Dispatches to the {@link LocaleEchoService} event-bus proxy, which reads the
 * {@link dev.vertique.localization.context.LocalizationContext} bound by
 * {@link dev.vertique.rest.localization.RequestLocaleInterceptor} and propagated through the
 * service dispatch envelope. The returned DTO carries the resolved locale's BCP 47 language tag
 * and low-cardinality source label.
 */
@Path("/locale")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Locale", description = "Locale negotiation demonstration endpoint")
public class LocaleResource {

    private final LocaleEchoService localeEchoService;

    /**
     * Creates a new locale resource.
     *
     * @param localeEchoService the event-bus service client for locale-echo operations
     */
    @Inject
    LocaleResource(LocaleEchoService localeEchoService) {
        this.localeEchoService = localeEchoService;
    }

    /**
     * Returns the negotiated locale for the current request.
     *
     * <p>The locale is resolved from the {@code Accept-Language} header or the {@code ?lang=}
     * query parameter (custom source, higher priority), then propagated through service dispatch.
     *
     * @return a future containing the resolved locale's language tag and source label
     */
    @GET
    @Operation(
            operationId = "getLocale",
            summary = "Echo the negotiated locale",
            description = "Returns the locale resolved from Accept-Language or ?lang= query parameter, "
                    + "propagated through the service dispatch context")
    @ApiResponse(
            responseCode = "200",
            description = "Locale resolved",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocaleEchoResponse.class)))
    public Future<LocaleEchoResponse> getLocale() {
        return localeEchoService.echoLocale();
    }
}
