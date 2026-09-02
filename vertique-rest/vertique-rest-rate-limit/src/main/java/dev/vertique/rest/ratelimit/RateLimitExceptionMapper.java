// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.time.Duration;

/**
 * Maps {@link RateLimitExceededException}/{@link RateLimitUnavailableException} to the frozen HTTP
 * responses contracts/rest-adapter.md's "HTTP mapping" table declares. Registered via Dagger
 * multibinding into {@code Set<ExceptionMapper<?>>} (the same seam
 * {@code GreetingLimitExceptionMapper} uses in {@code vertique-example-hello}), where {@code
 * ErrorPipeline}/{@code ExceptionMapperRegistry} preserve every header this mapper writes —
 * including {@code Retry-After} and {@code Cache-Control} — verbatim to the wire.
 *
 * <p>JAX-RS requires one exception type per {@link ExceptionMapper}, so the two mapped exception
 * types are handled by the two nested mapper classes below; both delegate to this class's shared,
 * directly-testable {@code toResponse} overloads. Neither response body carries {@code
 * policyName}, key material, identity, backend detail, or the exception's message — only a fixed,
 * generic {@link ProblemDetail} per status (contracts/rest-adapter.md, "HTTP mapping").
 */
public final class RateLimitExceptionMapper {

    // Both bodies are constant across every request: neither status carries a per-request field
    // (contracts/rest-adapter.md, "HTTP mapping"). ProblemDetail is immutable (final fields), and
    // ErrorPipeline.enrichProblemDetail derives a copy via toBuilder() rather than mutating the
    // instance passed in, so sharing one instance across concurrent requests is safe.
    private static final ProblemDetail EXCEEDED_BODY = ProblemDetail.of(429, null);
    private static final ProblemDetail UNAVAILABLE_BODY = ProblemDetail.of(503, null);

    private RateLimitExceptionMapper() {}

    /**
     * Maps a quota denial to {@code 429}, with {@code Retry-After: max(1, ceil(retryAfter.toMillis()
     * / 1000))} and {@code Cache-Control: no-store}.
     *
     * @param exception the quota-denial exception; its decision supplies {@code retryAfter} only —
     *     no other decision field crosses this boundary
     * @return the mapped {@code 429} response
     */
    static Response toResponse(RateLimitExceededException exception) {
        Duration retryAfter = exception.decision().retryAfter().orElse(Duration.ZERO);
        return Response.status(429)
                .header(
                        RateLimitHttpMapping.RETRY_AFTER_HEADER,
                        Long.toString(RateLimitHttpMapping.retryAfterSeconds(retryAfter)))
                .header(RateLimitHttpMapping.CACHE_CONTROL_HEADER, RateLimitHttpMapping.CACHE_CONTROL_NO_STORE)
                .type(RateLimitHttpMapping.PROBLEM_JSON)
                .entity(EXCEEDED_BODY)
                .build();
    }

    /**
     * Maps a fail-closed backend failure to {@code 503}, with {@code Cache-Control: no-store} and
     * no quota headers of any kind.
     *
     * @param exception the fail-closed exception
     * @return the mapped {@code 503} response
     */
    static Response toResponse(RateLimitUnavailableException exception) {
        return Response.status(503)
                .header(RateLimitHttpMapping.CACHE_CONTROL_HEADER, RateLimitHttpMapping.CACHE_CONTROL_NO_STORE)
                .type(RateLimitHttpMapping.PROBLEM_JSON)
                .entity(UNAVAILABLE_BODY)
                .build();
    }

    /** Registered {@link ExceptionMapper} for {@link RateLimitExceededException} ({@code 429}). */
    public static final class Exceeded implements ExceptionMapper<RateLimitExceededException> {

        @Inject
        public Exceeded() {}

        @Override
        public Response toResponse(RateLimitExceededException exception) {
            return RateLimitExceptionMapper.toResponse(exception);
        }
    }

    /** Registered {@link ExceptionMapper} for {@link RateLimitUnavailableException} ({@code 503}). */
    public static final class Unavailable implements ExceptionMapper<RateLimitUnavailableException> {

        @Inject
        public Unavailable() {}

        @Override
        public Response toResponse(RateLimitUnavailableException exception) {
            return RateLimitExceptionMapper.toResponse(exception);
        }
    }
}
