// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import io.vertx.core.Vertx;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link InputStream} backed by Vert.x asynchronous file I/O.
 *
 * <p>File content is read asynchronously on the Vert.x event loop via {@link io.vertx.core.file.AsyncFile}
 * and streamed to the caller through a {@link PipedInputStream}/{@link PipedOutputStream} pair.
 * This avoids loading the entire file into memory while keeping the {@code InputStream} API
 * that {@link jakarta.ws.rs.core.EntityPart} requires.
 *
 * <p><strong>Threading:</strong> The caller must read from this stream on a thread other than the
 * Vert.x event loop (e.g., a worker thread via {@code vertx.executeBlocking()}), because
 * {@code read()} blocks until data is available. Reading on the event loop would deadlock.
 */
public class AsyncFileInputStream extends InputStream {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncFileInputStream.class);
    private static final int PIPE_BUFFER_SIZE = 8192;

    private final Vertx vertx;
    private final String path;

    private PipedInputStream pipedIn;
    private PipedOutputStream pipedOut;
    private volatile boolean initialized;
    private volatile Throwable error;
    private volatile AsyncFile asyncFile;
    private volatile boolean paused;

    /**
     * Creates a new async file input stream.
     *
     * @param vertx the Vert.x instance for async file operations
     * @param path  the absolute path to the file to read
     */
    public AsyncFileInputStream(Vertx vertx, String path) {
        this.vertx = vertx;
        this.path = path;
    }

    private void ensureInitialized() throws IOException {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            // Warn if called on event loop — read() blocks and would deadlock
            io.vertx.core.Context context = vertx.getOrCreateContext();
            if (context.isEventLoopContext()) {
                LOG.warn(
                        "AsyncFileInputStream.read() called on event loop thread — this will block. "
                                + "Use a worker thread or executeBlocking(). File: {}",
                        path);
            }

            pipedOut = new PipedOutputStream();
            pipedIn = new PipedInputStream(pipedOut, PIPE_BUFFER_SIZE);
            initialized = true;

            // All async I/O stays on the Vert.x event loop
            vertx.fileSystem()
                    .open(path, new OpenOptions().setRead(true))
                    .onSuccess(file -> {
                        asyncFile = file;
                        file.handler(buffer -> {
                            try {
                                pipedOut.write(buffer.getBytes());
                            } catch (IOException e) {
                                error = e;
                                file.close();
                                closeQuietly(pipedOut);
                                return;
                            }
                            try {
                                if (pipedIn.available() >= PIPE_BUFFER_SIZE / 2) {
                                    paused = true;
                                    file.pause();
                                }
                            } catch (IOException ignored) {
                            }
                        });

                        file.endHandler(v -> closeQuietly(pipedOut));

                        file.exceptionHandler(t -> {
                            error = t;
                            closeQuietly(pipedOut);
                        });
                    })
                    .onFailure(t -> {
                        error = t;
                        closeQuietly(pipedOut);
                    });
        }
    }

    @Override
    public int read() throws IOException {
        ensureInitialized();
        checkError();
        int b = pipedIn.read();
        checkError();
        resumeIfPaused();
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        ensureInitialized();
        checkError();
        int n = pipedIn.read(buf, off, len);
        checkError();
        resumeIfPaused();
        return n;
    }

    @Override
    public void close() throws IOException {
        if (pipedIn != null) {
            pipedIn.close();
        }
        closeQuietly(pipedOut);
        AsyncFile fileToClose = asyncFile;
        asyncFile = null;
        if (fileToClose != null) {
            fileToClose.close();
        }
    }

    /**
     * Resumes the underlying {@link AsyncFile} on the Vert.x event loop if it was paused
     * due to the pipe buffer filling up.
     */
    private void resumeIfPaused() {
        if (paused) {
            vertx.runOnContext(v -> {
                if (paused && asyncFile != null) {
                    paused = false;
                    asyncFile.resume();
                }
            });
        }
    }

    private void checkError() throws IOException {
        if (error != null) {
            throw error instanceof IOException ioe ? ioe : new IOException("Async file read failed", error);
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
