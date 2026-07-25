// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.aop.AopProcessor;
import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Cross-processor compile tests proving that processors sharing the same compilation can safely
 * generate common support types.
 */
class AllProcessorsCoexistTest {

    @Test
    void aspectInterceptedJaxRsParameterGeneratesWithoutDuplicateLiteral() {
        ProcessorTestHarness.run(
                        List.of(new AopProcessor(), new JaxRsPipelineProcessor()),
                        aspectAnnotation(),
                        runtimeParameterAnnotation(),
                        aspectInterceptedResource())
                .assertSuccess();
    }

    @Test
    void processorOrderDoesNotChangeGeneratedOutput() throws IOException {
        ProcessorTestHarness.Result forward = compile(new AopProcessor(), new JaxRsPipelineProcessor());
        ProcessorTestHarness.Result reverse = compile(new JaxRsPipelineProcessor(), new AopProcessor());

        forward.assertSuccess();
        reverse.assertSuccess();

        assertEquals(generatedSourceTree(forward), generatedSourceTree(reverse));
    }

    private static ProcessorTestHarness.Result compile(
            javax.annotation.processing.Processor first, javax.annotation.processing.Processor second) {
        return ProcessorTestHarness.run(
                List.of(first, second), aspectAnnotation(), runtimeParameterAnnotation(), aspectInterceptedResource());
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
