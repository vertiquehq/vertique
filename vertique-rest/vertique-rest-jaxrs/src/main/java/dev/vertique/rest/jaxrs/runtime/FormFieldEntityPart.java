// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link EntityPart} adapter wrapping a plain text form field value.
 *
 * <p>Provides access to a URL-encoded or multipart text field through the
 * standard JAX-RS {@code EntityPart} interface. The field value is stored
 * in memory as a string.
 *
 * <p>Per the {@code EntityPart} contract, {@link #getContent()} may only be
 * called once; subsequent calls throw {@link IllegalStateException}.
 */
public class FormFieldEntityPart implements VertxEntityPart {

    private final String name;
    private final String value;
    private final AtomicBoolean contentConsumed = new AtomicBoolean();

    /**
     * Creates a new form field entity part.
     *
     * @param name  the form field name
     * @param value the form field value
     */
    public FormFieldEntityPart(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Optional<String> getFileName() {
        return Optional.empty();
    }

    private void markConsumed() {
        if (!contentConsumed.compareAndSet(false, true)) {
            throw new IllegalStateException("Content has already been consumed");
        }
    }

    @Override
    public InputStream getContent() {
        markConsumed();
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the form field value as a {@link Buffer}. Since the value is already in memory,
     * the returned future completes immediately.
     */
    @Override
    public Future<Buffer> getContentAsync() {
        markConsumed();
        return Future.succeededFuture(Buffer.buffer(value));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getContent(Class<T> type) {
        if (type == String.class) {
            markConsumed();
            return (T) value;
        }
        if (type == InputStream.class) {
            return (T) getContent();
        }
        if (type == byte[].class) {
            markConsumed();
            return (T) value.getBytes(StandardCharsets.UTF_8);
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
        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Content-Type", MediaType.TEXT_PLAIN);
        headers.putSingle("Content-Disposition", "form-data; name=\"" + HeaderUtils.escapeQuoted(name) + "\"");
        return headers;
    }

    @Override
    public MediaType getMediaType() {
        return MediaType.TEXT_PLAIN_TYPE;
    }
}
