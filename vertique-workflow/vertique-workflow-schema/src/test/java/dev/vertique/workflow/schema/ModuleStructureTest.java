// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Architecture and dependency guards for the schema module.
 *
 * <p>The POM is parsed with the JDK's DOM parser and {@code src/main/java} is scanned line by line, as the sibling
 * validation-support module's structure test does. Surefire sets the working directory to the module basedir, so
 * every path below is resolved against {@code user.dir}.
 */
class ModuleStructureTest {

    /** The exact compile-scope dependency set. */
    private static final Set<String> ALLOWED_COMPILE_DEPENDENCIES = Set.of(
            "dev.vertique:vertique-workflow-core",
            "dev.vertique:vertique-json-schema",
            "dev.vertique:vertique-json-schema-validation",
            "dev.vertique:vertique-json",
            "dev.vertique:vertique-core",
            "io.vertx:vertx-json-schema",
            "io.vertx:vertx-core",
            "com.fasterxml.jackson.core:jackson-databind",
            "com.google.dagger:dagger",
            "jakarta.inject:jakarta.inject-api",
            "jakarta.annotation:jakarta.annotation-api",
            "jakarta.validation:jakarta.validation-api",
            "org.slf4j:slf4j-api");

    /**
     * Import prefixes the module must never use: the REST modules, and the modules that depend on this one's SPI
     * rather than the other way round (engine, task composition, persistence, document definitions).
     */
    private static final List<String> BANNED_IMPORT_PREFIXES = List.of(
            "dev.vertique.rest.",
            "dev.vertique.workflow.engine.",
            "dev.vertique.workflow.tasks.compose.",
            "dev.vertique.workflow.tasks.di.",
            "dev.vertique.workflow.postgresql.",
            "dev.vertique.workflow.definition.");

    /** Imports banned by exact name: the process-wide codecs, which would bypass the profile registry. */
    private static final List<String> BANNED_IMPORTS =
            List.of("io.vertx.core.json.Json", "dev.vertique.core.json.VertiqueJson");

    /** Source text the module must never contain outside comments. */
    private static final List<String> BANNED_SOURCE_TEXT = List.of("Json.CODEC", "VertiqueJson.mapper(");

    @Test
    @DisplayName("pom.xml declares exactly the thirteen expected compile-scope dependencies")
    void compileDependenciesAreExactlyTheExpectedThirteen() throws Exception {
        Path pom = Path.of(System.getProperty("user.dir"), "pom.xml");
        assertThat(pom).isRegularFile();

        assertThat(compileScopeDependencies(pom)).hasSize(13).isEqualTo(new TreeSet<>(ALLOWED_COMPILE_DEPENDENCIES));
    }

    @Test
    @DisplayName(
            "main sources import nothing from the REST, engine, task composition, persistence or definition modules")
    void mainSourcesImportNoUpstreamConsumers() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachMainSource(file -> {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String stripped = lines.get(index).strip();
                if (!stripped.startsWith("import ")) {
                    continue;
                }
                String imported = stripped.substring("import ".length())
                        .replace("static ", "")
                        .replace(";", "")
                        .strip();
                boolean bannedPrefix = BANNED_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith);
                boolean bannedName = BANNED_IMPORTS.stream().anyMatch(imported::equals);
                if (bannedPrefix || bannedName) {
                    violations.add(file + ":" + (index + 1) + ": " + stripped);
                }
            }
        });

        assertThat(violations).as("banned imports in src/main/java").isEmpty();
    }

    @Test
    @DisplayName("main sources never reach for the process-wide JSON codec")
    void mainSourcesNeverUseTheProcessWideCodec() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachMainSource(file -> {
            List<String> lines = readLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String stripped = lines.get(index).strip();
                boolean comment = stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*");
                if (!comment && BANNED_SOURCE_TEXT.stream().anyMatch(stripped::contains)) {
                    violations.add(file + ":" + (index + 1) + ": " + stripped);
                }
            }
        });

        assertThat(violations).as("process-wide codec use in src/main/java").isEmpty();
    }

    // --- pom and source scanning ---

    private static void forEachMainSource(java.util.function.Consumer<Path> action) throws IOException {
        Path sources = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertThat(sources).isDirectory();
        try (Stream<Path> javaFiles =
                Files.walk(sources).filter(path -> path.toString().endsWith(".java"))) {
            javaFiles.forEach(action);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * Returns the {@code groupId:artifactId} of every dependency in the POM with no {@code <scope>} or an explicit
     * {@code compile} scope.
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
}
