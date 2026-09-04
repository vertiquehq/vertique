// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.meta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.palantir.javapoet.JavaFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;
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

/** In-memory contract proof for scalar annotation-literal rendering and equality. */
class AnnotationLiteralScalarKindsTest {

    private static final String ANNOTATION_FQN = "com.example.ScalarMembers";
    private static final String FIXTURES_FQN = "com.example.ScalarFixtures";
    private static final String FACTORY_FQN = "com.example.ScalarLiteralFixtures";
    private static final String LITERAL_FQN = "com.example.ScalarMembers$CoreLiteral";

    @Test
    @DisplayName("char, char[], float, and double members render and obey the Annotation equality contract")
    void rendersCharFloatAndDoubleMembersWithAnnotationContractEquality() throws Exception {
        CompiledFixture fixture = compileFixture();
        Class<?> annotationType = fixture.loader().loadClass(ANNOTATION_FQN);
        Class<?> literalType = fixture.loader().loadClass(LITERAL_FQN);
        Class<?> factoryType = fixture.loader().loadClass(FACTORY_FQN);

        Annotation liveAnnotation =
                fixture.loader().loadClass(FIXTURES_FQN).getAnnotation(annotationType.asSubclass(Annotation.class));
        List<ScalarValues> rows = List.of(
                new ScalarValues('x', 1.5f, -0.0d, new char[] {'x'}, new double[] {1.0d, -0.0d}),
                new ScalarValues(' ', Float.NaN, Double.NaN, new char[] {' '}, new double[] {Double.MIN_VALUE, -0.0d}),
                new ScalarValues('x', -0.0f, Double.MIN_VALUE, new char[] {'x'}, new double[] {Double.NaN, 1.0d}),
                new ScalarValues(' ', Float.MAX_VALUE, Double.POSITIVE_INFINITY, new char[] {' '}, new double[0]),
                new ScalarValues('\n', 1.5f, -0.0d, new char[] {'\n', '\r', '\t', 'x'}, new double[] {1.0d, -0.0d}));

        for (int i = 0; i < rows.size(); i++) {
            ScalarValues expectedValues = rows.get(i);
            Annotation expected = i == 0 ? liveAnnotation : annotationProxy(annotationType, expectedValues);
            Annotation literal = (Annotation) factoryType.getField("ROW_" + i).get(null);

            assertEquals(annotationType, literal.annotationType(), "row " + i + " annotation type");
            assertEquals(expectedValues.character(), invoke(literalType, literal, "character"), "row " + i + " char");
            assertArrayEquals(
                    expectedValues.characters(),
                    (char[]) invoke(literalType, literal, "characters"),
                    "row " + i + " chars");
            assertFloatBitsEqual(expectedValues.floatValue(), invoke(literalType, literal, "floatValue"), "row " + i);
            assertDoubleBitsEqual(
                    expectedValues.doubleValue(), invoke(literalType, literal, "doubleValue"), "row " + i);
            assertDoubleArrayBitsEqual(
                    expectedValues.doubles(), (double[]) invoke(literalType, literal, "doubles"), "row " + i);

            assertTrue(literal.equals(expected), "generated literal must equal the expected annotation for row " + i);
            assertTrue(expected.equals(literal), "annotation equality must be symmetric for row " + i);
            assertEquals(expected.hashCode(), literal.hashCode(), "annotation hashCode for row " + i);
        }
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
                    List.of(source(ANNOTATION_FQN, """
                                    package com.example;
                                    import java.lang.annotation.Retention;
                                    import java.lang.annotation.RetentionPolicy;
                                    import java.lang.annotation.Target;
                                    import static java.lang.annotation.ElementType.TYPE;
                                    @Retention(RetentionPolicy.RUNTIME)
                                    @Target(TYPE)
                                    public @interface ScalarMembers {
                                        char character();
                                        char[] characters();
                                        float floatValue();
                                        double doubleValue();
                                        double[] doubles();
                                    }
                                    """), source(FIXTURES_FQN, """
                                    package com.example;
                                    @ScalarMembers(character = 'x', floatValue = 1.5f,
                                            characters = {'x'}, doubleValue = -0.0d,
                                            doubles = {1.0d, -0.0d})
                                    public final class ScalarFixtures {}
                                    """)));
            task.setProcessors(List.of(new ScalarLiteralProcessor()));
            if (!Boolean.TRUE.equals(task.call())) {
                throw new AssertionError("in-memory scalar-literal fixture failed:\n" + diagnostics(diagnostics));
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

    private static Object invoke(Class<?> literalType, Annotation literal, String member) throws Exception {
        return literalType.getMethod(member).invoke(literal);
    }

    private static void assertFloatBitsEqual(float expected, Object actual, String row) {
        assertEquals(
                Float.floatToRawIntBits(expected),
                Float.floatToRawIntBits((Float) actual),
                "float bit pattern for " + row);
    }

    private static void assertDoubleBitsEqual(double expected, Object actual, String row) {
        assertEquals(
                Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits((Double) actual),
                "double bit pattern for " + row);
    }

    private static void assertDoubleArrayBitsEqual(double[] expected, double[] actual, String row) {
        assertEquals(expected.length, actual.length, "array length for " + row);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(
                    Double.doubleToRawLongBits(expected[i]),
                    Double.doubleToRawLongBits(actual[i]),
                    "array element " + i + " bit pattern for " + row);
        }
    }

    private static Annotation annotationProxy(Class<?> annotationType, ScalarValues values) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("character", values.character());
        members.put("characters", values.characters().clone());
        members.put("floatValue", values.floatValue());
        members.put("doubleValue", values.doubleValue());
        members.put("doubles", values.doubles().clone());
        return (Annotation) Proxy.newProxyInstance(
                annotationType.getClassLoader(), new Class<?>[] {annotationType}, (proxy, method, args) -> {
                    if (method.getName().equals("annotationType")) {
                        return annotationType;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> annotationEquals(annotationType, members, args[0]);
                            case "hashCode" -> annotationHashCode(annotationType, members);
                            case "toString" -> annotationType.getName() + members;
                            default -> throw new UnsupportedOperationException(method.toString());
                        };
                    }
                    Object value = members.get(method.getName());
                    return value instanceof char[] array
                            ? array.clone()
                            : value instanceof double[] array ? array.clone() : value;
                });
    }

    private static boolean annotationEquals(Class<?> annotationType, Map<String, Object> members, Object other)
            throws Exception {
        if (!(other instanceof Annotation annotation) || annotation.annotationType() != annotationType) {
            return false;
        }
        for (Method method : annotationType.getDeclaredMethods()) {
            Object expected = members.get(method.getName());
            Object actual = method.invoke(annotation);
            if (expected instanceof char[] expectedArray && actual instanceof char[] actualArray) {
                if (!java.util.Arrays.equals(expectedArray, actualArray)) {
                    return false;
                }
            } else if (expected instanceof double[] expectedArray && actual instanceof double[] actualArray) {
                if (!java.util.Arrays.equals(expectedArray, actualArray)) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static int annotationHashCode(Class<?> annotationType, Map<String, Object> members) {
        int hash = 0;
        for (Method method : annotationType.getDeclaredMethods()) {
            Object value = members.get(method.getName());
            int valueHash = value instanceof char[] array
                    ? java.util.Arrays.hashCode(array)
                    : value instanceof double[] array
                            ? java.util.Arrays.hashCode(array)
                            : value instanceof Character character
                                    ? character.hashCode()
                                    : value instanceof Float floatValue
                                            ? floatValue.hashCode()
                                            : value instanceof Double doubleValue
                                                    ? doubleValue.hashCode()
                                                    : value.hashCode();
            hash += (127 * method.getName().hashCode()) ^ valueHash;
        }
        return hash;
    }

    private record ScalarValues(
            char character, float floatValue, double doubleValue, char[] characters, double[] doubles) {}

    private record CompiledFixture(Map<String, byte[]> classBytes, ClassLoader loader) {}

    private static final class ScalarLiteralProcessor extends AbstractProcessor {

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
            TypeElement annotationType = processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN);
            TypeElement fixtureType = processingEnv.getElementUtils().getTypeElement(FIXTURES_FQN);
            if (annotationType == null || fixtureType == null) {
                return false;
            }
            AnnotationMirror liveMirror = fixtureType.getAnnotationMirrors().stream()
                    .filter(m -> ((TypeElement) m.getAnnotationType().asElement())
                            .getQualifiedName()
                            .contentEquals(ANNOTATION_FQN))
                    .findFirst()
                    .orElseThrow();
            try {
                JavaFile literal = AnnotationLiteralEmitter.emit(
                        annotationType,
                        liveMirror,
                        processingEnv.getElementUtils(),
                        processingEnv.getTypeUtils(),
                        "Core");
                writeSource(LITERAL_FQN, literal.toString());

                List<ScalarValues> rows = List.of(
                        new ScalarValues('x', 1.5f, -0.0d, new char[] {'x'}, new double[] {1.0d, -0.0d}),
                        new ScalarValues(
                                ' ', Float.NaN, Double.NaN, new char[] {' '}, new double[] {Double.MIN_VALUE, -0.0d}),
                        new ScalarValues(
                                'x', -0.0f, Double.MIN_VALUE, new char[] {'x'}, new double[] {Double.NaN, 1.0d}),
                        new ScalarValues(
                                ' ', Float.MAX_VALUE, Double.POSITIVE_INFINITY, new char[] {' '}, new double[0]),
                        new ScalarValues(
                                '\n', 1.5f, -0.0d, new char[] {'\n', '\r', '\t', 'x'}, new double[] {1.0d, -0.0d}));
                StringBuilder factory =
                        new StringBuilder("package com.example;\npublic final class ScalarLiteralFixtures {\n");
                for (int i = 0; i < rows.size(); i++) {
                    SyntheticMirror synthetic = syntheticMirror(annotationType, rows.get(i));
                    factory.append("public static final ScalarMembers ROW_")
                            .append(i)
                            .append(" = new ScalarMembers$CoreLiteral(")
                            .append(AnnotationLiteralEmitter.constructorArgs(
                                    synthetic.mirror(), synthetic.elements(), processingEnv.getTypeUtils()))
                            .append(");\n");
                }
                writeSource(FACTORY_FQN, factory.append("}\n").toString());
                generated = true;
            } catch (IOException e) {
                throw new IllegalStateException("cannot write in-memory scalar literal sources", e);
            }
            return false;
        }

        private void writeSource(String fqn, String source) throws IOException {
            try (var writer = processingEnv.getFiler().createSourceFile(fqn).openWriter()) {
                writer.write(source);
            }
        }

        private static SyntheticMirror syntheticMirror(TypeElement annotationType, ScalarValues values) {
            Map<ExecutableElement, AnnotationValue> members = new LinkedHashMap<>();
            for (ExecutableElement member : ElementFilter.methodsIn(annotationType.getEnclosedElements())) {
                Object raw =
                        switch (member.getSimpleName().toString()) {
                            case "character" -> values.character();
                            case "characters" -> {
                                List<AnnotationValue> arrayValues = new ArrayList<>();
                                for (char character : values.characters()) {
                                    arrayValues.add(annotationValue(character));
                                }
                                yield arrayValues;
                            }
                            case "floatValue" -> values.floatValue();
                            case "doubleValue" -> values.doubleValue();
                            case "doubles" -> {
                                List<AnnotationValue> arrayValues = new ArrayList<>();
                                for (double value : values.doubles()) {
                                    arrayValues.add(annotationValue(value));
                                }
                                yield arrayValues;
                            }
                            default ->
                                throw new IllegalArgumentException(
                                        member.getSimpleName().toString());
                        };
                members.put(member, annotationValue(raw));
            }
            AnnotationMirror mirror = mock(AnnotationMirror.class);
            when(mirror.getAnnotationType()).thenReturn((DeclaredType) annotationType.asType());
            // The processing environment supplies a real Elements implementation; only the
            // synthetic mirror lookup needs a deterministic test double.
            Elements syntheticElements = mock(Elements.class);
            doReturn(members).when(syntheticElements).getElementValuesWithDefaults(mirror);
            return new SyntheticMirror(mirror, syntheticElements);
        }

        private record SyntheticMirror(AnnotationMirror mirror, Elements elements) {}

        private static AnnotationValue annotationValue(Object raw) {
            AnnotationValue value = mock(AnnotationValue.class);
            when(value.getValue()).thenReturn(raw);
            return value;
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
            super(AnnotationLiteralScalarKindsTest.class.getClassLoader());
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
