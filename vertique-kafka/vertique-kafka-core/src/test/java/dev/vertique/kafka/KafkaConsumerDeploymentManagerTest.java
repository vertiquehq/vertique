// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.kafka.config.KafkaConfig;
import dev.vertique.kafka.producer.KafkaProducerFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KafkaConsumerDeploymentManager}: codec registration, deployment of
 * enabled/disabled consumers, rollback on failure, undeploy, and producer factory close.
 */
@ExtendWith(VertxExtension.class)
@ExtendWith(MockitoExtension.class)
class KafkaConsumerDeploymentManagerTest {

    // --- Test event type ---

    record TestEvent(String name) {}

    // --- Helpers ---

    /**
     * Builds a {@link ResolvedKafkaConsumerConfig} with minimal defaults for testing.
     *
     * @param name the consumer binding name
     * @param enabled whether the consumer is enabled
     * @return a resolved config suitable for unit tests
     */
    private static ResolvedKafkaConsumerConfig testConfig(String name, boolean enabled) {
        JsonObject cfg = new JsonObject()
                .put(
                        "consumers",
                        new JsonObject()
                                .put(
                                        name,
                                        new JsonObject()
                                                .put("topic", "test.topic." + name)
                                                .put("groupId", "test-group")
                                                .put("enabled", enabled)));
        KafkaConfig kafkaConfig = KafkaConfig.fromConfig(
                new JsonObject().put("kafka", cfg), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        return ResolvedKafkaConsumerConfig.resolve(
                name,
                "test.topic." + name,
                "test-group",
                enabled,
                CommitStrategy.AUTO,
                ErrorStrategy.SKIP,
                "",
                5000L,
                null,
                kafkaConfig,
                kafkaConfig.consumerIndex().get(name));
    }

    /**
     * Builds a {@link ConsumerEntry} of kind {@link ConsumerEntry.Kind#BINDING} for testing.
     *
     * @param name the consumer binding name
     * @param enabled whether the consumer is enabled
     * @return a fully populated consumer entry
     */
    private static ConsumerEntry testEntry(String name, boolean enabled) {
        return new ConsumerEntry(
                name,
                testConfig(name, enabled),
                ConsumerEntry.Kind.BINDING,
                TestEvent.class,
                "service/test/" + name,
                null,
                true,
                List.of(),
                null,
                null,
                false,
                null,
                dev.vertique.kafka.serialization.KafkaSerdeRegistry.DEFAULT_FORMAT);
    }

    /**
     * Builds a {@link KafkaConsumerRegistry} backed by a fixed list of entries, bypassing the
     * full registrar scan.
     *
     * @param entries the entries to populate the registry with
     * @return a registry holding exactly those entries
     */
    private static KafkaConsumerRegistry registryOf(ConsumerEntry... entries) {
        KafkaConsumerRegistry mock = mock(KafkaConsumerRegistry.class);
        when(mock.entries()).thenReturn(List.of(entries));
        return mock;
    }

    // --- Tests ---

    @Nested
    @DisplayName("deployAll")
    class DeployAll {

        @Test
        @DisplayName("deploys all enabled consumers and succeeds")
        void shouldDeployAllEnabledConsumers(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            ConsumerEntry entry1 = testEntry("consumer-a", true);
            ConsumerEntry entry2 = testEntry("consumer-b", true);

            when(deployer.deploy(any(VerticleDeployment.class)))
                    .thenReturn(Future.succeededFuture("deploy-id-1"))
                    .thenReturn(Future.succeededFuture("deploy-id-2"));

            KafkaConsumerRegistry registry = registryOf(entry1, entry2);
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.deployAll().onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> verify(deployer, times(2)).deploy(any(VerticleDeployment.class)));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("skips disabled consumers and only deploys enabled ones")
        void shouldSkipDisabledConsumers(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            ConsumerEntry enabled = testEntry("consumer-on", true);
            ConsumerEntry disabled = testEntry("consumer-off", false);

            when(deployer.deploy(any(VerticleDeployment.class))).thenReturn(Future.succeededFuture("deploy-id-1"));

            KafkaConsumerRegistry registry = registryOf(enabled, disabled);
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.deployAll().onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> verify(deployer, times(1)).deploy(any(VerticleDeployment.class)));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("registers dispatch.envelope and dispatch.result codecs before deploying")
        void shouldRegisterCodecsBeforeDeploying(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            // Use an empty registry — no deployments needed, just trigger codec registration
            KafkaConsumerRegistry registry = registryOf();
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            // Should not throw — codecs are registered idempotently
            manager.deployAll().onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    // Confirm both codec names are actually registered on the event bus.
                    // Attempting to register a duplicate codec throws IllegalStateException,
                    // which means the codec was already registered by deployAll().
                    assertThrows(
                            IllegalStateException.class,
                            () -> vertx.eventBus()
                                    .registerCodec(
                                            new dev.vertique.core.eventbus.LocalMessageCodec<>("dispatch.envelope")),
                            "dispatch.envelope codec must already be registered");
                    assertThrows(
                            IllegalStateException.class,
                            () -> vertx.eventBus()
                                    .registerCodec(
                                            new dev.vertique.core.eventbus.LocalMessageCodec<>("dispatch.result")),
                            "dispatch.result codec must already be registered");
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("rolls back successfully deployed consumers when one deployment fails")
        void shouldRollbackOnDeploymentFailure(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            ConsumerEntry entry1 = testEntry("consumer-ok", true);
            ConsumerEntry entry2 = testEntry("consumer-fail", true);

            // First deploy succeeds, second fails
            when(deployer.deploy(any(VerticleDeployment.class)))
                    .thenReturn(Future.succeededFuture("deploy-id-ok"))
                    .thenReturn(Future.failedFuture(new RuntimeException("deploy failed")));
            when(deployer.undeploy(anyString())).thenReturn(Future.succeededFuture());

            KafkaConsumerRegistry registry = registryOf(entry1, entry2);
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.deployAll().onComplete(ctx.failing(cause -> {
                ctx.verify(() -> {
                    // The overall deployment must have failed
                    assertNotNull(cause);
                    // The successfully deployed consumer must have been rolled back
                    verify(deployer, atLeastOnce()).undeploy(anyString());
                });
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("succeeds immediately when no enabled consumers are registered")
        void shouldSucceedWithNoEnabledConsumers(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            ConsumerEntry disabled = testEntry("consumer-off", false);

            KafkaConsumerRegistry registry = registryOf(disabled);
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.deployAll().onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> verify(deployer, never()).deploy(any()));
                ctx.completeNow();
            }));
        }
    }

    @Nested
    @DisplayName("undeployAll")
    class UndeployAll {

        @Test
        @DisplayName("undeploys all previously deployed consumers")
        void shouldUndeployAllOnUndeploy(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            ConsumerEntry entry1 = testEntry("consumer-a", true);
            ConsumerEntry entry2 = testEntry("consumer-b", true);

            when(deployer.deploy(any(VerticleDeployment.class)))
                    .thenReturn(Future.succeededFuture("deploy-id-1"))
                    .thenReturn(Future.succeededFuture("deploy-id-2"));
            when(deployer.undeploy(anyString())).thenReturn(Future.succeededFuture());

            KafkaConsumerRegistry registry = registryOf(entry1, entry2);
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.deployAll().compose(v -> manager.undeployAll()).onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> verify(deployer, times(2)).undeploy(anyString()));
                ctx.completeNow();
            }));
        }

        @Test
        @DisplayName("succeeds immediately when no deployments are tracked")
        void shouldSucceedWithNoTrackedDeployments(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory,
                @Mock KafkaConsumerRegistry registry) {
            // Do not stub registry.entries() — undeployAll() bypasses the registry entirely
            // and operates only on the internal deploymentNames tracking map.
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            manager.undeployAll().onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> verify(deployer, never()).undeploy(anyString()));
                ctx.completeNow();
            }));
        }
    }

    @Nested
    @DisplayName("producer factory lifecycle")
    class ProducerLifecycle {

        @Test
        @DisplayName("codec registration is idempotent — second deployAll call does not throw")
        void shouldRegisterCodecsIdempotently(
                Vertx vertx,
                VertxTestContext ctx,
                @Mock VerticleDeployer deployer,
                @Mock KafkaProducerFactory producerFactory) {
            KafkaConsumerRegistry registry = registryOf();
            KafkaConsumerDeploymentManager manager = new KafkaConsumerDeploymentManager(
                    vertx,
                    deployer,
                    registry,
                    Set.of(),
                    Set.of(),
                    producerFactory,
                    null,
                    null,
                    null,
                    KafkaTestSupport.noOpInboundExecutionContextScope(),
                    KafkaTestSupport.noOpEnvelopeBuilder(),
                    KafkaTestSupport.jsonSerdeRegistry());

            // Call deployAll twice — second call must not throw from duplicate codec registration
            manager.deployAll().compose(v -> manager.deployAll()).onComplete(ctx.succeeding(v -> ctx.completeNow()));
        }
    }
}
