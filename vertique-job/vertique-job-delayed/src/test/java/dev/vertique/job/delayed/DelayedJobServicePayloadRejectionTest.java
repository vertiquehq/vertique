// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies every {@link DelayedJobService} enqueue path, which back {@link DelayedJobClient}, against
 * the real {@link PgJobRepository}: a payload that does not serialize to a JSON object fails the
 * returned future instead of throwing out of it.
 */
@DisplayName("DelayedJobService payload rejection")
class DelayedJobServicePayloadRejectionTest {

    private DelayedJobService service;

    @BeforeEach
    void setUp() {
        PgJobRepository repository = new PgJobRepository(mock(Pool.class), new PgDbExceptionMapper());
        DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
        when(registrar.handlerAddresses()).thenReturn(Map.of("my-handler", "test/my-svc/execute"));
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        service = new DelayedJobService(repository, repository, registrar, propagator, new DelayedJobExceptionMapper());
    }

    private static DelayedJob stringJob() {
        return DelayedJob.builder()
                .handler("my-handler")
                .payload("hello")
                .metadata(DurableMetadata.empty())
                .build();
    }

    private static void assertRejected(Future<?> result) {
        assertTrue(result.failed());
        assertInstanceOf(IllegalArgumentException.class, result.cause());
    }

    @Test
    @DisplayName("enqueue fails the future")
    void enqueueFailsTheFuture() {
        assertRejected(service.enqueue(stringJob()));
    }

    @Test
    @DisplayName("transactional enqueue fails the future")
    void transactionalEnqueueFailsTheFuture() {
        assertRejected(service.enqueue(stringJob(), mock(SqlClient.class)));
    }

    @Test
    @DisplayName("pre-merged enqueue fails the future")
    void premergedEnqueueFailsTheFuture() {
        assertRejected(service.enqueuePremerged(stringJob()));
    }

    @Test
    @DisplayName("transactional pre-merged enqueue fails the future")
    void transactionalPremergedEnqueueFailsTheFuture() {
        assertRejected(service.enqueuePremerged(stringJob(), mock(SqlClient.class)));
    }
}
