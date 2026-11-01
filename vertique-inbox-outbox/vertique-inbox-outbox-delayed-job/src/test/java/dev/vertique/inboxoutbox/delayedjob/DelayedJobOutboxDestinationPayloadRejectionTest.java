// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

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
import dev.vertique.inboxoutbox.DelayedJobControl;
import dev.vertique.inboxoutbox.OutboxDeliveryMetadata;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.job.delayed.DelayedJobExceptionMapper;
import dev.vertique.job.delayed.DelayedJobHandlerRegistrar;
import dev.vertique.job.delayed.DelayedJobService;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link DelayedJobOutboxDestinationHandler} through the real {@link DelayedJobService} and
 * {@link PgJobRepository}, so the payload an outbox row carries reaches the storage conversion that
 * a mocked service never exercises.
 */
@DisplayName("DelayedJobOutboxDestinationHandler payload rejection")
class DelayedJobOutboxDestinationPayloadRejectionTest {

    @Test
    @DisplayName("a payload the job store rejects is a permanent failure")
    void rejectedPayloadIsPermanentFailure() {
        PgJobRepository repository = new PgJobRepository(mock(Pool.class), new PgDbExceptionMapper());
        DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
        when(registrar.handlerAddresses()).thenReturn(Map.of("deliver-webhook", "job/deliver-webhook/execute"));
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = new DurableContextPropagator(
                new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
        DelayedJobService service =
                new DelayedJobService(repository, repository, registrar, propagator, new DelayedJobExceptionMapper());
        DelayedJobTargetResolver resolver = mock(DelayedJobTargetResolver.class);
        when(resolver.resolve("deliver-webhook"))
                .thenReturn(new ResolvedDelayedJobTarget(
                        "deliver-webhook", "deliver-webhook", "job/deliver-webhook/execute", "default", 5, 3));
        DelayedJobOutboxDestinationHandler handler = new DelayedJobOutboxDestinationHandler(service, resolver);
        OutboxEnvelope envelope = new OutboxEnvelope(
                99L,
                null,
                null,
                "webhook.deliver",
                "deliver-webhook",
                PayloadCodec.encode(new Object()),
                Map.of(),
                new OutboxMetadata(
                        DurableMetadata.empty(),
                        new OutboxDeliveryMetadata(
                                Optional.empty(), Optional.of(new DelayedJobControl("default", 5, 3)))),
                null,
                0,
                Instant.now());

        Future<OutboxPublishResult> result = handler.publish(envelope);

        assertTrue(result.succeeded(), "the handler must complete with an outcome, not a failed future");
        assertInstanceOf(OutboxPublishResult.PermanentFailure.class, result.result());
    }
}
