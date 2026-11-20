// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Configuration for outbox entry queries.
 *
 * <p>Deserialized from the {@code inboxOutbox.query} section of the application config. The value must be
 * positive and at most {@value #MAX_PAYLOAD_BYTES_LIMIT} bytes (16 MiB); a value outside that range fails with
 * {@link InboxOutboxConfigurationException} naming {@code inboxOutbox.query.maxPayloadBytes} when the config is
 * built or deserialized.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   query:
 *     maxPayloadBytes: 1048576
 * }</pre>
 *
 * @param maxPayloadBytes largest combined UTF-8 length, in bytes, of the stored payload and headers JSON text that
 *                        {@link OutboxQueryService#getPayload(long)} will transfer; 1 to
 *                        {@value #MAX_PAYLOAD_BYTES_LIMIT}. Default: 1 048 576 (1 MiB).
 */
public record OutboxQueryConfig(int maxPayloadBytes) {

    /** Largest accepted {@code maxPayloadBytes}: 16 MiB. */
    public static final int MAX_PAYLOAD_BYTES_LIMIT = 16_777_216;

    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 1_048_576;

    /**
     * Validates the config.
     *
     * @throws InboxOutboxConfigurationException if {@code maxPayloadBytes} is outside its range
     */
    public OutboxQueryConfig {
        if (maxPayloadBytes < 1 || maxPayloadBytes > MAX_PAYLOAD_BYTES_LIMIT) {
            throw new InboxOutboxConfigurationException(
                    "inboxOutbox.query.maxPayloadBytes must be between 1 and " + MAX_PAYLOAD_BYTES_LIMIT);
        }
    }

    /**
     * Creates the config from its JSON form, filling the default for an omitted property.
     *
     * @param maxPayloadBytes the configured limit, or {@code null} for the default
     * @return the validated config
     */
    @JsonCreator
    static OutboxQueryConfig fromJson(@JsonProperty("maxPayloadBytes") @Nullable Integer maxPayloadBytes) {
        return new OutboxQueryConfig(maxPayloadBytes != null ? maxPayloadBytes : DEFAULT_MAX_PAYLOAD_BYTES);
    }

    /**
     * Returns the default configuration.
     *
     * @return a config with a 1 MiB payload limit
     */
    public static OutboxQueryConfig defaults() {
        return new OutboxQueryConfig(DEFAULT_MAX_PAYLOAD_BYTES);
    }
}
