// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compiler-mirror truth table. Policy and Jakarta security types are fixture source under their
 * real FQNs so codegen-core does not depend on security-core. Retention cases that reflection
 * cannot see are asserted here, not copied onto the runtime test.
 */
class AccessPolicyAnnotationResolverTest {

    @Test
    @DisplayName("mirror selection matches the runtime truth table, including retention differences")
    void shouldMatchRuntimePolicySelectionForJavaMethodFamilies() throws Exception {
        Path dir = Files.createTempDirectory("policy-mirrors");
        try {
            Probe.ran = false;
            Probe.expect = Expect.FRESH;
            CompileResult fresh = compile(dir.resolve("fresh"), false, frameworkSources(), freshFixtures());
            assertTrue(fresh.success(), fresh.errors()::toString);
            assertTrue(Probe.failures.isEmpty(), Probe.failures::toString);

            CompileResult classRetained = compile(
                    dir.resolve("class-retained"),
                    true,
                    List.of(
                            source("jakarta.annotation.security.RolesAllowed", ROLES_ALLOWED),
                            source("fixture.ClassComposed", """
                            package fixture;
                            import jakarta.annotation.security.RolesAllowed;
                            import java.lang.annotation.Retention;
                            import java.lang.annotation.RetentionPolicy;
                            @Retention(RetentionPolicy.CLASS)
                            @RolesAllowed("admin")
                            public @interface ClassComposed {}
                            """)),
                    List.of());
            assertTrue(classRetained.success(), classRetained.errors()::toString);

            Probe.failures.clear();
            Probe.ran = false;
            Probe.expect = Expect.CLASS_VISIBLE;
            CompileResult classConsumer = compile(
                    dir.resolve("class-consumer"),
                    false,
                    frameworkSources(),
                    List.of(source("fixture.ClassConsumer", """
                            package fixture;
                            import dev.vertique.security.authz.AccessPolicy;
                            import dev.vertique.security.authz.RequiresPolicy;
                            import jakarta.annotation.security.PermitAll;
                            @RequiresPolicy(ClassConsumer.Hidden.class)
                            public class ClassConsumer {
                              @PermitAll
                              @fixture.ClassComposed
                              public interface Hidden extends AccessPolicy {}
                              public void op() {}
                            }
                            """)),
                    dir.resolve("class-retained"));
            assertTrue(classConsumer.success(), classConsumer.errors()::toString);
            assertTrue(Probe.failures.isEmpty(), Probe.failures::toString);

            CompileResult sourceRetained = compile(
                    dir.resolve("source-retained"),
                    true,
                    frameworkSources(),
                    List.of(
                            source("fixture.SourceComposed", """
                            package fixture;
                            import jakarta.annotation.security.RolesAllowed;
                            import java.lang.annotation.Retention;
                            import java.lang.annotation.RetentionPolicy;
                            @Retention(RetentionPolicy.SOURCE)
                            @RolesAllowed("admin")
                            public @interface SourceComposed {}
                            """),
                            source("fixture.PrecompiledPermit", """
                                    package fixture;
                                    import dev.vertique.security.authz.AccessPolicy;
                                    import jakarta.annotation.security.PermitAll;
                                    @PermitAll
                                    @SourceComposed
                                    public interface PrecompiledPermit extends AccessPolicy {}
                                    """),
                            source("fixture.PrecompiledEmpty", """
                                    package fixture;
                                    import dev.vertique.security.authz.AccessPolicy;
                                    @SourceComposed
                                    public interface PrecompiledEmpty extends AccessPolicy {}
                                    """)));
            assertTrue(sourceRetained.success(), sourceRetained.errors()::toString);

            Probe.failures.clear();
            Probe.ran = false;
            Probe.expect = Expect.SOURCE_ERASED;
            CompileResult sourceConsumer = compile(
                    dir.resolve("source-consumer"),
                    false,
                    List.of(),
                    List.of(source("fixture.SourceProbe", "package fixture; public class SourceProbe {}")),
                    dir.resolve("source-retained"));
            assertTrue(sourceConsumer.success(), sourceConsumer.errors()::toString);
            assertTrue(Probe.failures.isEmpty(), Probe.failures::toString);
        } finally {
            delete(dir);
        }
    }

    @Test
    @DisplayName("a static method corresponds to itself but not to a hidden static of the same name")
    void shouldMatchStaticOperationOnlyWithItself() throws Exception {
        Path dir = Files.createTempDirectory("policy-static-operation");
        try {
            givenStaticOperationProbe();
            CompileResult result = whenStaticOperationsAreCompiled(dir.resolve("static-operation"));
            thenStaticOperationCorrespondenceHolds(result);
        } finally {
            // The probe state is shared with the table test, so a finding must not leak into it.
            Probe.failures.clear();
            Probe.ran = false;
            Probe.expect = Expect.FRESH;
            delete(dir);
        }
    }

    private static void givenStaticOperationProbe() {
        Probe.failures.clear();
        Probe.ran = false;
        Probe.expect = Expect.STATIC_OPERATION;
    }

    private static CompileResult whenStaticOperationsAreCompiled(Path out) throws IOException {
        return compile(out, false, frameworkSources(), List.of(source("fixture.StaticShapes", STATIC_FIXTURES)));
    }

    private static void thenStaticOperationCorrespondenceHolds(CompileResult result) {
        assertTrue(result.success(), result.errors()::toString);
        assertTrue(Probe.ran, "the probe must have run");
        assertTrue(Probe.failures.isEmpty(), Probe.failures::toString);
    }

    private static List<JavaFileObject> frameworkSources() {
        return List.of(
                source("dev.vertique.security.authz.AccessPolicy", """
                        package dev.vertique.security.authz;
                        public interface AccessPolicy {}
                        """),
                source("dev.vertique.security.authz.RequiresPolicy", """
                        package dev.vertique.security.authz;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;
                        @Retention(RetentionPolicy.RUNTIME)
                        @Target({ElementType.TYPE, ElementType.METHOD})
                        public @interface RequiresPolicy { Class<? extends AccessPolicy> value(); }
                        """),
                source("dev.vertique.security.authz.Authorized", """
                        package dev.vertique.security.authz;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;
                        @Retention(RetentionPolicy.RUNTIME)
                        @Target({ElementType.TYPE, ElementType.METHOD})
                        public @interface Authorized {
                          String[] scopes() default {};
                          boolean matchAll() default true;
                        }
                        """),
                source("dev.vertique.security.authz.RequiresAction", """
                        package dev.vertique.security.authz;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;
                        @Retention(RetentionPolicy.RUNTIME)
                        @Target({ElementType.TYPE, ElementType.METHOD})
                        public @interface RequiresAction { String value(); }
                        """),
                source("jakarta.annotation.security.PermitAll", PERMIT_ALL),
                source("jakarta.annotation.security.DenyAll", DENY_ALL),
                source("jakarta.annotation.security.RolesAllowed", ROLES_ALLOWED));
    }

    private static List<JavaFileObject> freshFixtures() {
        return List.of(source("fixture.Shapes", FIXTURES));
    }

    private static CompileResult compile(
            Path out, boolean classesOnly, List<JavaFileObject> sources, List<JavaFileObject> extra)
            throws IOException {
        return compile(out, classesOnly, sources, extra, null);
    }

    private static CompileResult compile(
            Path out, boolean classesOnly, List<JavaFileObject> sources, List<JavaFileObject> extra, Path classpath)
            throws IOException {
        Files.createDirectories(out);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>();
        units.addAll(sources);
        units.addAll(extra);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add("21");
            options.add("-d");
            options.add(out.toString());
            if (classesOnly) {
                options.add("-proc:none");
            }
            if (classpath != null) {
                options.add("-classpath");
                options.add(classpath.toString());
            }
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, options, null, units);
            if (!classesOnly) {
                task.setProcessors(List.of(new Probe()));
            }
            boolean success = Boolean.TRUE.equals(task.call());
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                    .toList();
            return new CompileResult(success, errors);
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static JavaFileObject source(String fqn, String text) {
        return new InMemorySource(fqn, text);
    }

    private record CompileResult(boolean success, List<String> errors) {}

    private enum Expect {
        FRESH,
        CLASS_VISIBLE,
        SOURCE_ERASED,
        STATIC_OPERATION
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class Probe extends AbstractProcessor {

        static final List<String> failures = new ArrayList<>();
        static Expect expect = Expect.FRESH;
        static boolean ran;

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver() || ran) {
                return false;
            }
            ran = true;
            try {
                AccessPolicyAnnotationResolver resolver = new AccessPolicyAnnotationResolver(
                        processingEnv.getTypeUtils(), processingEnv.getElementUtils());
                if (expect == Expect.FRESH) {
                    checkFresh(resolver);
                } else if (expect == Expect.CLASS_VISIBLE) {
                    checkClassVisible(resolver);
                } else if (expect == Expect.STATIC_OPERATION) {
                    checkStaticOperation(resolver);
                } else {
                    checkSourceErased(resolver);
                }
            } catch (RuntimeException e) {
                failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return false;
        }

        private void checkFresh(AccessPolicyAnnotationResolver resolver) {
            TypeElement shapes = type("fixture.Shapes");
            assertMirror(resolver, "PermitPolicy", "jakarta.annotation.security.PermitAll");
            assertMirror(resolver, "DenyPolicy", "jakarta.annotation.security.DenyAll");
            assertThrows(resolver, "EmptyPolicy");
            assertThrows(resolver, "GenericPolicy");
            assertThrows(resolver, "UppercaseActionPolicy");
            assertThrows(resolver, "EmptySegmentActionPolicy");
            assertThrows(resolver, "TrailingDotActionPolicy");
            assertThrows(resolver, "RuntimeComposedPolicy");
            TypeElement permitHolder = nested(shapes, "RepeatA");
            TypeElement permitAgain = nested(shapes, "RepeatB");
            TypeElement denyHolder = nested(shapes, "DenyHolder");
            Optional<String> replaced = resolver.select(List.of(requires(denyHolder)), List.of(requires(permitHolder)));
            if (replaced.isEmpty() || !replaced.get().endsWith("DenyPolicy")) {
                failures.add("method policy did not replace the type policy: " + replaced);
            }
            Optional<String> coalesced =
                    resolver.select(List.of(), List.of(requires(permitHolder), requires(permitAgain)));
            if (coalesced.isEmpty() || !coalesced.get().endsWith("PermitPolicy")) {
                failures.add("identical references did not coalesce: " + coalesced);
            }
            assertSelectThrows(resolver, List.of(), List.of(requires(permitHolder), requires(denyHolder)));
            assertSelectThrows(resolver, List.of(requires(permitHolder), requires(denyHolder)), List.of());
            TypeElement consumer = nested(shapes, "Consumer");
            ExecutableElement inherited = method(nested(shapes, "Base"), "inherited");
            List<? extends javax.lang.model.element.AnnotationMirror> collected =
                    resolver.collectMethodAnnotations(consumer, inherited, List.of());
            if (collected.stream()
                    .noneMatch(mirror -> mirror.getAnnotationType().toString().endsWith("RequiresPolicy"))) {
                failures.add("consumer-rooted collection missed the interface policy");
            }
            ExecutableElement declaredOnBase = method(nested(shapes, "Base"), "inherited");
            List<? extends javax.lang.model.element.AnnotationMirror> declaringOnly =
                    resolver.collectMethodAnnotations(nested(shapes, "Base"), declaredOnBase, List.of());
            if (declaringOnly.stream()
                    .anyMatch(mirror -> mirror.getAnnotationType().toString().endsWith("RequiresPolicy"))) {
                failures.add("declaring class collected an interface policy it does not implement");
            }
        }

        private void checkStaticOperation(AccessPolicyAnnotationResolver resolver) {
            TypeElement shapes = type("fixture.StaticShapes");
            TypeElement base = nested(shapes, "Base");
            TypeElement sub = nested(shapes, "Sub");
            ExecutableElement subStatic = method(sub, "op");
            ExecutableElement baseStatic = method(base, "op");
            if (!resolver.corresponds(sub, subStatic, subStatic)) {
                failures.add("a static method must correspond to itself");
            }
            if (!resolver.corresponds(sub, baseStatic, baseStatic)) {
                failures.add("an inherited static method must correspond to itself");
            }
            if (resolver.corresponds(sub, subStatic, baseStatic)) {
                failures.add("a hidden static method of the same name must stay excluded");
            }
            if (resolver.corresponds(sub, baseStatic, subStatic)) {
                failures.add("a hiding static method must not become the hidden method's operation");
            }
            ExecutableElement subInstance = method(sub, "inst");
            ExecutableElement baseInstance = method(base, "inst");
            if (!resolver.corresponds(sub, subInstance, subInstance)
                    || !resolver.corresponds(sub, subInstance, baseInstance)) {
                failures.add("an instance method must correspond to itself and to the method it overrides");
            }
        }

        private void checkClassVisible(AccessPolicyAnnotationResolver resolver) {
            TypeElement hidden = nested(type("fixture.ClassConsumer"), "Hidden");
            try {
                resolver.resolve(hidden);
                failures.add("CLASS-retained composition stayed visible to mirrors and must be rejected");
            } catch (IllegalArgumentException expected) {
                // CLASS retention is visible to mirrors, unlike reflection.
            }
        }

        private void checkSourceErased(AccessPolicyAnnotationResolver resolver) {
            var mirrors = resolver.resolve(type("fixture.PrecompiledPermit"));
            if (mirrors.size() != 1
                    || !mirrors.get(0).getAnnotationType().toString().endsWith("PermitAll")) {
                failures.add("erased SOURCE composition changed the PermitAll result: " + mirrors);
            }
            assertThrows(resolver, type("fixture.PrecompiledEmpty"));
        }

        private void assertMirror(AccessPolicyAnnotationResolver resolver, String nested, String fqn) {
            var mirrors = resolver.resolve(nested(type("fixture.Shapes"), nested));
            if (mirrors.size() != 1
                    || !mirrors.get(0).getAnnotationType().toString().equals(fqn)) {
                failures.add(nested + " resolved to " + mirrors);
            }
        }

        private void assertThrows(AccessPolicyAnnotationResolver resolver, String nested) {
            assertThrows(resolver, nested(type("fixture.Shapes"), nested));
        }

        private void assertThrows(AccessPolicyAnnotationResolver resolver, TypeElement policy) {
            try {
                resolver.resolve(policy);
                failures.add("expected rejection of " + policy.getQualifiedName());
            } catch (IllegalArgumentException expected) {
                // Invalid policy.
            }
        }

        private void assertSelectThrows(
                AccessPolicyAnnotationResolver resolver,
                List<? extends javax.lang.model.element.AnnotationMirror> method,
                List<? extends javax.lang.model.element.AnnotationMirror> type) {
            try {
                resolver.select(method, type);
                failures.add("expected select rejection");
            } catch (IllegalArgumentException expected) {
                // Distinct references.
            }
        }

        private javax.lang.model.element.AnnotationMirror requires(TypeElement element) {
            return element.getAnnotationMirrors().stream()
                    .filter(mirror -> mirror.getAnnotationType().toString().endsWith("RequiresPolicy"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing RequiresPolicy on " + element));
        }

        private TypeElement type(String fqn) {
            TypeElement element = processingEnv.getElementUtils().getTypeElement(fqn);
            if (element == null) {
                throw new AssertionError("missing " + fqn);
            }
            return element;
        }

        private TypeElement nested(TypeElement parent, String simple) {
            return ElementFilter.typesIn(parent.getEnclosedElements()).stream()
                    .filter(element -> element.getSimpleName().contentEquals(simple))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + simple));
        }

        private ExecutableElement method(Element owner, String name) {
            return ElementFilter.methodsIn(owner.getEnclosedElements()).stream()
                    .filter(element -> element.getSimpleName().contentEquals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + name));
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        private InMemorySource(String className, String source) {
            super(
                    URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final String PERMIT_ALL = """
            package jakarta.annotation.security;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface PermitAll {}
            """;

    private static final String DENY_ALL = """
            package jakarta.annotation.security;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface DenyAll {}
            """;

    private static final String ROLES_ALLOWED = """
            package jakarta.annotation.security;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface RolesAllowed { String[] value(); }
            """;

    private static final String FIXTURES = """
            package fixture;
            import dev.vertique.security.authz.AccessPolicy;
            import dev.vertique.security.authz.RequiresAction;
            import dev.vertique.security.authz.RequiresPolicy;
            import jakarta.annotation.security.DenyAll;
            import jakarta.annotation.security.PermitAll;
            import jakarta.annotation.security.RolesAllowed;
            public class Shapes {
              @PermitAll public interface PermitPolicy extends AccessPolicy {}
              @DenyAll public interface DenyPolicy extends AccessPolicy {}
              public interface EmptyPolicy extends AccessPolicy {}
              public interface GenericPolicy<T> extends AccessPolicy {}
              @RequiresAction("A.b.c") public interface UppercaseActionPolicy extends AccessPolicy {}
              @RequiresAction("a..c") public interface EmptySegmentActionPolicy extends AccessPolicy {}
              @RequiresAction("a.b.") public interface TrailingDotActionPolicy extends AccessPolicy {}
              @RequiresAction("cms.content.read") public interface ValidActionPolicy extends AccessPolicy {}
              @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
              @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
              @RolesAllowed("admin")
              public @interface RuntimeComposed {}
              @RuntimeComposed public interface RuntimeComposedPolicy extends AccessPolicy {}
              @RequiresPolicy(PermitPolicy.class) public static class RepeatA {}
              @RequiresPolicy(PermitPolicy.class) public static class RepeatB {}
              @RequiresPolicy(DenyPolicy.class) public static class DenyHolder {}
              public static class Base { public String inherited() { return "ok"; } }
              public static class Consumer extends Base implements Secured {}
              public interface Secured {
                @RequiresPolicy(Shapes.Admin.class) String inherited();
              }
              @RolesAllowed("admin") public interface Admin extends AccessPolicy {}
            }
            """;

    private static final String STATIC_FIXTURES = """
            package fixture;
            public class StaticShapes {
              public static class Base {
                public static String op() { return "base"; }
                public String inst() { return "base"; }
              }
              public static class Sub extends Base {
                public static String op() { return "sub"; }
                @Override public String inst() { return "sub"; }
              }
            }
            """;
}
