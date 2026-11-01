// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.examples.webhook.webhook.DeliverWebhookJob;
import dev.vertique.examples.webhook.webhook.DeliverWebhookJob_DelayedJobProxy;
import dev.vertique.examples.webhook.webhook.WebhookPayload;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import dev.vertique.job.delayed.DelayedJobExceptionMapper;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * End-to-end check that the {@code vertique-codegen-delayed-job} processor generated a static proxy
 * for {@link DeliverWebhookJob} and that {@link DelayedJobClientFactory} selects it (rather than the
 * JDK dynamic proxy), plus the {@code GeneratedDelayedJobClientsModule} that binds the contract in
 * the Dagger graph. Proves the example opted into codegen via its
 * {@code annotationProcessorPaths}.
 *
 * <p>The factory only passes the service into the proxy constructor (it never invokes it during
 * {@code create}), so a {@code null} service is sufficient for selection.
 */
class DelayedJobCodegenTest {

    @Test
    @DisplayName("factory.create returns the generated DeliverWebhookJob proxy, not a JDK proxy")
    void factorySelectsGeneratedProxy() {
        DelayedJobClientFactory factory = new DelayedJobClientFactory(null, Map.of());

        Object client = factory.create(DeliverWebhookJob.class);

        assertTrue(
                client.getClass().getName().endsWith("_DelayedJobProxy"),
                "expected a generated *_DelayedJobProxy, got "
                        + client.getClass().getName());
    }

    @Test
    @DisplayName("GeneratedDelayedJobClientsModule is present and binds DeliverWebhookJob")
    void generatedClientsModuleBindsTheContract() {
        Class<?> module = assertDoesNotThrow(
                () -> Class.forName("dev.vertique.examples.webhook.webhook.GeneratedDelayedJobClientsModule"),
                "GeneratedDelayedJobClientsModule must be on the classpath");

        assertNotNull(assertDoesNotThrow(
                () -> module.getDeclaredMethod("provideDeliverWebhookJobClient", DelayedJobClientFactory.class),
                "the module must declare a factory-delegating binding for DeliverWebhookJob"));
    }

    @Test
    @DisplayName("a scoped client is the generated DeliverWebhookJob proxy and saves its scope")
    void scopedClientUsesTheGeneratedProxy() {
        // Given: a real delayed-job service over a recording repository, and a factory over it
        List<JobExecution> saved = new ArrayList<>();
        DelayedJobClientFactory factory = new DelayedJobClientFactory(recordingService(saved), Map.of());

        // When: a client scoped to acme enqueues
        DeliverWebhookJob client =
                factory.client(DeliverWebhookJob.class).scope("acme").create();
        client.enqueue(new WebhookPayload("pay_123"));

        // Then: the client is the generated proxy, and the saved execution is in acme
        assertInstanceOf(DeliverWebhookJob_DelayedJobProxy.class, client);
        assertFalse(Proxy.isProxyClass(client.getClass()), "should not be a JDK dynamic proxy");
        assertEquals(1, saved.size(), "the enqueue must save exactly one execution");
        assertEquals("acme", saved.get(0).scopeKey());
        assertEquals("test/webhook/deliver", saved.get(0).handler());
        assertEquals(new WebhookPayload("pay_123"), saved.get(0).payload());
    }

    private static final Instant RUN_AT = Instant.parse("2031-01-02T03:04:05Z");
    private static final DurableMetadata PREMERGED = DurableMetadata.of("custom", new JsonObject().put("flag", "kept"));

    /** One call of a {@link DeliverWebhookJob} overload. */
    @FunctionalInterface
    interface Enqueue {
        Future<UUID> call(DeliverWebhookJob client, SqlClient tx);
    }

    private static Named<Enqueue> overload(String name, Enqueue call) {
        return Named.of(name, call);
    }

    /** Every client overload, with and without pre-merged metadata; the metadata is {@code null} when not given. */
    private static Stream<Arguments> overloads() {
        WebhookPayload payload = new WebhookPayload("pay_123");
        DelayedJobOptions options = DelayedJobOptions.builder()
                .runAt(RUN_AT)
                .queue("urgent")
                .priority(9)
                .maxAttempts(5)
                .jobId("job-1")
                .build();
        DelayedJobOptions premerged = DelayedJobOptions.builder()
                .runAt(RUN_AT)
                .queue("urgent")
                .priority(9)
                .maxAttempts(5)
                .jobId("job-1")
                .premergedMetadata(PREMERGED)
                .build();
        return Stream.of(
                Arguments.of(overload("enqueue(payload)", (c, tx) -> c.enqueue(payload)), null),
                Arguments.of(overload("enqueue(payload, runAt)", (c, tx) -> c.enqueue(payload, RUN_AT)), null),
                Arguments.of(
                        overload("enqueue(payload, delay)", (c, tx) -> c.enqueue(payload, Duration.ofHours(1))), null),
                Arguments.of(overload("enqueue(payload, tx)", (c, tx) -> c.enqueue(payload, tx)), null),
                Arguments.of(overload("enqueue(payload, options)", (c, tx) -> c.enqueue(payload, options)), null),
                Arguments.of(
                        overload("enqueue(payload, options, tx)", (c, tx) -> c.enqueue(payload, options, tx)), null),
                Arguments.of(
                        overload("enqueue(payload, premerged options)", (c, tx) -> c.enqueue(payload, premerged)),
                        PREMERGED),
                Arguments.of(
                        overload(
                                "enqueue(payload, premerged options, tx)",
                                (c, tx) -> c.enqueue(payload, premerged, tx)),
                        PREMERGED));
    }

    @ParameterizedTest
    @MethodSource("overloads")
    @DisplayName("the generated proxy saves the selected scope on every overload and keeps every other field")
    void generatedProxyStoresScopeOnEveryOverload(Enqueue enqueue, DurableMetadata premergedMetadata) {
        // Given: a real service, and a scoped and an unscoped client, both the generated DeliverWebhookJob proxy
        List<JobExecution> saved = new ArrayList<>();
        DelayedJobClientFactory factory = new DelayedJobClientFactory(recordingService(saved), Map.of());
        DeliverWebhookJob scoped =
                factory.client(DeliverWebhookJob.class).scope("acme").create();
        DeliverWebhookJob unscoped = factory.create(DeliverWebhookJob.class);
        SqlClient tx = mock(SqlClient.class);

        // When: the scoped client enqueues through the overload, then the unscoped client does the same
        Future<UUID> scopedResult = enqueue.call(scoped, tx);
        Future<UUID> unscopedResult = enqueue.call(unscoped, tx);

        // Then: the scoped row is in acme and equals the unscoped row in every other field
        assertInstanceOf(DeliverWebhookJob_DelayedJobProxy.class, scoped);
        assertTrue(scopedResult.succeeded(), "scoped enqueue must succeed: " + scopedResult.cause());
        assertTrue(unscopedResult.succeeded(), "unscoped enqueue must succeed: " + unscopedResult.cause());
        assertEquals(2, saved.size(), "each enqueue must save exactly one row");
        JobExecution acme = saved.get(0);
        JobExecution other = saved.get(1);
        assertEquals("acme", acme.scopeKey());
        assertEquals(ScopeKey.DEFAULT.value(), other.scopeKey(), "scoping one client must not scope another");
        assertEquals("test/webhook/deliver", acme.handler());
        assertEquals(new WebhookPayload("pay_123"), acme.payload());
        assertTrue(
                Duration.between(other.scheduledAt(), acme.scheduledAt()).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "the scheduled time must be unchanged by the scope");
        assertEquals(
                withoutVolatileFields(other),
                withoutVolatileFields(acme),
                "only the scope may differ between a scoped and an unscoped enqueue");
        if (premergedMetadata != null) {
            assertEquals(premergedMetadata, acme.metadata(), "pre-merged metadata must be saved as given");
        }
    }

    /** The row without its scope or the fields that differ between two calls: the id, a generated job id, and time. */
    private static JobExecution withoutVolatileFields(JobExecution row) {
        return new JobExecution(
                new UUID(0, 0),
                row.jobId().startsWith("delayed-") ? "delayed-generated" : row.jobId(),
                row.jobType(),
                row.handler(),
                row.queue(),
                row.state(),
                row.attemptNumber(),
                row.maxAttempts(),
                row.payload(),
                row.priority(),
                row.lockedBy(),
                Instant.EPOCH,
                row.enqueuedAt(),
                row.startedAt(),
                row.completedAt(),
                row.errorMessage(),
                row.errorType(),
                row.progress(),
                row.parameters(),
                row.attributes(),
                row.metadata(),
                ScopeKey.DEFAULT.value());
    }

    /** A real service over a repository that records what it saves, as the scope graph helper does for the module. */
    private static DelayedJobService recordingService(List<JobExecution> saved) {
        JobRepository repository = mock(JobRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            JobExecution execution = invocation.getArgument(0);
            saved.add(execution);
            return Future.succeededFuture(execution.id());
        });
        PgJobRepository pgRepository = mock(PgJobRepository.class);
        when(pgRepository.save(any(), any())).thenAnswer(invocation -> {
            JobExecution execution = invocation.getArgument(0);
            saved.add(execution);
            return Future.succeededFuture(execution.id());
        });
        DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
        when(registrar.handlerAddresses()).thenReturn(Map.of("deliver-webhook", "test/webhook/deliver"));
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        return new DelayedJobService(repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
    }
}
