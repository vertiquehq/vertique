// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.vertx.core.Vertx;
import io.vertx.ext.web.FileUpload;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.core.MediaType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VertxFileUploadEntityPart}.
 *
 * <p>Uses a real {@link Vertx} instance so that {@link AsyncFileInputStream} can perform
 * async file I/O. Test content is written to a temp file; the mock {@link FileUpload}
 * provides metadata and points to that file.
 */
@ExtendWith(VertxExtension.class)
class VertxFileUploadEntityPartTest {

    private static final String FILE_CONTENT = "hello upload";

    @TempDir
    Path tempDir;

    private Vertx vertx;
    private FileUpload fileUpload;
    private Path tempFile;

    @BeforeEach
    void setUp(Vertx vertx) throws Exception {
        this.vertx = vertx;
        tempFile = tempDir.resolve("upload.txt");
        Files.writeString(tempFile, FILE_CONTENT);

        fileUpload = mock(FileUpload.class);
        when(fileUpload.name()).thenReturn("file");
        when(fileUpload.fileName()).thenReturn("upload.txt");
        when(fileUpload.uploadedFileName()).thenReturn(tempFile.toString());
        when(fileUpload.contentType()).thenReturn("text/plain");
    }

    @Test
    @DisplayName("getName() returns the form field name from FileUpload")
    void shouldReturnName() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        assertEquals("file", part.getName());
    }

    @Test
    @DisplayName("getFileName() returns Optional.of(fileName) when fileName is present")
    void shouldReturnFileName() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        assertTrue(part.getFileName().isPresent());
        assertEquals("upload.txt", part.getFileName().get());
    }

    @Test
    @DisplayName("getFileName() returns Optional.empty() when fileName is null")
    void shouldReturnEmptyFileNameWhenNull() {
        when(fileUpload.fileName()).thenReturn(null);
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        assertTrue(part.getFileName().isEmpty());
    }

    @Test
    @DisplayName("getContent() returns an InputStream that streams file content asynchronously")
    void shouldReturnContentAsInputStream() throws Exception {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        try (InputStream in = part.getContent()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(FILE_CONTENT, content);
        }
    }

    @Test
    @DisplayName("getContent() throws IllegalStateException on second call")
    void shouldThrowOnSecondGetContent() throws Exception {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        try (InputStream in = part.getContent()) {
            in.readAllBytes();
        }
        assertThrows(IllegalStateException.class, part::getContent);
    }

    @Test
    @DisplayName("getContent(String.class) reads content as UTF-8 text")
    void shouldReturnContentAsString() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        String content = part.getContent(String.class);
        assertEquals(FILE_CONTENT, content);
    }

    @Test
    @DisplayName("getContent(byte[].class) reads content as raw bytes")
    void shouldReturnContentAsBytes() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        byte[] bytes = part.getContent(byte[].class);
        assertArrayEquals(FILE_CONTENT.getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    @DisplayName("getContent(Integer.class) throws IllegalArgumentException for unsupported type")
    void shouldThrowForUnsupportedContentType() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        assertThrows(IllegalArgumentException.class, () -> part.getContent(Integer.class));
    }

    @Test
    @DisplayName("getMediaType() returns parsed MediaType from contentType")
    void shouldReturnParsedMediaType() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        MediaType mediaType = part.getMediaType();
        assertEquals("text", mediaType.getType());
        assertEquals("plain", mediaType.getSubtype());
    }

    @Test
    @DisplayName("getMediaType() returns APPLICATION_OCTET_STREAM when contentType is null")
    void shouldReturnOctetStreamWhenContentTypeNull() {
        when(fileUpload.contentType()).thenReturn(null);
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_TYPE, part.getMediaType());
    }

    @Test
    @DisplayName("getHeaders() contains Content-Type and Content-Disposition with name and filename")
    void shouldReturnHeadersWithContentTypeAndDisposition() {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        var headers = part.getHeaders();

        assertEquals("text/plain", headers.getFirst("Content-Type"));

        String disposition = headers.getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("name=\"file\""), "disposition should include name: " + disposition);
        assertTrue(
                disposition.contains("filename=\"upload.txt\""), "disposition should include filename: " + disposition);
    }

    @Test
    @DisplayName("getHeaders() escapes double-quotes in fileName")
    void shouldEscapeQuotesInFileName() {
        when(fileUpload.fileName()).thenReturn("file\"name.txt");
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        String disposition = part.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(
                disposition.contains("filename=\"file\\\"name.txt\""),
                "disposition should escape quote in filename: " + disposition);
    }

    @Test
    @DisplayName("getHeaders() strips newlines from name")
    void shouldStripNewlinesFromName() {
        when(fileUpload.name()).thenReturn("field\r\nname");
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        String disposition = part.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(
                disposition.contains("name=\"fieldname\""),
                "disposition should strip newlines from name: " + disposition);
    }

    // --- getContentAsync() ---

    @Test
    @DisplayName("getContentAsync() returns correct bytes without blocking")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shouldReturnContentAsync(VertxTestContext ctx) {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        part.getContentAsync().onComplete(ctx.succeeding(buffer -> {
            assertEquals(FILE_CONTENT, buffer.toString(StandardCharsets.UTF_8));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("getContentAsync() enforces single consumption")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shouldThrowOnSecondGetContentAsync(VertxTestContext ctx) {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        part.getContentAsync().onComplete(ctx.succeeding(buffer -> {
            assertThrows(IllegalStateException.class, part::getContentAsync);
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("getContentAsync() then getContent() throws IllegalStateException")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shouldThrowGetContentAfterGetContentAsync(VertxTestContext ctx) {
        VertxFileUploadEntityPart part = new VertxFileUploadEntityPart(vertx, fileUpload);
        part.getContentAsync().onComplete(ctx.succeeding(buffer -> {
            assertThrows(IllegalStateException.class, part::getContent);
            ctx.completeNow();
        }));
    }
}
