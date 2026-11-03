// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import dev.vertique.core.exception.ValidationException;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for outbox dead-letter administration.
 *
 * <p>Deserialized from the {@code inboxOutbox.admin} section of the application config. Both values must
 * be positive; a non-positive value fails with {@link ValidationException} when the config is built or
 * deserialized. The retention key uses the {@code Days} unit of the cleanup retention keys.
 *
 * <p>Example YAML:
 * <pre>{@code
 * inboxOutbox:
 *   admin:
 *     operationRetentionDays: 90
 *     maxContentBytes: 1048576
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class InboxOutboxAdminConfig {

    /**
     * Number of days to retain administration operation records before maintenance deletes them.
     * Default: 90 days.
     */
    @Builder.Default
    private final int operationRetentionDays = 90;

    /**
     * Largest combined UTF-8 length, in bytes, of the stored payload and headers JSON text that
     * {@link OutboxDeadLetterService#readContent(long)} will transfer. Default: 1 048 576 (1 MiB).
     */
    @Builder.Default
    private final int maxContentBytes = 1_048_576;

    /**
     * Creates a validated config; reached through the builder and Jackson deserialization.
     *
     * @param operationRetentionDays retention window in days; positive
     * @param maxContentBytes        content size limit in bytes; positive
     * @throws ValidationException if a value is not positive
     */
    InboxOutboxAdminConfig(int operationRetentionDays, int maxContentBytes) {
        this.operationRetentionDays = (int) AdminChecks.positive(operationRetentionDays, "operationRetentionDays");
        this.maxContentBytes = (int) AdminChecks.positive(maxContentBytes, "maxContentBytes");
    }
}
