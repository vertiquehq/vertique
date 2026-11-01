// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaRecordFilter;
import dev.vertique.workflow.services.source.SourceStages;

/**
 * The stages of a Kafka signal source: the shared signal stages and the source's default
 * {@code onNoMatch}, then the stages of the topic.
 *
 * <p>The source offers no stage for a commit strategy, an error strategy or a retry strategy. The
 * binding {@link #build() built} here commits manually, retries, requires both strategies, so that
 * {@code kafka.consumers.{name}.*} cannot restore automatic commit or skipping, installs no header
 * context, and acknowledges a record that cannot be decoded after reporting it.
 *
 * <p>{@link #build()} fails with an {@link IllegalStateException} naming the source, never a key, for
 * a signal the contract does not declare and for a business-key signal with no {@code dedupBy}.
 *
 * @param <T> the type of the decoded record value
 */
public interface KafkaSignals<T> extends SourceStages<T, KafkaSignals<T>, KafkaConsumerBinding<T>> {

    /**
     * Sets the topic.
     *
     * @param topic the Kafka topic
     * @return this source
     * @throws IllegalArgumentException if {@code topic} is blank
     */
    KafkaSignals<T> topic(String topic);

    /**
     * Sets the consumer group.
     *
     * @param groupId the consumer group id
     * @return this source
     * @throws IllegalArgumentException if {@code groupId} is blank
     */
    KafkaSignals<T> groupId(String groupId);

    /**
     * Replaces the default name, {@code {groupId}-{topic}}. The name is the consumer's configuration
     * key, and the source name in diagnostics and in events.
     *
     * @param name the source name
     * @return this source
     * @throws IllegalArgumentException if {@code name} is blank
     */
    KafkaSignals<T> name(String name);

    /**
     * Drops the records the filter rejects. A rejected record is committed as
     * ignored and reported once as {@code IGNORED}, with no key, header or payload. A filter that
     * throws is reported as {@code STAGE_FAILED} and takes the consumer's exhausted strategy at once,
     * with the exception's class name as the error type: it is never retried and never pauses the
     * consumer.
     *
     * @param filter the filter over a record's key and headers
     * @return this source
     */
    KafkaSignals<T> filter(KafkaRecordFilter filter);

    /**
     * Marks the source as consuming records replayed from a dead-letter topic.
     *
     * @return this source
     */
    KafkaSignals<T> dlqReplay();
}
