// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.TypeResolver;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of {@link TypeResolver#resolveTypeArgument} against the real APT model. Verifies
 * that array type arguments (e.g., {@code Handler<byte[]>}) are returned, mirroring the behavior
 * of the runtime sibling {@link dev.vertique.core.util.TypeResolver#resolveTypeArgument}.
 */
class TypeResolverAptTest {

    @Test
    void resolveTypeArgument_arrayArg_returnsArrayMirror() {
        JavaFileObject handlerInterface = SourceFiles.inline("com.example.Handler", """
                        package com.example;
                        public interface Handler<T> {}
                        """);
        JavaFileObject impl = SourceFiles.inline("com.example.ByteHandler", """
                        package com.example;
                        @Deprecated
                        public class ByteHandler implements Handler<byte[]> {}
                        """);

        ProcessorTestHarness.run(new ResolveProbe("com.example.Handler"), handlerInterface, impl)
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.ByteHandlerProbe", "// arg=byte[]");
    }

    /**
     * Probe processor: for every {@link Deprecated} type, resolves the type argument of a target
     * interface and writes a stub source whose comment encodes the resolved {@link TypeMirror}.
     */
    static final class ResolveProbe extends AbstractProcessor {
        private final String targetInterfaceFqn;

        ResolveProbe(String targetInterfaceFqn) {
            this.targetInterfaceFqn = targetInterfaceFqn;
        }

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
            CodegenContext ctx = new CodegenContext(processingEnv);
            TypeResolver resolver = ctx.typeResolver();
            TypeElement target = ctx.elements().getTypeElement(targetInterfaceFqn);
            Filer filer = processingEnv.getFiler();

            for (Element e : env.getElementsAnnotatedWith(Deprecated.class)) {
                if (!(e instanceof TypeElement type)) continue;
                TypeMirror resolved =
                        resolver.resolveTypeArgument(type.asType(), target, 0).orElse(null);
                String resolvedDesc = resolved == null ? "<unresolved>" : resolved.toString();
                String fqn = type.getQualifiedName().toString() + "Probe";
                String simpleName = type.getSimpleName().toString() + "Probe";
                String pkg = processingEnv
                        .getElementUtils()
                        .getPackageOf(type)
                        .getQualifiedName()
                        .toString();
                try (var w = filer.createSourceFile(fqn).openWriter()) {
                    w.write("package " + pkg + ";\n// arg=" + resolvedDesc + "\nclass " + simpleName + " {}\n");
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
            return false;
        }
    }
}
