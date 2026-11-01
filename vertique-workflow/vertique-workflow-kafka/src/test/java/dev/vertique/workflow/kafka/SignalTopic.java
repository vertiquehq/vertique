// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * One topic of a proof on the shared broker, with its dead-letter topic, its consumer group and its source name, all
 * unique to the proof, and the producer and admin client that feed and observe it. Closing deletes the group.
 */
final class SignalTopic implements AutoCloseable {

    private static final Duration ASSIGNMENT = Duration.ofSeconds(30);

    private final String topic;
    private final String group;
    private final String name;
    private final AdminClient admin;
    private final KafkaProducer<String, byte[]> producer;

    private SignalTopic(
            String topic, String group, String name, AdminClient admin, KafkaProducer<String, byte[]> producer) {
        this.topic = topic;
        this.group = group;
        this.name = name;
        this.admin = admin;
        this.producer = producer;
    }

    /**
     * Creates the topic and its dead-letter topic on the shared broker.
     *
     * @param base the name the topic is a unique variant of
     * @return the topic
     */
    static SignalTopic create(String base) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return open(base + "." + unique, "g-" + base + "." + unique, "carriers-" + unique);
    }

    /**
     * Creates the topic of the exact name, and its dead-letter topic, for a source whose class states its topic, group
     * and name itself. The group is deleted on close; the topic is not, so one proof may produce to the name once per
     * broker.
     *
     * @param topic the topic the source reads
     * @param group the consumer group the source states
     * @param name the name the source states, which is also its consumer's configuration key
     * @return the topic
     */
    static SignalTopic createExact(String topic, String group, String name) {
        return open(topic, group, name);
    }

    private static SignalTopic open(String topic, String group, String name) {
        AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SourceScenario.Infrastructure.bootstrap(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000"));
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SourceScenario.Infrastructure.bootstrap(),
                        ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.LINGER_MS_CONFIG, "0"),
                new StringSerializer(),
                new ByteArraySerializer());
        try {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".dlq", 1, (short) 1)))
                    .all()
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            producer.close(Duration.ofSeconds(1));
            admin.close(Duration.ofSeconds(1));
            throw new AssertionError("interrupted while creating the topics", e);
        } catch (ExecutionException e) {
            // A node of an earlier proof that installs the same generated listener subscribes to an exact name, and the
            // broker then creates the topic. No proof but this one produces to it, so it is empty, and both topics
            // exist.
            if (!(e.getCause() instanceof TopicExistsException)) {
                producer.close(Duration.ofSeconds(1));
                admin.close(Duration.ofSeconds(1));
                throw new AssertionError("the topics were not created", e);
            }
        } catch (TimeoutException e) {
            producer.close(Duration.ofSeconds(1));
            admin.close(Duration.ofSeconds(1));
            throw new AssertionError("the topics were not created", e);
        }
        return new SignalTopic(topic, group, name, admin, producer);
    }

    /** The topic the source reads. */
    String topic() {
        return topic;
    }

    /** The dead-letter topic of the source. */
    String deadLetterTopic() {
        return topic + ".dlq";
    }

    /** The consumer group of the source. */
    String group() {
        return group;
    }

    /** The name of the source, which is also its consumer's configuration key. */
    String name() {
        return name;
    }

    /** Produces the text as the value of a record under the Kafka key. */
    SourceScenario.Produced produce(String key, String json) {
        try {
            RecordMetadata metadata = producer.send(
                            new ProducerRecord<>(topic, key, json.getBytes(StandardCharsets.UTF_8)))
                    .get(20, TimeUnit.SECONDS);
            return new SourceScenario.Produced(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while producing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("the record was not produced", e);
        }
    }

    /** Waits until the consumer group has been assigned the topic's partition. */
    void awaitAssigned() {
        long deadline = System.nanoTime() + ASSIGNMENT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ConsumerGroupDescription description = admin.describeConsumerGroups(List.of(group))
                        .describedGroups()
                        .get(group)
                        .get(10, TimeUnit.SECONDS);
                int assigned = description.members().stream()
                        .mapToInt(
                                member -> member.assignment().topicPartitions().size())
                        .sum();
                if (assigned >= 1) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the assignment", e);
            } catch (ExecutionException | TimeoutException e) {
                // the group may not exist yet; ask again
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the assignment", e);
            }
        }
        throw new AssertionError("the consumers of " + group + " were not assigned their partition");
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
        try {
            admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // a group that never formed has nothing to delete
        } finally {
            admin.close(Duration.ofSeconds(5));
        }
    }
}
