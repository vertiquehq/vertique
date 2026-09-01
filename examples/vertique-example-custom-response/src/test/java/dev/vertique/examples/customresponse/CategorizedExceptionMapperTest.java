// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.exception.TooManyRequestsException;
import dev.vertique.examples.customresponse.ErrorResponse.ErrorCategory;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * T021 W1 ripple: {@link TooManyRequestsException} re-parented directly under {@code
 * VertiqueException} (no longer a {@code ValidationException}), so {@link
 * CategorizedExceptionMapper#categorize} would otherwise fall it out of {@link
 * ErrorCategory#VALIDATION} into the generic {@link ErrorCategory#TECHNICAL} (500) bucket — wrong
 * both semantically and on the wire, since a rate-limit denial is a 429, not a 500. This mapper gets
 * its own explicit {@link ErrorCategory#RATE_LIMITED} branch so the example's categorized-response
 * demonstration stays honest for a rate-limited request.
 */
class CategorizedExceptionMapperTest {

    private final CategorizedExceptionMapper mapper = new CategorizedExceptionMapper();

    @Test
    void tooManyRequestsExceptionIsCategorizedRateLimitedAndMapsTo429() {
        Response response = mapper.toResponse(new TooManyRequestsException("slow down", Duration.ofSeconds(5)));

        assertEquals(429, response.getStatus());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertEquals(ErrorCategory.RATE_LIMITED, body.type());
        assertEquals("slow down", body.message());
    }
}
