// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies cron-expression syntax validation surfaces as compile-time {@code ERROR} diagnostics
 * via {@code new CronExpression(expr)}, with the parser's exact failure message preserved.
 */
class CronJobProcessorCronExpressionTest {

    @Test
    @DisplayName("blank cron expression -> error")
    void blankCron_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("cron");
    }

    @Test
    @DisplayName("wrong field count -> error with the parser message")
    void wrongFieldCount_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("exactly 6 fields");
    }

    @Test
    @DisplayName("out-of-bounds value -> error")
    void outOfBoundsValue_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 99 * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("out of bounds");
    }

    @Test
    @DisplayName("negative step -> error")
    void negativeStep_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0/-5 * * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("Step value must be positive");
    }

    @Test
    @DisplayName("valid cron expression -> no error")
    void validCron_succeeds() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertSuccess();
    }
}
