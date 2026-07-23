// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.delayed.DelayedJob;
import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobContract;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.job.delayed.DelayedJobService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies {@link DelayedJobProxyEmitter} generates a {@code {Contract}_DelayedJobProxy} whose source
 * shape and runtime behavior mirror the reflective {@code DelayedJobClientProxy}, including
 * {@code Object}-method parity ({@code toString} returns {@code "DelayedJobClient[name]"}).
 */
class DelayedJobProxyEmitterTest {

    private static final String GENERATED_FQN = "com.example.DeliverJob_DelayedJobProxy";

    private static JavaFileObject deliverContract() {
        return SourceFiles.inline("com.example.DeliverJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "deliver", maxAttempts = 2, queue = "webhooks", priority = 4)
                public interface DeliverJob extends DelayedJobClient<String> {}
                """);
    }

    @Test
    @DisplayName("generates a proxy implementing the contract with all six enqueue overloads")
    void generatesProxySource() {
        ProcessorTestHarness.run(new DelayedJobContractProcessor(), deliverContract())
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "implements DeliverJob")
                .assertGeneratedSourceContains(GENERATED_FQN, "enqueue(String payload)")
                .assertGeneratedSourceContains(GENERATED_FQN, "enqueue(String payload, Instant runAt)")
                .assertGeneratedSourceContains(GENERATED_FQN, "enqueue(String payload, Duration delay)")
                .assertGeneratedSourceContains(GENERATED_FQN, "enqueue(String payload, SqlClient tx)")
                .assertGeneratedSourceContains(GENERATED_FQN, "enqueue(String payload, DelayedJobOptions options)")
                .assertGeneratedSourceContains(
                        GENERATED_FQN, "enqueue(String payload, DelayedJobOptions options, SqlClient tx)")
                .assertGeneratedSourceContains(GENERATED_FQN, "Instant.now().plus(delay)")
                .assertGeneratedSourceContains(GENERATED_FQN, "jobService.enqueue(job)")
                .assertGeneratedSourceContains(GENERATED_FQN, "jobService.enqueuePremerged(job)")
                .assertGeneratedSourceContains(GENERATED_FQN, "SqlClient argument must not be null");
    }

    @Test
    @DisplayName("nested @DelayedJobContract generates a flattened Outer_Inner proxy in the origin package")
    void generatesFlattenedProxyForNestedContract() {
        JavaFileObject nested = SourceFiles.inline("com.example.Outer", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                public final class Outer {
                    @DelayedJobContract(name = "nested")
                    public interface Inner extends DelayedJobClient<String> {}
                }
                """);

        // The generated name flattens Outer$Inner -> Outer_Inner (matching GeneratedNames.companionFqn),
        // and lands in the origin package com.example.
        ProcessorTestHarness.run(new DelayedJobContractProcessor(), nested)
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.Outer_Inner_DelayedJobProxy", "implements Outer.Inner");
    }

    @Test
    @DisplayName("emits Object-method parity: toString/equals/hashCode")
    void generatesObjectMethodParity() {
        ProcessorTestHarness.run(new DelayedJobContractProcessor(), deliverContract())
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "return \"DelayedJobClient[\" + handlerName + \"]\"")
                .assertGeneratedSourceContains(GENERATED_FQN, "return this == o")
                .assertGeneratedSourceContains(GENERATED_FQN, "System.identityHashCode(this)");
    }

    @Test
    @DisplayName("toString reproduces the reflective proxy's handler-named form")
    void toStringIncludesHandlerName() throws Exception {
        Object proxy = instantiate(mock(DelayedJobService.class));
        assertEquals("DelayedJobClient[deliver]", proxy.toString());
    }

    @Test
    @DisplayName("enqueue(payload) delegates to DelayedJobService.enqueue with effective contract fields")
    @SuppressWarnings("unchecked")
    void enqueueDelegatesWithEffectiveFields() throws Exception {
        DelayedJobService service = mock(DelayedJobService.class);
        Future<UUID> expected = Future.succeededFuture(UUID.randomUUID());
        when(service.enqueue(any(DelayedJob.class))).thenReturn(expected);

        DelayedJobClient<String> client = (DelayedJobClient<String>) instantiate(service);
        Future<UUID> result = client.enqueue("payload");

        assertSame(expected, result);
        ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
        verify(service).enqueue(captor.capture());
        DelayedJob job = captor.getValue();
        // Effective config from @DelayedJobContract(name="deliver", maxAttempts=2, queue="webhooks", priority=4).
        assertEquals("deliver", job.handler());
        assertEquals("payload", job.payload());
        assertEquals("webhooks", job.queue());
        assertEquals(4, job.priority());
        assertEquals(2, job.maxAttempts());
        assertNull(job.runAt());
        assertNull(job.jobId());
    }

    @Test
    @DisplayName("enqueue(payload, Duration) computes runAt as now + delay (parity with reflective proxy)")
    @SuppressWarnings("unchecked")
    void durationOverloadComputesRunAt() throws Exception {
        DelayedJobService service = mock(DelayedJobService.class);
        when(service.enqueue(any(DelayedJob.class))).thenReturn(Future.succeededFuture(UUID.randomUUID()));

        DelayedJobClient<String> client = (DelayedJobClient<String>) instantiate(service);
        Instant before = Instant.now();
        client.enqueue("payload", Duration.ofMinutes(5));
        Instant after = Instant.now();

        ArgumentCaptor<DelayedJob> captor = ArgumentCaptor.forClass(DelayedJob.class);
        verify(service).enqueue(captor.capture());
        Instant runAt = captor.getValue().runAt();
        assertTrue(
                !runAt.isBefore(before.plus(Duration.ofMinutes(5)))
                        && !runAt.isAfter(after.plus(Duration.ofMinutes(5))),
                "runAt should be now + 5m, was " + runAt);
    }

    @Test
    @DisplayName("transactional enqueue with a null SqlClient fails fast (parity with reflective proxy)")
    @SuppressWarnings("unchecked")
    void nullTransactionFailsFast() throws Exception {
        DelayedJobService service = mock(DelayedJobService.class);

        DelayedJobClient<String> client = (DelayedJobClient<String>) instantiate(service);
        Future<UUID> result = client.enqueue("payload", (SqlClient) null);

        assertTrue(result.failed());
        assertTrue(result.cause() instanceof NullPointerException);
        verify(service, never()).enqueue(any(DelayedJob.class));
    }

    @Test
    @DisplayName("premerged metadata routes to enqueuePremerged, not enqueue")
    @SuppressWarnings("unchecked")
    void premergedMetadataRoutesToEnqueuePremerged() throws Exception {
        DelayedJobService service = mock(DelayedJobService.class);
        when(service.enqueuePremerged(any(DelayedJob.class))).thenReturn(Future.succeededFuture(UUID.randomUUID()));

        DelayedJobClient<String> client = (DelayedJobClient<String>) instantiate(service);
        DelayedJobOptions options = DelayedJobOptions.builder()
                .premergedMetadata(DurableMetadata.empty())
                .build();
        client.enqueue("payload", options);

        verify(service).enqueuePremerged(any(DelayedJob.class));
        verify(service, never()).enqueue(any(DelayedJob.class));
    }

    /** Compiles the contract, then constructs the generated proxy with the given (mock) service. */
    private static Object instantiate(DelayedJobService service) throws Exception {
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(), deliverContract())
                .assertSuccess();
        Class<?> contractClass = result.loadGeneratedClass("com.example.DeliverJob");
        DelayedJobContract annotation = contractClass.getAnnotation(DelayedJobContract.class);
        Class<?> proxyClass = result.loadGeneratedClass(GENERATED_FQN);
        Constructor<?> ctor =
                proxyClass.getDeclaredConstructor(DelayedJobService.class, DelayedJobContract.class, JsonObject.class);
        return ctor.newInstance(service, annotation, new JsonObject());
    }
}
