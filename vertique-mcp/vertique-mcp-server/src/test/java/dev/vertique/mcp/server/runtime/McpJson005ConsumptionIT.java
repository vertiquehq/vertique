// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T009 — end-to-end consumption of JSON-005's {@code generateCanonical(Type)} through
 * {@link McpToolRuntimeFactory#create}, distinct from {@link McpSchemaHardeningTest} (which exercises
 * {@link McpSchemaHardener} directly against hand-authored fixtures pinned to the real generator's
 * shape) and {@link McpSchemaHardeningDifferentialTest} (which proves {@link McpCanonicalJsonWriter}
 * alone against committed JSON-005-produced bytes).
 *
 * <p>Given a real generated-shaped input carrier — a nested-object parameter and a resolved-map
 * parameter — {@code create(...)} must generate the input schema through JSON-005's own generator,
 * harden it, and publish it on the returned descriptor; a declared structured-output type must
 * publish JSON-005's canonical output schema unchanged (no argument-object hardening applies to
 * output).
 */
@DisplayName("MCP JSON-005 consumption — T009 end-to-end")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpJson005ConsumptionIT {

    record Address(String city, String street) {}

    record ConsumptionInput(
            @JsonProperty("addr") Address argument0,
            @JsonProperty("counts") Map<String, Integer> argument1) {}

    record ConsumptionOutput(String status) {}

    @Test
    @DisplayName("shouldGenerateHardenInputAndPublishOutputThroughTheRealJson005Generator")
    void shouldGenerateHardenInputAndPublishOutputThroughTheRealJson005Generator() {
        // --- Given: the factory wired to the framework vertx profile, and this tool's real metadata ---
        McpToolRuntimeFactory factory = McpJson005ConsumptionITFixture.factory();
        List<McpToolParameterMetadata> parameters = List.of(
                new McpToolParameterMetadata("argument0", "addr", "The address."),
                new McpToolParameterMetadata("argument1", "counts", "Item counts by key."));

        // --- When: create() consumes JSON-005 for both the input carrier and the output type ---
        McpToolRuntime<ConsumptionInput> runtime = factory.create(
                "consumption.tool",
                null,
                "Exercises real JSON-005 consumption.",
                new McpToolAnnotations(false, false, true, false),
                ConsumptionInput.class,
                ConsumptionOutput.class,
                parameters,
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        McpToolDescriptor descriptor = runtime.descriptor();

        // --- Then: the input schema is hardened — root and the nested "addr" object are closed, and
        // the resolved-map "counts" property now describes its Integer value (rest-023 T003) ---
        JsonNode inputSchema = McpCanonicalJsonWriter.read(descriptor.inputSchema());
        assertThat(inputSchema.get("additionalProperties").asBoolean())
                .as("the root carrier object must be closed")
                .isFalse();
        assertThat(inputSchema.at("/properties/addr/additionalProperties").asBoolean())
                .as("the nested addr object must be closed too — it declares non-empty properties")
                .isFalse();
        assertThat(inputSchema.at("/properties/addr/description").asText())
                .as("the addr parameter's description must be attached")
                .isEqualTo("The address.");
        assertThat(inputSchema
                        .at("/properties/counts/additionalProperties/type")
                        .asText())
                .as("the resolved-map counts property must describe its Integer value (rest-023 T003)")
                .isEqualTo("integer");

        // --- Then: a declared structured-output type publishes JSON-005's canonical schema unchanged ---
        assertThat(descriptor.outputSchema())
                .as("a declared structured-output type must publish a schema")
                .isNotNull();
        JsonNode outputSchema = McpCanonicalJsonWriter.read(descriptor.outputSchema());
        assertThat(outputSchema.has("additionalProperties"))
                .as("output is server-produced and carries no argument-object boundary to close")
                .isFalse();
        assertThat(outputSchema.at("/properties/status/type").asText()).isEqualTo("string");
    }
}
