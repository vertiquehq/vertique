// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ValidationException;

/**
 * Shared profile-mapper materialization helpers for the request-body binding/decoding layers
 * (FR-JSON-022/023/024B).
 *
 * <p>Centralizes the one rule every profile-aware materialization site obeys: bind the request body
 * via the resolved non-{@code vertx} profile {@link ObjectMapper} so its strict materialization
 * features apply, and translate any rejection to a single, frozen, value-free HTTP {@code 400}
 * surfaced through the standard error pipeline (a {@link ValidationException} carrying the static
 * message {@code "Request body rejected by JSON profile"}). Routing every site through here keeps
 * that message contract identical across the decoder, the reflective two-phase path, and the
 * binder, and guarantees no request-body scalar value leaks into the HTTP response.
 *
 * <p>The original rejection throwable (which may embed request-body scalar values) is retained as
 * the {@code cause} of the {@link ValidationException} for server-side diagnosis. It is never
 * serialized to the client: the REST error mapper surfaces only the static, value-free
 * {@code getMessage()}, so the sanitized client message and the full diagnostic cause coexist.
 *
 * <p>This helper covers only the profile branch — the {@code vertx}/null-mapper path stays at each
 * call site, byte-for-byte unchanged.
 *
 * <p>This is a stateless helper; it is not instantiated. It is {@code public} only so the
 * {@code request} sub-package binder ({@code DefaultBoundRequest}) can share the {@link #rejection}
 * factory; the {@code convertValue} overloads stay package-private to the decoder/extractor sites.
 */
public final class ProfileBodyMaterialization {

    private ProfileBodyMaterialization() {}

    /**
     * Materializes {@code value} to {@code targetType} via {@code profileMapper}, translating a
     * profile-mapper rejection to a 400 {@link ValidationException}.
     *
     * @param profileMapper the resolved non-{@code vertx} profile mapper; never {@code null}
     * @param value         the JSON value to materialize (an intermediate map, list, or raw map)
     * @param targetType    the DTO class to materialize
     * @return the materialized DTO
     * @throws ValidationException when {@code profileMapper} rejects the body (HTTP 400)
     */
    static Object convertValue(ObjectMapper profileMapper, Object value, Class<?> targetType) {
        try {
            return profileMapper.convertValue(value, targetType);
        } catch (RuntimeException rejected) {
            throw rejection(rejected);
        }
    }

    /**
     * Materializes {@code value} to {@code javaType} via {@code profileMapper}, translating a
     * profile-mapper rejection to a 400 {@link ValidationException}. Used for collection/array
     * targets whose element binding is driven by a precomputed {@link JavaType}.
     *
     * @param profileMapper the resolved non-{@code vertx} profile mapper; never {@code null}
     * @param value         the JSON element list to materialize
     * @param javaType      the target collection/array {@link JavaType}
     * @return the materialized collection or array
     * @throws ValidationException when {@code profileMapper} rejects the body (HTTP 400)
     */
    static Object convertValue(ObjectMapper profileMapper, Object value, JavaType javaType) {
        try {
            return profileMapper.convertValue(value, javaType);
        } catch (RuntimeException rejected) {
            throw rejection(rejected);
        }
    }

    /**
     * Builds the frozen 400 {@link ValidationException} for a profile-mapper rejection, so every
     * materialization and first-parse site surfaces an identical, static, value-free client message.
     *
     * <p>The underlying {@code rejected} throwable — which may embed request-body scalar values via
     * Jackson's {@code MismatchedInputException} — is retained as the exception {@code cause} for
     * server-side diagnosis; it is NOT interpolated into the client-facing message and is never
     * serialized to the client (the REST mapper surfaces only {@code getMessage()}).
     *
     * @param rejected the underlying profile-mapper rejection; retained as the exception cause
     * @return the {@link ValidationException} to throw, carrying a static value-free message and the
     *     original rejection as its cause
     */
    public static ValidationException rejection(Throwable rejected) {
        return new ValidationException("Request body rejected by JSON profile", rejected);
    }
}
