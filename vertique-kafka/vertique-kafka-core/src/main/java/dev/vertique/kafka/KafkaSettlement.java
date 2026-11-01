// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import dev.vertique.kafka.KafkaPartitionLedger.Entry;
import dev.vertique.kafka.KafkaPartitionLedger.State;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import io.vertx.core.Future;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import lombok.extern.slf4j.Slf4j;

/**
 * Settles the records of a {@link CommitStrategy#MANUAL} consumer: it registers every record the client returns,
 * runs records concurrently up to the permit limit, retries and dead-letters them, and commits only what has finished.
 *
 * <p>Records of a partition run concurrently and in any order. The consumer commits a partition's
 * {@linkplain KafkaPartitionLedger#watermark() watermark}: the offset after the longest prefix of its records that
 * have finished. A record that is still running, waiting for a retry or waiting for its dead-letter publish keeps
 * every record after it uncommitted, and a restart reads them again. Nothing seeks, so a record is never lost to a
 * seek that drops the records buffered behind it.
 *
 * <h2>Assignments</h2>
 * Each assignment of a partition has its own {@link KafkaPartitionLedger}. A retry timer, a dead-letter publish, a
 * commit, a pause and a handler's result all hold the ledger they started under, and a callback of an ended ledger
 * changes nothing, apart from freeing the permit of a handler. A partition that returns to the same consumer is a new
 * assignment.
 *
 * <h2>Permits and retention</h2>
 * A permit is held by a running handler only. A record that waits for a retry, for its dead-letter publish or for the
 * gate holds none, so records that cannot run never keep the records behind them from running. The records a
 * consumer keeps in memory are bounded by count, per partition and for the consumer, at twice and four times the
 * larger of {@code max.poll.records} and {@code maxInFlight}; when a bound is reached the partition stops fetching, and
 * it fetches again once the count has fallen to half the bound. The bytes follow from the records the client fetches.
 *
 * <h2>Threading</h2>
 * The class is not thread-safe. Every method runs on the consumer's owner context, and every callback of the
 * {@link Port} comes back through {@link Port#execute}.
 */
@Slf4j
final class KafkaSettlement {

    /** How long a consumer waits before it asks a closed gate, or a refusing handler, again. */
    private static final long RECHECK_MS = 250L;

    /** How a run of one record ended. */
    record Result(
            @Nullable KafkaTerminalOutcome outcome,
            @Nullable Throwable failure,
            @Nullable KafkaConsumerRecordView view) {

        /** The record finished: it was dispatched, skipped or recovered. */
        static Result settled(KafkaTerminalOutcome outcome, @Nullable KafkaConsumerRecordView view) {
            return new Result(outcome, null, view);
        }

        /** The record failed; the binding's strategy decides what happens to it. */
        static Result failed(Throwable failure, @Nullable KafkaConsumerRecordView view) {
            return new Result(null, failure, view);
        }
    }

    /** What the settlement needs from the consumer, its context and the clock. */
    interface Port {

        /** Runs one record through the whole pipeline. The future carries how the run ended; it never fails the record. */
        Future<Result> run(KafkaConsumerRecord<String, byte[]> record, int attempt);

        /** Publishes the record's original key, value and headers to the dead-letter topic. */
        Future<Void> publishDlq(KafkaConsumerRecord<String, byte[]> record, Throwable cause);

        /** Commits {@code offset} as the next offset to read of the partition. */
        Future<Void> commit(TopicPartition partition, long offset);

        /** Pauses fetching of one partition; the consumer keeps polling. */
        Future<Void> pause(TopicPartition partition);

        /** Resumes fetching of one partition. */
        Future<Void> resume(TopicPartition partition);

        /** Runs the task once after the delay. */
        void schedule(long delayMs, Runnable task);

        /** Runs the task on the consumer's owner context, in order, and not before this call returns. */
        void execute(Runnable task);

        /** Tells the interceptors that a run of a record reached its disposition. */
        void completed(KafkaConsumerRecordView view, KafkaTerminalOutcome outcome);
    }

    /**
     * The settings of one consumer.
     *
     * @param name the consumer's name, for logs
     * @param maxInFlight the permits: the most handlers that run at once
     * @param errorStrategy the binding's error strategy
     * @param retryConfig the retry settings; {@code null} unless the strategy is {@link ErrorStrategy#RETRY}
     * @param forced whether the binding forces its strategies: a handler's {@link KafkaRedeliverFailure} then returns
     *     the record to the queue, and a retry waiting does not stop its partition's fetching
     * @param retentionLimit the records one partition may keep before it stops fetching; the consumer stops at twice
     *     this, and fetching resumes at half of either
     * @param gate the dispatch gate; {@code null} for none
     */
    record Settings(
            String name,
            int maxInFlight,
            ErrorStrategy errorStrategy,
            @Nullable RetryConfig retryConfig,
            boolean forced,
            int retentionLimit,
            @Nullable KafkaDispatchGate gate) {}

    private final Settings settings;
    private final Port port;
    private final KafkaHoldController holds;
    private final KafkaCommitController commits;
    private final Map<TopicPartition, KafkaPartitionLedger> ledgers = new HashMap<>();
    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Long.compare(a.sequence, b.sequence));

    private final long partitionRecords;
    private final long consumerRecords;

    private boolean stopping;
    private long nextSequence;
    private int permits;
    private int publishes;
    private long retainedRecords;
    private boolean consumerRetentionHeld;
    private boolean intakeOpen = true;
    private boolean refused;
    private boolean recheck;

    KafkaSettlement(Settings settings, Port port) {
        this.settings = settings;
        this.port = port;
        this.holds = new KafkaHoldController(settings.name(), port);
        this.commits = new KafkaCommitController(settings.name(), port);
        this.partitionRecords = settings.retentionLimit();
        this.consumerRecords = 2 * partitionRecords;
    }

    // --- Assignment ---

    /** Partitions were assigned: each gets a new ledger with an empty record list and its own identity. */
    void assigned(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            KafkaPartitionLedger old = ledgers.remove(partition);
            if (old != null) {
                finish(old);
            }
            ledgers.put(partition, new KafkaPartitionLedger(partition));
            holds.assigned(partition);
        }
    }

    /**
     * Partitions were revoked. Each commits its settled prefix as a best effort and ends: the records that did not
     * finish are not committed, and the handlers that still run finish into a ledger that no longer exists.
     */
    void revoked(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            KafkaPartitionLedger ledger = ledgers.remove(partition);
            if (ledger != null) {
                finish(ledger);
            }
        }
        evaluateRetention();
        pump();
    }

    private void finish(KafkaPartitionLedger ledger) {
        commits.revoke(ledger);
        retainedRecords -= ledger.size();
        // The queue may not be polled again for a long time, while the gate is closed or every permit is held, so the
        // ended assignment's entries leave it now, with their payloads.
        queue.removeIf(entry -> entry.ledger == ledger);
        ledger.release();
        holds.revoked(ledger.partition());
    }

    // --- Register and Start ---

    /**
     * Registers a record the client returned. Every returned record of an assigned partition is registered before
     * anything else happens to it, so a watermark can never pass an offset that was returned and not registered. A
     * record of a partition that is not assigned is dropped.
     */
    void register(KafkaConsumerRecord<String, byte[]> record) {
        KafkaPartitionLedger ledger = ledgers.get(new TopicPartition(record.topic(), record.partition()));
        if (ledger == null) {
            log.debug(
                    "[{}] Dropped a record of a partition that is not assigned: topic={} partition={} offset={}",
                    settings.name(),
                    record.topic(),
                    record.partition(),
                    record.offset());
            return;
        }
        Entry entry = ledger.register(record, nextSequence++);
        retainedRecords++;
        queue.add(entry);
        evaluateRetention();
        pump();
    }

    /** Starts queued records, oldest first, while a permit is free and the gate is open. */
    private void pump() {
        refreshGate();
        while (!stopping && permits < settings.maxInFlight() && intakeOpen && !refused) {
            Entry entry = queue.poll();
            if (entry == null) {
                break;
            }
            start(entry);
        }
    }

    private void start(Entry entry) {
        entry.ledger.move(entry, State.RUNNING);
        permits++;
        port.run(entry.record, entry.attempts)
                .onComplete(result -> port.execute(
                        () -> ran(entry, result.succeeded() ? result.result() : Result.failed(result.cause(), null))));
    }

    // --- Settle ---

    private void ran(Entry entry, Result result) {
        permits--;
        KafkaPartitionLedger ledger = entry.ledger;
        if (!ledger.ended()) {
            if (result.failure() == null) {
                settle(entry, result.view(), result.outcome());
            } else {
                failed(entry, result.failure(), result.view());
            }
        }
        pump();
    }

    private void failed(Entry entry, Throwable cause, @Nullable KafkaConsumerRecordView view) {
        KafkaPartitionLedger ledger = entry.ledger;
        if (settings.forced() && cause instanceof KafkaRedeliverFailure) {
            // The record is fine and the system is busy: it goes back to the queue, spends no retry and waits for the
            // gate.
            ledger.move(entry, State.QUEUED);
            queue.add(entry);
            refused = true;
            notifyCompleted(view, KafkaTerminalOutcome.RETRY_SCHEDULED);
            return;
        }
        ErrorStrategy strategy = settings.errorStrategy();
        RetryConfig retry = settings.retryConfig();
        if (strategy == ErrorStrategy.RETRY && retry == null) {
            retry = RetryConfig.DEFAULT;
        }
        boolean terminal = cause instanceof KafkaTerminalFailure;
        if (!terminal && strategy == ErrorStrategy.RETRY && entry.attempts < retry.maxRetries()) {
            retryLater(entry, cause, view, retry);
            return;
        }
        ErrorStrategy last = strategy == ErrorStrategy.RETRY ? retry.exhaustedStrategy() : strategy;
        if (strategy == ErrorStrategy.RETRY && !terminal) {
            log.warn(
                    "[{}] Retry exhausted ({}/{}) for topic={} partition={} offset={}, applying exhausted strategy",
                    settings.name(),
                    entry.attempts,
                    retry.maxRetries(),
                    ledger.partition().getTopic(),
                    ledger.partition().getPartition(),
                    entry.offset);
        }
        if (last == ErrorStrategy.DEAD_LETTER) {
            ledger.move(entry, State.DLQ_PENDING);
            entry.cause = cause;
            entry.view = view;
            publish(entry);
        } else {
            if (last != ErrorStrategy.SKIP) {
                log.error("[{}] Invalid exhausted strategy: {}", settings.name(), last);
            }
            KafkaErrorHandler.logSkipped(
                    settings.name(),
                    ledger.partition().getTopic(),
                    ledger.partition().getPartition(),
                    entry.offset,
                    cause);
            settle(entry, view, KafkaTerminalOutcome.SKIP);
        }
    }

    private void settle(Entry entry, @Nullable KafkaConsumerRecordView view, KafkaTerminalOutcome outcome) {
        KafkaPartitionLedger ledger = entry.ledger;
        ledger.settle(entry);
        retainedRecords -= ledger.advance();
        commits.commit(ledger);
        updateBackoff(ledger);
        evaluateRetention();
        notifyCompleted(view, outcome);
    }

    private void notifyCompleted(@Nullable KafkaConsumerRecordView view, KafkaTerminalOutcome outcome) {
        if (view != null) {
            port.completed(view, outcome);
        }
    }

    // --- Retry ---

    private void retryLater(Entry entry, Throwable cause, @Nullable KafkaConsumerRecordView view, RetryConfig retry) {
        KafkaPartitionLedger ledger = entry.ledger;
        long delayMs = retry.delayMs(entry.attempts);
        log.warn(
                "[{}] Will retry ({}/{}) topic={} partition={} offset={} in {}ms: {}",
                settings.name(),
                entry.attempts + 1,
                retry.maxRetries(),
                ledger.partition().getTopic(),
                ledger.partition().getPartition(),
                entry.offset,
                delayMs,
                cause.getMessage());
        ledger.move(entry, State.RETRY_WAIT);
        entry.cause = cause;
        port.schedule(delayMs, () -> fireRetry(entry));
        updateBackoff(ledger);
        notifyCompleted(view, KafkaTerminalOutcome.RETRY_SCHEDULED);
    }

    private void fireRetry(Entry entry) {
        KafkaPartitionLedger ledger = entry.ledger;
        if (ledger.ended() || entry.state != State.RETRY_WAIT) {
            return;
        }
        entry.attempts++;
        ledger.move(entry, State.QUEUED);
        queue.add(entry);
        updateBackoff(ledger);
        pump();
    }

    /** An ordinary binding stops fetching a partition while one of its records waits out a retry backoff. */
    private void updateBackoff(KafkaPartitionLedger ledger) {
        if (settings.forced() || ledger.ended()) {
            return;
        }
        if (ledger.retryWaiting() > 0) {
            holds.hold(ledger.partition(), KafkaHoldController.Reason.BACKOFF);
        } else {
            holds.release(ledger.partition(), KafkaHoldController.Reason.BACKOFF);
        }
    }

    // --- Dead letter ---

    private void publish(Entry entry) {
        KafkaPartitionLedger ledger = entry.ledger;
        if (ledger.ended() || entry.state != State.DLQ_PENDING) {
            return;
        }
        publishes++;
        Future<Void> publish;
        try {
            publish = port.publishDlq(entry.record, entry.cause);
        } catch (Throwable thrown) {
            publish = Future.failedFuture(thrown);
        }
        publish.onComplete(result -> port.execute(() -> published(entry, result.succeeded(), result.cause())));
    }

    private void published(Entry entry, boolean succeeded, Throwable cause) {
        publishes--;
        KafkaPartitionLedger ledger = entry.ledger;
        if (!ledger.ended()) {
            KafkaConsumerRecordView view = entry.view;
            if (succeeded) {
                settle(entry, view, KafkaTerminalOutcome.DLQ_PUBLISHED);
            } else {
                entry.publishFailures++;
                RetryConfig retry = settings.retryConfig() != null ? settings.retryConfig() : RetryConfig.DEFAULT;
                long delayMs = retry.delayMs(entry.publishFailures - 1);
                log.error(
                        "[{}] Failed to publish topic={} partition={} offset={} to the DLQ; trying again in {}ms",
                        settings.name(),
                        ledger.partition().getTopic(),
                        ledger.partition().getPartition(),
                        entry.offset,
                        delayMs,
                        cause);
                port.schedule(delayMs, () -> publish(entry));
                notifyCompleted(view, KafkaTerminalOutcome.DLQ_FAILED);
            }
        }
    }

    // --- Gate ---

    private void refreshGate() {
        boolean open = stopping || !refused && gateOpen();
        if (open == intakeOpen) {
            if (!open) {
                armRecheck();
            }
            return;
        }
        intakeOpen = open;
        if (open) {
            holds.releaseAll(KafkaHoldController.Reason.INTAKE);
        } else {
            holds.holdAll(KafkaHoldController.Reason.INTAKE);
            armRecheck();
        }
    }

    private boolean gateOpen() {
        KafkaDispatchGate gate = settings.gate();
        if (gate == null) {
            return true;
        }
        try {
            return gate.open();
        } catch (RuntimeException e) {
            log.warn("[{}] The dispatch gate failed; treating it as open", settings.name(), e);
            return true;
        }
    }

    private void armRecheck() {
        if (recheck || stopping) {
            return;
        }
        recheck = true;
        port.schedule(RECHECK_MS, () -> {
            recheck = false;
            refused = false;
            pump();
        });
    }

    // --- Retention ---

    /**
     * Stops fetching a partition while it, or the consumer, holds as many records as its bound, and fetches again once
     * the count has fallen to half the bound. Every partition is evaluated, because the consumer's count can hold or
     * release them all.
     */
    private void evaluateRetention() {
        consumerRetentionHeld = retentionHeld(consumerRetentionHeld, retainedRecords, consumerRecords);
        for (KafkaPartitionLedger ledger : ledgers.values()) {
            ledger.retentionHeld(retentionHeld(ledger.retentionHeld(), ledger.size(), partitionRecords));
            if (ledger.retentionHeld() || consumerRetentionHeld) {
                holds.hold(ledger.partition(), KafkaHoldController.Reason.RETENTION);
            } else {
                holds.release(ledger.partition(), KafkaHoldController.Reason.RETENTION);
            }
        }
    }

    private static boolean retentionHeld(boolean held, long count, long bound) {
        return held ? count > bound / 2 : count >= bound;
    }

    // --- Stop ---

    /**
     * Begins the stop: no record starts, no retry timer starts a record and no partition fetches. Records already
     * returned are still registered, and runs, publishes and commits in flight finish.
     */
    void beginStop() {
        if (stopping) {
            return;
        }
        stopping = true;
        holds.holdAll(KafkaHoldController.Reason.SHUTDOWN);
    }

    /** Whether no handler runs and no dead-letter publish or commit is outstanding. */
    boolean drained() {
        return permits == 0 && publishes == 0 && commits.outstanding() == 0;
    }

    /** The handlers that run and the dead-letter publishes outstanding. */
    int running() {
        return permits + publishes;
    }

    /** Whether no commit is outstanding. */
    boolean committed() {
        return commits.outstanding() == 0;
    }

    /** Submits the settled prefix of every partition as the final commit. */
    void commitSettled() {
        for (KafkaPartitionLedger ledger : ledgers.values()) {
            commits.commit(ledger);
        }
    }

    /**
     * Ends every assignment and the consumer. Records that did not finish stay uncommitted for a replay. The final
     * commit never comes from the client's fetch position.
     */
    void finishStop() {
        for (KafkaPartitionLedger ledger : new ArrayList<>(ledgers.values())) {
            finish(ledger);
        }
        ledgers.clear();
        queue.clear();
    }

    // --- State for tests and diagnostics ---

    int permits() {
        return permits;
    }

    boolean hasLedger(TopicPartition partition) {
        return ledgers.containsKey(partition);
    }

    @Nullable
    KafkaPartitionLedger ledger(TopicPartition partition) {
        return ledgers.get(partition);
    }

    KafkaHoldController holds() {
        return holds;
    }

    /** The entries waiting for a permit, of any assignment. */
    int queuedEntries() {
        return queue.size();
    }

    long retainedRecords() {
        return retainedRecords;
    }
}
