// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import io.vertx.core.buffer.Buffer;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Singleton {@link PayloadSource} implementation for {@link PayloadKind#ABSENT}.
 *
 * <p>All accessors return empty / zero-length results. The singleton is exposed via
 * {@link PayloadSources#absent()}.
 */
final class AbsentPayloadSource implements PayloadSource {

    /** Shared singleton instance. */
    static final AbsentPayloadSource INSTANCE = new AbsentPayloadSource();

    private static final byte[] EMPTY = new byte[0];

    private AbsentPayloadSource() {}

    @Override
    public PayloadKind kind() {
        return PayloadKind.ABSENT;
    }

    @Override
    public Optional<String> contentType() {
        return Optional.empty();
    }

    @Override
    public OptionalLong declaredLength() {
        return OptionalLong.empty();
    }

    @Override
    public Optional<Buffer> bufferedView() {
        return Optional.empty();
    }

    @Override
    public Optional<InputStream> bufferedStream() {
        return Optional.empty();
    }

    @Override
    public byte[] copyPrefix(int maxBytes) {
        return EMPTY;
    }
}
