// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.core.EntityPart;

/**
 * Extended {@link EntityPart} with a non-blocking content accessor for Vert.x.
 *
 * <p>The standard {@link EntityPart#getContent()} returns a blocking {@link java.io.InputStream}
 * that must not be called on the Vert.x event loop. This interface adds {@link #getContentAsync()}
 * which returns the content as a Vert.x {@link Buffer} via a non-blocking {@link Future}.
 *
 * <p>Like {@code getContent()}, {@code getContentAsync()} may only be called once per the
 * single-consumption contract; subsequent calls to either method throw {@link IllegalStateException}.
 *
 * <p>Both {@link VertxFileUploadEntityPart} and {@link FormFieldEntityPart} implement this
 * interface.
 */
public interface VertxEntityPart extends EntityPart {

    /**
     * Returns the part content as a Vert.x {@link Buffer} without blocking the event loop.
     *
     * <p>This is the preferred accessor when running on the event loop. For file uploads, the
     * content is read asynchronously via {@link io.vertx.core.file.FileSystem#readFile}. For
     * in-memory form fields, the future completes immediately.
     *
     * <p>May only be called once; subsequent calls throw {@link IllegalStateException}.
     *
     * @return a future that completes with the content buffer
     * @throws IllegalStateException if the content has already been consumed
     */
    Future<Buffer> getContentAsync();
}
