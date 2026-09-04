// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palantir.javapoet.JavaFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** In-memory contract proof for nested annotation float/double equality and hashing. */
class NestedAnnotationFloatingPointEqualityTest {

    private static final String NESTED_ANNOTATION_FQN = "com.example.NestedMembers";
    private static final String OUTER_ANNOTATION_FQN = "com.example.OuterMembers";
    private static final String FACTORY_FQN = "com.example.NestedLiteralFixtures";
    private static final String LITERAL_FQN = "com.example.OuterMembers$CoreLiteral";
    private static final List<String> FIXTURE_FQNS = List.of(
            "com.example.NestedNaNFixture",
            "com.example.NestedPositiveZeroFixture",
            "com.example.NestedNegativeZeroFixture");

    @Test
    @DisplayName("nested float and double members preserve symmetric equality and matching hashes")
    void nestedFloatingPointMembersObeyAnnotationEqualityContract() throws Exception {
        CompiledFixture fixture = compileFixture();
        Class<?> outerAnnotationType = fixture.loader().loadClass(OUTER_ANNOTATION_FQN);
        Class<?> factoryType = fixture.loader().loadClass(FACTORY_FQN);
        Method nestedMember = outerAnnotationType.getMethod("nested");

        for (int i = 0; i < FIXTURE_FQNS.size(); i++) {
            Annotation expected = fixture.loader()
                    .loadClass(FIXTURE_FQNS.get(i))
                    .getAnnotation(outerAnnotationType.asSubclass(Annotation.class));
            Annotation literal = (Annotation) factoryType.getField("ROW_" + i).get(null);
            Annotation expectedNested = (Annotation) nestedMember.invoke(expected);
            Annotation literalNested = (Annotation) nestedMember.invoke(literal);

            assertTrue(literalNested.equals(expectedNested), "generated nested literal equals row " + i);
            assertTrue(expectedNested.equals(literalNested), "nested annotation equality is symmetric for row " + i);
            assertEquals(expectedNested.hashCode(), literalNested.hashCode(), "nested hashCode for row " + i);
            assertTrue(literal.equals(expected), "generated outer literal equals row " + i);
            assertTrue(expected.equals(literal), "outer annotation equality is symmetric for row " + i);
            assertEquals(expected.hashCode(), literal.hashCode(), "outer hashCode for row " + i);
        }

        Annotation positiveZero =
                (Annotation) nestedMember.invoke(factoryType.getField("ROW_1").get(null));
        Annotation negativeZero = (Annotation) nestedMember.invoke(fixture.loader()
                .loadClass(FIXTURE_FQNS.get(2))
                .getAnnotation(outerAnnotationType.asSubclass(Annotation.class)));
        assertFalse(positiveZero.equals(negativeZero), "+0.0 and -0.0 nested annotations must differ");
        assertFalse(negativeZero.equals(positiveZero), "nested equality must remain symmetric for signed zero");
    }

    private static CompiledFixture compileFixture() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("JDK compiler unavailable; this proof requires javac");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standard =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            InMemoryFileManager files = new InMemoryFileManager(standard);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of("--release", "21"),
                    null,
                    List.of(
                            source(NESTED_ANNOTATION_FQN, """
                                    package com.example;
                                    import java.lang.annotation.Retention;
                                    import java.lang.annotation.RetentionPolicy;
                                    @Retention(RetentionPolicy.RUNTIME)
                                    public @interface NestedMembers {
                                        float floatValue();
                                        double doubleValue();
                                    }
                                    """),
                            source(OUTER_ANNOTATION_FQN, """
                                    package com.example;
                                    import java.lang.annotation.Retention;
                                    import java.lang.annotation.RetentionPolicy;
                                    import java.lang.annotation.Target;
                                    import static java.lang.annotation.ElementType.TYPE;
                                    @Retention(RetentionPolicy.RUNTIME)
                                    @Target(TYPE)
                                    public @interface OuterMembers {
                                        NestedMembers nested();
                                    }
                                    """),
                            source(FIXTURE_FQNS.get(0), """
                                    package com.example;
                                    @OuterMembers(nested = @NestedMembers(
                                            floatValue = 0.0f / 0.0f, doubleValue = 0.0d / 0.0d))
                                    public final class NestedNaNFixture {}
                                    """),
                            source(FIXTURE_FQNS.get(1), """
                                    package com.example;
                                    @OuterMembers(nested = @NestedMembers(floatValue = 0.0f, doubleValue = 0.0d))
                                    public final class NestedPositiveZeroFixture {}
                                    """),
                            source(FIXTURE_FQNS.get(2), """
                                    package com.example;
                                    @OuterMembers(nested = @NestedMembers(floatValue = -0.0f, doubleValue = -0.0d))
                                    public final class NestedNegativeZeroFixture {}
                                    """)));
            task.setProcessors(List.of(new NestedLiteralProcessor()));
            if (!Boolean.TRUE.equals(task.call())) {
                throw new AssertionError("in-memory nested-literal fixture failed:\n" + diagnostics(diagnostics));
            }
            return new CompiledFixture(files.classBytes(), new ByteArrayClassLoader(files.classBytes()));
        }
    }

    private static JavaFileObject source(String fqn, String body) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + fqn.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return body;
            }
        };
    }

    private static String diagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .map(d -> "%s: %s".formatted(d.getKind(), d.getMessage(null)))
                .reduce("", (all, next) -> all + next + "\n");
    }

    private record CompiledFixture(Map<String, byte[]> classBytes, ClassLoader loader) {}

    private static final class NestedLiteralProcessor extends AbstractProcessor {

        private boolean generated;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.RELEASE_21;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (generated || roundEnv.processingOver()) {
                return false;
            }

            TypeElement outerAnnotation = processingEnv.getElementUtils().getTypeElement(OUTER_ANNOTATION_FQN);
            if (outerAnnotation == null) {
                return false;
            }

            Map<String, AnnotationMirror> mirrors = new LinkedHashMap<>();
            for (String fixtureFqn : FIXTURE_FQNS) {
                TypeElement fixture = processingEnv.getElementUtils().getTypeElement(fixtureFqn);
                if (fixture == null) {
                    return false;
                }
                fixture.getAnnotationMirrors().stream()
                        .filter(mirror -> ((TypeElement)
                                        mirror.getAnnotationType().asElement())
                                .getQualifiedName()
                                .contentEquals(OUTER_ANNOTATION_FQN))
                        .findFirst()
                        .ifPresent(mirror -> mirrors.put(fixtureFqn, mirror));
            }
            if (mirrors.size() != FIXTURE_FQNS.size()) {
                return false;
            }

            try {
                Elements elements = processingEnv.getElementUtils();
                JavaFile literal = AnnotationLiteralEmitter.emit(
                        outerAnnotation,
                        mirrors.get(FIXTURE_FQNS.get(0)),
                        elements,
                        processingEnv.getTypeUtils(),
                        "Core");
                writeSource(LITERAL_FQN, literal.toString());

                StringBuilder factory =
                        new StringBuilder("package com.example;\npublic final class NestedLiteralFixtures {\n");
                for (int i = 0; i < FIXTURE_FQNS.size(); i++) {
                    factory.append("public static final OuterMembers ROW_")
                            .append(i)
                            .append(" = new OuterMembers$CoreLiteral(")
                            .append(AnnotationLiteralEmitter.constructorArgs(
                                    mirrors.get(FIXTURE_FQNS.get(i)), elements, processingEnv.getTypeUtils()))
                            .append(");\n");
                }
                writeSource(FACTORY_FQN, factory.append("}\n").toString());
                generated = true;
            } catch (IOException e) {
                throw new IllegalStateException("cannot write in-memory nested literal sources", e);
            }
            return false;
        }

        private void writeSource(String fqn, String source) throws IOException {
            try (var writer = processingEnv.getFiler().createSourceFile(fqn).openWriter()) {
                writer.write(source);
            }
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, ByteArrayOutputStream> classOutputs = new LinkedHashMap<>();

        private InMemoryFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                JavaFileManager.Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (kind == JavaFileObject.Kind.CLASS) {
                classOutputs.put(className, bytes);
            }
            return new MemoryOutput(className, kind, bytes);
        }

        private Map<String, byte[]> classBytes() {
            Map<String, byte[]> bytes = new LinkedHashMap<>();
            classOutputs.forEach((name, output) -> bytes.put(name, output.toByteArray()));
            return bytes;
        }
    }

    private static final class MemoryOutput extends SimpleJavaFileObject {

        private final ByteArrayOutputStream bytes;

        private MemoryOutput(String className, Kind kind, ByteArrayOutputStream bytes) {
            super(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind);
            this.bytes = bytes;
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }

        @Override
        public ByteArrayInputStream openInputStream() {
            return new ByteArrayInputStream(bytes.toByteArray());
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {

        private final Map<String, byte[]> classBytes;

        private ByteArrayClassLoader(Map<String, byte[]> classBytes) {
            super(NestedAnnotationFloatingPointEqualityTest.class.getClassLoader());
            this.classBytes = new LinkedHashMap<>(classBytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classBytes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
