// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import jakarta.annotation.Nullable;
import java.util.Optional;

/**
 * Thrown when events are requested from a response head whose media type is not
 * {@code text/event-stream}. The message is constant; {@link #contentType()} carries the media type
 * only.
 */
public final class SseProtocolException extends HttpClientTechnicalException {

    private static final String MESSAGE = "The response is not a server-sent event stream";

    @Nullable
    private final String contentType;

    /**
     * Creates a protocol failure.
     *
     * @param contentType the response's {@code Content-Type} value, or null when absent; parameters
     *     after the first semicolon are dropped
     */
    public SseProtocolException(@Nullable String contentType) {
        super(MESSAGE);
        this.contentType = mediaType(contentType);
    }

    /**
     * Returns the media type of the response, without parameters.
     *
     * @return the media type, or empty when the response had none
     */
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    @Nullable
    private static String mediaType(@Nullable String value) {
        if (value == null) {
            return null;
        }
        int semicolon = value.indexOf(';');
        String type = (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
        return type.isEmpty() ? null : type;
    }
}
