// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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

    // --- Event-loop warning guard ---

    /**
     * Verifies the lazy-initialization guard that warns about a blocking read issued from a Vert.x
     * event-loop thread.
     *
     * <p>The guard must key off the <em>caller's</em> context. {@code vertx.getOrCreateContext()}
     * cannot express that: it creates an event-loop context for a caller that is not on a Vert.x
     * thread at all, so the warning would fire precisely when the usage is correct. These tests pin
     * all three caller shapes — ordinary thread, event loop, worker — so the distinction cannot
     * silently regress.
     */
    @Nested
    @DisplayName("Event-loop warning guard")
    class EventLoopGuard {

        private Logger streamLogger;
        private Level previousLevel;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void captureStreamLogs() {
            streamLogger = (Logger) LoggerFactory.getLogger(AsyncFileInputStream.class);
            previousLevel = streamLogger.getLevel();
            streamLogger.setLevel(Level.WARN);
            appender = new ListAppender<>();
            appender.start();
            streamLogger.addAppender(appender);
        }

        @AfterEach
        void releaseStreamLogs() {
            streamLogger.detachAppender(appender);
            appender.stop();
            streamLogger.setLevel(previousLevel);
        }

        @Test
        @DisplayName("Correct usage from an ordinary non-Vert.x thread must not warn")
        void shouldNotWarnOnOrdinaryThread(Vertx vertx) throws Exception {
            Path file = tempDir.resolve("ordinary-thread.txt");
            Files.writeString(file, "read from the JUnit thread");

            try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
                in.readAllBytes();
            }

            assertEquals(List.of(), warnings(), "A read off any Vert.x thread is correct usage and must be silent");
        }

        @Test
        @DisplayName("Initialization from an event-loop context warns")
        void shouldWarnOnEventLoopContext(Vertx vertx, VertxTestContext testContext) throws Exception {
            Path file = tempDir.resolve("event-loop.txt");
            Files.writeString(file, "el");

            vertx.getOrCreateContext().runOnContext(v -> {
                try {
                    triggerInitialization(vertx, file);
                } catch (IOException e) {
                    testContext.failNow(e);
                    return;
                }
                testContext.verify(() -> {
                    assertEquals(1, warnings().size(), "Exactly one warning expected from the event loop");
                    assertTrue(
                            warnings().get(0).contains("event loop thread"),
                            () -> "Unexpected warning text: " + warnings().get(0));
                });
                testContext.completeNow();
            });
        }

        @Test
        @DisplayName("Initialization from a worker context must not warn")
        void shouldNotWarnOnWorkerContext(Vertx vertx, VertxTestContext testContext) throws Exception {
            Path file = tempDir.resolve("worker.txt");
            Files.writeString(file, "wk");

            vertx.deployVerticle(
                            new AbstractVerticle() {
                                @Override
                                public void start(Promise<Void> startPromise) {
                                    try {
                                        triggerInitialization(vertx, file);
                                        startPromise.complete();
                                    } catch (IOException e) {
                                        startPromise.fail(e);
                                    }
                                }
                            },
                            new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER))
                    .onComplete(testContext.succeeding(id -> testContext.verify(() -> {
                        assertEquals(List.of(), warnings(), "Blocking is what worker threads are for — no warning");
                        testContext.completeNow();
                    })));
        }

        /**
         * Drives {@code AsyncFileInputStream}'s lazy initialization — and therefore its guard —
         * without blocking the calling thread.
         *
         * <p>A zero-length {@code read} is specified by {@link InputStream#read(byte[], int, int)}
         * to return {@code 0} without reading any bytes, so it reaches the initialization path but
         * never waits on the pipe. A real read cannot be used here: on an event loop it would
         * deadlock, which is the very hazard the guard exists to announce.
         *
         * @param vertx the Vert.x instance backing the stream
         * @param file  the file to open
         * @throws IOException if initialization or close fails
         */
        private void triggerInitialization(Vertx vertx, Path file) throws IOException {
            try (InputStream in = new AsyncFileInputStream(vertx, file.toString())) {
                in.read(new byte[1], 0, 0);
            }
        }

        /**
         * Returns the formatted messages of every WARN event captured from
         * {@link AsyncFileInputStream}.
         *
         * @return the captured warning messages, in emission order
         */
        private List<String> warnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }
}
