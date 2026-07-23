// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.MigrationResult;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link FlywayMigrationStartupStep}: it runs in the {@link LifecyclePhase#MIGRATE} phase,
 * delegates {@link FlywayMigrationStartupStep#start()} to {@link MigrationRunner#migrate(Vertx)}
 * mapping the result to {@code Void}, and propagates a failed migration as a failed start future.
 */
@ExtendWith(VertxExtension.class)
class FlywayMigrationStartupStepTest {

    @Test
    @DisplayName("phase() returns MIGRATE")
    void phaseReturnsMigrate(Vertx vertx) {
        MigrationRunner runner = mock(MigrationRunner.class);
        FlywayMigrationStartupStep step = new FlywayMigrationStartupStep(runner, vertx);

        assertSame(LifecyclePhase.MIGRATE, step.phase());
    }

    @Test
    @DisplayName("start() invokes migrate(vertx) and completes with a succeeded Void future")
    void startInvokesMigrateAndSucceeds(Vertx vertx, VertxTestContext ctx) {
        MigrationRunner runner = mock(MigrationRunner.class);
        when(runner.migrate(vertx)).thenReturn(Future.succeededFuture(new MigrationResult(2, "1.2")));
        FlywayMigrationStartupStep step = new FlywayMigrationStartupStep(runner, vertx);

        step.start()
                .onComplete(ctx.succeeding(unused -> ctx.verify(() -> {
                    verify(runner).migrate(vertx);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("start() propagates a failed migration as a failed future")
    void startPropagatesFailure(Vertx vertx, VertxTestContext ctx) {
        MigrationRunner runner = mock(MigrationRunner.class);
        RuntimeException boom = new RuntimeException("migration failed");
        when(runner.migrate(vertx)).thenReturn(Future.failedFuture(boom));
        FlywayMigrationStartupStep step = new FlywayMigrationStartupStep(runner, vertx);

        step.start()
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertSame(boom, err);
                    assertEquals("migration failed", err.getMessage());
                    ctx.completeNow();
                })));
    }
}
