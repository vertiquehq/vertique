// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.mcp.server.McpServerConfig;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins MCP's dependency direction towards prerequisite API contracts only. */
class McpPrerequisiteArchitectureTest {
    private static final String INPUT_PROCESSING_API = "dev.vertique.input.processing";
    private static final String JSON_SCHEMA_API = "dev.vertique.json.schema";

    @Test
    void shouldConsumeInputAndJsonSchemaArtifactsWithoutOwningTheirPackages() {
        List<DependencyReference> approvedDependencies = List.of(
                new DependencyReference(INPUT_PROCESSING_API + ".InputObjectProcessor"),
                new DependencyReference(JSON_SCHEMA_API + ".AnnotationJsonSchemaGenerator"),
                new DependencyReference(JsonMapperProfileRegistry.class.getName()));
        List<DependencyReference> syntheticViolations = List.of(
                new DependencyReference(INPUT_PROCESSING_API + ".internal.MutableInputPipeline"),
                new DependencyReference(JSON_SCHEMA_API + ".internal.VictoolsSchemaAdapter"),
                new DependencyReference(INPUT_PROCESSING_API));

        assertThat(ownershipViolations(approvedDependencies)).isEmpty();
        assertThat(ownershipViolations(syntheticViolations)).hasSize(3);
        assertThat(profileRegistryConstructorDependency()).isEqualTo(JsonMapperProfileRegistry.class.getName());
        assertThat(McpServerConfig.class.getPackageName()).isNotIn(INPUT_PROCESSING_API, JSON_SCHEMA_API);
    }

    private static List<DependencyReference> ownershipViolations(List<DependencyReference> dependencies) {
        return dependencies.stream()
                .filter(McpPrerequisiteArchitectureTest::isImplementationOrOwnedPackage)
                .toList();
    }

    private static boolean isImplementationOrOwnedPackage(DependencyReference dependency) {
        String typeName = dependency.typeName();
        return typeName.equals(INPUT_PROCESSING_API)
                || typeName.equals(JSON_SCHEMA_API)
                || typeName.contains(".internal.");
    }

    private static String profileRegistryConstructorDependency() {
        for (Constructor<?> constructor : constructorHolder().getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                if (parameterType == JsonMapperProfileRegistry.class) {
                    return parameterType.getName();
                }
            }
        }
        throw new AssertionError("MCP profile validator must consume JsonMapperProfileRegistry");
    }

    private static Class<?> constructorHolder() {
        try {
            return Class.forName("dev.vertique.mcp.server.McpJsonProfileDefaultValidator");
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("MCP JSON profile validator is missing", exception);
        }
    }

    private record DependencyReference(String typeName) {}
}
