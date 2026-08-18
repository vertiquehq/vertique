// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Architecture and dependency-isolation guards for {@code vertique-json-schema} (PRD-JSON-005
 * FR-JSON-070, NFR-JSON-017).
 *
 * <p>Follows the source-level dependency-boundary pattern established by {@code
 * dev.vertique.kafka.CoreDependencyBoundaryTest} and {@code
 * dev.vertique.rest.core.RestCoreHasNoOpenApiImportsTest}: the POM is parsed with the JDK's
 * built-in DOM parser (no {@code maven-model} test dependency), and {@code src/main/java} is
 * scanned line-by-line for {@code import} statements. Surefire sets the working directory to the
 * module basedir, so every path below is resolved relative to {@code user.dir}.
 */
class ModuleStructureTest {

    /**
     * The exact, frozen compile-scope dependency allowlist for {@code vertique-json-schema}
     * (PRD-JSON-005 §6.1). Test-scope entries (JUnit, {@code vertique-json},
     * {@code vertx-json-schema}) are deliberately excluded — they exist only to exercise the real
     * built-in {@code vertique-strict} profile and to pin validator wire-honesty, and are not part
     * of the artifact's production dependency contract.
     */
    private static final Set<String> ALLOWED_COMPILE_DEPENDENCIES = Set.of(
            "dev.vertique:vertique-core",
            "com.fasterxml.jackson.core:jackson-databind",
            "com.github.victools:jsonschema-generator",
            "com.github.victools:jsonschema-module-jackson",
            "com.github.victools:jsonschema-module-jakarta-validation",
            "com.github.victools:jsonschema-module-swagger-2",
            "jakarta.validation:jakarta.validation-api",
            "io.swagger.core.v3:swagger-annotations-jakarta");

    /**
     * Import-statement prefixes permitted in {@code src/main/java}. {@code com.fasterxml} covers
     * both Jackson databind and {@code com.fasterxml.classmate}, which Victools pulls in
     * transitively and the generator internals reference directly. {@code java.} covers every JDK
     * package the generator uses (reflection types, collections).
     */
    private static final List<String> ALLOWED_MAIN_IMPORT_PREFIXES = List.of(
            "com.github.victools.",
            "com.fasterxml.",
            "dev.vertique.core.",
            "jakarta.validation.",
            "io.swagger.",
            "java.");

    /**
     * Explicitly banned prefixes (FR-JSON-070: no REST, MCP, transport, validator-runtime,
     * telemetry, or enterprise type). Every one of these is already excluded by {@link
     * #ALLOWED_MAIN_IMPORT_PREFIXES} being an allowlist, not a denylist — this constant exists so
     * the forbidden surface named in the plan is documented and independently assertable.
     */
    private static final List<String> BANNED_MAIN_IMPORT_PREFIXES = List.of(
            "dev.vertique.rest.",
            "dev.vertique.mcp.",
            "io.vertx.",
            "dagger.",
            "jakarta.inject.",
            "io.micrometer.",
            "io.opentelemetry.");

    /** The only two types this module may expose publicly (PRD §6.2, plan §3a). */
    private static final Set<String> ALLOWED_PUBLIC_TYPES =
            Set.of("AnnotationJsonSchemaGenerator", "JsonSchemaGenerationException");

    // --- #1: dependency allowlist ---

    @Test
    @DisplayName("vertique-json-schema pom.xml declares exactly the PRD §6.1 compile-scope dependency set")
    void dependencyAllowlistIsExact() throws Exception {
        Path pom = Path.of(System.getProperty("user.dir"), "pom.xml");
        assertTrue(Files.isRegularFile(pom), "Expected pom.xml to exist at " + pom);

        Set<String> actual = compileScopeDependencies(pom);
        assertEquals(
                ALLOWED_COMPILE_DEPENDENCIES,
                actual,
                "vertique-json-schema compile-scope dependencies must be exactly the PRD §6.1 allowlist");
    }

    /**
     * Parses {@code pom.xml}'s top-level {@code <dependencies>} block and returns the
     * {@code groupId:artifactId} of every dependency with no {@code <scope>} element or an
     * explicit {@code compile} scope. This module's POM has no {@code <dependencyManagement>} and
     * no plugin-scoped {@code <dependency>} elements, so a flat {@code getElementsByTagName}
     * traversal is unambiguous; a future POM restructure that adds either would need a
     * path-aware traversal instead.
     *
     * @param pom the module's {@code pom.xml} path
     * @return the set of {@code groupId:artifactId} compile-scope dependencies
     * @throws Exception if the POM cannot be parsed
     */
    private static Set<String> compileScopeDependencies(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(pom.toFile());
        doc.getDocumentElement().normalize();

        Set<String> result = new HashSet<>();
        NodeList dependencyNodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < dependencyNodes.getLength(); i++) {
            Element dependency = (Element) dependencyNodes.item(i);
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String scope = childText(dependency, "scope");
            if (scope == null || "compile".equals(scope)) {
                result.add(groupId + ":" + artifactId);
            }
        }
        return result;
    }

    /**
     * Returns the text content of the first direct child element named {@code tagName}, or
     * {@code null} when no such child exists.
     *
     * @param parent  the element to search
     * @param tagName the direct child tag name to find
     * @return the child's trimmed text content, or {@code null} when absent
     */
    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    // --- #2: main-source import allowlist ---

    @Test
    @DisplayName("dev.vertique.json.schema main sources import only the PRD §6.1 allowed packages")
    void mainSourcesImportOnlyAllowedPackages() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> javaFiles =
                Files.walk(srcMain).filter(p -> p.toString().endsWith(".java"))) {
            javaFiles.forEach(file -> collectImportViolations(file, violations));
        }

        if (!violations.isEmpty()) {
            fail("dev.vertique.json.schema main sources must import only "
                    + ALLOWED_MAIN_IMPORT_PREFIXES
                    + " (FR-JSON-070: no REST, MCP, Vert.x, Dagger, jakarta.inject, Micrometer, or"
                    + " OpenTelemetry). Found " + violations.size() + " violation(s):\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Reads every {@code import} line in the given source file and appends a diagnostic entry to
     * {@code violations} for each import whose fully-qualified name does not start with an
     * allowed prefix.
     *
     * @param file       the {@code .java} source file to scan
     * @param violations the mutable diagnostics list to append to
     */
    private static void collectImportViolations(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            if (!stripped.startsWith("import ")) {
                continue;
            }
            String imported = stripped.substring("import ".length()).replace("static ", "");
            imported = imported.substring(0, imported.length() - 1); // strip trailing ';'
            boolean allowed = ALLOWED_MAIN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
            if (!allowed) {
                violations.add(file + ":" + (i + 1) + ": " + stripped);
            }
        }
    }

    @Test
    @DisplayName("dev.vertique.json.schema main sources contain none of the explicitly banned import prefixes")
    void mainSourcesContainNoBannedImports() {
        // mainSourcesImportOnlyAllowedPackages() already proves every import in src/main/java
        // starts with an allowed prefix. A banned import can therefore only ever reach the source
        // tree undetected if some allowed prefix were itself a prefix of a banned one — in which
        // case a banned import would incorrectly satisfy the allowlist check. Proving that never
        // happens makes the banned surface structurally unreachable without a second full file
        // walk: no source file scan can find what the allowlist already forecloses.
        List<String> reachable = new ArrayList<>();
        for (String banned : BANNED_MAIN_IMPORT_PREFIXES) {
            boolean coveredByAllowlist = ALLOWED_MAIN_IMPORT_PREFIXES.stream().anyMatch(banned::startsWith);
            if (coveredByAllowlist) {
                reachable.add(banned);
            }
        }

        if (!reachable.isEmpty()) {
            fail("the following banned prefixes are covered by an allowed prefix, so "
                    + "mainSourcesImportOnlyAllowedPackages() could not catch an import matching them: "
                    + reachable);
        }
    }

    // --- #3: frozen public surface ---

    @Test
    @DisplayName(
            "dev.vertique.json.schema exposes exactly AnnotationJsonSchemaGenerator and JsonSchemaGenerationException as public")
    void publicSurfaceIsFrozen() throws IOException {
        Path packageDir =
                Path.of(System.getProperty("user.dir"), "target", "classes", "dev", "vertique", "json", "schema");
        assertTrue(
                Files.isDirectory(packageDir),
                "Expected compiled classes at " + packageDir + " — run `mvn test-compile` first");

        Set<String> actualPublicTypes = new HashSet<>();
        List<String> signatureViolations = new ArrayList<>();

        try (Stream<Path> classFiles =
                Files.list(packageDir).filter(p -> p.toString().endsWith(".class"))) {
            for (Path classFile : classFiles.toList()) {
                String simpleName = classFile.getFileName().toString().replace(".class", "");
                if (simpleName.equals("package-info")) {
                    continue;
                }
                Class<?> type = loadClass("dev.vertique.json.schema." + simpleName);
                if (Modifier.isPublic(type.getModifiers())) {
                    actualPublicTypes.add(simpleName);
                    signatureViolations.addAll(victoolsOrClassmateSignatureLeaks(type));
                }
            }
        }

        assertEquals(
                ALLOWED_PUBLIC_TYPES,
                actualPublicTypes,
                "dev.vertique.json.schema must expose exactly AnnotationJsonSchemaGenerator and"
                        + " JsonSchemaGenerationException as public types");

        if (!signatureViolations.isEmpty()) {
            fail("No public member of dev.vertique.json.schema may expose a com.github.victools or"
                    + " com.fasterxml.classmate type. Found " + signatureViolations.size() + " violation(s):\n  "
                    + String.join("\n  ", signatureViolations));
        }
    }

    /**
     * Loads a class by fully-qualified name, wrapping the checked reflection exception so this
     * method can be used inside a stream pipeline.
     *
     * @param fqcn the fully-qualified class name
     * @return the loaded {@link Class}
     */
    private static Class<?> loadClass(String fqcn) {
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Expected class " + fqcn + " to be loadable", e);
        }
    }

    /**
     * Scans every public constructor, method, and field declared directly on {@code type} for a
     * parameter, return, field, or declared-exception type whose package starts with {@code
     * com.github.victools} or {@code com.fasterxml.classmate}.
     *
     * @param type the public type to scan
     * @return a diagnostic string per offending member; empty when the type's public surface is clean
     */
    private static List<String> victoolsOrClassmateSignatureLeaks(Class<?> type) {
        List<String> violations = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                checkExecutable(
                        type,
                        constructor,
                        constructor.getParameterTypes(),
                        constructor.getExceptionTypes(),
                        violations);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                List<Class<?>> checked = new ArrayList<>(List.of(method.getParameterTypes()));
                checked.add(method.getReturnType());
                checked.addAll(List.of(method.getExceptionTypes()));
                checkTypes(type, method, checked, violations);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                checkTypes(type, field, List.of(field.getType()), violations);
            }
        }
        return violations;
    }

    private static void checkExecutable(
            Class<?> owner,
            Executable executable,
            Class<?>[] parameterTypes,
            Class<?>[] exceptionTypes,
            List<String> violations) {
        List<Class<?>> checked = new ArrayList<>(List.of(parameterTypes));
        checked.addAll(List.of(exceptionTypes));
        checkTypes(owner, executable, checked, violations);
    }

    private static void checkTypes(Class<?> owner, Object member, List<Class<?>> types, List<String> violations) {
        for (Class<?> candidate : types) {
            String packageName = candidate.getPackageName();
            if (packageName.startsWith("com.github.victools") || packageName.startsWith("com.fasterxml.classmate")) {
                violations.add(owner.getSimpleName() + "." + member + " exposes " + candidate.getName());
            }
        }
    }

    // --- #4: sibling-module dependency isolation (NFR-JSON-017) ---

    /**
     * Markers whose presence anywhere in a sibling module's {@code pom.xml} text proves that
     * module has (directly) acquired part of the schema-generator family this feature introduces.
     * {@code com.github.victools} and {@code io.swagger} are groupId prefixes; the remainder are
     * artifact ids. A substring search is sufficient here — unlike the allowlist-exactness proof
     * in {@link #dependencyAllowlistIsExact()}, this is a negative "must never appear" check, and
     * neither sibling POM has any other occurrence of these strings (verified 2026-08-17).
     */
    private static final List<String> SCHEMA_GENERATOR_FAMILY_MARKERS = List.of(
            "com.github.victools", "io.swagger", "jakarta.validation-api", "vertx-json-schema", "vertique-json-schema");

    /**
     * NFR-JSON-017: a consumer of {@code vertique-json} alone must not inherit Victools, Swagger,
     * Jakarta Validation, or {@code vertx-json-schema} because of the schema-generation feature,
     * and {@code vertique-core} must gain no schema-generator or validator dependency.
     *
     * <p>This module's own tests are the natural place for this proof (per the plan's S9 scope):
     * both sibling POMs are read via a path relative to this module's basedir — Surefire always
     * sets the working directory to the module basedir, so {@code ../vertique-json/pom.xml} and
     * {@code ../vertique-core/pom.xml} are stable, not brittle, for this repository's flat
     * reactor-root module layout. Both POMs currently declare zero matching dependencies in any
     * scope, so the assertion is "contains none of the markers at all", matching the plan's S9
     * scope note verbatim.
     */
    @Test
    @DisplayName(
            "vertique-json and vertique-core do not depend on the Victools/Swagger/Jakarta-Validation schema-generator family (NFR-JSON-017)")
    void vertiqueJsonDoesNotInheritSchemaGeneratorFamily() throws IOException {
        Path jsonPom = Path.of(System.getProperty("user.dir"), "..", "vertique-json", "pom.xml")
                .normalize();
        Path corePom = Path.of(System.getProperty("user.dir"), "..", "vertique-core", "pom.xml")
                .normalize();
        assertTrue(Files.isRegularFile(jsonPom), "Expected sibling pom.xml to exist at " + jsonPom);
        assertTrue(Files.isRegularFile(corePom), "Expected sibling pom.xml to exist at " + corePom);

        assertNoMarkers(jsonPom, "vertique-json");
        assertNoMarkers(corePom, "vertique-core");
    }

    /**
     * Fails with a descriptive message naming every {@link #SCHEMA_GENERATOR_FAMILY_MARKERS}
     * string found in the given POM's raw text.
     *
     * @param pom          the sibling {@code pom.xml} to scan
     * @param moduleLabel  the module name to name in a failure message
     */
    private static void assertNoMarkers(Path pom, String moduleLabel) throws IOException {
        String contents = Files.readString(pom);
        List<String> found = SCHEMA_GENERATOR_FAMILY_MARKERS.stream()
                .filter(contents::contains)
                .toList();
        assertTrue(
                found.isEmpty(),
                moduleLabel + "/pom.xml must not reference any of " + SCHEMA_GENERATOR_FAMILY_MARKERS
                        + " (NFR-JSON-017); found: " + found);
    }
}
