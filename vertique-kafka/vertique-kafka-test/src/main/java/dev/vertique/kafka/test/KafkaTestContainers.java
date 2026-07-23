// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared, reachability-verified Testcontainers Kafka broker for the framework's Kafka integration
 * tests. A single broker is started once per JVM (i.e. once per Maven Surefire/Failsafe fork) and
 * reused by every IT class in that fork via {@link #shared()}, rather than each IT class starting
 * its own container.
 *
 * <p><b>Why a shared, verified singleton.</b> The {@code apache/kafka} {@link KafkaContainer} is
 * subject to <a href="https://github.com/testcontainers/testcontainers-java/issues/11682">
 * Testcontainers #11682</a>: its startup command races the copy of its advertised-listener
 * bootstrap script, so under CPU/IO contention (a full multi-module reactor build, or a constrained
 * CI runner) the broker can come up with a broken listener while still printing the log line the
 * default wait strategy keys on. {@code start()} then returns "ready" while clients get
 * {@code NoAvailableBrokersException} forever, hanging an unbounded {@code @BeforeAll}. Two
 * mitigations combine here:
 * <ol>
 *   <li><b>Fewer starts.</b> One broker per fork instead of one per IT class cuts the number of
 *       start attempts — and thus the number of chances to lose the race — several-fold.</li>
 *   <li><b>Real verification.</b> After {@code start()} the broker is probed with an
 *       {@link AdminClient} <em>and</em> a real {@link KafkaConsumer} that must join a group and
 *       receive a partition assignment. The consumer probe forces {@code __consumer_offsets}
 *       creation and group-coordinator readiness — the exact path that hangs when only cluster
 *       metadata (AdminClient) is checked. If verification fails the container is recreated (up to
 *       {@value #START_ATTEMPTS} attempts), turning a probabilistic infinite hang into a
 *       self-healing, bounded start.</li>
 * </ol>
 *
 * <p>The shared container is intentionally never stopped: Testcontainers' Ryuk sidecar reaps it when
 * the JVM exits. IT classes must therefore NOT call {@code stop()} on it.
 */
public final class KafkaTestContainers {

    private static final Logger log = LoggerFactory.getLogger(KafkaTestContainers.class);

    /** Docker image for the shared Kafka broker. */
    public static final String KAFKA_IMAGE = "apache/kafka:3.8.1";

    /** Max times {@link #startVerified()} recreates the container before giving up. */
    private static final int START_ATTEMPTS = 3;

    /** Startup-log wait timeout; the 60s Testcontainers default is tight under reactor/CI load. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);

    /** Bound for each reachability/consumer probe operation. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    /** Overall bound for the consumer to obtain a partition assignment. */
    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(30);

    private static volatile KafkaContainer shared;

    private KafkaTestContainers() {}

    /**
     * Returns the shared, reachability-verified Kafka broker for this JVM, starting and verifying it
     * on first call. Subsequent calls return the same instance. The caller must NOT stop it.
     *
     * @return the shared, ready Kafka container
     */
    public static KafkaContainer shared() {
        KafkaContainer local = shared;
        if (local != null) {
            return local;
        }
        return initShared();
    }

    private static synchronized KafkaContainer initShared() {
        if (shared == null) {
            shared = startVerified();
        }
        return shared;
    }

    private static KafkaContainer startVerified() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            KafkaContainer container = new KafkaContainer(KAFKA_IMAGE).withStartupTimeout(STARTUP_TIMEOUT);
            try {
                container.start();
                String bootstrap = container.getBootstrapServers();
                verifyClusterReachable(bootstrap);
                verifyConsumerCanJoin(bootstrap);
                log.info("Shared Kafka broker ready at {} (attempt {}/{})", bootstrap, attempt, START_ATTEMPTS);
                return container;
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn(
                        "Kafka container start attempt {}/{} did not yield a usable broker, recreating: {}",
                        attempt,
                        START_ATTEMPTS,
                        e.toString());
                safeStop(container);
            }
        }
        throw new IllegalStateException(
                "Kafka broker did not become usable after " + START_ATTEMPTS + " attempts", lastFailure);
    }

    /**
     * Probes that a broker is serving cluster metadata at {@code bootstrap} via a short-lived
     * {@link AdminClient} with bounded timeouts.
     *
     * @param bootstrap the mapped {@code host:port}
     * @throws IllegalStateException if the broker is not reachable within the probe timeout
     */
    private static void verifyClusterReachable(String bootstrap) {
        try (AdminClient admin = AdminClient.create(adminProps(bootstrap))) {
            admin.describeCluster().nodes().get(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted probing Kafka cluster reachability", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka cluster not reachable at " + bootstrap, e);
        }
    }

    /**
     * Verifies a real consumer can join a group and receive a partition assignment on a throwaway
     * probe topic. This exercises group-coordinator readiness and {@code __consumer_offsets}
     * creation — the path that hangs when a broker is metadata-reachable but not yet serving
     * consumer group coordination.
     *
     * @param bootstrap the mapped {@code host:port}
     * @throws IllegalStateException if no assignment is obtained within {@link #ASSIGNMENT_TIMEOUT}
     */
    private static void verifyConsumerCanJoin(String bootstrap) {
        String probeTopic = "vertique-kafka-test-probe";
        ensureProbeTopic(bootstrap, probeTopic);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "vertique-kafka-test-probe-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(probeTopic));
            long deadline = System.currentTimeMillis() + ASSIGNMENT_TIMEOUT.toMillis();
            while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500));
            }
            if (consumer.assignment().isEmpty()) {
                throw new IllegalStateException("Kafka consumer obtained no partition assignment at " + bootstrap
                        + " within " + ASSIGNMENT_TIMEOUT.toSeconds() + "s");
            }
        }
    }

    private static void ensureProbeTopic(String bootstrap, String topic) {
        try (AdminClient admin = AdminClient.create(adminProps(bootstrap))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all()
                    .get(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted creating Kafka probe topic", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                return; // already created by a prior probe/run — fine
            }
            throw new IllegalStateException("Failed to create Kafka probe topic at " + bootstrap, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out creating Kafka probe topic at " + bootstrap, e);
        }
    }

    private static Map<String, Object> adminProps(String bootstrap) {
        return Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000",
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
    }

    private static void safeStop(KafkaContainer container) {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // Best-effort teardown of a broker that never became usable; the recreate proceeds.
        }
    }
}
