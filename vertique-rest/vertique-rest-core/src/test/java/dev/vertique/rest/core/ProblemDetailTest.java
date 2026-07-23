// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemDetailTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("of(status, detail, instance) creates full ProblemDetail")
    void shouldCreateFullProblemDetail() {
        ProblemDetail pd = ProblemDetail.of(404, "Item not found", "/items/42");
        assertEquals("about:blank", pd.type());
        assertEquals("Not Found", pd.title());
        assertEquals(404, pd.status());
        assertEquals("Item not found", pd.detail());
        assertEquals("/items/42", pd.instance());
    }

    @Test
    @DisplayName("of(status, detail) creates ProblemDetail without instance")
    void shouldCreateProblemDetailWithoutInstance() {
        ProblemDetail pd = ProblemDetail.of(400, "Invalid input");
        assertEquals("about:blank", pd.type());
        assertEquals("Bad Request", pd.title());
        assertEquals(400, pd.status());
        assertEquals("Invalid input", pd.detail());
        assertNull(pd.instance());
    }

    @Test
    @DisplayName("titleForStatus maps standard HTTP status codes")
    void shouldMapStandardStatusCodes() {
        assertEquals("Bad Request", ProblemDetail.titleForStatus(400));
        assertEquals("Unauthorized", ProblemDetail.titleForStatus(401));
        assertEquals("Forbidden", ProblemDetail.titleForStatus(403));
        assertEquals("Not Found", ProblemDetail.titleForStatus(404));
        assertEquals("Method Not Allowed", ProblemDetail.titleForStatus(405));
        assertEquals("Not Acceptable", ProblemDetail.titleForStatus(406));
        assertEquals("Conflict", ProblemDetail.titleForStatus(409));
        assertEquals("Payload Too Large", ProblemDetail.titleForStatus(413));
        assertEquals("Unsupported Media Type", ProblemDetail.titleForStatus(415));
        assertEquals("Unprocessable Entity", ProblemDetail.titleForStatus(422));
        assertEquals("Too Many Requests", ProblemDetail.titleForStatus(429));
        assertEquals("Internal Server Error", ProblemDetail.titleForStatus(500));
        assertEquals("Bad Gateway", ProblemDetail.titleForStatus(502));
        assertEquals("Service Unavailable", ProblemDetail.titleForStatus(503));
        assertEquals("Gateway Timeout", ProblemDetail.titleForStatus(504));
    }

    @Test
    @DisplayName("titleForStatus returns 'Error' for unknown status codes")
    void shouldReturnErrorForUnknownStatus() {
        assertEquals("Error", ProblemDetail.titleForStatus(418));
        assertEquals("Error", ProblemDetail.titleForStatus(599));
    }

    @Test
    @DisplayName("JSON serialization omits null fields")
    void shouldOmitNullFieldsInJson() throws JsonProcessingException {
        ProblemDetail pd = ProblemDetail.of(500, "Something went wrong");
        String json = mapper.writeValueAsString(pd);

        assertTrue(json.contains("\"type\""));
        assertTrue(json.contains("\"title\""));
        assertTrue(json.contains("\"status\""));
        assertTrue(json.contains("\"detail\""));
        assertFalse(json.contains("\"instance\"")); // null, should be omitted
    }

    @Test
    @DisplayName("JSON serialization includes all non-null fields")
    void shouldIncludeAllNonNullFieldsInJson() throws JsonProcessingException {
        ProblemDetail pd = ProblemDetail.of(404, "Not found", "/items/42");
        String json = mapper.writeValueAsString(pd);

        assertTrue(json.contains("\"type\":\"about:blank\""));
        assertTrue(json.contains("\"title\":\"Not Found\""));
        assertTrue(json.contains("\"status\":404"));
        assertTrue(json.contains("\"detail\":\"Not found\""));
        assertTrue(json.contains("\"instance\":\"/items/42\""));
    }

    @Test
    @DisplayName("builder() provides fluent setters for all standard fields")
    void shouldBuildWithAllStandardFields() {
        ProblemDetail pd = ProblemDetail.builder()
                .type("https://example.com/problems/test")
                .title("Test Problem")
                .status(422)
                .detail("Something went wrong")
                .instance("/requests/42")
                .build();

        assertEquals("https://example.com/problems/test", pd.type());
        assertEquals("Test Problem", pd.title());
        assertEquals(422, pd.status());
        assertEquals("Something went wrong", pd.detail());
        assertEquals("/requests/42", pd.instance());
    }

    @Test
    @DisplayName("extension() adds entries serialized inline in JSON")
    void shouldSerializeExtensionsInline() throws JsonProcessingException {
        ProblemDetail pd = ProblemDetail.builder()
                .status(429)
                .detail("Rate limit exceeded")
                .extension("limit", 100)
                .extension("retryAfter", 60)
                .build();

        String json = mapper.writeValueAsString(pd);
        assertTrue(json.contains("\"limit\":100"));
        assertTrue(json.contains("\"retryAfter\":60"));
    }

    @Test
    @DisplayName("toBuilder() produces a modified copy without changing the original")
    void shouldSupportToBuilder() {
        ProblemDetail original = ProblemDetail.of(404, "Not found");
        assertNull(original.instance());

        ProblemDetail enriched = original.toBuilder().instance("/items/42").build();

        assertNull(original.instance());
        assertEquals("/items/42", enriched.instance());
        assertEquals(original.status(), enriched.status());
        assertEquals(original.detail(), enriched.detail());
        assertEquals(original.type(), enriched.type());
        assertEquals(original.title(), enriched.title());
    }
}
