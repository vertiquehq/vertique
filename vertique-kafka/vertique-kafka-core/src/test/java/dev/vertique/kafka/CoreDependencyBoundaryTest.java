// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-level dependency-boundary guard for {@code vertique-kafka-core}.
 *
 * <p>This test enforces the format-neutral-core invariant (ADR-0075): the core module must never
 * acquire direct compile-scope imports of the Jackson serde engine, Avro, or Apicurio. Those
 * serialization concerns belong in dedicated sibling modules ({@code vertique-kafka-json} for Jackson
 * JSON, {@code vertique-kafka-avro} for Avro/Apicurio).
 *
 * <p>Per ADR-0107 (amends ADR-0075 §6), {@code com.fasterxml.jackson.annotation.*} imports are
 * <em>permitted</em> in {@code src/main} for typed config records (e.g. {@code @JsonCreator},
 * {@code @JsonProperty}). The annotation package carries no runtime serde behavior; actual
 * deserialization is performed by the shared {@code ConfigParser} in {@code vertique-core}.
 * Only the serde engine packages — {@code databind}, {@code core}, and {@code dataformat} — remain
 * banned.
 *
 * <p>The scan targets {@code src/main/java} relative to the Maven module directory (Surefire sets the
 * working directory to the module root). It inspects every {@code *.java} file for lines that begin
 * with an {@code import} statement (leading whitespace is ignored) matching any of the forbidden
 * package prefixes. Javadoc references ({@code @code}, inline mentions) do not start with {@code
 * import} and are therefore not flagged.
 *
 * <p>Forbidden prefixes (serde engines and schema registries — not the annotation metadata package):
 * <ul>
 *   <li>{@code com.fasterxml.jackson.databind} — Jackson Databind (ObjectMapper, serde engine)
 *   <li>{@code com.fasterxml.jackson.core} — Jackson Core (streaming parser/generator engine)
 *   <li>{@code com.fasterxml.jackson.dataformat} — Jackson dataformat extensions (Avro, CBOR, etc.)
 *   <li>{@code org.apache.avro} — Apache Avro runtime
 *   <li>{@code io.apicurio} — Apicurio Registry client
 *   <li>{@code io.vertx.core.json.jackson.DatabindCodec} — Vert.x Jackson bridge (thin wrapper over
 *       Jackson {@code ObjectMapper}; its presence implies Jackson databind on the class-path)
 * </ul>
 *
 * <p>This is a <em>secondary</em> guard that complements the {@code maven-enforcer-plugin}
 * {@code bannedDependencies} execution defined in {@code vertique-kafka-core/pom.xml}. The enforcer
 * catches POM-declared direct dependencies; this test additionally catches any import that sneaks in
 * via a transitively-provided class (i.e. a class that is reachable at compile time but not a direct
 * dependency).
 *
 * <p><strong>Known limitation:</strong> this scan matches {@code import} statements only, so a
 * fully-qualified reference with no import (e.g. {@code com.fasterxml.jackson...Foo x}) would evade
 * it. That residual gap is backed by the enforcer (a forbidden type can only be referenced if its
 * artifact is on the compile class-path, which the enforcer bans as a direct dependency) and the
 * compile class-path itself — so the two guards together remain sufficient in practice.
 */
@DisplayName("vertique-kafka-core source-level dependency boundary")
class CoreDependencyBoundaryTest {

    // --- Banned import prefixes ---
    // NOTE: com.fasterxml.jackson.annotation is intentionally absent — permitted per ADR-0107.
    // Only the serde engine packages (databind, core, dataformat) remain banned.

    private static final List<String> BANNED_PREFIXES = List.of(
            "import com.fasterxml.jackson.databind",
            "import com.fasterxml.jackson.core",
            "import com.fasterxml.jackson.dataformat",
            "import org.apache.avro",
            "import io.apicurio",
            "import io.vertx.core.json.jackson.DatabindCodec");

    // --- Test ---

    @Test
    @DisplayName("no banned serde-engine imports in src/main/java (Jackson databind/core/dataformat, Avro, Apicurio)")
    void noForbiddenImportsInMainSources() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            fail("Expected src/main/java to exist relative to module basedir. Current dir: "
                    + Path.of(".").toAbsolutePath());
        }

        List<String> violations = new ArrayList<>();

        try (Stream<Path> javaFiles =
                Files.walk(srcMain).filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))) {
            javaFiles.forEach(file -> {
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String stripped = lines.get(i).stripLeading();
                        for (String prefix : BANNED_PREFIXES) {
                            if (stripped.startsWith(prefix)) {
                                violations.add(file + ":" + (i + 1) + ": "
                                        + lines.get(i).strip());
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read " + file, e);
                }
            });
        }

        if (!violations.isEmpty()) {
            String report = String.join("\n  ", violations);
            fail("vertique-kafka-core src/main must not import Jackson serde engines (databind/core/dataformat),"
                    + " Avro, or Apicurio. Jackson annotation metadata (com.fasterxml.jackson.annotation) is"
                    + " permitted for typed config records per ADR-0107. JSON serde belongs in"
                    + " vertique-kafka-json; Avro in vertique-kafka-avro."
                    + "\n  Found " + violations.size() + " violation(s):\n  " + report);
        }
    }
}
