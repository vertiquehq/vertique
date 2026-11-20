// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.codec.spi.BodyStream;
import java.util.Objects;

/**
 * The body codec of a stream request: instead of buffering, it hands the response body to the stream the
 * accepted head created. The stream is bound when the head is accepted, which is before Vert.x asks the
 * codec for its body stream.
 */
final class StreamCodec implements BodyCodec<Void> {

    private volatile BodyStream<Void> sink;

    /** Binds the stream the body goes to. */
    void bind(BodyStream<Void> bound) {
        this.sink = bound;
    }

    @Override
    public BodyStream<Void> stream() {
        return Objects.requireNonNull(sink, "The stream head was not accepted");
    }
}
