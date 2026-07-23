// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.client.exception.RestClientException;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpClientResponse}.
 *
 * <p>Verifies status code/message accessors, header access, body deserialization via
 * {@code bodyAs(Class, ObjectMapper)}, {@code bodyAs(TypeReference, ObjectMapper)},
 * and {@code bodyAsString()}.
 */
class HttpClientResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Simple DTO used in deserialization tests. */
    record Item(String name) {}

    @SuppressWarnings("unchecked")
    private HttpResponse<Buffer> mockResponse(int statusCode, String statusMessage, String bodyJson) {
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.statusMessage()).thenReturn(statusMessage);
        when(response.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        Buffer body = bodyJson != null ? Buffer.buffer(bodyJson) : null;
        when(response.body()).thenReturn(body);
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<Buffer> mockResponseWithHeaders(
            int statusCode, String statusMessage, String bodyJson, MultiMap headers) {
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.statusMessage()).thenReturn(statusMessage);
        when(response.headers()).thenReturn(headers);
        Buffer body = bodyJson != null ? Buffer.buffer(bodyJson) : null;
        when(response.body()).thenReturn(body);
        return response;
    }

    private HttpClientResponse subject;

    @BeforeEach
    void setUp() {
        subject = new HttpClientResponse(mockResponse(200, "OK", "{\"name\":\"test\"}"));
    }

    // --- Accessor tests ---

    @Test
    @DisplayName("statusCode() returns the HTTP status code from the underlying response")
    void statusCodeReturnsCorrectValue() {
        assertThat(subject.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("statusMessage() returns the HTTP status message from the underlying response")
    void statusMessageReturnsCorrectValue() {
        assertThat(subject.statusMessage()).isEqualTo("OK");
    }

    @Test
    @DisplayName("headers() returns the response headers, never null")
    void headersAreNeverNull() {
        assertThat(subject.headers()).isNotNull();
    }

    @Test
    @DisplayName("headers() returns headers from the underlying response")
    void headersContainExpectedValues() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("Content-Type", "application/json");
        headers.set("X-Request-Id", "abc-123");

        HttpClientResponse response =
                new HttpClientResponse(mockResponseWithHeaders(200, "OK", "{\"name\":\"a\"}", headers));

        assertThat(response.headers().get("Content-Type")).isEqualTo("application/json");
        assertThat(response.headers().get("X-Request-Id")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("body() returns the raw buffer, never null")
    void bodyIsNeverNull() {
        assertThat(subject.body()).isNotNull();
    }

    @Test
    @DisplayName("body() returns empty buffer when underlying response body is null")
    void bodyIsEmptyWhenUnderlyingBodyIsNull() {
        HttpClientResponse response = new HttpClientResponse(mockResponse(204, "No Content", null));
        assertThat(response.body()).isNotNull();
        assertThat(response.body().length()).isEqualTo(0);
    }

    // --- bodyAsString tests ---

    @Test
    @DisplayName("bodyAsString() decodes body as UTF-8 string")
    void bodyAsStringDecodesUtf8() {
        assertThat(subject.bodyAsString()).isEqualTo("{\"name\":\"test\"}");
    }

    @Test
    @DisplayName("bodyAsString() returns empty string when body is empty")
    void bodyAsStringReturnsEmptyForEmptyBody() {
        HttpClientResponse response = new HttpClientResponse(mockResponse(204, "No Content", null));
        assertThat(response.bodyAsString()).isEqualTo("");
    }

    // --- bodyAs(Class) tests ---

    @Test
    @DisplayName("bodyAs(Class, ObjectMapper) deserializes JSON body into the target type")
    void bodyAsClassDeserializesJson() {
        Item item = subject.bodyAs(Item.class, MAPPER);
        assertThat(item.name()).isEqualTo("test");
    }

    @Test
    @DisplayName("bodyAs(Class, ObjectMapper) throws RestClientException on invalid JSON")
    void bodyAsClassThrowsOnInvalidJson() {
        HttpClientResponse response = new HttpClientResponse(mockResponse(200, "OK", "not-json"));
        assertThatThrownBy(() -> response.bodyAs(Item.class, MAPPER))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Failed to deserialize response body");
    }

    // --- bodyAs(TypeReference) tests ---

    @Test
    @DisplayName("bodyAs(TypeReference, ObjectMapper) deserializes JSON array into List<T>")
    void bodyAsTypeRefDeserializesList() {
        HttpClientResponse response =
                new HttpClientResponse(mockResponse(200, "OK", "[{\"name\":\"a\"},{\"name\":\"b\"}]"));

        List<Item> items = response.bodyAs(new TypeReference<List<Item>>() {}, MAPPER);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).name()).isEqualTo("a");
        assertThat(items.get(1).name()).isEqualTo("b");
    }

    @Test
    @DisplayName("bodyAs(TypeReference, ObjectMapper) throws RestClientException on invalid JSON")
    void bodyAsTypeRefThrowsOnInvalidJson() {
        HttpClientResponse response = new HttpClientResponse(mockResponse(200, "OK", "not-json-array"));
        assertThatThrownBy(() -> response.bodyAs(new TypeReference<List<Item>>() {}, MAPPER))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("Failed to deserialize response body");
    }
}
