// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * TP-001: {@code RateLimitExceptionMapper} maps {@code RateLimitExceededException}/{@code
 * RateLimitUnavailableException} to the frozen HTTP responses contracts/rest-adapter.md's "HTTP
 * mapping" table declares.
 */
class RateLimitExceptionMapperTest {

    private static final String SENSITIVE_POLICY_NAME = "internal-tenant-quota-policy-42";

    @Test
    void shouldMap429WithRoundedRetryAfterAndNoStore() {
        RateLimitExceededException row1500ms = exceeded(Duration.ofMillis(1500));
        Response response1500 = RateLimitExceptionMapper.toResponse(row1500ms);

        assertThat(response1500.getStatus()).isEqualTo(429);
        assertThat(response1500.getHeaderString("Retry-After")).isEqualTo("2");
        assertThat(response1500.getHeaderString("Cache-Control")).isEqualTo("no-store");

        // Sensitivity proof: 2001ms must round to 3s, not a hardcoded 2 — proves the assertion is
        // sensitive to the actual duration.
        RateLimitExceededException row2001ms = exceeded(Duration.ofMillis(2001));
        Response response2001 = RateLimitExceptionMapper.toResponse(row2001ms);
        assertThat(response2001.getHeaderString("Retry-After")).isEqualTo("3");
    }

    @Test
    void shouldMap503WithNoQuotaHeaders() {
        RateLimitUnavailableException exception = unavailable();

        Response response = RateLimitExceptionMapper.toResponse(exception);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaderString("Retry-After")).isNull();
    }

    @Test
    void shouldNeverExposeDecisionKeyOrCauseDetail() {
        RateLimitExceededException exceededException = exceeded(Duration.ofMillis(1500));
        RateLimitUnavailableException unavailableException = unavailable();

        Response exceededResponse = RateLimitExceptionMapper.toResponse(exceededException);
        Response unavailableResponse = RateLimitExceptionMapper.toResponse(unavailableException);

        assertNoSensitiveContent(exceededResponse, exceededException);
        assertNoSensitiveContent(unavailableResponse, unavailableException);
    }

    private static void assertNoSensitiveContent(Response response, RuntimeException exception) {
        Object entity = response.getEntity();
        assertThat(entity).isInstanceOf(ProblemDetail.class);
        ProblemDetail problem = (ProblemDetail) entity;

        String bodyDump = String.valueOf(problem.type())
                + problem.title()
                + problem.status()
                + problem.detail()
                + problem.instance()
                + problem.extensions();
        String headerDump = response.getStringHeaders().toString();

        assertThat(bodyDump).doesNotContain(SENSITIVE_POLICY_NAME);
        assertThat(bodyDump).doesNotContain(exception.getMessage());
        assertThat(headerDump).doesNotContain(SENSITIVE_POLICY_NAME);
        assertThat(headerDump).doesNotContain(exception.getMessage());
    }

    private static RateLimitExceededException exceeded(Duration retryAfter) {
        RateLimitDecision decision = new RateLimitDecision(
                SENSITIVE_POLICY_NAME,
                RateLimitOutcome.QUOTA_EXCEEDED,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                10L,
                OptionalLong.of(0L),
                Optional.of(retryAfter),
                Optional.empty(),
                Optional.empty());
        return new RateLimitExceededException(decision);
    }

    private static RateLimitUnavailableException unavailable() {
        RateLimitDecision decision = new RateLimitDecision(
                SENSITIVE_POLICY_NAME,
                RateLimitOutcome.BACKEND_FAILURE_CLOSED,
                RateLimitMode.CLUSTERED,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                10L,
                OptionalLong.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(RateLimitFailureCode.UNAVAILABLE));
        return new RateLimitUnavailableException(decision);
    }
}
