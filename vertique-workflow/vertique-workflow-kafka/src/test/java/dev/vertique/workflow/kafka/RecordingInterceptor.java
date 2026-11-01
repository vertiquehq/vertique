// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.kafka.interceptor.KafkaConsumerCompletedEvent;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.interceptor.KafkaConsumerRecordView;
import dev.vertique.kafka.interceptor.KafkaTerminalOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps, in order, every completion the consumer reports for a record: the record's position, how often it had been
 * retried and the {@link KafkaTerminalOutcome} of that attempt. A record that is retried reports
 * {@code RETRY_SCHEDULED} for each failed attempt and a last outcome when it settles.
 */
final class RecordingInterceptor implements KafkaConsumerInterceptor {

    /**
     * One completion of a record.
     *
     * @param consumer the consumer name
     * @param topic the record's topic
     * @param partition the record's partition
     * @param offset the record's offset
     * @param retryCount how often the record had been retried when this attempt completed
     * @param outcome the outcome of the attempt
     */
    record Completion(
            String consumer, String topic, int partition, long offset, int retryCount, KafkaTerminalOutcome outcome) {

        boolean settles() {
            return outcome != KafkaTerminalOutcome.RETRY_SCHEDULED;
        }
    }

    private final List<Completion> completions = new CopyOnWriteArrayList<>();

    @Override
    public void onRecordCompleted(KafkaConsumerCompletedEvent event, KafkaConsumerRecordView record) {
        var identity = event.identity();
        completions.add(new Completion(
                identity.consumerName(),
                identity.topic(),
                identity.partition(),
                identity.offset(),
                identity.retryCount(),
                event.outcome()));
    }

    /**
     * Returns every completion so far.
     *
     * @return the completions, oldest first
     */
    List<Completion> completions() {
        return List.copyOf(completions);
    }

    /**
     * Returns the completions of one record, oldest first.
     *
     * @param position where the record was produced
     * @return the completions
     */
    List<Completion> of(SourceScenario.Produced position) {
        return completions.stream()
                .filter(completion -> completion.topic().equals(position.topic())
                        && completion.partition() == position.partition()
                        && completion.offset() == position.offset())
                .toList();
    }

    /**
     * Returns the outcomes of one record, oldest first.
     *
     * @param position where the record was produced
     * @return the outcomes
     */
    List<KafkaTerminalOutcome> outcomesOf(SourceScenario.Produced position) {
        return of(position).stream().map(Completion::outcome).toList();
    }

    /**
     * Waits until the record has settled: its last completion is not a scheduled retry.
     *
     * @param position where the record was produced
     * @param timeout how long to wait
     * @return the settling completion, or empty when the record did not settle in time
     */
    Optional<Completion> awaitSettled(SourceScenario.Produced position, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<Completion> settled =
                    of(position).stream().filter(Completion::settles).findFirst();
            if (settled.isPresent() || System.nanoTime() >= deadline) {
                return settled;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Waits until the record has the given number of completions.
     *
     * @param position where the record was produced
     * @param count how many completions to wait for
     * @param timeout how long to wait
     * @return whether the completions arrived in time
     */
    boolean awaitCompletions(SourceScenario.Produced position, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (of(position).size() >= count) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return of(position).size() >= count;
    }
}
