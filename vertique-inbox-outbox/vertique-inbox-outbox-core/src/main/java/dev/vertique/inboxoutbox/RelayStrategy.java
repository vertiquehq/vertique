// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * Strategy used by the outbox relay to detect and claim pending entries.
 *
 * <p>The chosen strategy affects latency, database load, and infrastructure requirements.
 * Both strategies provide at-least-once delivery with idempotency guaranteed by the relay's
 * claim-and-mark protocol.
 */
public enum RelayStrategy {

    /**
     * The relay runs a periodic timer and queries the outbox table on each tick to find
     * pending entries. Simple to configure but introduces polling latency proportional to
     * {@link OutboxRelayConfig#pollingIntervalMs()}.
     */
    POLLING,

    /**
     * The relay uses PostgreSQL {@code LISTEN/NOTIFY} to receive instant wake-up signals
     * whenever a new outbox entry is inserted. Reduces latency to near-zero and eliminates
     * unnecessary polling queries when the outbox is idle. Requires PostgreSQL as the backing
     * database.
     */
    LISTEN_NOTIFY
}
