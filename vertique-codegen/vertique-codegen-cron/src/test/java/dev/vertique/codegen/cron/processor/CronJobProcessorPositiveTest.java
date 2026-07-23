// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Positive end-to-end check: a complete, valid @CronJob with all attributes set on a properly-
 * coupled service compiles cleanly with no diagnostics.
 */
class CronJobProcessorPositiveTest {

    @Test
    @DisplayName("complete valid @CronJob compiles clean with no diagnostics")
    void completeValidCronJob_succeedsWithNoDiagnostics() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                """
                                id = "close-stale-orders", \
                                cron = "0 0 0 * * *", \
                                mode = dev.vertique.job.cron.ExecutionMode.SINGLE_INSTANCE, \
                                timezone = "America/New_York", \
                                maxAttempts = 5, \
                                overlapPolicy = dev.vertique.job.cron.OverlapPolicy.SKIP, \
                                misfirePolicy = dev.vertique.job.cron.MisfirePolicy.FIRE_NOW\
                                """,
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertSuccess()
                .assertNoWarnings();
    }
}
