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
 * be positive and at most {@value #MAX_OPERATION_RETENTION_DAYS} days and {@value #MAX_CONTENT_BYTES_LIMIT}
 * bytes (16 MiB) respectively; a value outside its range fails with {@link ValidationException} when the
 * config is built or deserialized. The retention key uses the {@code Days} unit of the cleanup retention keys.
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

    /** Largest accepted {@code operationRetentionDays}. */
    public static final int MAX_OPERATION_RETENTION_DAYS = 3650;

    /** Largest accepted {@code maxContentBytes}: 16 MiB. */
    public static final int MAX_CONTENT_BYTES_LIMIT = 16_777_216;

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
     * @param operationRetentionDays retention window in days; 1 to {@value #MAX_OPERATION_RETENTION_DAYS}
     * @param maxContentBytes        content size limit in bytes; 1 to {@value #MAX_CONTENT_BYTES_LIMIT}
     * @throws ValidationException if a value is outside its range
     */
    InboxOutboxAdminConfig(int operationRetentionDays, int maxContentBytes) {
        this.operationRetentionDays = (int) AdminChecks.atMost(
                AdminChecks.positive(operationRetentionDays, "operationRetentionDays"),
                MAX_OPERATION_RETENTION_DAYS,
                "operationRetentionDays");
        this.maxContentBytes = (int) AdminChecks.atMost(
                AdminChecks.positive(maxContentBytes, "maxContentBytes"), MAX_CONTENT_BYTES_LIMIT, "maxContentBytes");
    }
}
