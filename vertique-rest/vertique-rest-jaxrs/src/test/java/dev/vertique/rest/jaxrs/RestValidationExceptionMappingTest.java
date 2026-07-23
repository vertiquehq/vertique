// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the FR-012 parity guarantee that a {@link RestValidationException} — the exception the
 * web-validation gate fails the request with — is mapped by the framework's default exception mapper
 * to a 400 {@code application/problem+json} response whose body carries the structured field errors.
 *
 * <p>This exercises {@link RestModule#defaultExceptionMapper()} directly (the same package-private
 * factory the runtime wires), so the assertion runs end-to-end through the production mapping path
 * rather than against a hand-built {@link ValidationProblemDetail}.
 */
class RestValidationExceptionMappingTest {

    @Test
    @DisplayName("RestValidationException maps to 400 application/problem+json with an errors array")
    void restValidationExceptionMapsTo400ProblemDetail() {
        DefaultExceptionMapper mapper = RestModule.defaultExceptionMapper();
        RestValidationException ex = new RestValidationException(
                "Request validation failed",
                List.of(new ValidationErrorDetail("/name", "is required", "body", "required", null)));

        Response response = mapper.toResponse(ex);

        assertEquals(400, response.getStatus());
        assertEquals("application/problem+json", response.getMediaType().toString());
        ValidationProblemDetail problem = assertInstanceOf(ValidationProblemDetail.class, response.getEntity());
        assertFalse(problem.errors().isEmpty(), "the problem body must carry at least one error entry");
        assertEquals("/name", problem.errors().get(0).path());
    }
}
