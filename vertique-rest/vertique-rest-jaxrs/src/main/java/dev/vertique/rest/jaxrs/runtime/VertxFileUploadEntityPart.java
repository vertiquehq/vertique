// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.FileUpload;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EntityPart} adapter wrapping a Vert.x {@link FileUpload}.
 *
 * <p>Provides access to the uploaded file's metadata and content through the
 * standard JAX-RS {@code EntityPart} interface. The file content is streamed
 * asynchronously via {@link AsyncFileInputStream} using Vert.x's non-blocking
 * file I/O — no blocking {@code FileInputStream} is used.
 *
 * <p><strong>Threading:</strong> {@link #getContent()} and typed content accessors
 * must be called from a worker thread (e.g., via {@code vertx.executeBlocking()}),
 * not from the Vert.x event loop, because the underlying stream blocks while
 * waiting for async I/O to deliver data.
 *
 * <p>Per the {@code EntityPart} contract, {@link #getContent()} may only be
 * called once; subsequent calls throw {@link IllegalStateException}.
 *
 * <p><strong>Encoding:</strong> {@code getContent(String.class)} assumes UTF-8 encoding.
 */
public class VertxFileUploadEntityPart implements VertxEntityPart {

    private final Vertx vertx;
    private final FileUpload fileUpload;
    private final AtomicBoolean contentConsumed = new AtomicBoolean();

    /**
     * Creates a new entity part adapter for a file upload.
     *
     * @param vertx      the Vert.x instance for async file operations
     * @param fileUpload the Vert.x file upload descriptor
     */
    public VertxFileUploadEntityPart(Vertx vertx, FileUpload fileUpload) {
        this.vertx = vertx;
        this.fileUpload = fileUpload;
    }

    @Override
    public String getName() {
        return fileUpload.name();
    }

    @Override
    public Optional<String> getFileName() {
        return Optional.ofNullable(fileUpload.fileName());
    }

    private void markConsumed() {
        if (!contentConsumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Content has already been consumed");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Warning:</strong> The returned stream uses blocking I/O via
     * {@link AsyncFileInputStream}. Do not call this method on the Vert.x event loop thread;
     * use a worker thread or {@code vertx.executeBlocking()}. Prefer {@link #getContentAsync()}
     * for non-blocking access.
     *
     * @see #getContentAsync()
     */
    @Override
    public InputStream getContent() {
        markConsumed();
        return new AsyncFileInputStream(vertx, fileUpload.uploadedFileName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the uploaded file asynchronously using {@link io.vertx.core.file.FileSystem#readFile}.
     * Safe to call on the Vert.x event loop.
     */
    @Override
    public Future<Buffer> getContentAsync() {
        markConsumed();
        return vertx.fileSystem().readFile(fileUpload.uploadedFileName());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getContent(Class<T> type) {
        if (type == InputStream.class) {
            return (T) getContent();
        }
        if (type == String.class) {
            markConsumed();
            try (InputStream in = new AsyncFileInputStream(vertx, fileUpload.uploadedFileName())) {
                return (T) new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (type == byte[].class) {
            markConsumed();
            try (InputStream in = new AsyncFileInputStream(vertx, fileUpload.uploadedFileName())) {
                return (T) in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("Unsupported content type: " + type.getName());
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getContent(GenericType<T> type) {
        return (T) getContent(type.getRawType());
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        String ct = fileUpload.contentType();
        if (ct != null) {
            headers.putSingle("Content-Type", ct);
        }
        StringBuilder disposition = new StringBuilder("form-data; name=\"")
                .append(HeaderUtils.escapeQuoted(fileUpload.name()))
                .append("\"");
        if (fileUpload.fileName() != null && !fileUpload.fileName().isEmpty()) {
            disposition
                    .append("; filename=\"")
                    .append(HeaderUtils.escapeQuoted(fileUpload.fileName()))
                    .append("\"");
        }
        headers.putSingle("Content-Disposition", disposition.toString());
        return headers;
    }

    @Override
    public MediaType getMediaType() {
        String ct = fileUpload.contentType();
        if (ct != null && !ct.isEmpty()) {
            return MediaType.valueOf(ct);
        }
        return MediaType.APPLICATION_OCTET_STREAM_TYPE;
    }
}
