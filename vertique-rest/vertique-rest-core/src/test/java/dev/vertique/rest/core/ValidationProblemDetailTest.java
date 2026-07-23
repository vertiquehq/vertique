// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationProblemDetail}.
 *
 * <p>Verifies the factory method, JSON serialization of the {@code errors} array,
 * empty error list handling, and field accessor correctness.
 */
class ValidationProblemDetailTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("of() creates a 400 ValidationProblemDetail with the given detail and errors")
    void shouldCreateViaFactory() {
        List<ValidationErrorDetail> errors = List.of(
                new ValidationErrorDetail("/name", "must not be blank", "body", "required", null),
                new ValidationErrorDetail("/age", "must be a positive integer", "body", "invalid_value", null));

        ValidationProblemDetail pd = ValidationProblemDetail.of("Request validation failed", errors);

        assertEquals("about:blank", pd.type());
        assertEquals("Bad Request", pd.title());
        assertEquals(400, pd.status());
        assertEquals("Request validation failed", pd.detail());
        assertEquals(2, pd.errors().size());
        assertEquals("/name", pd.errors().get(0).path());
        assertEquals("must not be blank", pd.errors().get(0).detail());
        assertEquals("body", pd.errors().get(0).location());
        assertEquals("required", pd.errors().get(0).type());
    }

    @Test
    @DisplayName("of() with empty errors list produces a ValidationProblemDetail with no errors")
    void shouldCreateWithEmptyErrors() {
        ValidationProblemDetail pd = ValidationProblemDetail.of("Validation failed", List.of());

        assertEquals(400, pd.status());
        assertNotNull(pd.errors());
        assertTrue(pd.errors().isEmpty());
    }

    @Test
    @DisplayName("JSON serialization includes the errors array with path and detail fields")
    void shouldSerializeErrorsArrayToJson() throws JsonProcessingException {
        List<ValidationErrorDetail> errors =
                List.of(new ValidationErrorDetail("/email", "invalid format", "body", "invalid_value", null));

        ValidationProblemDetail pd = ValidationProblemDetail.of("Validation failed", errors);
        String json = mapper.writeValueAsString(pd);

        assertTrue(json.contains("\"errors\""));
        assertTrue(json.contains("\"path\":\"/email\""));
        assertTrue(json.contains("\"detail\":\"invalid format\""));
        assertTrue(json.contains("\"location\":\"body\""));
        assertTrue(json.contains("\"status\":400"));
    }

    @Test
    @DisplayName("JSON serialization omits null location and type in error details")
    void shouldOmitNullLocationAndType() throws JsonProcessingException {
        ValidationProblemDetail pd =
                ValidationProblemDetail.of("Validation failed", List.of(ValidationErrorDetail.of("/x", "bad")));
        String json = mapper.writeValueAsString(pd);

        // Error detail should NOT contain location or type (they're null)
        // But the top-level ProblemDetail DOES have a "type" field ("about:blank")
        assertFalse(json.contains("\"location\""));
        assertTrue(json.contains("\"path\":\"/x\""));
        // Verify the error object only has path and detail (no location/type keys)
        assertTrue(json.contains("{\"path\":\"/x\",\"detail\":\"bad\"}"));
    }

    @Test
    @DisplayName("toBuilder() produces a modified copy without changing the original")
    void shouldSupportToBuilder() {
        List<ValidationErrorDetail> errors = List.of(ValidationErrorDetail.of("/name", "required"));
        ValidationProblemDetail original = ValidationProblemDetail.of("Validation failed", errors);
        assertNull(original.instance());

        ValidationProblemDetail enriched =
                original.toBuilder().instance("/api/users").build();

        assertNull(original.instance());
        assertEquals("/api/users", enriched.instance());
        assertEquals(original.errors(), enriched.errors());
        assertEquals(original.status(), enriched.status());
    }

    @Test
    @DisplayName("ValidationErrorDetail.of() creates detail with null location and type")
    void shouldCreateErrorDetailWithConvenienceFactory() {
        ValidationErrorDetail detail = ValidationErrorDetail.of("/foo/bar", "too long");

        assertEquals("/foo/bar", detail.path());
        assertEquals("too long", detail.detail());
        assertNull(detail.location());
        assertNull(detail.type());
    }
}
