// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

/**
 * Thrown when a durable context carrier or namespaces document is structurally malformed —
 * specifically, when a {@code context} section or a namespace body that is expected to be a JSON
 * object is present but is not one.
 *
 * <p>Raised by {@link dev.vertique.core.context.DurableMetadata#fromCarrier(io.vertx.core.json.JsonObject)}
 * and {@link dev.vertique.core.context.DurableMetadata#fromJson(io.vertx.core.json.JsonObject)} at
 * decode time, closing the late {@link ClassCastException} that would otherwise surface only when a
 * malformed namespace body is later read by a specific decoder (e.g. at
 * {@link dev.vertique.core.context.DurableMetadata#merge}). Decode-time validation lets callers
 * classify the failure as an input-validation problem instead of an unclassified runtime crash.
 *
 * <p>The exception message names the offending namespace key and its actual JSON type; it never
 * echoes the offending value's content, to avoid leaking potentially sensitive durable-context data
 * into logs or error responses.
 */
public class MalformedDurableMetadataException extends ValidationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message; names the offending key and its actual JSON type, without
     *                echoing the value's content
     */
    public MalformedDurableMetadataException(String message) {
        super(message);
    }
}
