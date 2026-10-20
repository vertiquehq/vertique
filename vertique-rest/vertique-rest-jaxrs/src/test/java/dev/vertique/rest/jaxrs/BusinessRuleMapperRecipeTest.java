// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.rest.core.ProblemDetail;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the recipe the module reference gives for a business rule that carries its own code: an
 * application exception under {@code BusinessRuleException} with its own mapper outranks the default
 * mapping and returns a problem document with the code as a sibling member.
 */
class BusinessRuleMapperRecipeTest {

    private static class CreditLimitExceededException extends BusinessRuleException {
        private final String code;

        CreditLimitExceededException(String message, String code) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static class CreditLimitExceededMapper implements ExceptionMapper<CreditLimitExceededException> {
        @Override
        public Response toResponse(CreditLimitExceededException ex) {
            return Response.status(422)
                    .entity(ProblemDetail.builder()
                            .type("about:blank")
                            .title("Business rule violated")
                            .status(422)
                            .detail(ex.getMessage())
                            .extension("code", ex.code())
                            .build())
                    .type("application/problem+json")
                    .build();
        }
    }

    @Test
    @DisplayName("an application mapper for a business-rule subclass wins and renders the code as a sibling member")
    void applicationMapperRendersTheCode() throws Exception {
        // Given: the default mapping plus the application's mapper for its own exception
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(
                RestModule.defaultExceptionMapper(), Set.of(new CreditLimitExceededMapper()));

        // When: the exception is mapped
        Response response = registry.toResponse(new CreditLimitExceededException("credit limit exceeded", "RULE001"));

        // Then: the response is the application's 422 problem with code beside the standard members
        assertEquals(422, response.getStatus());
        JsonNode body = VertiqueJson.mapper().valueToTree(assertInstanceOf(ProblemDetail.class, response.getEntity()));
        assertEquals("RULE001", body.path("code").asText());
        assertEquals("credit limit exceeded", body.path("detail").asText());
        assertEquals(422, body.path("status").asInt());
    }
}
