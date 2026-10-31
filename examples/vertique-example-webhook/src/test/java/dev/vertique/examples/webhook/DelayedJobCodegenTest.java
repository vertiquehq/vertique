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
import dev.vertique.examples.webhook.webhook.DeliverWebhookJob;
import dev.vertique.examples.webhook.webhook.DeliverWebhookJob_DelayedJobProxy;
import dev.vertique.examples.webhook.webhook.WebhookPayload;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import dev.vertique.job.delayed.DelayedJobExceptionMapper;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    /** A real service over a repository that records what it saves, as the scope graph helper does for the module. */
    private static DelayedJobService recordingService(List<JobExecution> saved) {
        JobRepository repository = mock(JobRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            JobExecution execution = invocation.getArgument(0);
            saved.add(execution);
            return Future.succeededFuture(execution.id());
        });
        DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
        when(registrar.handlerAddresses()).thenReturn(Map.of("deliver-webhook", "test/webhook/deliver"));
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        return new DelayedJobService(
                repository, mock(PgJobRepository.class), registrar, propagator, new DelayedJobExceptionMapper());
    }
}
