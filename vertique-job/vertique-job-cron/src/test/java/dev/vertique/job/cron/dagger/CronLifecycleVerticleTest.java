// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronRegistrationException;
import dev.vertique.job.cron.CronScheduler;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Tests for {@link CronLifecycleVerticle} — the verticle that owns cron startup/shutdown and is
 * contributed as a {@code SERVICES}-phase deployment by {@link CronModule} and
 * {@link CronPersistenceModule}.
 *
 * <p>Verifies that {@link CronLifecycleVerticle#start} runs {@link CronJobRegistrar#scan()}
 * before {@link CronScheduler#start()}, fails the start promise on either failure path, and
 * delegates {@link CronLifecycleVerticle#stop} to {@link CronScheduler#stop()}.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("CronLifecycleVerticle")
class CronLifecycleVerticleTest {

    @Nested
    @DisplayName("start()")
    class Start {

        @Test
        @DisplayName("invokes registrar.scan() then scheduler.start() in order")
        void invokesScanThenStart(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            when(scheduler.start()).thenReturn(Future.succeededFuture());

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.succeeded(), () -> "deploy should succeed: " + failureMessage(ar));
                        InOrder inOrder = inOrder(registrar, scheduler);
                        inOrder.verify(registrar).scan();
                        inOrder.verify(scheduler).start();
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("fails start when registrar.scan() throws — scheduler.start() is never called")
        void failsWhenScanThrows(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            CronRegistrationException scanFailure = new CronRegistrationException(List.of("bad job"));
            doThrow(scanFailure).when(registrar).scan();

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.failed(), "deploy should fail when scan throws");
                        assertInstanceOf(CronRegistrationException.class, ar.cause());
                        assertSame(scanFailure, ar.cause());
                        verify(scheduler, never()).start();
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("fails start when scheduler.start() returns a failed future and best-effort stops the scheduler")
        void failsWhenSchedulerStartFails(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            RuntimeException startFailure = new RuntimeException("scheduler boom");
            when(scheduler.start()).thenReturn(Future.failedFuture(startFailure));
            when(scheduler.stop()).thenReturn(Future.succeededFuture());

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.failed(), "deploy should fail when scheduler.start() fails");
                        assertSame(startFailure, ar.cause());
                        verify(registrar).scan();
                        verify(scheduler).start();
                        // scan() registered jobs into the singleton scheduler; Vert.x will not
                        // call stop() on a failed start, so the verticle must roll back.
                        verify(scheduler).stop();
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName(
                "synchronous throw from scheduler.start() triggers best-effort rollback and propagates the original cause")
        void syncStartThrowAlsoTriggersRollback(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            RuntimeException startFailure = new RuntimeException("scheduler sync boom");
            // A synchronous throw from start() (e.g. vertx.setTimer rejecting on a closed Vert.x)
            // would otherwise bypass the .onFailure(...) rollback entirely because no Future is
            // produced. The verticle must catch the sync throw and run the same scheduler.stop()
            // best-effort cleanup as the failed-Future path.
            Mockito.doThrow(startFailure).when(scheduler).start();
            when(scheduler.stop()).thenReturn(Future.succeededFuture());

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.failed(), "deploy should fail when scheduler.start() throws synchronously");
                        assertSame(startFailure, ar.cause());
                        verify(scheduler).start();
                        verify(scheduler).stop();
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("propagates the original start failure even when best-effort stop also fails")
        void preservesStartFailureWhenStopAlsoFails(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            RuntimeException startFailure = new RuntimeException("scheduler boom");
            RuntimeException stopFailure = new RuntimeException("rollback boom");
            when(scheduler.start()).thenReturn(Future.failedFuture(startFailure));
            when(scheduler.stop()).thenReturn(Future.failedFuture(stopFailure));

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.failed());
                        assertSame(
                                startFailure,
                                ar.cause(),
                                "the original cause must win — best-effort cleanup must not mask it");
                        verify(scheduler).stop();
                        ctx.completeNow();
                    }));
        }
    }

    @Nested
    @DisplayName("stop()")
    class Stop {

        @Test
        @DisplayName("delegates to scheduler.stop() and succeeds")
        void delegatesStop(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            when(scheduler.start()).thenReturn(Future.succeededFuture());
            when(scheduler.stop()).thenReturn(Future.succeededFuture());

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .compose(deploymentId -> vertx.undeploy(deploymentId))
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.succeeded(), () -> "undeploy should succeed: " + failureMessage(ar));
                        verify(scheduler).stop();
                        ctx.completeNow();
                    }));
        }

        @Test
        @DisplayName("fails stop when scheduler.stop() returns a failed future")
        void failsWhenSchedulerStopFails(Vertx vertx, VertxTestContext ctx) {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            RuntimeException stopFailure = new RuntimeException("scheduler stop boom");
            when(scheduler.start()).thenReturn(Future.succeededFuture());
            when(scheduler.stop()).thenReturn(Future.failedFuture(stopFailure));

            CronLifecycleVerticle verticle = new CronLifecycleVerticle(registrar, scheduler);

            vertx.deployVerticle(verticle)
                    .compose(deploymentId -> vertx.undeploy(deploymentId))
                    .onComplete(ar -> ctx.verify(() -> {
                        assertTrue(ar.failed(), "undeploy should fail when scheduler.stop() fails");
                        assertSame(stopFailure, ar.cause());
                        ctx.completeNow();
                    }));
        }
    }

    /**
     * Direct-promise smoke: the verticle's start/stop callbacks are exercised without going through
     * Vert.x deployment so failures surface as the promise's cause unmodified. Complements the
     * deployment-flow tests above.
     */
    @Nested
    @DisplayName("direct promise contract")
    class DirectPromise {

        @Test
        @DisplayName("start: scan failure fails the promise directly with the thrown exception")
        void startScanFailureFailsPromiseDirectly() {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            CronRegistrationException scanFailure = new CronRegistrationException(List.of("bad job"));
            doThrow(scanFailure).when(registrar).scan();

            Promise<Void> startPromise = Promise.promise();
            new CronLifecycleVerticle(registrar, scheduler).start(startPromise);

            assertTrue(startPromise.future().failed());
            assertSame(scanFailure, startPromise.future().cause());
            verify(scheduler, never()).start();
        }

        @Test
        @DisplayName("stop: scheduler.stop() failure fails the promise with the underlying cause")
        void stopSchedulerFailureFailsPromise() {
            CronJobRegistrar registrar = Mockito.mock(CronJobRegistrar.class);
            CronScheduler scheduler = Mockito.mock(CronScheduler.class);
            RuntimeException stopFailure = new RuntimeException("scheduler stop boom");
            when(scheduler.stop()).thenReturn(Future.failedFuture(stopFailure));

            Promise<Void> stopPromise = Promise.promise();
            new CronLifecycleVerticle(registrar, scheduler).stop(stopPromise);

            assertTrue(stopPromise.future().failed());
            assertSame(stopFailure, stopPromise.future().cause());
        }
    }

    private static String failureMessage(AsyncResult<?> ar) {
        return ar.cause() == null ? "<no cause>" : ar.cause().toString();
    }
}
