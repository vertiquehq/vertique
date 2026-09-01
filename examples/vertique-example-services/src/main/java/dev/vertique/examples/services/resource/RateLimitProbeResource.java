// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.resource;

import dev.vertique.examples.services.service.RateLimitProbeResult;
import dev.vertique.examples.services.service.RateLimitProbeService;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST resource exercising the programmatic {@link RateLimiters} handle and the {@code
 * @RateLimited}-annotated {@link RateLimitProbeService} side by side (T013, spec.md §4.4
 * programmatic usage + §13 item 9 transport-neutrality proof), proving both entry points resolve
 * through the same runtime rather than two independent counters.
 */
@Path("/rate-limit-probe")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
@Tag(name = "Rate limit probe", description = "Programmatic and annotated rate-limit demonstration endpoints")
public class RateLimitProbeResource {

    private final RateLimiters rateLimiters;
    private final RateLimitProbeService rateLimitProbeService;

    /**
     * Creates a rate-limit probe resource.
     *
     * @param rateLimiters the application-scoped rate-limit runtime, used directly by the
     *     programmatic path
     * @param rateLimitProbeService the generated typed client for the annotated probe operation
     */
    @Inject
    public RateLimitProbeResource(RateLimiters rateLimiters, RateLimitProbeService rateLimitProbeService) {
        this.rateLimiters = rateLimiters;
        this.rateLimitProbeService = rateLimitProbeService;
    }

    /**
     * Calls {@link RateLimiters#limiter(String)}/{@code acquire(...)} directly against {@link
     * RateLimitProbeService#POLICY_NAME}'s policy — the programmatic usage pattern (spec.md §4.4).
     * {@code acquire(...)} never fails for quota reasons (decision-first), so the resolved outcome
     * is always reported in the {@code 200} response body, including {@code QUOTA_EXCEEDED}.
     *
     * @return a future completing with the resolved decision's outcome
     */
    @GET
    @Path("/programmatic")
    @Operation(operationId = "rateLimitProbeProgrammatic", summary = "Programmatic rate-limit probe")
    @ApiResponse(responseCode = "200", description = "Decision outcome returned")
    public Future<RateLimitProbeResult> programmaticProbe() {
        return rateLimiters
                .limiter(RateLimitProbeService.POLICY_NAME)
                .acquire(RateLimitKey.global())
                .map(decision -> new RateLimitProbeResult(decision.outcome().name()));
    }

    /**
     * Invokes the {@code @RateLimited}-annotated service method through the generated typed
     * client; a quota denial surfaces as {@code RateLimitExceededException}, mapped to {@code 429}
     * by {@code RestRateLimitModule} (contracts/rest-adapter.md).
     *
     * @return a future completing with a fixed admitted outcome, or failing once the shared bucket
     *     is exhausted
     */
    @GET
    @Path("/annotated")
    @Operation(operationId = "rateLimitProbeAnnotated", summary = "Annotated rate-limit probe")
    @ApiResponse(responseCode = "200", description = "Call admitted")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public Future<RateLimitProbeResult> annotatedProbe() {
        return rateLimitProbeService.probe();
    }
}
