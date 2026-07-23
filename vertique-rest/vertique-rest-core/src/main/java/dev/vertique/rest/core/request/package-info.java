// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Request processing types for the REST layer.
 *
 * <p>Includes the {@link dev.vertique.rest.core.request.RequestBodyDecoder} SPI for pluggable
 * request body deserialization (contributed via Dagger {@code Set<RequestBodyDecoder>}
 * multibinding), the {@link dev.vertique.rest.core.request.RequestParams} annotation that
 * marks a class as a composite parameter object (enabling automatic injection without
 * {@code @BeanParam}), and {@link dev.vertique.rest.core.request.RequestPreconditions}
 * for conditional request handling (ETag / Last-Modified).
 *
 * <p>Media type support is provided by {@link dev.vertique.rest.core.request.MediaType}
 * (an RFC 9110 value object with parsing and compatibility checks) and
 * {@link dev.vertique.rest.core.request.AcceptNegotiator} (q-value–based content
 * negotiation that selects the best producer media type for a given {@code Accept} header,
 * returning {@code 406 Not Acceptable} when no match is found).
 */
package dev.vertique.rest.core.request;
