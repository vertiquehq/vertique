// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Architecture and dependency guards for the shared validation-support module.
 *
 * <p>The POM is parsed with the JDK's DOM parser and {@code src/main/java} is scanned line by line for
 * {@code import} statements, as the sibling schema-generation module's structure test does; the public
 * surface is read from the compiled classes. Surefire sets the working directory to the module basedir, so
 * every path below is resolved against {@code user.dir}.
 */
class ModuleStructureTest {

    /** The exact compile-scope dependency set: the framework core and the schema engine, nothing else. */
    private static final Set<String> ALLOWED_COMPILE_DEPENDENCIES =
            Set.of("dev.vertique:vertique-core", "io.vertx:vertx-json-schema");

    /** Import prefixes the module must never use: the REST modules and dependency injection. */
    private static final List<String> BANNED_MAIN_IMPORT_PREFIXES = List.of("dev.vertique.rest.", "dagger.");

    /** The package under test, as a directory below {@code target/classes}. */
    private static final Path PACKAGE_CLASSES =
            Path.of("target", "classes", "dev", "vertique", "json", "schema", "validation");

    @Test
    @DisplayName("pom.xml declares exactly vertique-core and vertx-json-schema at compile scope")
    void compileDependenciesAreExactlyTheCoreAndTheSchemaEngine() throws Exception {
        Path pom = Path.of(System.getProperty("user.dir"), "pom.xml");
        assertThat(pom).isRegularFile();

        assertThat(compileScopeDependencies(pom)).isEqualTo(ALLOWED_COMPILE_DEPENDENCIES);
    }

    @Test
    @DisplayName("main sources import nothing from the REST modules or Dagger")
    void mainSourcesImportNothingFromTheRestModulesOrDagger() throws IOException {
        Path sources = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertThat(sources).isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> javaFiles =
                Files.walk(sources).filter(path -> path.toString().endsWith(".java"))) {
            javaFiles.forEach(file -> collectBannedImports(file, violations));
        }

        assertThat(violations).as("banned imports in src/main/java").isEmpty();
    }

    @Test
    @DisplayName("the four reused format checks are package-private and nothing outside the guard reaches them")
    void theFormatChecksArePackagePrivate() throws ClassNotFoundException {
        for (String name : List.of("UriFormatChecks", "PointerFormatChecks", "UrlFormatCheck", "UriTemplateSyntax")) {
            Class<?> check = Class.forName("dev.vertique.json.schema.validation." + name);

            assertThat(Modifier.isPublic(check.getModifiers()))
                    .as(name + " is public")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the package exposes exactly the guard, the keyword vocabulary, the options, the pattern walk,"
            + " the path form and the detail builder as public, with exactly their public members")
    void publicSurfaceIsFrozen() throws IOException {
        Path packageDir = Path.of(System.getProperty("user.dir")).resolve(PACKAGE_CLASSES);
        assertThat(packageDir).as("compiled classes").isDirectory();

        Map<String, Set<String>> actual = new TreeMap<>();
        try (Stream<Path> classFiles =
                Files.list(packageDir).filter(path -> path.toString().endsWith(".class"))) {
            for (Path classFile : classFiles.sorted().toList()) {
                String fileName = classFile.getFileName().toString();
                String className = fileName.substring(0, fileName.length() - ".class".length());
                if (className.equals("package-info")) {
                    continue;
                }
                Class<?> type = loadClass("dev.vertique.json.schema.validation." + className);
                if (isPubliclyReachable(type)) {
                    actual.put(typeDescriptor(type), publicMembers(type));
                }
            }
        }

        assertThat(actual).isEqualTo(expectedSurface());
    }

    /**
     * The frozen surface: each public type, as {@link #typeDescriptor(Class)} spells it, mapped to its exact
     * public constructors, methods and fields as {@link #publicMembers(Class)} spells them.
     *
     * @return the expected surface
     */
    private static Map<String, Set<String>> expectedSurface() {
        Map<String, Set<String>> surface = new TreeMap<>();

        surface.put(
                "final class PatternInputGuard implements JsonFormatValidator",
                members(
                        "public static final String BOUND_FORMAT",
                        "public static final int DEFAULT_MAX_CHARS",
                        "public static final int DEFAULT_MAX_TOTAL_CHARS",
                        "public PatternInputGuard(int, int)",
                        "public static JsonObject rewrite(JsonObject)",
                        "public Validator compile(JsonObject, JsonSchemaOptions)",
                        "public PatternInputGuard.Window openWindow()",
                        "public String validateFormat(String, String, Object)"));
        surface.put(
                "interface PatternInputGuard.Window extends AutoCloseable", members("public abstract void close()"));
        surface.put(
                "static final class PatternInputGuard.PatternInputTooLong extends RuntimeException",
                members("public PatternInputTooLong(int)", "public int maxChars()"));
        surface.put(
                "static final class PatternInputGuard.PatternInputTotalExceeded extends RuntimeException",
                members("public PatternInputTotalExceeded(int)", "public int maxTotalChars()"));

        surface.put(
                "final class SchemaKeywords",
                members(
                        "public static final String PATTERN",
                        "public static final String PATTERN_PROPERTIES",
                        "public static final Set<String> LITERAL_KEYWORDS",
                        "public static final Set<String> NAMED_MEMBER_KEYWORDS",
                        "public static final Set<String> STRUCTURAL_KEYWORDS",
                        "public static String extractKeyword(String)",
                        "public static boolean isStructuralLocation(String)",
                        "public static String constraintKeyword(OutputUnit)"));

        surface.put(
                "final class JsonSchemaValidationOptions", members("public static JsonSchemaOptions draft202012()"));

        surface.put(
                "record PatternCompileFailure",
                members(
                        "public PatternCompileFailure(String, String, int)",
                        "public String pointer()",
                        "public String description()",
                        "public int index()",
                        "public final boolean equals(Object)",
                        "public final int hashCode()",
                        "public final String toString()"));

        surface.put(
                "final class SchemaPatterns",
                members("public static List<PatternCompileFailure> precompile(JsonObject)"));

        surface.put(
                "enum PathForm",
                members(
                        "public static final PathForm URI_FRAGMENT",
                        "public static final PathForm RFC6901",
                        "public static PathForm[] values()",
                        "public static PathForm valueOf(String)"));

        surface.put(
                "final class ViolationDetails",
                members(
                        "public static final String VALUE_FREE_MESSAGE",
                        "public static final String PATTERN_INPUT_LENGTH_TYPE",
                        "public static final String PATTERN_INPUT_TOTAL_LENGTH_TYPE",
                        "public static List<ViolationDetail> collect(OutputUnit, JsonObject, boolean, PathForm)",
                        "public static ViolationDetail valueFree(String)",
                        "public static String safeDetail(String, Map<String, Object>, String)",
                        "public static Optional<ViolationDetail> patternInputBound(Throwable)",
                        "public static String rfc6901(String)"));

        return surface;
    }

    private static Set<String> members(String... descriptors) {
        return new TreeSet<>(Set.of(descriptors));
    }

    // --- descriptors ---

    /**
     * Spells a public type: its modifiers that matter, its kind, its simple name qualified by enclosing
     * types, and its supertypes other than {@code Object}, {@code Record} and {@code Enum}.
     */
    private static String typeDescriptor(Class<?> type) {
        List<String> words = new ArrayList<>();
        boolean plainClass = !type.isInterface() && !type.isEnum() && !type.isRecord();
        if (plainClass && type.isMemberClass() && Modifier.isStatic(type.getModifiers())) {
            words.add("static");
        }
        if (plainClass && Modifier.isFinal(type.getModifiers())) {
            words.add("final");
        }
        words.add(type.isInterface() ? "interface" : type.isEnum() ? "enum" : type.isRecord() ? "record" : "class");
        words.add(nestedName(type));
        Class<?> parent = type.getSuperclass();
        if (parent != null && parent != Object.class && parent != Record.class && parent != Enum.class) {
            words.add("extends " + simpleName(parent));
        }
        String supertypes = Stream.of(type.getGenericInterfaces())
                .map(ModuleStructureTest::simpleName)
                .sorted()
                .collect(Collectors.joining(", "));
        if (!supertypes.isEmpty()) {
            words.add((type.isInterface() ? "extends " : "implements ") + supertypes);
        }
        return String.join(" ", words);
    }

    /**
     * Spells the public constructors, methods and fields declared directly on {@code type}, ignoring
     * synthetic and bridge members.
     */
    private static Set<String> publicMembers(Class<?> type) {
        Set<String> descriptors = new TreeSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers()) && !constructor.isSynthetic()) {
                descriptors.add("public " + type.getSimpleName() + "("
                        + parameters(constructor.getGenericParameterTypes()) + ")");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                descriptors.add(modifiers(method.getModifiers()) + " " + simpleName(method.getGenericReturnType()) + " "
                        + method.getName() + "(" + parameters(method.getGenericParameterTypes()) + ")");
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && !field.isSynthetic()) {
                descriptors.add(modifiers(field.getModifiers()) + " " + simpleName(field.getGenericType()) + " "
                        + field.getName());
            }
        }
        return descriptors;
    }

    private static String modifiers(int modifiers) {
        return Modifier.toString(modifiers & (Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT));
    }

    private static String parameters(Type[] types) {
        return Stream.of(types).map(ModuleStructureTest::simpleName).collect(Collectors.joining(", "));
    }

    /** A type's name with every package prefix removed and nested types joined by a dot. */
    private static String simpleName(Type type) {
        return type.getTypeName()
                .replaceAll("\\b(?:[a-z_][a-zA-Z0-9_]*\\.)+(?=[A-Z])", "")
                .replace('$', '.');
    }

    private static String nestedName(Class<?> type) {
        return type.getEnclosingClass() == null
                ? type.getSimpleName()
                : nestedName(type.getEnclosingClass()) + "." + type.getSimpleName();
    }

    private static boolean isPubliclyReachable(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Expected class " + name + " to be loadable", e);
        }
    }

    // --- pom and source scanning ---

    /**
     * Returns the {@code groupId:artifactId} of every dependency in the POM with no {@code <scope>} or an
     * explicit {@code compile} scope.
     */
    private static Set<String> compileScopeDependencies(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        document.getDocumentElement().normalize();

        Set<String> result = new TreeSet<>();
        NodeList dependencies = document.getElementsByTagName("dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String scope = childText(dependency, "scope");
            if (scope == null || "compile".equals(scope)) {
                result.add(childText(dependency, "groupId") + ":" + childText(dependency, "artifactId"));
            }
        }
        return result;
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static void collectBannedImports(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        for (int index = 0; index < lines.size(); index++) {
            String stripped = lines.get(index).strip();
            if (!stripped.startsWith("import ")) {
                continue;
            }
            String imported = stripped.substring("import ".length()).replace("static ", "");
            if (BANNED_MAIN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith)) {
                violations.add(file + ":" + (index + 1) + ": " + stripped);
            }
        }
    }
}
