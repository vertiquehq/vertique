// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import java.util.Objects;

/**
 * Thrown when a byte bound of the client was exceeded. The message is constant; {@link #direction()}
 * and {@link #limitBytes()} describe the bound.
 */
public final class HttpBodyLimitException extends HttpClientTechnicalException {

    private static final String MESSAGE = "A configured byte bound was exceeded";

    /** The kind of payload a bound applies to. */
    public enum Direction {
        /** The request body. */
        REQUEST,
        /** The response body. */
        RESPONSE,
        /** One server-sent event. */
        EVENT
    }

    private final Direction direction;
    private final long limitBytes;

    /**
     * Creates a body limit failure.
     *
     * @param direction the payload the bound applies to
     * @param limitBytes the bound that was exceeded, in bytes
     * @throws NullPointerException if {@code direction} is null
     */
    public HttpBodyLimitException(Direction direction, long limitBytes) {
        super(MESSAGE);
        this.direction = Objects.requireNonNull(direction, "direction");
        this.limitBytes = limitBytes;
    }

    /**
     * Returns the payload the exceeded bound applies to.
     *
     * @return the direction
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Returns the bound that was exceeded.
     *
     * @return the limit in bytes
     */
    public long limitBytes() {
        return limitBytes;
    }
}
