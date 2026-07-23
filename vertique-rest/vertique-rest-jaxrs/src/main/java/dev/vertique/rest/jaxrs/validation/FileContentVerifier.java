// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import io.vertx.ext.web.FileUpload;

/**
 * Optional deep verification of an uploaded file part's actual content beyond its declared
 * content type. All bound verifiers run for every applicable file part in {@link OrderedExtension}
 * order, and every verifier must accept. A verifier that does not apply returns an already-completed
 * {@link FileVerificationResult#accepted()} result.
 *
 * <p><b>Lifecycle contract:</b> the framework makes no instance-identity guarantee. An
 * implementation may be instantiated more than once, and every instance must be thread-safe and
 * stateless because it is invoked from multiple mounts, event loops, and concurrent requests. The
 * {@link FileUpload} and its temporary file are valid only until the request's response ends;
 * implementations must not retain either.
 *
 * <p><b>Extension trust boundary:</b> verifiers are trusted application extensions. The framework
 * controls invocation, ordering, failure mapping, and filesystem lifetime, but does not sanitize
 * verifier-supplied rejection text or arguments. Authors must not include secrets, filenames,
 * temporary paths, raw headers, or submitted content.
 *
 * <p><b>Threading contract:</b> {@link #verify(FileUpload)} is invoked on the event loop.
 * Implementations must not block it; use asynchronous file I/O or offload internally. The framework
 * deliberately does not wrap calls with {@code executeBlocking}.
 *
 * <p><b>Failure contract (fail-closed):</b> throwing synchronously, returning {@code null}, or
 * completing with a {@code null} result are infrastructure failures that map to 500. A rejected
 * result maps to 400. Errors never fail open.
 */
public interface FileContentVerifier extends OrderedExtension {

    /**
     * Verifies one uploaded file part without blocking the event loop.
     *
     * @param part the uploaded file part, valid only for the request lifetime
     * @return a non-null future completing with a non-null verification result
     */
    Future<FileVerificationResult> verify(FileUpload part);
}
