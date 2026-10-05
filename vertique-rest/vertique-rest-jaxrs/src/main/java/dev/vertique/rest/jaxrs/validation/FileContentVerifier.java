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
 * <p><b>Failure contract (fail-closed):</b> throwing synchronously, returning {@code null},
 * completing with a {@code null} result, or exceeding the framework wait deadline are infrastructure
 * failures that map to 500. A rejected result maps to 400. Errors never fail open.
 *
 * <p><b>Wait deadline and cancellation contract:</b> the {@code web-validation} gate bounds
 * <em>each</em> verifier invocation with {@code jaxrs.fileContentVerifierDeadlineMs} (a positive
 * millisecond budget, default {@code 5000}). The bound is per verifier call, not an overall chain
 * budget: after one verifier settles, the next call gets a fresh deadline. When the deadline elapses
 * first the gate fails closed (500) and stops waiting on that future. The framework does
 * <strong>not</strong> cancel or interrupt underlying scanner / HTTP / worker work — Vert.x
 * {@code Future.timeout} is a wait race only, and this SPI stays a single {@link #verify(FileUpload)}
 * method with no cancellation token. Implementors of remote or scanner-backed verifiers therefore
 * must:
 *
 * <ul>
 *   <li>configure verifier-owned transport and client deadlines at or below the framework wait
 *       deadline so abandoned work does not run unbounded;
 *   <li>release scanner sessions, HTTP clients, file handles, and offloaded workers when their future
 *       completes <em>or</em> when they observe that the request has ended (response end, connection
 *       close, or stream reset) — request-end upload cleanup deletes the temporary file regardless of
 *       a still-running verifier, and a hung scan that still reads the path must tolerate that and
 *       release resources;
 *   <li>not rely on the framework wait timeout alone as cancellation; it only reclaims the HTTP
 *       request chain.
 * </ul>
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
