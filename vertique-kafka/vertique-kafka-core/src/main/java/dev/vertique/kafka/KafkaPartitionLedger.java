// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * The records one consumer holds for one assignment of a partition, in the order it read them, and what it has
 * committed for them.
 *
 * <p>An assignment has an identity: this object. A partition that is revoked and assigned again, even to the same
 * consumer, gets a new ledger, and anything that still holds the old one, such as a running handler, a retry timer or
 * a commit, finds it {@linkplain #ended() ended} and changes nothing.
 *
 * <p>The ledger is not thread-safe. The consumer's owner context is the only caller.
 */
final class KafkaPartitionLedger {

    /** The offset of a ledger that has not settled a prefix yet, and of a commit that is not in flight. */
    static final long NONE = -1L;

    /** Where a registered record is. */
    enum State {

        /** Registered, or waiting for a permit or for the dispatch gate. Holds no permit. */
        QUEUED,

        /** A handler runs it. Holds a permit. */
        RUNNING,

        /** Failed, and waiting in memory for its retry timer. Holds no permit. */
        RETRY_WAIT,

        /** Failed for good, and waiting for its dead-letter publish to be acknowledged. Holds no permit. */
        DLQ_PENDING,

        /** Finished. It counts toward the watermark once every record before it has finished too. */
        SETTLED
    }

    /** One registered record. */
    static final class Entry {

        final KafkaPartitionLedger ledger;
        final long offset;
        final long sequence;

        State state = State.QUEUED;

        /** The retries that have started: 0 for the first delivery. */
        int attempts;

        /** The dead-letter publishes that have failed. */
        int publishFailures;

        /** The record, until it settles: the original evidence of a retry and of a dead-letter publish. */
        @Nullable
        KafkaConsumerRecord<String, byte[]> record;

        /** What the last run failed with, for the dead-letter headers. */
        @Nullable
        Throwable cause;

        /** The view of the last run, for the completion notification of a dead-letter publish. */
        @Nullable
        KafkaConsumerRecordView view;

        /** The pending retry or publish timer. */
        @Nullable
        KafkaSettlement.Timer timer;

        Entry(KafkaPartitionLedger ledger, KafkaConsumerRecord<String, byte[]> record, long sequence) {
            this.ledger = ledger;
            this.offset = record.offset();
            this.sequence = sequence;
            this.record = record;
        }
    }

    private final TopicPartition partition;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    private boolean ended;
    private long watermark = NONE;
    private long lastAcked = NONE;
    private long pendingCommit = NONE;
    private int retryWaiting;
    private boolean retentionHeld;

    KafkaPartitionLedger(TopicPartition partition) {
        this.partition = partition;
    }

    TopicPartition partition() {
        return partition;
    }

    boolean ended() {
        return ended;
    }

    /** Ends the assignment. Nothing that holds this ledger changes anything afterwards. */
    void end() {
        ended = true;
    }

    /** The registered records that have not left the ledger: unsettled ones and settled ones above a gap. */
    int size() {
        return entries.size();
    }

    /**
     * The offset after the longest settled prefix, or {@link #NONE} while the first record has not settled. It is the
     * offset to commit, and it never falls.
     */
    long watermark() {
        return watermark;
    }

    long lastAcked() {
        return lastAcked;
    }

    void acknowledged(long offset) {
        lastAcked = offset;
    }

    long pendingCommit() {
        return pendingCommit;
    }

    void pendingCommit(long offset) {
        pendingCommit = offset;
    }

    /** The records in {@link State#RETRY_WAIT}. */
    int retryWaiting() {
        return retryWaiting;
    }

    boolean retentionHeld() {
        return retentionHeld;
    }

    void retentionHeld(boolean held) {
        retentionHeld = held;
    }

    /**
     * Appends a record as {@link State#QUEUED}.
     *
     * @return the new entry
     */
    Entry register(KafkaConsumerRecord<String, byte[]> record, long sequence) {
        Entry entry = new Entry(this, record, sequence);
        entries.addLast(entry);
        return entry;
    }

    /** Moves an entry to a state, keeping the counts that depend on it. */
    void move(Entry entry, State to) {
        if (entry.state == State.RETRY_WAIT) {
            retryWaiting--;
        }
        if (to == State.RETRY_WAIT) {
            retryWaiting++;
        }
        entry.state = to;
    }

    /**
     * Settles an entry: it releases its payload at once. The ledger keeps the entry until the watermark passes it.
     */
    void settle(Entry entry) {
        move(entry, State.SETTLED);
        entry.record = null;
        entry.cause = null;
        entry.view = null;
        entry.timer = null;
    }

    /**
     * Drops the settled prefix and raises the watermark to the offset after its last record.
     *
     * @return the number of entries dropped
     */
    int advance() {
        int dropped = 0;
        Entry head = entries.peekFirst();
        while (head != null && head.state == State.SETTLED) {
            entries.pollFirst();
            watermark = head.offset + 1;
            dropped++;
            head = entries.peekFirst();
        }
        return dropped;
    }

    /**
     * Lets go of every record the ledger holds, for the end of the assignment. A handler that still runs, or a timer or
     * callback that is slow to notice the end, keeps this ledger reachable, and it must not keep the payloads with it.
     */
    void release() {
        for (Entry entry : entries) {
            entry.record = null;
            entry.cause = null;
            entry.view = null;
            entry.timer = null;
        }
        entries.clear();
    }

    /** Every entry, oldest first, for the end of the assignment. */
    Iterable<Entry> entries() {
        return entries;
    }
}
