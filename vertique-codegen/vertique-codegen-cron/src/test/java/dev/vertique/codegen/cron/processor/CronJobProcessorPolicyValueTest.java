// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import com.google.testing.compile.Compilation;
import dev.vertique.codegen.test.ProcessorTestHarness;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Verifies policy-bound validation (blank id, maxAttempts, overlap/mode contradiction, timezone
 * resolution) and the timezone-warning behaviour required by FR-CG004-007.
 */
class CronJobProcessorPolicyValueTest {

    @Test
    @DisplayName("blank id -> error")
    void blankId_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"\", cron = \"0 0 * * * *\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("id must not be blank");
    }

    @Test
    @DisplayName("maxAttempts = 0 -> error")
    void maxAttemptsZero_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * * *\", maxAttempts = 0",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("maxAttempts must be >= 1");
    }

    @Test
    @DisplayName("overlapPolicy = QUEUE_ONE with mode = SINGLE_INSTANCE -> error")
    void queueOneWithSingleInstance_isError() {
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                """
                                id = "x", cron = "0 0 * * * *", \
                                mode = dev.vertique.job.cron.ExecutionMode.SINGLE_INSTANCE, \
                                overlapPolicy = dev.vertique.job.cron.OverlapPolicy.QUEUE_ONE\
                                """,
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertFailed()
                .assertErrorMessage("QUEUE_ONE is only valid with mode=EVERY_INSTANCE");
    }

    @Test
    @DisplayName("invalid timezone string -> warning, not error")
    void invalidTimezone_isWarningOnly() {
        Compilation compilation = ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        CronJobProcessorFixtures.contractWithServiceOperation(),
                        CronJobProcessorFixtures.implWithCronJobBody(
                                "id = \"x\", cron = \"0 0 * * * *\", timezone = \"Foo/Bar\"",
                                "@Override public io.vertx.core.Future<Void> closeStaleOrders() { return io.vertx.core.Future.succeededFuture(); }"))
                .assertSuccess()
                .compilation();

        boolean warningFound = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains("timezone") && msg.contains("Foo/Bar"));
        if (!warningFound) {
            throw new AssertionFailedError("Expected a WARNING diagnostic about the unresolved timezone 'Foo/Bar'");
        }
    }
}
