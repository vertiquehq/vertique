// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Offset commit strategy for Kafka consumers.
 */
public enum CommitStrategy {
    /** Kafka auto-commits offsets at regular intervals. At-most-once delivery semantics. */
    AUTO,
    /** Offsets committed only after successful dispatch. At-least-once delivery semantics. */
    MANUAL
}
