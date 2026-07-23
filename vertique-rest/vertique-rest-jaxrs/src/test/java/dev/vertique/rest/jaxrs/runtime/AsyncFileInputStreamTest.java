// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link AsyncFileInputStream}.
 *
 * <p>Verifies that file content is correctly streamed through piped streams
 * backed by Vert.x async file I/O. Tests run on the JUnit thread (not the
 * event loop), so blocking reads work without deadlock.
 */
@ExtendWith(VertxExtension.class)
class AsyncFileInputStreamTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("readAllBytes() returns full file content")
    void shouldReadFullContent(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("test.txt");
        String expected = "hello async world";
        Files.writeString(file, expected);

        try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
            byte[] bytes = in.readAllBytes();
            assertEquals(expected, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("read() returns bytes one at a time and -1 at EOF")
    void shouldReadSingleBytes(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("tiny.txt");
        Files.writeString(file, "AB");

        try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
            assertEquals('A', in.read());
            assertEquals('B', in.read());
            assertEquals(-1, in.read());
        }
    }

    @Test
    @DisplayName("read(byte[], off, len) fills the buffer correctly")
    void shouldReadIntoBuffer(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("buf.txt");
        String content = "buffer test data";
        Files.writeString(file, content);

        try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
            byte[] buf = new byte[1024];
            int totalRead = 0;
            int n;
            while ((n = in.read(buf, totalRead, buf.length - totalRead)) != -1) {
                totalRead += n;
            }
            assertEquals(content, new String(buf, 0, totalRead, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("reads large file content correctly (exceeds pipe buffer size)")
    void shouldReadLargeFile(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("large.bin");
        // 32KB > 8KB pipe buffer — exercises flow control
        byte[] expected = new byte[32 * 1024];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 256);
        }
        Files.write(file, expected);

        try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
            byte[] actual = in.readAllBytes();
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    @DisplayName("reading from a non-existent file throws IOException or returns EOF")
    void shouldFailOnMissingFile(Vertx vertx) throws Exception {
        String missingPath = tempDir.resolve("no-such-file.txt").toString();
        try (InputStream in = new AsyncFileInputStream(vertx, missingPath)) {
            // The async open failure closes the pipe; read() either throws IOException
            // (if checkError() catches the error) or returns -1 (if the pipe closes
            // before the error is visible). Both are acceptable — no data is returned.
            byte[] data = in.readAllBytes();
            assertEquals(0, data.length, "No data should be returned for a missing file");
        } catch (IOException expected) {
            // Expected — the async error was propagated
        }
    }

    @Test
    @DisplayName("reading an empty file returns -1 immediately")
    void shouldHandleEmptyFile(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
            assertEquals(-1, in.read());
        }
    }

    @Test
    @DisplayName("close() can be called multiple times without error")
    void shouldAllowMultipleClose(Vertx vertx) throws Exception {
        Path file = tempDir.resolve("close.txt");
        Files.writeString(file, "data");

        AsyncFileInputStream in = new AsyncFileInputStream(vertx, file.toString());
        in.readAllBytes();
        in.close();
        assertDoesNotThrow(in::close);
    }
}
