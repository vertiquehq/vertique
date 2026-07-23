// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import dev.vertique.codegen.dagger.DaggerModuleWriter;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * End-to-end compile validation of {@link DaggerModuleWriter} output: emits a module via the
 * writer and feeds the generated source through {@link ProcessorTestHarness} alongside real
 * Dagger annotations on the classpath. Catches package-name and modifier-visibility regressions
 * that snapshot-only tests in {@code DaggerModuleWriterTest} cannot detect.
 */
class DaggerModuleWriterCompileTest {

    private static final ClassName MODULE_NAME = ClassName.get("com.example", "GeneratedModule");

    @Test
    void bindsOptionalOf_emittedModule_compilesAgainstRealDaggerAnnotations() {
        ClassName optionalType = ClassName.get("com.example", "OptionalService");
        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addBindsOptionalOf(optionalType)
                .build();

        JavaFileObject optionalServiceStub = SourceFiles.inline("com.example.OptionalService", """
                        package com.example;
                        public interface OptionalService {}
                        """);
        JavaFileObject emitted = SourceFiles.inline("com.example.GeneratedModule", file.toString());

        ProcessorTestHarness.run(new NoOpProcessor(), optionalServiceStub, emitted)
                .assertSuccess();
    }

    @Test
    void intoSetProvides_emittedModule_compilesAgainstRealDaggerAnnotations() {
        ClassName produced = ClassName.get("com.example", "MyService");
        ClassName impl = ClassName.get("com.example", "MyServiceImpl");
        JavaFile file = DaggerModuleWriter.named(MODULE_NAME)
                .addIntoSetProvides(null, produced, "myService", impl)
                .build();

        JavaFileObject producedStub = SourceFiles.inline("com.example.MyService", """
                        package com.example;
                        public interface MyService {}
                        """);
        JavaFileObject implStub = SourceFiles.inline("com.example.MyServiceImpl", """
                        package com.example;
                        public class MyServiceImpl implements MyService {}
                        """);
        JavaFileObject emitted = SourceFiles.inline("com.example.GeneratedModule", file.toString());

        ProcessorTestHarness.run(new NoOpProcessor(), producedStub, implStub, emitted)
                .assertSuccess();
    }

    /** No-op processor; the actual validation is javac compiling the inputs. */
    static final class NoOpProcessor extends AbstractProcessor {
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            return false;
        }
    }
}
