// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;

/**
 * An immutable checkpoint recording a named key-value pair for a job execution.
 *
 * <p>Checkpoints enable idempotent re-execution: a handler that writes a checkpoint before
 * a side-effecting operation can skip that operation on retry if the checkpoint already exists.
 *
 * @param key       the checkpoint identifier within the execution
 * @param value     the checkpoint value (may be any object; serialisation is repository-specific)
 * @param updatedAt the instant at which this checkpoint was last written
 */
public record Checkpoint(String key, Object value, Instant updatedAt) {}
