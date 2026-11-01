// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Answers whether a consumer may start handling a record now, so a consumer whose handler cannot take work yet stops
 * dispatching without spending a retry and without leaving its group.
 *
 * <p>A {@code MANUAL} binding asks its gate before it starts a record, and again after the gate answered closed. While
 * the gate is closed no record starts, every assigned partition stops fetching and the consumer keeps polling, so it
 * keeps its group membership. Records already received stay queued in memory, and records that are running finish. When
 * the gate opens the queued records start in the order they were read and the partitions fetch again. A record the gate
 * kept from starting has not been attempted: no retry is spent, no dead letter is published and nothing is committed
 * past it.
 *
 * <p>A handler that finds the gate closed after it started the record, because the gate closed in between, fails the
 * record with {@link KafkaDeferredFailure} on a binding that requires its strategies. The record then returns to the
 * queue with its attempts unchanged and the binding waits for the gate, as it does for a closed one.
 *
 * <p>The gate is read from the consumer's own context and often, so {@link #open()} must be cheap, must not block and
 * must read node-local state only.
 *
 * @see KafkaConsumerBinding.Builder#dispatchGate(KafkaDispatchGate)
 */
public interface KafkaDispatchGate {

    /**
     * Whether a record may start now.
     *
     * @return {@code true} when the consumer may start records, {@code false} to make it hold them
     */
    boolean open();
}
