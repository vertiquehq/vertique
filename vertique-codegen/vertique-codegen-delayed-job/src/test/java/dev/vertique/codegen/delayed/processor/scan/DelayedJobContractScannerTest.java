// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.delayed.processor.DelayedJobContractModel;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DelayedJobContractScanner} reads {@code @DelayedJobContract} attributes and
 * resolves the {@code DelayedJobClient<P>} payload type. The scanner is exercised through a
 * capturing {@link AbstractProcessor} compiled by the {@link ProcessorTestHarness} against the real
 * {@code vertique-job-delayed} types on the test classpath.
 */
class DelayedJobContractScannerTest {

    @Test
    @DisplayName("scans @DelayedJobContract attributes and resolves payload type")
    void scansContractAttributesAndPayload() {
        CapturingProcessor processor = new CapturingProcessor();
        JavaFileObject contract = SourceFiles.inline("com.example.DeliverJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "deliver", maxAttempts = 5, queue = "webhooks", priority = 7)
                public interface DeliverJob extends DelayedJobClient<String> {}
                """);

        ProcessorTestHarness.run(processor, contract).assertSuccess();

        assertEquals(1, processor.models.size());
        DelayedJobContractModel model = processor.models.get(0);
        assertEquals("deliver", model.name());
        assertEquals(5, model.maxAttempts());
        assertEquals("webhooks", model.queue());
        assertEquals(7, model.priority());
        assertNotNull(model.payloadType());
        assertEquals("java.lang.String", model.payloadType().toString());
    }

    @Test
    @DisplayName("applies @DelayedJobContract attribute defaults")
    void appliesAttributeDefaults() {
        CapturingProcessor processor = new CapturingProcessor();
        JavaFileObject contract = SourceFiles.inline("com.example.MinimalJob", """
                package com.example;
                import dev.vertique.job.delayed.DelayedJobClient;
                import dev.vertique.job.delayed.DelayedJobContract;
                @DelayedJobContract(name = "minimal")
                public interface MinimalJob extends DelayedJobClient<Integer> {}
                """);

        ProcessorTestHarness.run(processor, contract).assertSuccess();

        DelayedJobContractModel model = processor.models.get(0);
        assertEquals("minimal", model.name());
        assertEquals(3, model.maxAttempts());
        assertEquals("default", model.queue());
        assertEquals(0, model.priority());
        assertEquals("java.lang.Integer", model.payloadType().toString());
    }

    /** Test processor that runs the scanner over every {@code @DelayedJobContract} and records the model. */
    @SupportedAnnotationTypes("dev.vertique.job.delayed.DelayedJobContract")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    static final class CapturingProcessor extends AbstractProcessor {

        final List<DelayedJobContractModel> models = new ArrayList<>();
        private DelayedJobContractScanner scanner;

        @Override
        public synchronized void init(ProcessingEnvironment env) {
            super.init(env);
            scanner = new DelayedJobContractScanner(new CodegenContext(env));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            TypeElement contractAnnotation =
                    processingEnv.getElementUtils().getTypeElement("dev.vertique.job.delayed.DelayedJobContract");
            if (contractAnnotation == null) {
                return false;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(contractAnnotation)) {
                if (element instanceof TypeElement type) {
                    models.add(scanner.scan(type));
                }
            }
            return false;
        }
    }
}
