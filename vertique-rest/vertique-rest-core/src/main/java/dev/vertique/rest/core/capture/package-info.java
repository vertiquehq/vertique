// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Request-evidence capture seam for the REST framework.
 *
 * <p>This package provides a neutral SPI ({@link dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer})
 * that is invoked by the JAX-RS routing layer once per request after the body is available, allowing
 * downstream audit adapters to capture the request body and resolved policy without any dependency
 * on rest-jaxrs internals. Implementations own their evidence storage; the in-repo audit-rest
 * implementation keeps it off {@link io.vertx.ext.web.RoutingContext#data()} in an internal,
 * identity-keyed side table (GH-118).
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.rest.core.capture.HttpOperationMeta} — neutral operation descriptor
 *       passed to each capturer</li>
 *   <li>{@link dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer} — SPI interface;
 *       registered via Dagger multibinding</li>
 * </ul>
 *
 * <p>With no registered capturers the framework performs a pure no-op — the multibinding
 * produces an empty set and the loop in {@code ResourceMethodInvoker} is not entered.
 */
package dev.vertique.rest.core.capture;
