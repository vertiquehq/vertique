// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link AutoWireProcessor} generates {@code GeneratedDelayedJobsModule} for concrete
 * implementations of {@code DelayedJobExecutor<P, C>}.
 *
 * <p>Discovery is root-element-rooted: the scanner checks whether each concrete root-element type
 * is assignable to the erased {@code DelayedJobExecutor} interface via APT-layer type erasure.
 */
class AutoWireProcessorDelayedJobsTest {

    private static final String GENERATED_MODULE_FQN = "dev.vertique.examples.job.GeneratedDelayedJobsModule";

    private static final String DELAYED_JOB_EXECUTOR_INTERFACE = """
            package dev.vertique.job.delayed;

            public interface DelayedJobExecutor<P, C> {
                void execute(P payload, C context);
            }
            """;

    private static final String DELAYED_JOBS_QUALIFIER = """
            package dev.vertique.job.delayed.dagger;

            import java.lang.annotation.*;
            import jakarta.inject.Qualifier;

            @Qualifier
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            public @interface DelayedJobs {}
            """;

    @Test
    @DisplayName("DelayedJobExecutor impl with @Inject constructor generates @DelayedJobs multibinding")
    void delayedJobImpl_withInjectConstructor_generatesBinding() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.job.delayed.DelayedJobExecutor", DELAYED_JOB_EXECUTOR_INTERFACE),
                SourceFiles.inline("dev.vertique.job.delayed.dagger.DelayedJobs", DELAYED_JOBS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.job.DeliverWebhookJob", """
                        package dev.vertique.examples.job;

                        import dev.vertique.job.delayed.DelayedJobExecutor;
                        import jakarta.inject.Inject;

                        public class DeliverWebhookJob implements DelayedJobExecutor<String, Object> {
                            @Inject
                            DeliverWebhookJob() {}

                            @Override
                            public void execute(String payload, Object context) {}
                        }
                        """));

        result.assertSuccess();
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Module");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@IntoSet");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "deliverWebhookJobBinding");
        result.assertGeneratedSourceContains(GENERATED_MODULE_FQN, "DeliverWebhookJob");
    }

    @Test
    @DisplayName("DelayedJobExecutor impl without @Inject constructor is skipped with NOTE")
    void delayedJobImpl_noInjectConstructor_skipped() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.job.delayed.DelayedJobExecutor", DELAYED_JOB_EXECUTOR_INTERFACE),
                SourceFiles.inline("dev.vertique.job.delayed.dagger.DelayedJobs", DELAYED_JOBS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.job.DeliverWebhookJob", """
                        package dev.vertique.examples.job;

                        import dev.vertique.job.delayed.DelayedJobExecutor;

                        public class DeliverWebhookJob implements DelayedJobExecutor<String, Object> {
                            public DeliverWebhookJob() {}

                            @Override
                            public void execute(String payload, Object context) {}
                        }
                        """));

        result.assertSuccess();
        var generated = result.compilation().generatedSourceFile(GENERATED_MODULE_FQN);
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(), "Expected no module when impl has no @Inject constructor");
    }

    @Test
    @DisplayName("abstract DelayedJobExecutor impl is skipped")
    void abstractDelayedJobImpl_isSkipped() {
        var result = ProcessorTestHarness.run(
                new AutoWireProcessor(),
                SourceFiles.inline("dev.vertique.job.delayed.DelayedJobExecutor", DELAYED_JOB_EXECUTOR_INTERFACE),
                SourceFiles.inline("dev.vertique.job.delayed.dagger.DelayedJobs", DELAYED_JOBS_QUALIFIER),
                SourceFiles.inline("dev.vertique.examples.job.AbstractWebhookJob", """
                        package dev.vertique.examples.job;

                        import dev.vertique.job.delayed.DelayedJobExecutor;
                        import jakarta.inject.Inject;

                        public abstract class AbstractWebhookJob implements DelayedJobExecutor<String, Object> {
                            @Inject
                            AbstractWebhookJob() {}
                        }
                        """));

        result.assertSuccess();
        var generated = result.compilation().generatedSourceFile(GENERATED_MODULE_FQN);
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.isEmpty(), "Expected no module for abstract implementation");
    }
}
