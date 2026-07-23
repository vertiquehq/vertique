// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies within-class @CronJob#id uniqueness — two methods on the same impl class sharing an
 * id surface as a compile-time error on the second declaration.
 */
class CronJobProcessorDuplicateIdTest {

    @Test
    @DisplayName("two @CronJob methods on the same class with the same id -> error")
    void duplicateId_isError() {
        // Define a contract with TWO operations so both impl methods can match a contract method
        // and pass the @ServiceOperation check; the only failure should be the duplicate id.
        ProcessorTestHarness.run(
                        new CronJobProcessor(),
                        SourceFiles.inline("dev.vertique.examples.cron.TwoOpContract", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.services.ServiceContract;
                                import dev.vertique.services.ServiceOperation;
                                import io.vertx.core.Future;

                                @ServiceContract("two-op-service")
                                public interface TwoOpContract {
                                    @ServiceOperation("first-op")
                                    Future<Void> first();

                                    @ServiceOperation("second-op")
                                    Future<Void> second();
                                }
                                """),
                        SourceFiles.inline("dev.vertique.examples.cron.DuplicateService", """
                                package dev.vertique.examples.cron;

                                import dev.vertique.job.cron.CronJob;
                                import io.vertx.core.Future;

                                public class DuplicateService implements TwoOpContract {
                                    @CronJob(id = "shared", cron = "0 0 * * * *")
                                    @Override
                                    public Future<Void> first() { return Future.succeededFuture(); }

                                    @CronJob(id = "shared", cron = "0 0 * * * *")
                                    @Override
                                    public Future<Void> second() { return Future.succeededFuture(); }
                                }
                                """))
                .assertFailed()
                .assertErrorMessage("'shared' is duplicated");
    }
}
