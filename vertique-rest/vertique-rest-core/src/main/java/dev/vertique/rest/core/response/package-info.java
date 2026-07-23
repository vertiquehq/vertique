// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Response pipeline SPIs and types for the REST layer.
 *
 * <p>The pipeline dispatches the JAX-RS method result to a type-matched
 * {@link dev.vertique.rest.core.response.ResponseProducer} (registered via
 * {@link dev.vertique.rest.core.response.ResponseProducerBinding} in the Dagger
 * {@code Set<ResponseProducerBinding<?>>} multibinding), then delegates serialization to
 * {@link dev.vertique.rest.core.response.ResponseSerializer}. The serializer selects a
 * {@link dev.vertique.rest.core.response.ResponseBodyEncoder} from the
 * {@code Set<ResponseBodyEncoder>} multibinding to convert the entity to a
 * {@link dev.vertique.rest.core.response.SerializedBody} — either a
 * {@link dev.vertique.rest.core.response.BufferedBody} (fully materialized bytes) or a
 * {@link dev.vertique.rest.core.response.StreamingBody} (lazy {@link io.vertx.core.streams.ReadStream}).
 */
package dev.vertique.rest.core.response;
