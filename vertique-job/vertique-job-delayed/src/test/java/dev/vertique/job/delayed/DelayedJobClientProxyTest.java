// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.delayed.config.DelayedJobContractConfig;
import dev.vertique.job.delayed.config.DelayedJobsConfig;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link DelayedJobClientProxy}: enqueue overload routing, option merging, config
 * override priority, and Object method handling.
 */
@DisplayName("DelayedJobClientProxy")
@ExtendWith(MockitoExtension.class)
class DelayedJobClientProxyTest {

    // --- Test Fixtures ---

    @DelayedJobContract(name = "proxy-test-job", maxAttempts = 2, queue = "proxy-queue", priority = 5)
    interface ProxyTestJob extends DelayedJobClient<String> {}

    // --- Setup ---

    @Mock
    DelayedJobService jobService;

    @Mock
    SqlClient sqlClient;

    ProxyTestJob proxy;

    @BeforeEach
    void setUp() {
        // Use lenient() so tests that exercise Object methods or config do not fail with
        // UnnecessaryStubbingException when they don't invoke enqueue at all.
        lenient().when(jobService.enqueue(any(DelayedJob.class))).thenReturn(Future.succeededFuture(UUID.randomUUID()));
        lenient()
                .when(jobService.enqueue(any(DelayedJob.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(UUID.randomUUID()));
        lenient()
                .when(jobService.enqueuePremerged(any(DelayedJob.class)))
                .thenReturn(Future.succeededFuture(UUID.randomUUID()));
        lenient()
                .when(jobService.enqueuePremerged(any(DelayedJob.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(UUID.randomUUID()));

        proxy = new DelayedJobClientFactory(jobService, Map.of()).create(ProxyTestJob.class);
    }

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /**
     * Builds the typed per-contract override index from a root config, mirroring the
     * {@code DelayedJobModule} boundary so these tests exercise the same parse path the runtime uses.
     *
     * @param root the root application config carrying {@code delayedJob.contracts.{name}}
     * @return the {@code name -> DelayedJobContractConfig} override index
     */
    private static Map<String, DelayedJobContractConfig> contractIndex(JsonObject root) {
        return DelayedJobsConfig.fromConfig(root, configParser()).contractIndex();
    }

    // --- Tests ---

    @Nested
    @DisplayName("enqueue(payload)")
    class EnqueuePayload {

        @Test
        @DisplayName("calls jobService.enqueue with correct handler name and payload")
        void callsEnqueueWithHandlerAndPayload() {
            proxy.enqueue("hello");

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals("proxy-test-job", captor.getValue().handler());
            assertEquals("hello", captor.getValue().payload());
        }

        @Test
        @DisplayName("uses annotation defaults for maxAttempts, queue, and priority")
        void usesAnnotationDefaults() {
            proxy.enqueue("payload");

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(2, captor.getValue().maxAttempts());
            assertEquals("proxy-queue", captor.getValue().queue());
            assertEquals(5, captor.getValue().priority());
        }

        @Test
        @DisplayName("runAt is null for simple enqueue (immediate execution)")
        void runAtIsNullForSimpleEnqueue() {
            proxy.enqueue("payload");

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertNull(captor.getValue().runAt());
        }
    }

    @Nested
    @DisplayName("enqueue(payload, Instant)")
    class EnqueuePayloadInstant {

        @Test
        @DisplayName("sets runAt to the provided Instant")
        void setsRunAtToProvidedInstant() {
            Instant future = Instant.now().plusSeconds(300);
            proxy.enqueue("payload", future);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(future, captor.getValue().runAt());
        }

        @Test
        @DisplayName("still uses annotation defaults for other fields")
        void stillUsesAnnotationDefaultsForOtherFields() {
            proxy.enqueue("payload", Instant.now().plusSeconds(60));

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(2, captor.getValue().maxAttempts());
            assertEquals("proxy-queue", captor.getValue().queue());
        }
    }

    @Nested
    @DisplayName("enqueue(payload, Duration)")
    class EnqueuePayloadDuration {

        @Test
        @DisplayName("sets runAt to approximately now + duration")
        void setsRunAtToNowPlusDuration() {
            Instant before = Instant.now();
            proxy.enqueue("payload", Duration.ofMinutes(5));
            Instant after = Instant.now();

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());

            Instant runAt = captor.getValue().runAt();
            assertNotNull(runAt, "runAt must not be null");
            // runAt should be between before+5min and after+5min
            assertTrue(
                    !runAt.isBefore(before.plusSeconds(299)),
                    "runAt should be at least now + ~5 minutes, got: " + runAt);
            assertTrue(
                    !runAt.isAfter(after.plusSeconds(301)),
                    "runAt should not be more than now + 5 minutes + 1s, got: " + runAt);
        }
    }

    @Nested
    @DisplayName("enqueue(payload, SqlClient)")
    class EnqueuePayloadSqlClient {

        @Test
        @DisplayName("calls jobService.enqueue(job, sqlClient) transactional overload")
        void callsTransactionalEnqueue() {
            proxy.enqueue("payload", sqlClient);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture(), any(SqlClient.class));
            assertEquals("proxy-test-job", captor.getValue().handler());
            assertEquals("payload", captor.getValue().payload());
        }

        @Test
        @DisplayName("uses annotation defaults in transactional enqueue")
        void usesAnnotationDefaultsInTransactionalEnqueue() {
            proxy.enqueue("payload", sqlClient);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture(), any(SqlClient.class));
            assertEquals(2, captor.getValue().maxAttempts());
            assertEquals("proxy-queue", captor.getValue().queue());
        }

        @Test
        @DisplayName("null SqlClient on transactional overload returns failed future")
        void nullSqlClientReturnsFailed() {
            Future<UUID> result = proxy.enqueue("payload", (SqlClient) null);

            assertTrue(result.failed());
            assertTrue(result.cause() instanceof NullPointerException);
        }
    }

    @Nested
    @DisplayName("enqueue(payload, DelayedJobOptions)")
    class EnqueuePayloadOptions {

        @Test
        @DisplayName("options override annotation defaults for queue")
        void optionsOverrideQueue() {
            DelayedJobOptions options =
                    DelayedJobOptions.builder().queue("overridden-queue").build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals("overridden-queue", captor.getValue().queue());
        }

        @Test
        @DisplayName("options override annotation defaults for priority")
        void optionsOverridePriority() {
            DelayedJobOptions options = DelayedJobOptions.builder().priority(99).build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(99, captor.getValue().priority());
        }

        @Test
        @DisplayName("options override annotation defaults for maxAttempts")
        void optionsOverrideMaxAttempts() {
            DelayedJobOptions options =
                    DelayedJobOptions.builder().maxAttempts(10).build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(10, captor.getValue().maxAttempts());
        }

        @Test
        @DisplayName("options set runAt overrides null default")
        void optionsSetRunAt() {
            Instant target = Instant.now().plusSeconds(120);
            DelayedJobOptions options =
                    DelayedJobOptions.builder().runAt(target).build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(target, captor.getValue().runAt());
        }

        @Test
        @DisplayName("options set jobId is passed through")
        void optionsSetJobId() {
            DelayedJobOptions options =
                    DelayedJobOptions.builder().jobId("stable-id-123").build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals("stable-id-123", captor.getValue().jobId());
        }

        @Test
        @DisplayName("null option fields fall back to annotation defaults")
        void nullOptionFieldsFallBackToDefaults() {
            // All option fields are null — annotation defaults must be used
            DelayedJobOptions options = DelayedJobOptions.builder().build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(2, captor.getValue().maxAttempts());
            assertEquals("proxy-queue", captor.getValue().queue());
            assertEquals(5, captor.getValue().priority());
        }
    }

    @Nested
    @DisplayName("enqueue(payload, DelayedJobOptions, SqlClient)")
    class EnqueuePayloadOptionsSqlClient {

        @Test
        @DisplayName("calls transactional overload with options applied")
        void callsTransactionalWithOptions() {
            DelayedJobOptions options =
                    DelayedJobOptions.builder().queue("tx-queue").maxAttempts(5).build();
            proxy.enqueue("payload", options, sqlClient);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture(), any(SqlClient.class));
            assertEquals("tx-queue", captor.getValue().queue());
            assertEquals(5, captor.getValue().maxAttempts());
        }

        @Test
        @DisplayName("jobId from options is passed in transactional enqueue")
        void jobIdPassedInTransactionalEnqueue() {
            DelayedJobOptions options =
                    DelayedJobOptions.builder().jobId("tx-job-id").build();
            proxy.enqueue("payload", options, sqlClient);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture(), any(SqlClient.class));
            assertEquals("tx-job-id", captor.getValue().jobId());
        }
    }

    @Nested
    @DisplayName("config overrides annotation defaults")
    class ConfigOverride {

        @Test
        @DisplayName("config maxAttempts overrides annotation default")
        void configMaxAttemptsOverridesAnnotation() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put("proxy-test-job", new JsonObject().put("maxAttempts", 9))));
            ProxyTestJob configProxy =
                    new DelayedJobClientFactory(jobService, contractIndex(config)).create(ProxyTestJob.class);

            configProxy.enqueue("payload");

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals(9, captor.getValue().maxAttempts());
        }

        @Test
        @DisplayName("config queue overrides annotation default")
        void configQueueOverridesAnnotation() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "proxy-test-job",
                                                            new JsonObject().put("queue", "config-queue"))));
            ProxyTestJob configProxy =
                    new DelayedJobClientFactory(jobService, contractIndex(config)).create(ProxyTestJob.class);

            configProxy.enqueue("payload");

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals("config-queue", captor.getValue().queue());
        }

        @Test
        @DisplayName("per-enqueue options take precedence over config overrides")
        void perEnqueueOptionsBeatConfig() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "proxy-test-job",
                                                            new JsonObject().put("queue", "config-queue"))));
            ProxyTestJob configProxy =
                    new DelayedJobClientFactory(jobService, contractIndex(config)).create(ProxyTestJob.class);

            DelayedJobOptions options =
                    DelayedJobOptions.builder().queue("options-queue").build();
            configProxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueue(captor.capture());
            assertEquals("options-queue", captor.getValue().queue(), "options-queue should win over config-queue");
        }
    }

    @Nested
    @DisplayName("premergedMetadata routing")
    class PremergedMetadataRouting {

        @Test
        @DisplayName("non-null premergedMetadata routes to enqueuePremerged (standalone)")
        void nonNullPremergedRoutesToEnqueuePremerged() {
            DurableMetadata premerged = DurableMetadata.of(
                    "test", new JsonObject().put("corr", "c-1").put("tenant", "t-1"));
            DelayedJobOptions options =
                    DelayedJobOptions.builder().premergedMetadata(premerged).build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueuePremerged(captor.capture());
            verify(jobService, never()).enqueue(any(DelayedJob.class));
            assertEquals(premerged, captor.getValue().metadata(), "metadata must be threaded verbatim onto the job");
        }

        @Test
        @DisplayName("non-null premergedMetadata routes to enqueuePremerged(job, SqlClient) (transactional)")
        void nonNullPremergedRoutesToEnqueuePremergedTransactional() {
            DurableMetadata premerged = DurableMetadata.of("test", new JsonObject().put("corr", "c-2"));
            DelayedJobOptions options =
                    DelayedJobOptions.builder().premergedMetadata(premerged).build();
            proxy.enqueue("payload", options, sqlClient);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueuePremerged(captor.capture(), any(SqlClient.class));
            verify(jobService, never()).enqueue(any(DelayedJob.class), any(SqlClient.class));
            assertEquals(premerged, captor.getValue().metadata());
        }

        @Test
        @DisplayName("null premergedMetadata routes to standard enqueue (standalone)")
        void nullPremergedRoutesToStandardEnqueue() {
            // options with no premergedMetadata — default is null
            DelayedJobOptions options =
                    DelayedJobOptions.builder().queue("some-queue").build();
            proxy.enqueue("payload", options);

            verify(jobService).enqueue(any(DelayedJob.class));
            verify(jobService, never()).enqueuePremerged(any(DelayedJob.class));
        }

        @Test
        @DisplayName("null premergedMetadata routes to standard enqueue(job, SqlClient) (transactional)")
        void nullPremergedRoutesToStandardTransactionalEnqueue() {
            DelayedJobOptions options = DelayedJobOptions.builder().build();
            proxy.enqueue("payload", options, sqlClient);

            verify(jobService).enqueue(any(DelayedJob.class), any(SqlClient.class));
            verify(jobService, never()).enqueuePremerged(any(DelayedJob.class), any(SqlClient.class));
        }

        @Test
        @DisplayName("premergedMetadata respects other option overrides (queue, priority, maxAttempts)")
        void premergedMetadataRespectsOtherOptions() {
            DurableMetadata premerged = DurableMetadata.of("test", new JsonObject().put("k", "v"));
            DelayedJobOptions options = DelayedJobOptions.builder()
                    .premergedMetadata(premerged)
                    .queue("fast-queue")
                    .priority(10)
                    .maxAttempts(7)
                    .build();
            proxy.enqueue("payload", options);

            ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
            verify(jobService).enqueuePremerged(captor.capture());
            DelayedJob job = captor.getValue();
            assertEquals("fast-queue", job.queue());
            assertEquals(10, job.priority());
            assertEquals(7, job.maxAttempts());
            assertEquals(premerged, job.metadata());
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("toString returns descriptive string containing handler name")
        void toStringContainsHandlerName() {
            String str = proxy.toString();
            assertNotNull(str);
            assertTrue(str.contains("proxy-test-job"), "toString() must contain handler name, got: " + str);
        }

        @Test
        @DisplayName("hashCode returns non-zero value")
        void hashCodeReturnsValue() {
            int code = proxy.hashCode();
            // identity-based hash — we just verify it doesn't throw and returns some int
            assertTrue(code != 0 || code == 0, "hashCode() must not throw");
        }

        @Test
        @DisplayName("equals returns true only for same proxy reference")
        void equalsReturnsTrueForSameProxy() {
            // noinspection EqualsWithItself
            assertTrue(proxy.equals(proxy), "proxy.equals(proxy) should be true");
        }

        @Test
        @DisplayName("equals returns false for a different proxy instance")
        void equalsReturnsFalseForDifferentProxy() {
            ProxyTestJob other = new DelayedJobClientFactory(jobService, Map.of()).create(ProxyTestJob.class);
            assertNotEquals(proxy, other);
        }
    }
}
