// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;

/**
 * A generic local-only message codec that passes object references without serialization.
 * This is safe for local event bus delivery where sender and receiver share the same JVM.
 *
 * <p>Attempting to use this codec for clustered event bus delivery will throw an exception,
 * since the objects cannot be serialized to wire format.
 *
 * @param <T> the message type
 */
public class LocalMessageCodec<T> implements MessageCodec<T, T> {

    private final String name;

    public LocalMessageCodec(String name) {
        this.name = name;
    }

    @Override
    public void encodeToWire(Buffer buffer, T obj) {
        throw new UnsupportedOperationException(
                name + " codec is local-only and cannot be used for clustered event bus delivery");
    }

    @Override
    public T decodeFromWire(int pos, Buffer buffer) {
        throw new UnsupportedOperationException(
                name + " codec is local-only and cannot be used for clustered event bus delivery");
    }

    @Override
    public T transform(T obj) {
        // Local delivery: pass the object reference directly (no copy)
        return obj;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public byte systemCodecID() {
        return -1; // custom codec
    }
}
