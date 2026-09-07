// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.mcp.server.runtime.McpToolRuntime;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.SyntheticReversedMcpCoreToServerEdge;
import java.net.URL;
import java.security.CodeSource;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the one-way profile dependency direction between {@code vertique-core}, {@code vertique-json},
 * {@code vertique-json-schema}, {@code vertique-mcp-core}, {@code vertique-mcp-server} and
 * {@code vertique-codegen-mcp} on real compiled bytecode.
 *
 * <p>The frozen direction graph is the one stated in the schema/profile/mapping contract: MCP core and the MCP
 * annotation processor use the profile annotation and id contracts from {@code vertique-core}; the MCP server
 * consumes {@code vertique-json} for the registry/config, {@code vertique-json-schema} for schema generation, and
 * {@code vertique-input-processing} for the T014 fixed input boundary; neither JSON artifact nor
 * {@code vertique-input-processing} depends on MCP; and no reversed edge exists.
 *
 * <p><strong>Scanned scope.</strong> The rule reads the compiled production bytecode of the six artifacts that
 * {@code vertique-mcp-server} compiles against — {@code vertique-core}, {@code vertique-json},
 * {@code vertique-json-schema} (T009), {@code vertique-input-processing} (T014), {@code vertique-mcp-core},
 * {@code vertique-mcp-server} — so a newly added production type is covered without touching this test.
 * {@code vertique-codegen-mcp} and generated application source are not on this module's classpath, so their
 * <em>outgoing</em> edges cannot be read here; their zones are still pinned as forbidden <em>targets</em>, which is
 * what keeps the server and core artifacts from ever reaching into the processor.
 */
class McpProfileDependencyArchitectureTest {

    @Test
    void shouldKeepCoreCodegenJsonAndGeneratedSourceOnApprovedEdges() {
        JavaClasses compiledProfileGraph = McpProfileDependencyArchitectureTestFixture.importCompiledProfileGraph();
        JavaClasses syntheticReversedEdge =
                McpProfileDependencyArchitectureTestFixture.importClasses(SyntheticReversedMcpCoreToServerEdge.class);
        ArchRule approvedDirection = McpProfileDependencyArchitectureTestFixture.approvedDirectionRule();

        List<String> productionViolations =
                McpProfileDependencyArchitectureTestFixture.violations(approvedDirection, compiledProfileGraph);
        List<String> syntheticViolations =
                McpProfileDependencyArchitectureTestFixture.violations(approvedDirection, syntheticReversedEdge);

        assertThat(McpProfileDependencyArchitectureTestFixture.typeNames(compiledProfileGraph))
                .as("every classpath-reachable zone of the frozen direction graph must be inside the scanned scope")
                .contains(
                        "dev.vertique.core.json.JsonProfileId",
                        "dev.vertique.json.DefaultJsonMapperProfileRegistry",
                        "dev.vertique.json.schema.AnnotationJsonSchemaGenerator",
                        "dev.vertique.input.processing.InputObjectProcessor",
                        "dev.vertique.mcp.tool.McpToolDescriptor",
                        "dev.vertique.mcp.server.runtime.McpToolRuntime");
        assertThat(productionViolations)
                .as("no compiled production edge may leave the frozen direction graph")
                .isEmpty();
        assertThat(McpProfileDependencyArchitectureTestFixture.observedZoneEdges(compiledProfileGraph))
                .as("the compiled edges must equal the frozen graph, not merely stay inside an empty one")
                .containsExactlyInAnyOrder(
                        "mcp-core -> core",
                        "mcp-server -> core",
                        "mcp-server -> mcp-core",
                        "mcp-server -> json",
                        "mcp-server -> json-schema",
                        "mcp-server -> input-processing",
                        "json -> core",
                        "json-schema -> core",
                        "input-processing -> core");
        assertThat(syntheticViolations).hasSize(1);
        assertThat(syntheticViolations.getFirst())
                .contains(
                        "dev.vertique.mcp.tool.SyntheticReversedMcpCoreToServerEdge",
                        "dev.vertique.mcp.server.runtime.McpToolRuntime");
    }

    /** Framework wiring for the direction scan: class import, rule construction, violation and edge extraction. */
    private static final class McpProfileDependencyArchitectureTestFixture {

        /**
         * Package prefix to artifact zone. Nested prefixes are deliberate — {@code dev.vertique.mcp.server} must win
         * over {@code dev.vertique.mcp}, and {@code dev.vertique.json.schema} over {@code dev.vertique.json} — so
         * {@link #zoneOf(String)} resolves by longest match rather than by iteration order.
         */
        private static final Map<String, String> ZONES_BY_PACKAGE_PREFIX = zonesByPackagePrefix();

        /** The frozen one-way direction graph, as {@code "<source zone> -> <target zone>"} edges. */
        private static final Set<String> APPROVED_EDGES = Set.of(
                "mcp-core -> core",
                "codegen-mcp -> core",
                "codegen-mcp -> mcp-core",
                // codegen-mcp's own outgoing edges are never scanned (see class Javadoc); listed for
                // completeness, not as proof that this direction holds.
                "codegen-mcp -> input-processing",
                "mcp-server -> core",
                "mcp-server -> mcp-core",
                "mcp-server -> json",
                "mcp-server -> json-schema",
                "mcp-server -> input-processing",
                "json -> core",
                "json-schema -> core",
                "input-processing -> core");

        private McpProfileDependencyArchitectureTestFixture() {}

        private static Map<String, String> zonesByPackagePrefix() {
            Map<String, String> zones = new LinkedHashMap<>();
            zones.put("dev.vertique.codegen.mcp", "codegen-mcp");
            zones.put("dev.vertique.json.schema", "json-schema");
            zones.put("dev.vertique.json", "json");
            zones.put("dev.vertique.input.processing", "input-processing");
            zones.put("dev.vertique.mcp.server", "mcp-server");
            zones.put("dev.vertique.mcp", "mcp-core");
            zones.put("dev.vertique.core", "core");
            return Map.copyOf(zones);
        }

        /**
         * Imports every compiled production class of the six artifacts {@code vertique-mcp-server} compiles
         * against: {@code vertique-core}, {@code vertique-json}, {@code vertique-json-schema} (T009),
         * {@code vertique-input-processing} (T014), {@code vertique-mcp-core} and {@code vertique-mcp-server}.
         */
        static JavaClasses importCompiledProfileGraph() {
            return new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importUrls(List.of(
                            compiledLocationOf(JsonProfileId.class),
                            compiledLocationOf(DefaultJsonMapperProfileRegistry.class),
                            compiledLocationOf(AnnotationJsonSchemaGenerator.class),
                            compiledLocationOf(InputObjectProcessor.class),
                            compiledLocationOf(McpToolDescriptor.class),
                            compiledLocationOf(McpToolRuntime.class)));
        }

        static JavaClasses importClasses(Class<?>... types) {
            return new ClassFileImporter().importClasses(types);
        }

        static ArchRule approvedDirectionRule() {
            return classes().should(new ApprovedDependencyDirectionCondition());
        }

        static List<String> violations(ArchRule rule, JavaClasses classes) {
            return rule.evaluate(classes).getFailureReport().getDetails();
        }

        static List<String> typeNames(JavaClasses classes) {
            return classes.stream().map(JavaClass::getName).toList();
        }

        /** Every distinct cross-zone edge the compiled bytecode actually contains. */
        static List<String> observedZoneEdges(JavaClasses classes) {
            return classes.stream()
                    .flatMap(type -> zoneEdgesFrom(type).stream())
                    .map(ZoneEdge::edge)
                    .distinct()
                    .sorted()
                    .toList();
        }

        private static List<ZoneEdge> zoneEdgesFrom(JavaClass type) {
            Optional<String> sourceZone = zoneOf(type.getPackageName());
            if (sourceZone.isEmpty()) {
                return List.of();
            }
            return type.getDirectDependenciesFromSelf().stream()
                    .map(Dependency::getTargetClass)
                    .flatMap(target -> zoneOf(target.getPackageName())
                            .filter(targetZone -> !targetZone.equals(sourceZone.get()))
                            .map(targetZone -> new ZoneEdge(sourceZone.get() + " -> " + targetZone, target.getName()))
                            .stream())
                    .toList();
        }

        /** Resolves the owning zone by longest matching package prefix, so {@code mcp.server} wins over {@code mcp}. */
        private static Optional<String> zoneOf(String packageName) {
            return ZONES_BY_PACKAGE_PREFIX.entrySet().stream()
                    .filter(zone -> packageName.equals(zone.getKey()) || packageName.startsWith(zone.getKey() + "."))
                    .max(Comparator.comparingInt(zone -> zone.getKey().length()))
                    .map(Map.Entry::getValue);
        }

        private static URL compiledLocationOf(Class<?> type) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new AssertionError("No compiled location available for " + type.getName());
            }
            return codeSource.getLocation();
        }

        /** One cross-zone edge and the target type that carries it. */
        private record ZoneEdge(String edge, String targetTypeName) {}

        /** Reports one violation per class whose compiled dependencies leave the frozen direction graph. */
        private static final class ApprovedDependencyDirectionCondition extends ArchCondition<JavaClass> {
            ApprovedDependencyDirectionCondition() {
                super("depend only along the frozen MCP/JSON profile direction graph");
            }

            @Override
            public void check(JavaClass type, ConditionEvents events) {
                Map<String, List<String>> forbiddenTargetsByEdge = zoneEdgesFrom(type).stream()
                        .filter(zoneEdge -> !APPROVED_EDGES.contains(zoneEdge.edge()))
                        .collect(Collectors.groupingBy(
                                ZoneEdge::edge,
                                TreeMap::new,
                                Collectors.mapping(ZoneEdge::targetTypeName, Collectors.toList())));
                forbiddenTargetsByEdge.forEach((edge, targets) -> events.add(SimpleConditionEvent.violated(
                        type,
                        type.getName() + " carries forbidden dependency edge " + edge + " via "
                                + targets.stream().distinct().sorted().toList())));
            }
        }
    }
}
