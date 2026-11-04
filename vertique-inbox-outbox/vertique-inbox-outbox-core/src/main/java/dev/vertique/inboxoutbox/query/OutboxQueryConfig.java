// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.vertique.core.exception.ValidationException;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for outbox entry queries.
 *
 * <p>Deserialized from the {@code inboxOutbox.query} section of the application config. The value must be
 * positive and at most {@value #MAX_PAYLOAD_BYTES_LIMIT} bytes (16 MiB); a value outside that range fails with
 * {@link ValidationException} when the config is built or deserialized.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   query:
 *     maxPayloadBytes: 1048576
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class OutboxQueryConfig {

    /** Largest accepted {@code maxPayloadBytes}: 16 MiB. */
    public static final int MAX_PAYLOAD_BYTES_LIMIT = 16_777_216;

    /**
     * Largest combined UTF-8 length, in bytes, of the stored payload and headers JSON text that
     * {@link OutboxQueryService#getPayload(long)} will transfer. Default: 1 048 576 (1 MiB).
     */
    @Builder.Default
    private final int maxPayloadBytes = 1_048_576;

    /**
     * Creates a validated config; reached through the builder and Jackson deserialization.
     *
     * @param maxPayloadBytes payload size limit in bytes; 1 to {@value #MAX_PAYLOAD_BYTES_LIMIT}
     * @throws ValidationException if the value is outside its range
     */
    OutboxQueryConfig(int maxPayloadBytes) {
        this.maxPayloadBytes = (int) QueryChecks.range(maxPayloadBytes, MAX_PAYLOAD_BYTES_LIMIT, "maxPayloadBytes");
    }
}
