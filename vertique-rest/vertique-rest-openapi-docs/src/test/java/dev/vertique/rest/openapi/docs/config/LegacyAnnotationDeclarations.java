// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Application interfaces compiled against a {@code @ApiDocs} that has the removed {@code access} and
 * {@code rolesAllowed} elements and no {@code policy}, then loaded against the real annotation, as an
 * application compiled before the migration and run on the migrated framework is.
 *
 * <p>The compiler is given the old annotation as a source file, which shadows the real annotation of
 * the class path for that compilation, so the class files record the old elements. The class loader
 * is parent-first, so the loaded interfaces resolve {@code @ApiDocs} to the real annotation, and the
 * stale old annotation class is deleted from the output directory.
 */
final class LegacyAnnotationDeclarations {

    /** The binary name of the interface declaring {@code @ApiDocs(access = PUBLIC)}. */
    static final String PUBLIC_DECLARATION = "legacy.compiled.LegacyPublicApi";

    /** The binary name of the interface declaring a protected document with a scheme and a role. */
    static final String PROTECTED_DECLARATION = "legacy.compiled.LegacyProtectedApi";

    /**
     * The binary name of the interface whose class file records {@code policy} as a string, an
     * intermediate shape the real annotation reads as a type mismatch.
     */
    static final String MISMATCHED_DECLARATION = "legacy.compiled.MismatchedPolicyApi";

    private static final String OLD_ANNOTATION = """
            package dev.vertique.rest.openapi.docs;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Documented
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface ApiDocs {
                Access access();
                String securityScheme() default "";
                String[] rolesAllowed() default {};
                enum Access { PUBLIC, PROTECTED }
            }
            """;

    private static final String PUBLIC_API = """
            package legacy.compiled;

            import dev.vertique.rest.core.application.RestApplication;
            import dev.vertique.rest.openapi.docs.ApiDocs;

            @ApiDocs(access = ApiDocs.Access.PUBLIC)
            @RestApplication(name = "ops", path = "/api/ops")
            public interface LegacyPublicApi {}
            """;

    private static final String PROTECTED_API = """
            package legacy.compiled;

            import dev.vertique.rest.core.application.RestApplication;
            import dev.vertique.rest.openapi.docs.ApiDocs;

            @ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = "bearerAuth", rolesAllowed = {"admin"})
            @RestApplication(name = "ops", path = "/api/ops")
            public interface LegacyProtectedApi {}
            """;

    private static final String MISMATCHED_ANNOTATION = """
            package dev.vertique.rest.openapi.docs;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Documented
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface ApiDocs {
                String policy();
                String securityScheme() default "";
                enum Access { PUBLIC, PROTECTED }
            }
            """;

    private static final String MISMATCHED_API = """
            package legacy.compiled;

            import dev.vertique.rest.core.application.RestApplication;
            import dev.vertique.rest.openapi.docs.ApiDocs;

            @ApiDocs(policy = "legacy.Policy", securityScheme = "bearerAuth")
            @RestApplication(name = "ops", path = "/api/ops")
            public interface MismatchedPolicyApi {}
            """;

    private LegacyAnnotationDeclarations() {}

    /**
     * Compiles both interfaces against the old annotation into {@code workDirectory} and returns a
     * parent-first class loader over the output.
     *
     * @param workDirectory an empty directory the caller removes
     * @return the class loader, which the caller closes
     * @throws IOException when a source cannot be written or the output cannot be opened
     */
    static URLClassLoader compile(Path workDirectory) throws IOException {
        return compile(
                workDirectory, OLD_ANNOTATION, "LegacyPublicApi", PUBLIC_API, "LegacyProtectedApi", PROTECTED_API);
    }

    /**
     * Compiles one interface against an annotation whose {@code policy} is a string, then returns a
     * parent-first class loader over the output, so reading the policy of the loaded interface fails
     * with a type mismatch instead of a missing member.
     *
     * @param workDirectory an empty directory the caller removes
     * @return the class loader, which the caller closes
     * @throws IOException when a source cannot be written or the output cannot be opened
     */
    static URLClassLoader compileMismatched(Path workDirectory) throws IOException {
        return compile(workDirectory, MISMATCHED_ANNOTATION, "MismatchedPolicyApi", MISMATCHED_API);
    }

    private static URLClassLoader compile(Path workDirectory, String annotation, String... namesAndSources)
            throws IOException {
        Path sources = workDirectory.resolve("src");
        Path output = workDirectory.resolve("classes");
        Files.createDirectories(output);
        List<Path> files = new ArrayList<>();
        files.add(write(sources, "dev/vertique/rest/openapi/docs/ApiDocs.java", annotation));
        for (int i = 0; i < namesAndSources.length; i += 2) {
            files.add(write(sources, "legacy/compiled/" + namesAndSources[i] + ".java", namesAndSources[i + 1]));
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("the test needs a JDK, not a JRE: no system Java compiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled = compiler.getTask(
                            null,
                            fileManager,
                            diagnostics,
                            List.of("-proc:none", "-d", output.toString(), "-classpath", testClassPath()),
                            null,
                            fileManager.getJavaFileObjectsFromPaths(files))
                    .call();
            if (!compiled) {
                StringBuilder message = new StringBuilder("the legacy declarations did not compile:");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    message.append('\n').append(diagnostic);
                }
                throw new IllegalStateException(message.toString());
            }
        }
        Path stale = output.resolve("dev/vertique/rest/openapi/docs");
        try (var staleClasses = Files.walk(stale)) {
            for (Path file : staleClasses.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        return new URLClassLoader(
                new URL[] {output.toUri().toURL()}, LegacyAnnotationDeclarations.class.getClassLoader());
    }

    /** The class path the tests run with: surefire's real one when it launches through a manifest-only jar. */
    private static String testClassPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private static Path write(Path root, String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }
}
