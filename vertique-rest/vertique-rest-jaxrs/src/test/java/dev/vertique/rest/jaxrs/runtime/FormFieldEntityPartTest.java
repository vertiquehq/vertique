// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.MediaType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormFieldEntityPart}.
 *
 * <p>Verifies that a plain form field value is correctly exposed through the
 * JAX-RS {@link jakarta.ws.rs.core.EntityPart} interface.
 */
class FormFieldEntityPartTest {

    @Test
    @DisplayName("getName() returns the field name supplied at construction")
    void shouldReturnName() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "value");
        assertEquals("field", part.getName());
    }

    @Test
    @DisplayName("getFileName() returns Optional.empty() for a plain form field")
    void shouldReturnEmptyFileName() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "value");
        assertTrue(part.getFileName().isEmpty());
    }

    @Test
    @DisplayName("getContent() returns a ByteArrayInputStream of the field value")
    void shouldReturnContentAsInputStream() throws Exception {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello");
        try (InputStream in = part.getContent()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("hello", content);
        }
    }

    @Test
    @DisplayName("getContent() throws IllegalStateException on second call")
    void shouldThrowOnSecondGetContent() throws Exception {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello");
        try (InputStream in = part.getContent()) {
            in.readAllBytes();
        }
        assertThrows(IllegalStateException.class, part::getContent);
    }

    @Test
    @DisplayName("getContent(String.class) returns the field value directly")
    void shouldReturnContentAsString() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello");
        assertEquals("hello", part.getContent(String.class));
    }

    @Test
    @DisplayName("getContent(byte[].class) returns the field value as UTF-8 bytes")
    void shouldReturnContentAsBytes() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello");
        byte[] bytes = part.getContent(byte[].class);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    @DisplayName("getContent(Integer.class) throws IllegalArgumentException for unsupported type")
    void shouldThrowForUnsupportedContentType() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello");
        assertThrows(IllegalArgumentException.class, () -> part.getContent(Integer.class));
    }

    @Test
    @DisplayName("getMediaType() returns TEXT_PLAIN_TYPE")
    void shouldReturnTextPlainMediaType() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "value");
        assertEquals(MediaType.TEXT_PLAIN_TYPE, part.getMediaType());
    }

    @Test
    @DisplayName("getHeaders() contains Content-Type text/plain and Content-Disposition with name")
    void shouldReturnHeadersWithContentTypeAndDisposition() {
        FormFieldEntityPart part = new FormFieldEntityPart("myField", "someValue");
        var headers = part.getHeaders();

        assertEquals(MediaType.TEXT_PLAIN, headers.getFirst("Content-Type"));

        String disposition = headers.getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("name=\"myField\""), "disposition should include name: " + disposition);
    }

    @Test
    @DisplayName("getHeaders() escapes double-quotes in name")
    void shouldEscapeQuotesInName() {
        FormFieldEntityPart part = new FormFieldEntityPart("field\"name", "value");
        String disposition = part.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(
                disposition.contains("name=\"field\\\"name\""),
                "disposition should escape quote in name: " + disposition);
    }

    @Test
    @DisplayName("getHeaders() strips newlines from name")
    void shouldStripNewlinesFromName() {
        FormFieldEntityPart part = new FormFieldEntityPart("field\nname", "value");
        String disposition = part.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(
                disposition.contains("name=\"fieldname\""),
                "disposition should strip newlines from name: " + disposition);
    }

    // --- getContentAsync() ---

    @Test
    @DisplayName("getContentAsync() returns correct content as Buffer")
    void shouldReturnContentAsyncAsBuffer() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "hello world");
        var future = part.getContentAsync();
        assertTrue(future.succeeded());
        assertEquals("hello world", future.result().toString());
    }

    @Test
    @DisplayName("getContentAsync() enforces single consumption")
    void shouldThrowOnSecondGetContentAsync() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "value");
        part.getContentAsync();
        assertThrows(IllegalStateException.class, part::getContentAsync);
    }

    @Test
    @DisplayName("getContentAsync() then getContent() throws IllegalStateException")
    void shouldThrowGetContentAfterGetContentAsync() {
        FormFieldEntityPart part = new FormFieldEntityPart("field", "value");
        part.getContentAsync();
        assertThrows(IllegalStateException.class, part::getContent);
    }
}
