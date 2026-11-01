// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.kafka.KafkaDlqHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads a dead-letter topic from its start with a consumer of its own, which joins no group and commits nothing, so a
 * read never moves an offset a source relies on.
 */
final class DlqReader {

    /**
     * One record of a dead-letter topic.
     *
     * @param key the record key, or {@code null}
     * @param headers the record's headers by name, the last value of a repeated header
     * @param value the record value, or {@code null}
     */
    record Entry(String key, Map<String, String> headers, byte[] value) {

        /**
         * Returns the error type the consumer put in {@code x-dlq-error}.
         *
         * @return the header's text, or {@code null} when the record has none
         */
        String errorType() {
            return headers.get(KafkaDlqHeaders.ERROR);
        }

        /**
         * Returns the headers the consumer itself wrote: every {@code x-dlq-} header, joined as text.
         *
         * @return the consumer's own headers as one text
         */
        String frameworkHeaders() {
            StringBuilder text = new StringBuilder();
            headers.forEach((name, value) -> {
                if (name.startsWith("x-dlq-")) {
                    text.append(name).append('=').append(value).append('\n');
                }
            });
            return text.toString();
        }
    }

    private final String bootstrap;
    private final String topic;

    private DlqReader(String bootstrap, String topic) {
        this.bootstrap = bootstrap;
        this.topic = topic;
    }

    /**
     * Opens a reader of one dead-letter topic.
     *
     * @param bootstrap the broker's bootstrap address
     * @param topic the dead-letter topic
     * @return the reader
     */
    static DlqReader of(String bootstrap, String topic) {
        return new DlqReader(bootstrap, topic);
    }

    /**
     * Returns every record the topic holds at this moment, read from its start to the end offsets seen when the read
     * began. A topic that does not exist yet reads as empty.
     *
     * @return the records, partition by partition in offset order
     */
    List<Entry> read() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        List<Entry> entries = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(5)).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && partitions.stream().anyMatch(tp -> consumer.position(tp) < ends.get(tp))) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (Header header : record.headers()) {
                        headers.put(
                                header.key(),
                                header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
                    }
                    entries.add(new Entry(record.key(), headers, record.value()));
                }
            }
        }
        return entries;
    }

    /**
     * Waits until the topic holds at least {@code count} records, then returns every record it holds.
     *
     * @param count how many records to wait for
     * @param timeout how long to wait
     * @return the records; fewer than {@code count} when the time ran out
     */
    List<Entry> await(int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Entry> entries = read();
        while (entries.size() < count && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return entries;
            }
            entries = read();
        }
        return entries;
    }
}
