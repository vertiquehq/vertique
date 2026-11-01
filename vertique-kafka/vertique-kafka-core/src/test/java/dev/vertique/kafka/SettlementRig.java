// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.mockito.Mockito;

/**
 * A {@link KafkaSettlement} over a port that does nothing by itself: a test completes every handler run, dead-letter
 * publish, commit, pause and timer, in the order it chooses, and reads what the settlement asked for. Everything runs
 * on the calling thread, so an interleaving is whatever the test writes.
 */
final class SettlementRig {

    static final String TOPIC = "t";
    static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    static final TopicPartition P1 = new TopicPartition(TOPIC, 1);

    /** One started run of a record. */
    static final class Run {

        final KafkaConsumerRecord<String, byte[]> record;
        final int attempt;
        final Promise<KafkaSettlement.Result> result = Promise.promise();

        Run(KafkaConsumerRecord<String, byte[]> record, int attempt) {
            this.record = record;
            this.attempt = attempt;
        }

        long offset() {
            return record.offset();
        }

        void succeed() {
            settle(KafkaTerminalOutcome.SUCCESS);
        }

        void settle(KafkaTerminalOutcome outcome) {
            result.complete(KafkaSettlement.Result.settled(outcome, mock(KafkaConsumerRecordView.class)));
        }

        void fail() {
            fail(new IllegalStateException("handler failed"));
        }

        void fail(Throwable cause) {
            result.complete(KafkaSettlement.Result.failed(cause, mock(KafkaConsumerRecordView.class)));
        }
    }

    /** One dead-letter publish. */
    static final class Publish {

        final KafkaConsumerRecord<String, byte[]> record;
        final Throwable cause;
        final Promise<Void> result = Promise.promise();

        Publish(KafkaConsumerRecord<String, byte[]> record, Throwable cause) {
            this.record = record;
            this.cause = cause;
        }
    }

    /** One commit the settlement submitted. */
    static final class Commit {

        final TopicPartition partition;
        final long offset;
        final Promise<Void> result = Promise.promise();

        Commit(TopicPartition partition, long offset) {
            this.partition = partition;
            this.offset = offset;
        }
    }

    /** One pause or resume call. */
    static final class PartitionCall {

        final TopicPartition partition;
        final boolean pause;
        final Promise<Void> result = Promise.promise();

        PartitionCall(TopicPartition partition, boolean pause) {
            this.partition = partition;
            this.pause = pause;
        }
    }

    /** One scheduled timer. */
    static final class FakeTimer {

        final long delayMs;
        final Runnable task;
        private final java.util.function.Consumer<Runnable> turn;
        boolean fired;

        FakeTimer(long delayMs, Runnable task, java.util.function.Consumer<Runnable> turn) {
            this.delayMs = delayMs;
            this.task = task;
            this.turn = turn;
        }

        /** Fires the timer, as a clock that lets it fire at any later point would. */
        void fire() {
            fired = true;
            turn.accept(task);
        }
    }

    /** The port. A call completes by itself only when {@link #autoComplete} says so. */
    final class FakePort implements KafkaSettlement.Port {

        final List<Run> runs = new ArrayList<>();
        final List<Publish> publishes = new ArrayList<>();
        final List<Commit> commits = new ArrayList<>();
        final List<PartitionCall> partitionCalls = new ArrayList<>();
        final List<FakeTimer> timers = new ArrayList<>();
        final List<KafkaTerminalOutcome> completions = new ArrayList<>();

        private final java.util.ArrayDeque<Runnable> deferred = new java.util.ArrayDeque<>();
        private boolean running;

        /** Whether commits, pauses and resumes are acknowledged at once. */
        boolean autoComplete = true;

        /** Told of each run as it starts, so a test can complete it in the same call, as a handler that is quick would. */
        java.util.function.Consumer<Run> onRun;

        @Override
        public Future<KafkaSettlement.Result> run(KafkaConsumerRecord<String, byte[]> record, int attempt) {
            Run run = new Run(record, attempt);
            runs.add(run);
            if (onRun != null) {
                onRun.accept(run);
            }
            return run.result.future();
        }

        @Override
        public Future<Void> publishDlq(KafkaConsumerRecord<String, byte[]> record, Throwable cause) {
            Publish publish = new Publish(record, cause);
            publishes.add(publish);
            return publish.result.future();
        }

        @Override
        public Future<Void> commit(TopicPartition partition, long offset) {
            Commit commit = new Commit(partition, offset);
            commits.add(commit);
            if (autoComplete) {
                commit.result.complete();
            }
            return commit.result.future();
        }

        @Override
        public Future<Void> pause(TopicPartition partition) {
            return partitionCall(partition, true);
        }

        @Override
        public Future<Void> resume(TopicPartition partition) {
            return partitionCall(partition, false);
        }

        private Future<Void> partitionCall(TopicPartition partition, boolean pause) {
            PartitionCall call = new PartitionCall(partition, pause);
            partitionCalls.add(call);
            if (autoComplete) {
                call.result.complete();
            }
            return call.result.future();
        }

        @Override
        public void schedule(long delayMs, Runnable task) {
            timers.add(new FakeTimer(delayMs, task, this::execute));
        }

        /**
         * Defers the task: it runs after the task that is running returns, as the owner context runs it. A task that
         * is submitted while none runs, such as a completion or a timer the test triggers, starts the turn.
         */
        @Override
        public void execute(Runnable task) {
            deferred.add(task);
            if (running) {
                return;
            }
            running = true;
            try {
                for (Runnable next = deferred.poll(); next != null; next = deferred.poll()) {
                    next.run();
                }
            } finally {
                running = false;
                deferred.clear();
            }
        }

        @Override
        public void completed(KafkaConsumerRecordView view, KafkaTerminalOutcome outcome) {
            completions.add(outcome);
        }
    }

    final FakePort port = new FakePort();
    final KafkaSettlement settlement;

    SettlementRig(KafkaSettlement.Settings settings) {
        this.settlement = new KafkaSettlement(settings, port);
    }

    /** Settings of a binding with the given strategy and generous retention. */
    static KafkaSettlement.Settings settings(int maxInFlight, ErrorStrategy strategy, boolean forced) {
        return settings(maxInFlight, strategy, forced, 1_000, null);
    }

    static KafkaSettlement.Settings settings(
            int maxInFlight,
            ErrorStrategy strategy,
            boolean forced,
            int retentionLimit,
            @Nullable KafkaDispatchGate gate) {
        RetryConfig retry = strategy == ErrorStrategy.RETRY
                ? new RetryConfig(2, 100L, 2.0, ErrorStrategy.DEAD_LETTER, 10_000L)
                : null;
        return new KafkaSettlement.Settings("test", maxInFlight, strategy, retry, forced, retentionLimit, gate);
    }

    /** A record of a partition with a value of {@code size} bytes. */
    @SuppressWarnings("unchecked")
    static KafkaConsumerRecord<String, byte[]> record(TopicPartition partition, long offset, int size) {
        KafkaConsumerRecord<String, byte[]> record =
                mock(KafkaConsumerRecord.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        Mockito.when(record.topic()).thenReturn(partition.getTopic());
        Mockito.when(record.partition()).thenReturn(partition.getPartition());
        Mockito.when(record.offset()).thenReturn(offset);
        Mockito.when(record.key()).thenReturn("k");
        Mockito.when(record.value()).thenReturn(new byte[size]);
        return record;
    }

    static KafkaConsumerRecord<String, byte[]> record(TopicPartition partition, long offset) {
        return record(partition, offset, 10);
    }

    SettlementRig assign(TopicPartition... partitions) {
        port.execute(() -> settlement.assigned(List.of(partitions)));
        return this;
    }

    SettlementRig revoke(TopicPartition... partitions) {
        port.execute(() -> settlement.revoked(List.of(partitions)));
        return this;
    }

    /** Registers records of one partition at the given offsets. */
    SettlementRig add(TopicPartition partition, long... offsets) {
        for (long offset : offsets) {
            port.execute(() -> settlement.register(record(partition, offset)));
        }
        return this;
    }

    /** The latest run of a record of the partition. */
    Run run(TopicPartition partition, long offset) {
        Run found = null;
        for (Run run : port.runs) {
            if (run.record.partition() == partition.getPartition() && run.offset() == offset) {
                found = run;
            }
        }
        if (found == null) {
            throw new AssertionError("offset " + offset + " of " + partition + " has not started");
        }
        return found;
    }

    /** The number of runs started for a record. */
    long started(TopicPartition partition, long offset) {
        return port.runs.stream()
                .filter(run -> run.record.partition() == partition.getPartition() && run.offset() == offset)
                .count();
    }

    /** The offsets submitted for commit, in order, for one partition. */
    List<Long> committed(TopicPartition partition) {
        return port.commits.stream()
                .filter(commit -> commit.partition.equals(partition))
                .map(commit -> commit.offset)
                .toList();
    }

    /** The unfired timers. */
    List<FakeTimer> pendingTimers() {
        return port.timers.stream().filter(timer -> !timer.fired).toList();
    }

    /** The state of the client's pause for a partition after the calls so far. */
    boolean clientPaused(TopicPartition partition) {
        boolean paused = false;
        for (PartitionCall call : port.partitionCalls) {
            if (call.partition.equals(partition) && call.result.future().succeeded()) {
                paused = call.pause;
            }
        }
        return paused;
    }
}
