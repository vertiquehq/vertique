// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.aop.AopProcessor;
import dev.vertique.codegen.application.VertiqueAppProcessor;
import dev.vertique.codegen.cron.processor.CronJobProcessor;
import dev.vertique.codegen.dagger.processor.AutoWireProcessor;
import dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor;
import dev.vertique.codegen.events.EventsProcessor;
import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor;
import dev.vertique.codegen.rest.client.processor.RestClientProcessor;
import dev.vertique.codegen.sanitization.processor.SanitizationProcessor;
import dev.vertique.codegen.services.processor.ServiceContractProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Cross-processor compile tests proving that processors sharing the same compilation can safely
 * generate common support types.
 */
class AllProcessorsCoexistTest {

    private static final String AOP_LITERAL = "dev.vertique.test.SharedParameter$AopLiteral";
    private static final String JAXRS_LITERAL = "dev.vertique.test.SharedParameter$JaxRsLiteral";

    @Test
    void aspectInterceptedJaxRsParameterGeneratesWithoutDuplicateLiteral() {
        ProcessorTestHarness.Result result = compile(processors());

        result.assertSuccess();
        assertOwnedLiteralsGenerated(result);
    }

    @Test
    void processorOrderDoesNotChangeGeneratedOutput() throws IOException {
        ProcessorTestHarness.Result forward = compile(processors());
        ProcessorTestHarness.Result reverse = compile(processors().reversed());

        forward.assertSuccess();
        reverse.assertSuccess();
        assertOwnedLiteralsGenerated(forward);
        assertOwnedLiteralsGenerated(reverse);

        assertEquals(generatedSourceTree(forward), generatedSourceTree(reverse));
    }

    private static void assertOwnedLiteralsGenerated(ProcessorTestHarness.Result result) {
        result.loadGeneratedClass(AOP_LITERAL);
        result.loadGeneratedClass(JAXRS_LITERAL);
    }

    private static List<Processor> processors() {
        return List.of(
                new AopProcessor(),
                new VertiqueAppProcessor(),
                new CronJobProcessor(),
                new AutoWireProcessor(),
                new DelayedJobContractProcessor(),
                new EventsProcessor(),
                new JaxRsPipelineProcessor(),
                new KafkaConsumerProcessor(),
                new RestClientProcessor(),
                new SanitizationProcessor(),
                new ServiceContractProcessor(),
                new WorkflowContractProcessor());
    }

    private static ProcessorTestHarness.Result compile(List<Processor> processors) {
        return ProcessorTestHarness.run(
                processors, aspectAnnotation(), runtimeParameterAnnotation(), aspectInterceptedResource());
    }

    private static Map<String, String> generatedSourceTree(ProcessorTestHarness.Result result) throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (JavaFileObject source : result.compilation().generatedSourceFiles()) {
            sources.put(source.toUri().getPath(), source.getCharContent(false).toString());
        }
        return sources;
    }

    private static JavaFileObject aspectAnnotation() {
        return SourceFiles.inline("dev.vertique.test.Intercepted", """
                package dev.vertique.test;

                import dev.vertique.aop.Aspect;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Aspect(ordering = 1000)
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Intercepted {}
                """);
    }

    private static JavaFileObject runtimeParameterAnnotation() {
        return SourceFiles.inline("dev.vertique.test.SharedParameter", """
                package dev.vertique.test;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target(ElementType.PARAMETER)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface SharedParameter {
                    String value();
                }
                """);
    }

    private static JavaFileObject aspectInterceptedResource() {
        return SourceFiles.inline("dev.vertique.test.GreetingResource", """
                package dev.vertique.test;

                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;

                @Path("/greetings")
                public class GreetingResource {
                    @Inject
                    public GreetingResource() {}

                    @POST
                    @Intercepted
                    public Future<String> greet(@SharedParameter("request-body") String name) {
                        return Future.succeededFuture("Hello " + name);
                    }
                }
                """);
    }
}
