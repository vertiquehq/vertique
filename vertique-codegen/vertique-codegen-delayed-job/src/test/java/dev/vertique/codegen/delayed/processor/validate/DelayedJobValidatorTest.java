// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.validate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DelayedJobValidator} compile-time checks for {@code @DelayedJobContract} interfaces:
 * contract shape and duplicate names (FR-CG007-004) and same-unit executor presence (FR-CG007-005).
 *
 * <p>FR-CG007-006 (executor payload mismatch) is not unit-tested with a fixture because the runtime
 * bound {@code DelayedJobExecutor<P, C extends DelayedJobClient<P>>} makes a mismatch a {@code javac}
 * error before the processor runs; the validator retains the check as defense-in-depth.
 */
class DelayedJobValidatorTest {

    private static final String DELIVER_CONTRACT = """
            package com.example;
            import dev.vertique.job.delayed.DelayedJobClient;
            import dev.vertique.job.delayed.DelayedJobContract;
            @DelayedJobContract(name = "deliver")
            public interface DeliverJob extends DelayedJobClient<String> {}
            """;

    @Test
    @DisplayName("FR-004: abstract class annotated @DelayedJobContract is rejected with must-be-interface message")
    void rejectsAbstractClassContract() {
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "deliver")
                public abstract class DeliverJob implements DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage("must be an interface");
    }

    @Test
    @DisplayName("FR-004: contract that does not extend DelayedJobClient is rejected")
    void rejectsContractNotExtendingClient() {
        JavaFileObject contract = SourceFiles.inline("com.example.BadJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "bad")
                public interface BadJob {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage("com.example.BadJob must extend DelayedJobClient<P>");
    }

    @Test
    @DisplayName("FR-004: contract extending raw DelayedJobClient (unresolvable P) is rejected")
    void rejectsUnresolvablePayload() {
        JavaFileObject contract = SourceFiles.inline("com.example.RawJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "raw")
                @SuppressWarnings({"rawtypes", "unchecked"})
                public interface RawJob extends DelayedJobClient {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage("Cannot resolve payload type P for com.example.RawJob");
    }

    @Test
    @DisplayName("FR-004: a contract declaring an extra abstract method is rejected")
    void rejectsContractWithExtraAbstractMethod() {
        JavaFileObject contract = SourceFiles.inline("com.example.ExtraJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "extra")
                public interface ExtraJob extends DelayedJobClient<String> {
                    void doExtra();
                }
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage(
                        "@DelayedJobContract com.example.ExtraJob must not declare or inherit methods other than DelayedJobClient's enqueue overloads (found 'doExtra')");
    }

    @Test
    @DisplayName("FR-004: an extra method inherited through an intermediate interface is rejected")
    void rejectsInheritedExtraMethod() {
        JavaFileObject base = SourceFiles.inline("com.example.BaseJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                public interface BaseJob extends DelayedJobClient<String> {
                    void inheritedExtra();
                }
                """);
        JavaFileObject contract = SourceFiles.inline("com.example.MyJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "inherited")
                public interface MyJob extends BaseJob {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), base, contract)
                .assertFailed()
                .assertErrorMessage(
                        "must not declare or inherit methods other than DelayedJobClient's enqueue overloads (found 'inheritedExtra')");
    }

    @Test
    @DisplayName("FR-004: a default method on the contract is rejected (generated/reflective parity)")
    void rejectsDefaultMethod() {
        JavaFileObject contract = SourceFiles.inline("com.example.DefaultJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "default")
                public interface DefaultJob extends DelayedJobClient<String> {
                    default String helper() { return "x"; }
                }
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertFailed()
                .assertErrorMessage(
                        "must not declare or inherit methods other than DelayedJobClient's enqueue overloads (found 'helper')");
    }

    @Test
    @DisplayName("FR-004: two contracts with the same name in one unit are rejected")
    void rejectsDuplicateName() {
        JavaFileObject a = SourceFiles.inline("com.example.JobA", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "dup")
                public interface JobA extends DelayedJobClient<String> {}
                """);
        JavaFileObject b = SourceFiles.inline("com.example.JobB", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "dup")
                public interface JobB extends DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(new DelayedJobContractProcessor(), a, b)
                .assertFailed()
                .assertErrorMessage("Duplicate @DelayedJobContract name 'dup' in this compilation unit");
    }

    @Test
    @DisplayName("FR-005: no same-unit executor warns by default")
    void warnsWhenNoExecutorByDefault() {
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", DELIVER_CONTRACT);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DelayedJobContractProcessor(), contract)
                .assertSuccess();

        assertTrue(
                hasWarning(result, "No DelayedJobExecutor for @DelayedJobContract com.example.DeliverJob"),
                "expected a missing-executor warning");
    }

    @Test
    @DisplayName("FR-005: no same-unit executor errors under requireExecutor option")
    void errorsWhenNoExecutorUnderStrictOption() {
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", DELIVER_CONTRACT);

        ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(),
                        Map.of(DelayedJobContractProcessor.OPTION_REQUIRE_EXECUTOR, "true"),
                        contract)
                .assertFailed()
                .assertErrorMessage("No DelayedJobExecutor for @DelayedJobContract com.example.DeliverJob");
    }

    @Test
    @DisplayName("FR-005: a matching same-unit executor suppresses the warning")
    void matchingExecutorSuppressesWarning() {
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", DELIVER_CONTRACT);
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

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                        new DelayedJobContractProcessor(), contract, executor)
                .assertSuccess();

        assertFalse(
                hasWarning(result, "No DelayedJobExecutor"),
                "a matching same-unit executor must suppress the missing-executor warning");
    }

    private static boolean hasWarning(ProcessorTestHarness.Result result, String substring) {
        return result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(d -> d.getMessage(null))
                .anyMatch(message -> message != null && message.contains(substring));
    }
}
