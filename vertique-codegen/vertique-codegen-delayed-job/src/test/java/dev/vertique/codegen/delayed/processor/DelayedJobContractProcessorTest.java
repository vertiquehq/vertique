// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DelayedJobContractProcessor} runs cleanly over a well-formed
 * {@code @DelayedJobContract} interface. Emission and validation are covered by dedicated slices;
 * this test pins the scan pass and processor registration shape.
 */
class DelayedJobContractProcessorTest {

    @Test
    @DisplayName("a valid @DelayedJobContract with its executor compiles without errors or warnings")
    void validContractCompilesClean() {
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "deliver")
                public interface DeliverJob extends DelayedJobClient<String> {}
                """);
        JavaFileObject executor = SourceFiles.inline("com.example.DeliverExecutor", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobExecutor;
                import dev.vertique.job.JobContext;
                import io.vertx.core.Future;
                public class DeliverExecutor implements DelayedJobExecutor<String, DeliverJob> {
                    @Override
                    public Future<Void> execute(String payload, JobContext ctx) {
                        return Future.succeededFuture();
                    }
                }
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract, executor)
                .assertSuccess()
                .assertNoWarnings();
    }
}
