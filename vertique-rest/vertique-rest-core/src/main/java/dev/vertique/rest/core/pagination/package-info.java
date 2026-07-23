// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Pagination types for REST APIs.
 *
 * <p>Provides two pagination strategies: offset-based pagination via
 * {@link dev.vertique.rest.core.pagination.OffsetPageRequest} (query parameters
 * {@code page} and {@code size}) and {@link dev.vertique.rest.core.pagination.OffsetPage}
 * (response wrapper with total count); and cursor-based pagination via
 * {@link dev.vertique.rest.core.pagination.CursorPageRequest} (query parameter {@code cursor})
 * and {@link dev.vertique.rest.core.pagination.CursorPage} (response wrapper with opaque
 * next/previous cursor tokens).
 *
 * <p>Cursor tokens are encoded and decoded through the {@link dev.vertique.rest.core.pagination.CursorCodec}
 * SPI, with {@link dev.vertique.rest.core.pagination.PlainCursorCodec} as the default
 * (no signing). Both request types are annotated with
 * {@link dev.vertique.rest.core.request.RequestParams} for automatic composite parameter
 * injection without requiring {@code @BeanParam} at the JAX-RS method site.
 * {@link dev.vertique.rest.core.pagination.InvalidCursorException} is thrown when a
 * cursor token cannot be decoded.
 */
package dev.vertique.rest.core.pagination;
