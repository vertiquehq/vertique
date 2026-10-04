<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Kafka Test Module

> **Status:** Stable
> **Package:** `dev.vertique.kafka.test`
> **Artifact:** `vertique-kafka-test`
> **Depends on:** no framework module — Testcontainers and the Vert.x Kafka client only

Shared integration-test support for the Vertique Kafka family. The module supplies one
reachability-verified Testcontainers Kafka broker per Surefire/Failsafe JVM fork so Kafka
integration tests do not start a broker for every test class.

## When To Use It

Add `dev.vertique:vertique-kafka-test` in test scope when an integration test needs a real Kafka
broker. The artifact is currently consumed by `vertique-kafka-core` and `vertique-kafka-avro`; it
is test infrastructure, not an application runtime dependency.

## Core Concepts

### Shared Broker

One broker is started per Surefire/Failsafe JVM fork and shared by every test class in that fork.
Initialization uses volatile publication plus synchronized creation, so concurrent callers share
one fully initialized container.

### Startup and Verification

The first `shared()` call starts the container and performs two bounded probes:

1. An `AdminClient` must retrieve cluster metadata.
2. A real `KafkaConsumer` must join a group and receive a partition assignment on the
   `vertique-kafka-test-probe` topic.

The consumer probe verifies group-coordinator readiness and `__consumer_offsets` creation, not
just metadata reachability. A failed attempt is stopped and recreated, up to three attempts. Each
cluster operation and assignment wait has a fixed timeout, so a broken advertised listener fails
boundedly instead of hanging test setup indefinitely.

**Worst-case startup bound: about 570 s.** Each attempt is bounded by roughly 190 s (120 s container
startup, 20 s cluster probe, 20 s probe-topic creation, 30 s partition assignment), and there are
three attempts. Size any `@Timeout` or CI step limit for the first `shared()` call with this bound
in mind; a healthy start takes seconds.

### Lifecycle and Constraints

- Callers must not call `stop()` on the shared container. It is intentionally retained for the JVM
  fork and Testcontainers Ryuk cleans it up when the JVM exits.
- Docker and the `apache/kafka:3.8.1` image must be available to integration tests.
- The helper owns its probe topic and group names; callers should use separate topic and group
  names for their test scenarios.
- There is no Dagger module or runtime configuration surface.

---

## Key Classes

### KafkaTestContainers

`dev.vertique.kafka.test.KafkaTestContainers` is a static utility with two public members:

| Member | Contract |
|---|---|
| `KAFKA_IMAGE` | Docker image used by the fixture: `apache/kafka:3.8.1` |
| `shared()` | Starts and verifies the broker on first use, then returns the same `KafkaContainer` instance for the rest of the JVM fork |

Typical use:

```java
static final KafkaContainer kafka = KafkaTestContainers.shared();

String bootstrapServers = kafka.getBootstrapServers();
```

---

## Dependencies

The direct compile-scope dependencies are `org.testcontainers:testcontainers-kafka` and
`io.vertx:vertx-kafka-client`. The raw `org.apache.kafka:kafka-clients` types used by the readiness
probes (`AdminClient`, `KafkaConsumer`) arrive transitively through `vertx-kafka-client`, so this
artifact never pins a Kafka client version of its own. Both direct dependencies are compile scope so
modules that depend on this artifact in test scope get them transitively. The artifact also depends
on SLF4J for fixture lifecycle logging and uses JUnit Jupiter only for its own integration test.

## Verification

`KafkaTestContainersIT#sharedStartsVerifiedBroker` proves that `shared()` returns a running broker
with bootstrap servers. Run it with Docker available:

```bash
./mvnw -ntp -pl :vertique-kafka-test -am verify
```
