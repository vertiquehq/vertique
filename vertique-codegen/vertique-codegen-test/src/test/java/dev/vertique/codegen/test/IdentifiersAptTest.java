// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import dev.vertique.codegen.support.Identifiers;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of {@link Identifiers#generatedClassName} against the real APT model. Mock-only
 * unit tests cannot detect the bug where {@link TypeElement#getSimpleName()} returns only the
 * innermost name and the helper fails to walk enclosing types.
 */
class IdentifiersAptTest {

    @Test
    void generatedClassName_realNestedType_walksEnclosingChain() {
        JavaFileObject source = SourceFiles.inline("com.example.Outer", """
                        package com.example;

                        public class Outer {
                            @Deprecated public static class Inner {}
                        }
                        """);

        ProcessorTestHarness.run(new GeneratedNameProbe(), source)
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.Outer_InnerProbe", "// generated");
    }

    /**
     * For every {@link Deprecated} type element it sees, resolves
     * {@link Identifiers#generatedClassName} with suffix {@code Probe} and writes a stub source
     * with that name. The integration test asserts the resolved name via the harness.
     */
    static final class GeneratedNameProbe extends AbstractProcessor {
        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(Deprecated.class.getName());
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            Filer filer = processingEnv.getFiler();
            for (Element e : env.getElementsAnnotatedWith(Deprecated.class)) {
                if (!(e instanceof TypeElement type)) continue;
                String name = Identifiers.generatedClassName(type, "Probe");
                String pkg = processingEnv
                        .getElementUtils()
                        .getPackageOf(type)
                        .getQualifiedName()
                        .toString();
                String fqn = pkg.isEmpty() ? name : pkg + "." + name;
                try (var w = filer.createSourceFile(fqn).openWriter()) {
                    w.write("package " + pkg + ";\n// generated\nclass " + name + " {}\n");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
            return false;
        }
    }
}
